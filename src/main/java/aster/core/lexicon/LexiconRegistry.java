package aster.core.lexicon;

import aster.core.canonicalizer.TransformerRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 词法表注册中心 - 管理所有已注册的 Lexicon 实现。
 * <p>
 * 使用单例模式，确保全局唯一的词法表注册表。
 */
public final class LexiconRegistry {

    private static final Logger LOGGER = Logger.getLogger(LexiconRegistry.class.getName());

    /**
     * 可选 keyword（validate 缺失只告警不报错）。用于新引入 keyword 的跨仓 lexicon
     * 迁移期：core 加 SemanticTokenKind 后，各语言包 lexicon 尚未同步更新时不至于
     * 全部 lexicon 加载失败（chicken-egg 发布顺序死锁）。迁移完成后可移除。
     */
    private static final Set<SemanticTokenKind> OPTIONAL_KINDS =
        EnumSet.of(SemanticTokenKind.IMPORT_VERSION);

    private static final LexiconRegistry INSTANCE = new LexiconRegistry();

    /**
     * R6-C1：lexicon + owner 合并到单一 Entry —— 避免双 map 非原子更新。
     *
     * @param lexicon 实际 lexicon 实例
     * @param owner   注册它的 classloader；内置 / 无 owner 为 null
     */
    private record LexiconEntry(Lexicon lexicon, ClassLoader owner) {}

    /**
     * 内部存储。ConcurrentHashMap 因为运行期 hot-plug（WatchService 线程）
     * 与读取（HTTP 请求线程）并发；用 compute() 让 register / unregister 单步原子。
     */
    private final ConcurrentHashMap<String, LexiconEntry> entries = new ConcurrentHashMap<>();

    /**
     * **持久意图** —— 运维显式声明应当被软下线的 lexicon 集合。
     * <p>
     * 关键设计点：此集合在 {@link #register} / {@link #registerBuiltin} 时 **不被清除**，
     * 这样"启动期 disable zh，运行期 SPI 又发现了 zh"也仍会保持 zh 隐藏。
     * 想恢复必须显式调 {@link #markAvailable}。
     * <p>
     * 也支持对"尚未注册的 id"标记 desired-disabled —— 将来注册时立即生效。
     * <p>
     * en-us 永远不会进入此集合（在 {@link #markUnavailable} 处守护）。
     */
    private final Set<String> desiredDisabled = ConcurrentHashMap.newKeySet();

    /**
     * 插件发现期捕获的错误，供运维诊断。每条记录 {@code provider-class -> error-message}。
     * 同一 provider 多次失败会覆盖（最新错误）。
     */
    private final Map<String, String> discoveryFailures = new ConcurrentHashMap<>();

    // R6-C1: lexiconOwners 合并到 entries 内部的 LexiconEntry.owner，删除独立 map

    /**
     * 注册表变更监听器。每当 lexicons 集合或 desiredDisabled 集合发生增减，
     * 异步通知监听者；payload 是当前**可用** lexicon ID 集合（已扣除 desiredDisabled）。
     * <p>
     * 使用 COW 列表，监听者增删与 fireChange() 遍历无冲突。
     */
    private final List<Consumer<Set<String>>> listeners = new CopyOnWriteArrayList<>();

    /**
     * R8-Backend-1：批量事务期间抑制 {@link #fireChange()}。
     * <p>
     * 用 ThreadLocal 计数：{@link #runAtomic(Runnable)} 入口 +1，出口 -1。
     * 计数 &gt; 0 时所有 fireChange() 调用累积到事务末尾，最后只发一次。
     * <p>
     * 设计理由：原子 swap（unregister 旧 + register 新 + 严格漂移校验 + 失败回滚）
     * 期间会触发多次 putIfAbsent/computeIfPresent，每次都会 fireChange，
     * 导致 SSE 监听器看到中间态（"zh 没了 / zh 又回来了"瞬变）。
     * 抑制后，事务无论成功还是回滚，都只对外宣布**最终态**。
     */
    private final ThreadLocal<int[]> suppressionDepth =
        ThreadLocal.withInitial(() -> new int[]{0});

    /**
     * R8-Backend-1：写操作互斥锁。
     * <p>
     * 让所有"事务式 replace"在同一时刻只能有一个进入：避免两个并发 hot-plug
     * 同时跑 unregisterByOwner + discoverPluginsDetailed 而互相交错。
     * <p>
     * 读路径（{@link #get}/{@link #has}/{@link #list}/{@link #availableIds}）
     * 仍直接走 ConcurrentHashMap，零锁，保持热路径性能。
     * 读侧最坏情况：在 {@code unregister} 与 {@code discover} 之间瞬时看到 fallback
     * 到 en-US —— 由 {@link FallbackLexicon} 兜底，不会 NPE。
     */
    private final Object replaceLock = new Object();

    private volatile String defaultLexiconId = "en-US";

    private LexiconRegistry() {
        // en-US 永远作为内嵌默认 lexicon 注册（aster-lang-core resources/builtin/en-US.json），
        // 不依赖外部 plugin jar。这保证 getDefault() 在任何 classpath 配置下都不会失败，
        // 并为 FallbackLexicon 装饰器提供"backbone"。
        loadEmbeddedDefaults();
        // 其他语言包通过 SPI 机制按需注册（META-INF/services/aster.core.lexicon.LexiconPlugin）。
        // 若 en-US plugin 也在 classpath，discoverPlugins() 会因 ID 已注册而跳过，避免双注册。
        discoverPlugins();
        // 完整性日志：明确打印启动后最终注册的 lexicon ids，便于多副本部署排查"某副本少一门
        // 语言"（此前坏副本连日志都没有）。仍有 discoveryFailures 残留则一并 WARN。
        logStartupLexiconSummary();
    }

    /** 启动 SPI 发现后打印最终 lexicon 清单 + 残留失败（多副本一致性诊断）。 */
    private void logStartupLexiconSummary() {
        java.util.List<String> ids = new java.util.ArrayList<>(availableIds());
        java.util.Collections.sort(ids);
        LOGGER.info(() -> "Lexicon registry ready: available=" + ids);
        if (!discoveryFailures.isEmpty()) {
            LOGGER.warning(() -> "Lexicon SPI discovery left "
                + discoveryFailures.size() + " unresolved entries after retries: "
                + discoveryFailures);
        }
    }

    /**
     * 加载 core 内嵌的 en-US 默认词法表。
     * <p>
     * 资源路径：classpath:builtin/en-US.json（由 aster-lang-core 直接打包）。
     * 失败抛 IllegalStateException —— 这是构建错误，不应运行时容忍。
     */
    private void loadEmbeddedDefaults() {
        // en-US 是默认 + fallback，缺失=构建错误，必须存在。core 只内嵌 en-US 这一个
        // builtin；其它语言（zh/de/hi）都通过 SPI 语言包（LexiconPlugin）按需加载，
        // 这样运维可热插拔/卸载。hi-IN（Hindi）的语言包是 aster-lang-hi。
        registerEmbedded("builtin/en-US.json", true);
    }

    /**
     * 加载并注册一个 core 内嵌 lexicon JSON。
     *
     * @param resourcePath classpath 资源路径
     * @param required true=缺失/解析失败抛异常（en-US）；false=best-effort 仅警告（hi-IN）
     */
    private void registerEmbedded(String resourcePath, boolean required) {
        try (var is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                if (required) {
                    throw new IllegalStateException(
                        "Core embedded lexicon missing (expected classpath:" + resourcePath + "). "
                            + "This is a build configuration error.");
                }
                return;
            }
            String json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            registerBuiltin(DynamicLexicon.fromJsonString(json));
        } catch (IOException e) {
            if (required) {
                throw new UncheckedIOException("Failed to load embedded lexicon " + resourcePath, e);
            }
        }
    }

    /**
     * 注册内置词法表（跳过验证，因为内置词法表已在代码中保证正确性）
     *
     * @param lexicon 内置词法表
     */
    private void registerBuiltin(Lexicon lexicon) {
        // 使用归一化 ID 注册，确保大小写一致性。
        // **不要清 desiredDisabled** —— 运维显式 disable 必须跨 register 持久；
        // 想恢复必须调 markAvailable。
        String id = normalizeId(lexicon.getId());
        // R6-C1: 内置 lexicon owner=null（永久存在，与内置 transformer 同语义）
        LexiconEntry prev = entries.put(id, new LexiconEntry(lexicon, null));
        if (prev == null) {
            fireChange();
        }
    }

    /**
     * 归一化词法表 ID
     * <p>
     * 转换为小写，并将下划线替换为连字符，确保注册和查询的一致性。
     *
     * @param id 原始 ID
     * @return 归一化后的 ID
     */
    private String normalizeId(String id) {
        if (id == null) {
            return null;
        }
        return id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * 获取 LexiconRegistry 单例
     */
    public static LexiconRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 注册一个 Lexicon
     *
     * @param lexicon 要注册的词法表
     * @throws IllegalArgumentException 如果词法表 ID 已存在或验证失败
     */
    public void register(Lexicon lexicon) {
        // 审计 #58：register() 此前只调用 validate()（无别名校验），别名遮蔽/重复只在
        // LexiconValidator.validateLexicon 里被拦（仅 CLI/测试调用）。任何声明
        // "aliases":{"RETURN":["if"]} 的 lexicon/plugin 都能干净注册并把每个 if 悄悄改写成
        // return。现改为复用同一别名硬校验契约（appendSemanticChecks），单一验证入口。
        // 注意用实例方法 validate(lexicon) 算 base（构造期 getInstance() 尚为 null）。
        ValidationResult result = LexiconValidator.appendSemanticChecks(lexicon, validate(lexicon));
        if (!result.isValid()) {
            throw new IllegalArgumentException(
                "Invalid lexicon '" + lexicon.getId() + "': " + String.join("; ", result.errors())
            );
        }

        String normalizedId = normalizeId(lexicon.getId());
        // R6-C1: 原子 putIfAbsent — 并发 register 同 id 只有一个能 win
        LexiconEntry prev = entries.putIfAbsent(normalizedId, new LexiconEntry(lexicon, null));
        if (prev != null) {
            throw new IllegalArgumentException("Lexicon '" + lexicon.getId() + "' already registered");
        }
        fireChange();
    }

    /**
     * 获取指定 ID 的 Lexicon。
     * <p>
     * 返回值已被 {@link FallbackLexicon} 装饰：target lexicon 缺少的 keyword
     * 自动回退到 en-US 对应值。en-US 自身或 fallback 不可用时返回 raw target。
     *
     * @param id 词法表 ID（支持大小写不敏感和下划线格式）
     * @return 对应的 Lexicon（已装饰 fallback），如果不存在则返回 empty
     */
    public Optional<Lexicon> get(String id) {
        String normalized = normalizeId(id);
        // en-US backbone 永远不能被软下线，否则 FallbackLexicon 也会瘫
        if (desiredDisabled.contains(normalized) && !"en-us".equals(normalized)) {
            return Optional.empty();
        }
        LexiconEntry e = entries.get(normalized);
        if (e == null) return Optional.empty();
        return Optional.of(decorateWithFallback(e.lexicon, normalized));
    }

    /**
     * 把 target lexicon 包成 FallbackLexicon。
     * <p>
     * 跳过装饰的情形：
     * <ul>
     *   <li>target 本身就是 en-US（无意义自包装）</li>
     *   <li>target 已是 FallbackLexicon（不重复包装）</li>
     *   <li>en-US fallback 不存在（理论不该发生 —— loadEmbeddedDefaults 保证；防御性返回 raw）</li>
     * </ul>
     */
    private Lexicon decorateWithFallback(Lexicon target, String normalizedId) {
        if ("en-us".equals(normalizedId)) return target;
        if (target instanceof FallbackLexicon) return target;
        LexiconEntry enUsEntry = entries.get("en-us");
        if (enUsEntry == null) return target;
        return new FallbackLexicon(target, enUsEntry.lexicon);
    }

    /**
     * 获取指定 ID 的 Lexicon，如果不存在则抛出异常
     *
     * @param id 词法表 ID
     * @return 对应的 Lexicon
     * @throws IllegalArgumentException 如果词法表不存在
     */
    public Lexicon getOrThrow(String id) {
        return get(id).orElseThrow(() ->
            new IllegalArgumentException("Lexicon '" + id + "' not found")
        );
    }

    /**
     * 检查是否存在指定 ID 的 Lexicon
     *
     * @param id 词法表 ID（支持大小写不敏感和下划线格式）
     * @return 如果存在返回 true
     */
    public boolean has(String id) {
        String normalized = normalizeId(id);
        if (desiredDisabled.contains(normalized) && !"en-us".equals(normalized)) {
            return false;
        }
        return entries.containsKey(normalized);
    }

    /**
     * 列出所有已注册的 Lexicon ID
     * <p>
     * 返回词法表声明的原始 ID（保留原始大小写），而非归一化后的 ID。
     *
     * @return Lexicon ID 列表
     */
    public List<String> list() {
        List<String> originalIds = new ArrayList<>();
        for (Map.Entry<String, LexiconEntry> e : entries.entrySet()) {
            if (desiredDisabled.contains(e.getKey()) && !"en-us".equals(e.getKey())) continue;
            originalIds.add(e.getValue().lexicon.getId());
        }
        return originalIds;
    }

    /**
     * 获取所有已注册的 Lexicon 实例。
     *
     * @return 所有 Lexicon 的集合
     */
    public Collection<Lexicon> getAll() {
        // R7-Backend-5：与 get/has/list/availableIds 语义一致 —— 过滤软下线
        List<Lexicon> all = new ArrayList<>(entries.size());
        for (Map.Entry<String, LexiconEntry> e : entries.entrySet()) {
            if (desiredDisabled.contains(e.getKey()) && !"en-us".equals(e.getKey())) continue;
            all.add(e.getValue().lexicon);
        }
        return all;
    }

    /**
     * 获取默认 Lexicon
     *
     * @return 默认的 Lexicon
     */
    public Lexicon getDefault() {
        return getOrThrow(defaultLexiconId);
    }

    /**
     * **物理**移除一个 lexicon，让其后续可以被 register() 重新加入。
     * <p>
     * 与 {@link #markUnavailable(String)} 的区别：
     * <ul>
     *   <li>markUnavailable：物理保留，只对外隐藏（前端看不到，但 ID 已占用）</li>
     *   <li>unregister：物理删除，腾出 ID slot 给新 lexicon</li>
     * </ul>
     * <p>
     * 用于 hot-plug 替换路径（{@code HotPlugLexiconLoader.replaceCleanup}）—— 旧 jar
     * 引入的 lexicon 必须先从 map 移除，新 jar 的 SPI discovery 才能注册同 ID。
     * <p>
     * en-US backbone 禁止 unregister（FallbackLexicon 依赖它）。
     * desiredDisabled 状态保留：如果运维之前 disable 过这个 id，re-register 后仍 disable。
     *
     * @param id lexicon ID
     * @return true 如果发生了移除；false 如果原本就没注册或被拒（en-US）
     */
    public boolean unregister(String id) {
        String normalized = normalizeId(id);
        if ("en-us".equals(normalized)) {
            LOGGER.warning("unregister: refusing to remove en-US backbone");
            return false;
        }
        // R6-C1: 原子 remove
        LexiconEntry removed = entries.remove(normalized);
        if (removed != null) {
            fireChange();
            return true;
        }
        return false;
    }

    /**
     * R5 + R6-C1：批量移除由指定 classloader 注册的所有 lexicon。
     *
     * <p>用于 hot-plug 替换 / 卸载路径：旧 jar 的 loader 即将被 close，必须先
     * 撤销其拥有的 lexicon。比按 id 逐个移除更可靠（不会漏删 plugin 提供的
     * 多个 lexicon）。
     *
     * <p>R6-C1：用 compute() 把 "check owner + remove" 单步原子化，
     * 避免并发 register/unregister 之间的不一致窗口。
     * <p>
     * en-US backbone 不受影响（其 owner=null，调用方传入的是 hot-plug loader）。
     *
     * @param ownerLoader 要清理的 loader；不能是 null（null = 内置，永不被批量删）
     * @return 实际移除的 lexicon 原始 ID 集合
     */
    public Set<String> unregisterByOwner(ClassLoader ownerLoader) {
        if (ownerLoader == null) return Set.of();
        Set<String> removed = new HashSet<>();
        for (String key : entries.keySet()) {
            if ("en-us".equals(key)) continue;
            entries.computeIfPresent(key, (k, existing) -> {
                if (existing.owner == ownerLoader) {
                    removed.add(existing.lexicon.getId());
                    return null;  // null = 删除
                }
                return existing;
            });
        }
        if (!removed.isEmpty()) {
            fireChange();
        }
        return removed;
    }

    /**
     * R5：查询某个 lexicon 的 owner classloader。
     * 用于 hot-plug 诊断："这个 zh-CN 是从哪个 jar 来的？"
     *
     * <p>R6-Minor：返回 raw ClassLoader 引用会增加 caller 持有旧 loader 的风险
     * （阻止 GC）。生产代码不应长期持有此返回值。
     */
    public ClassLoader ownerOf(String id) {
        LexiconEntry e = entries.get(normalizeId(id));
        return e == null ? null : e.owner;
    }

    /**
     * 设置默认 Lexicon ID
     *
     * @param id 新的默认词法表 ID（支持大小写不敏感和下划线格式）
     * @throws IllegalArgumentException 如果词法表不存在
     */
    public void setDefault(String id) {
        String normalizedId = normalizeId(id);
        if (!entries.containsKey(normalizedId)) {
            throw new IllegalArgumentException("Cannot set default: Lexicon '" + id + "' not found");
        }
        this.defaultLexiconId = normalizedId;
    }

    /**
     * 验证 Lexicon 的完整性和正确性
     *
     * @param lexicon 要验证的词法表
     * @return 验证结果
     */
    public ValidationResult validate(Lexicon lexicon) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. 验证 ID 格式 (BCP 47)
        if (!isValidBcp47(lexicon.getId())) {
            errors.add("Invalid ID format: must follow BCP 47 (e.g., 'en-US', 'zh-CN')");
        }

        // 2. 验证所有必需关键词都已定义。
        //    新引入的 keyword（OPTIONAL_KINDS）在跨仓 lexicon 迁移期内缺失只告警不报错，
        //    避免 core 加 enum 与各语言包 lexicon 更新之间的发布顺序死锁（chicken-egg）。
        Set<SemanticTokenKind> definedKinds = lexicon.getKeywords().keySet();
        Set<SemanticTokenKind> requiredKinds = EnumSet.allOf(SemanticTokenKind.class);
        requiredKinds.removeAll(OPTIONAL_KINDS);

        Set<SemanticTokenKind> missing = new HashSet<>(requiredKinds);
        missing.removeAll(definedKinds);
        if (!missing.isEmpty()) {
            errors.add("Missing keywords for: " + missing);
        }

        Set<SemanticTokenKind> missingOptional = new HashSet<>(OPTIONAL_KINDS);
        missingOptional.removeAll(definedKinds);
        if (!missingOptional.isEmpty()) {
            warnings.add("Missing optional keywords (migration in progress): " + missingOptional);
        }

        // 3. 验证关键词值非空，并检查唯一性（考虑 allowedDuplicates）
        Map<String, List<SemanticTokenKind>> keywordToKinds = new HashMap<>();
        for (Map.Entry<SemanticTokenKind, String> entry : lexicon.getKeywords().entrySet()) {
            String value = entry.getValue();
            // 检查空值
            if (value == null || value.isEmpty()) {
                errors.add("Empty or null keyword value for: " + entry.getKey());
                continue;
            }
            // 使用 Locale.ROOT 避免土耳其语等区域设置导致的大小写转换问题
            String keyword = value.toLowerCase(Locale.ROOT);
            keywordToKinds.computeIfAbsent(keyword, k -> new ArrayList<>()).add(entry.getKey());
        }

        Set<Set<SemanticTokenKind>> allowedDupes = new HashSet<>();
        if (lexicon.getCanonicalization().allowedDuplicates() != null) {
            allowedDupes.addAll(lexicon.getCanonicalization().allowedDuplicates());
        }

        for (Map.Entry<String, List<SemanticTokenKind>> entry : keywordToKinds.entrySet()) {
            if (entry.getValue().size() > 1) {
                Set<SemanticTokenKind> dupeSet = new HashSet<>(entry.getValue());
                boolean allowed = allowedDupes.stream().anyMatch(
                    allowedSet -> allowedSet.containsAll(dupeSet)
                );
                if (!allowed) {
                    errors.add("Duplicate keyword '" + entry.getKey() + "' used by: " + entry.getValue());
                }
            }
        }

        // 4. 验证标点符号配置
        PunctuationConfig punct = lexicon.getPunctuation();
        if (punct.statementEnd() == null || punct.statementEnd().isEmpty()) {
            errors.add("Missing punctuation: statementEnd");
        }
        if (punct.listSeparator() == null || punct.listSeparator().isEmpty()) {
            errors.add("Missing punctuation: listSeparator");
        }
        if (punct.blockStart() == null || punct.blockStart().isEmpty()) {
            errors.add("Missing punctuation: blockStart");
        }
        // 5. 验证字符串引号配置（Canonicalizer 依赖此配置）
        if (punct.stringQuoteOpen() == null || punct.stringQuoteOpen().isEmpty()) {
            errors.add("Missing punctuation: stringQuoteOpen");
        }
        if (punct.stringQuoteClose() == null || punct.stringQuoteClose().isEmpty()) {
            errors.add("Missing punctuation: stringQuoteClose");
        }
        // 6. 验证标记符号配对性（如果启用标记）
        if (punct.hasMarkers()) {
            if (punct.markerOpen() == null || punct.markerOpen().isEmpty()) {
                errors.add("Missing punctuation: markerOpen (when hasMarkers is true)");
            }
            if (punct.markerClose() == null || punct.markerClose().isEmpty()) {
                errors.add("Missing punctuation: markerClose (when hasMarkers is true)");
            }
        }

        // 7. 安全：筛查不可信 customRules 正则的 ReDoS 形状（纵深防御，register 时即拒绝）。
        var canon = lexicon.getCanonicalization();
        if (canon != null && canon.customRules() != null) {
            for (var rule : canon.customRules()) {
                for (String redosError : RegexGuard.screen(rule.pattern())) {
                    errors.add("Unsafe regex in customRule '" + rule.name() + "': " + redosError);
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * 验证 BCP 47 语言标签格式
     * <p>
     * 使用 Java 的 {@link Locale#forLanguageTag} 进行验证，支持：
     * <ul>
     *   <li>语言代码：en, zh, es 等</li>
     *   <li>语言-地区：en-US, zh-CN 等</li>
     *   <li>语言-脚本-地区：zh-Hant-TW, sr-Cyrl-RS 等</li>
     *   <li>数字区域代码：es-419 (拉美西班牙语) 等</li>
     * </ul>
     *
     * @param id 语言标签
     * @return 如果是有效的 BCP 47 标签返回 true
     */
    private boolean isValidBcp47(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        // 使用 Locale.forLanguageTag 解析，如果无法识别语言则返回空语言
        Locale locale = Locale.forLanguageTag(id);
        // 有效的 BCP 47 标签至少有一个非空的语言代码
        return !locale.getLanguage().isEmpty();
    }

    /**
     * 从目录加载 JSON 语言包。
     * <p>
     * 扫描指定目录下的 {@code *.json} 文件（最深 2 层），
     * 将每个文件作为 {@link DynamicLexicon} 加载并注册。
     * 已存在的 ID 会被跳过（内置词法表优先）。
     *
     * @param lexiconsDir 语言包目录（如 {@code ~/.aster/lexicons/}）
     * @return 成功加载的语言包数量
     */
    public int loadFromDirectory(Path lexiconsDir) {
        if (!Files.isDirectory(lexiconsDir)) {
            return 0;
        }
        int loaded = 0;
        try (Stream<Path> stream = Files.walk(lexiconsDir, 2)) {
            List<Path> jsonFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList();
            for (Path jsonFile : jsonFiles) {
                try {
                    DynamicLexicon lexicon = DynamicLexicon.fromJson(jsonFile);
                    String normalizedId = normalizeId(lexicon.getId());
                    if (entries.containsKey(normalizedId)) {
                        continue; // 内置词法表优先，跳过重复
                    }
                    register(lexicon);
                    loaded++;
                } catch (Exception e) {
                    // 单个文件加载失败不影响其他文件
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan lexicons directory: " + lexiconsDir, e);
        }
        return loaded;
    }

    /**
     * 通过 Java SPI 机制发现并注册语言包插件（使用当前线程 contextClassLoader）。
     */
    public int discoverPlugins() {
        return discoverPlugins(Thread.currentThread().getContextClassLoader());
    }

    /** discoverPlugins 启动重试的最大轮数（首轮 + 重试）。 */
    private static final int MAX_DISCOVERY_PASSES = 3;

    /**
     * 通过 SPI 在指定 {@link ClassLoader} 上发现并注册语言包插件。
     * <p>
     * 显式传 loader 让 hot-plug 路径无需污染调用线程的 contextClassLoader。
     * 调用者负责在加载阶段保留 loader 引用（避免被 GC 收走、jar 类被卸载）。
     * <p>
     * 容错策略（R-multi-replica）：bundled SPI 语言包是确定性依赖，启动时偶发的
     * {@link ServiceConfigurationError}（多副本并行启动 + 堆压力下的类加载竞态）是**瞬时**的。
     * 因此 discover **重试**：每轮用**全新** {@link ServiceLoader}（不复用可能卡死的 lazy
     * iterator）重扫，{@link #entries} 的 putIfAbsent 天然幂等，只补缺失的。重试判定用**本轮
     * 局部**的 iterator 级失败数（{@link DiscoveryPass#iteratorFailures}，与共享诊断映射
     * {@link #discoveryFailures} 解耦——后者并发 hot-plug 也会写，不可作控制流）：本轮有 iterator
     * 级瞬时失败且未达上限（{@link #MAX_DISCOVERY_PASSES}）就再扫一轮，0 失败即停。这样把
     * "best-effort 静默丢插件" 改为"最终一致加载全部 bundled 语言包"，消除跨副本
     * /api/v1/lexicons 不一致。
     *
     * @param loader 用于 SPI 扫描的 classloader。{@code null} 表示用 system loader
     * @return 成功加载的插件数量（累计本次调用各轮新注册）
     */
    public int discoverPlugins(ClassLoader loader) {
        Set<String> allNewlyRegistered = new HashSet<>();
        for (int pass = 1; pass <= MAX_DISCOVERY_PASSES; pass++) {
            // 重试判定用**本轮局部** iterator 级失败数（DiscoveryPass.iteratorFailures），
            // **不**读共享 discoveryFailures 映射——后者既是诊断又是并发 hot-plug 写入的，
            // 用它做控制流会被 stale key / 并发写入污染（Codex 审查）。也**不**依赖失败 key
            // 的 "spi-iter#" 前缀：带 provider hint 的瞬时 ServiceConfigurationError（生产
            // 最常见的类加载竞态）key=provider class，但它仍是 iterator 级瞬时失败、**应重试**。
            DiscoveryPass result = discoverPluginsPass(loader);
            allNewlyRegistered.addAll(result.newlyRegistered());
            // iterator 级失败（瞬时类加载/解析）才重试；确定性 ABI/validate 失败不计入
            // iteratorFailures（它们在 provider 解析成功之后发生，是真该 skip 的，见 pass 内）。
            if (result.iteratorFailures() == 0) {
                break;
            }
            if (pass < MAX_DISCOVERY_PASSES) {
                LOGGER.warning("Lexicon SPI discovery pass " + pass + " had "
                    + result.iteratorFailures() + " iterator-level failure(s); "
                    + "retrying with a fresh ServiceLoader.");
            }
        }
        return allNewlyRegistered.size();
    }

    /** 一轮 SPI 发现的结果：本轮新注册的 id + iterator 级瞬时失败次数（重试判定用）。 */
    private record DiscoveryPass(Set<String> newlyRegistered, int iteratorFailures) {}

    /**
     * R4：discoverPlugins 的"详细"变体——返回**本次调用真正新注册**的 lexicon
     * 原始 ID（保留大小写）集合。
     *
     * <p>与"调用前后 availableIds 集合差"不同：set-diff 在"先 unregister 再 register
     * 同 ID"的同名替换场景下永远是空集，无法区分"什么都没发生"和"成功替换"。
     * 此方法在 register 那一刻直接累计返回值，**与 ID 是否之前存在过无关**。
     *
     * <p>用于 {@code HotPlugLexiconLoader.loadJar} 的事务式替换路径——它需要明确
     * 知道"新 loader 真正提供了哪些 lexicon"以便跟踪并在删除时清理。
     */
    public Set<String> discoverPluginsDetailed(ClassLoader loader) {
        return discoverPluginsPass(loader).newlyRegistered();
    }

    /**
     * 单轮 SPI 发现的内部实现，返回 {@link DiscoveryPass}（新注册 id + iterator 级瞬时失败数）。
     * iterator 级失败数供 {@link #discoverPlugins} 决定是否重试——这是**本轮局部信号**，
     * 与共享 {@link #discoveryFailures} 诊断映射解耦（后者并发 hot-plug 也会写，不可作控制流）。
     */
    private DiscoveryPass discoverPluginsPass(ClassLoader loader) {
        Set<String> newlyRegistered = new HashSet<>();
        int skipped = 0;
        Iterator<LexiconPlugin> iter = ServiceLoader.load(LexiconPlugin.class, loader).iterator();
        // 单轮内 lazy iterator 失败的总上限：防真卡死的 iterator 无限循环，但**不**因瞬时失败
        // （类加载竞态）就放弃后续 provider。瞬时失败靠 discoverPlugins() 的重试包装补齐
        // （每轮 new ServiceLoader）。MAX 远超 bundled 插件数，仅作真卡死止损阀。
        int iterFailures = 0;
        final int maxIterFailures = 8;
        while (true) {
            LexiconPlugin plugin;
            try {
                if (!iter.hasNext()) break;
                plugin = iter.next();
            } catch (ServiceConfigurationError | RuntimeException e) {
                // **iterator 级失败 = 瞬时**（hasNext/next 抛错：缺类、解析竞态、provider 实例化
                // 失败）。无论能否提取 provider hint，都计入 iterFailures 触发重试——生产最常见
                // 的 "Provider <fqcn> could not be instantiated" 带 provider class，仍是瞬时的。
                ++iterFailures;
                String providerHint = extractProviderHint(e);
                String key = providerHint != null
                    ? providerHint
                    : "spi-iter#" + System.currentTimeMillis() + "-" + iterFailures;
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                discoveryFailures.put(key, msg);
                LOGGER.log(java.util.logging.Level.WARNING,
                    "Skipping unresolved LexiconPlugin entry [" + key + "]: " + msg, e);
                if (iterFailures >= maxIterFailures) {
                    LOGGER.warning("SPI iterator failed " + maxIterFailures + " times this pass — "
                        + "aborting this discovery pass (likely a genuinely stuck lazy iterator). "
                        + "discoverPlugins() retry will re-scan with a fresh ServiceLoader.");
                    break;
                }
                continue;
            }
            String providerClass = plugin.getClass().getName();
            try {
                String abi = plugin.getAbiVersion();
                if (!LexiconAbiVersion.isCompatible(abi)) {
                    LOGGER.warning(() -> "Skipping lexicon plugin "
                        + providerClass
                        + ": ABI version " + abi
                        + " incompatible with core " + LexiconAbiVersion.V1.version);
                    discoveryFailures.put(providerClass,
                        "incompatible ABI version " + abi);
                    skipped++;
                    continue;
                }

                var transformers = plugin.getTransformers();
                if (!transformers.isEmpty()) {
                    // R5-Backend-3: 用 owner-aware 注册，让后续 unregisterByOwner(loader)
                    // 能批量清理这些 transformer。同 owner 再注册支持升级；
                    // 不同 owner first-wins；内置 transformer 保持不动
                    TransformerRegistry.registerAllWithOwner(transformers, loader);
                }

                Lexicon lexicon = plugin.createLexicon();
                // 关键：SPI 注册路径必须复用与 register(Lexicon) 相同的 validate 契约，
                // 否则任何外部 jar 都能绕过验证污染 registry（缺关键字、id 冲突格式错、
                // 必需角色未覆盖等）。validate 失败的 plugin 计入 discoveryFailures
                // 让 admin diag 端点能看到原因。
                // 审计 #58：同样追加别名遮蔽/重复硬校验（appendSemanticChecks），使外部
                // 插件无法通过 SPI 绕过 ADR-0022 别名保护。用实例方法 validate() 算 base
                // （构造期 discoverPlugins 运行时 getInstance() 尚为 null）。
                ValidationResult validation =
                    LexiconValidator.appendSemanticChecks(lexicon, validate(lexicon));
                if (!validation.isValid()) {
                    String msg = "validation failed: " + String.join("; ", validation.errors());
                    discoveryFailures.put(providerClass, msg);
                    LOGGER.warning(() -> "Skipping lexicon plugin "
                        + providerClass + " (" + lexicon.getId() + "): " + msg);
                    continue;
                }
                String normalizedId = normalizeId(lexicon.getId());
                // R6-C1：单步原子 putIfAbsent —— lex + owner 在同一 Entry 中原子写
                LexiconEntry prev = entries.putIfAbsent(
                    normalizedId, new LexiconEntry(lexicon, loader));
                if (prev != null) {
                    // 已注册（通常 backbone 或并发竞争失败）—— 视为正常去重
                    discoveryFailures.remove(providerClass);
                    continue;
                }
                fireChange();
                discoveryFailures.remove(providerClass);
                newlyRegistered.add(lexicon.getId());
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                discoveryFailures.put(providerClass, msg);
                LOGGER.log(java.util.logging.Level.WARNING,
                    "Lexicon plugin " + providerClass + " failed to register: " + msg, e);
            }
        }
        if (skipped > 0) {
            int finalLoaded = newlyRegistered.size();
            int finalSkipped = skipped;
            LOGGER.info(() -> "Lexicon ABI summary: loaded=" + finalLoaded
                + " skipped=" + finalSkipped + " (incompatible)");
        }
        return new DiscoveryPass(newlyRegistered, iterFailures);
    }

    /**
     * 返回最近一次 SPI 发现中所有失败的 provider class → error message 映射。
     * 用于 admin 诊断端点（"为什么我的语言包没出现？"）。
     */
    public Map<String, String> discoveryFailures() {
        return new HashMap<>(discoveryFailures);
    }

    /**
     * R5-Backend-2 + R6-M1 + R7-3：**只读**地预扫描 loader 上的 plugin，
     * 返回它**声称**提供的 lexicon ID 集合，但**不**修改 registry。
     *
     * <p>实现：仅调用 {@link LexiconPlugin#providedLexiconIds()}（metadata）。
     * R7-3 起 default 实现返回空集，所以**没 override 的 plugin 将被视为提供 0 个 lexicon**——
     * 它们仍会在 {@link #discoverPluginsDetailed} 阶段被发现（那时会调 createLexicon），
     * 但 preview/drift-check 看不到它们。所有 hot-plug 候选 plugin **必须** override
     * {@code providedLexiconIds()}，否则 strict drift check 会把它们误判为新增 ID。
     *
     * <p>用于 hot-plug 的事务式替换：先 dry-run 确认新 jar 的 ID 集合，再据此
     * 决定是否提交替换。
     *
     * @return plugin 声称提供的原始 ID 集合
     */
    public Set<String> previewPluginIds(ClassLoader loader) {
        Set<String> ids = new HashSet<>();
        Iterator<LexiconPlugin> iter = ServiceLoader.load(LexiconPlugin.class, loader).iterator();
        int consecutiveFailures = 0;
        while (true) {
            LexiconPlugin plugin;
            try {
                if (!iter.hasNext()) break;
                plugin = iter.next();
                consecutiveFailures = 0;
            } catch (ServiceConfigurationError | RuntimeException e) {
                if (++consecutiveFailures >= 2) break;
                continue;
            }
            try {
                if (!LexiconAbiVersion.isCompatible(plugin.getAbiVersion())) continue;
                // R6-M1: 调 metadata，由 plugin 决定是否真正调用 createLexicon
                ids.addAll(plugin.providedLexiconIds());
            } catch (Exception ignored) {
                // 单 plugin 失败不影响其他
            }
        }
        return ids;
    }

    /**
     * 尝试从异常中提取 provider class 名（用于 SPI iterator 失败时的诊断 key）。
     * <p>
     * 启发式策略：
     * <ol>
     *   <li>{@link ServiceConfigurationError.message} 通常含 fully-qualified class name</li>
     *   <li>cause 链中的 ClassNotFoundException / NoClassDefFoundError 自带 class name</li>
     *   <li>都没有则返回 null 由调用方使用 fallback key</li>
     * </ol>
     */
    private static String extractProviderHint(Throwable e) {
        // 1. message 自身
        String msg = e.getMessage();
        if (msg != null) {
            // Pattern: "Provider <fqcn> not found" or "Provider <fqcn> could not be instantiated"
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Provider ([a-zA-Z_$][a-zA-Z0-9_$.]+)")
                .matcher(msg);
            if (m.find()) return m.group(1);
        }
        // 2. cause 链
        Throwable c = e.getCause();
        while (c != null) {
            if (c instanceof ClassNotFoundException || c instanceof NoClassDefFoundError) {
                String cmsg = c.getMessage();
                if (cmsg != null && !cmsg.isBlank()) return cmsg.trim();
            }
            c = c.getCause();
        }
        return null;
    }

    /**
     * 获取默认语言包目录路径。
     *
     * @return {@code ~/.aster/lexicons/}
     */
    public static Path getDefaultLexiconsDir() {
        return Path.of(System.getProperty("user.home"), ".aster", "lexicons");
    }

    /**
     * 清除所有注册的 Lexicon（仅用于测试）
     */
    public void clear() {
        entries.clear();
        desiredDisabled.clear();
        defaultLexiconId = "en-US";
        fireChange();
    }

    // ------------------------------------------------------------------
    // 变更监听 + 软下线 API（hot-plug 支持）
    // ------------------------------------------------------------------

    /**
     * 注册变更监听器。每次 lexicons / unavailable 集合改变后异步触发，
     * 入参是当前**可用**语言 ID 集合（已扣除 unavailable）。
     * <p>
     * 用于 aster-api SSE 推送：监听到变化后向已订阅的浏览器广播新列表。
     *
     * @param listener 处理函数。监听器内部抛出的异常会被记录但不传播
     * @return 用于取消订阅的 Runnable；调用即移除监听器
     */
    public Runnable addChangeListener(Consumer<Set<String>> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * 把 lexicon 标记为软下线 —— 对外宣布"不可用"，但物理上保留在 classloader 中。
     * <p>
     * 设计理由：JVM ClassLoader unload 由 GC 触发，时机不可控，且若有正在执行的请求
     * 持有 lexicon 引用会引发并发故障。运维"拔包"语义改为"通知前端该语言不可用"，
     * 实际 classloader 清理留待容器重启。
     * <p>
     * en-US 是 FallbackLexicon 的 backbone，禁止下线 —— 静默忽略。
     *
     * @param id lexicon ID（支持大小写不敏感、下划线 → 连字符归一化）
     * @return 如果下线状态发生变化返回 true（含通知监听器副作用）
     */
    public boolean markUnavailable(String id) {
        String normalized = normalizeId(id);
        if ("en-us".equals(normalized)) {
            LOGGER.warning("markUnavailable: refusing to take en-US backbone offline");
            return false;
        }
        if (desiredDisabled.add(normalized)) {
            fireChange();
            return true;
        }
        return false;
    }

    /**
     * 取消软下线，恢复可见性。
     *
     * @param id lexicon ID
     * @return 状态发生变化返回 true
     */
    public boolean markAvailable(String id) {
        String normalized = normalizeId(id);
        if (desiredDisabled.remove(normalized)) {
            fireChange();
            return true;
        }
        return false;
    }

    /**
     * 返回当前**可用**的 lexicon 原始 ID 集合（保留原始大小写）。
     * 与 {@link #list()} 不同：返回 Set，调用方更易做集合运算。
     */
    public Set<String> availableIds() {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, LexiconEntry> e : entries.entrySet()) {
            if (desiredDisabled.contains(e.getKey()) && !"en-us".equals(e.getKey())) continue;
            result.add(e.getValue().lexicon.getId());
        }
        return result;
    }

    /**
     * 返回当前**软下线**（desiredDisabled）的 lexicon 归一化 ID 只读快照。
     *
     * <p>用途：跨副本可用性对账需要把“本副本实际下线集”与持久真相源（如 Redis SET）做差分，
     * 从而恢复漏掉的 enable。直接读注册表是**唯一可靠的本地下线视图**——调用方自行跟踪“已应用
     * 下线”易在 origin 路径漏记（本地 disable 后 self-broadcast 的 markUnavailable 返回 false）。
     *
     * <p>en-US backbone 永不会进入 desiredDisabled（{@link #markUnavailable} 守护），故不会出现在
     * 此集合。返回防御性拷贝，调用方修改不影响内部状态。
     *
     * @return 归一化的已下线 ID 集合快照（可能为空）
     */
    public Set<String> disabledIds() {
        return new HashSet<>(desiredDisabled);
    }

    /**
     * 异步通知所有监听器当前可用集合。
     * <p>
     * 实现选择：内联调用而非另起线程池。理由：
     * 1. 监听者本身应是非阻塞的（SSE 入队 / log），不会拖慢调用线程
     * 2. 避免额外线程池让 lexicon-core 保持零运行时依赖
     * 3. 监听器抛错被吞掉，互不影响
     * <p>
     * R8-Backend-1：若当前线程在 {@link #runAtomic} 事务中，不立即广播 ——
     * 由事务出口统一发一次（基于"进入前 vs 退出后"的快照判定）。
     */
    private void fireChange() {
        if (suppressionDepth.get()[0] > 0) {
            return;
        }
        Set<String> snapshot = availableIds();
        notifyListeners(snapshot);
    }

    private void notifyListeners(Set<String> snapshot) {
        for (Consumer<Set<String>> l : listeners) {
            try {
                l.accept(snapshot);
            } catch (Throwable t) {
                LOGGER.warning("Lexicon change listener threw: " + t.getMessage());
            }
        }
    }

    /**
     * R8-Backend-1：执行一个事务式批量操作。
     *
     * <p>语义：
     * <ul>
     *   <li><b>互斥</b> —— 同一时刻只能有一个 {@code runAtomic} 在执行（写写串行化），
     *       两个并发 hot-plug 不会互相交错</li>
     *   <li><b>事务式广播</b> —— action 内部所有 {@link #fireChange()} 都被抑制；
     *       action 结束（无论成功 / 失败）后比较"进入前 vs 退出后"的可用集合，
     *       只有真正变化时才广播一次，且广播的是**最终态**</li>
     *   <li><b>异常透传</b> —— action 抛出的异常会被原样向上抛；事务出口仍然
     *       会基于"实际可见的最终态"发一次（或不发）change</li>
     * </ul>
     *
     * <p>调用方典型用法（hot-plug replace 路径）：
     * <pre>{@code
     *   registry.runAtomic(() -> {
     *       registry.unregisterByOwner(oldLoader);
     *       Set<String> newIds = registry.discoverPluginsDetailed(newLoader);
     *       if (driftRejected(newIds)) {
     *           registry.unregisterByOwner(newLoader);
     *           registry.discoverPluginsDetailed(oldLoader);  // rollback
     *       }
     *   });
     * }</pre>
     *
     * <p>读路径不参与此锁；它们仍直接读 ConcurrentHashMap。Rejected 替换的
     * 中间窗口期，读侧最坏看到 fallback 到 en-US（一次"软漂移"），但 SSE
     * 监听器只看到回滚后的稳态，不会接到"消失→重现"的抖动。
     *
     * @param action 要原子执行的写操作
     */
    public void runAtomic(Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        synchronized (replaceLock) {
            Set<String> before = availableIds();
            int[] depth = suppressionDepth.get();
            depth[0]++;
            try {
                action.run();
            } finally {
                depth[0]--;
                // R9-Backend-M4: 嵌套 runAtomic —— 如果仍在外层事务里（depth>0），
                // 不在内层出口广播；让最外层一次性发当时的最终态。
                // 这避免了 "outer 还没结束但 inner 已经把中间态告诉监听器" 的违反契约场景。
                if (depth[0] == 0) {
                    Set<String> after = availableIds();
                    if (!before.equals(after)) {
                        notifyListeners(after);
                    }
                }
            }
        }
    }

    /**
     * 验证结果
     *
     * @param valid    是否有效
     * @param errors   错误列表
     * @param warnings 警告列表
     */
    public record ValidationResult(
        boolean valid,
        List<String> errors,
        List<String> warnings
    ) {
        public boolean isValid() {
            return valid;
        }
    }
}
