package aster.core.identifier;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 词汇表注册中心。
 *
 * 管理所有领域词汇表的注册和查找，支持：
 * - 按领域+语言查找词汇表
 * - 租户自定义词汇表
 * - 多领域词汇表合并
 * - 动态加载和卸载
 */
public final class VocabularyRegistry {

    // R30+ audit P2：用 JDK 自带 System.Logger 代替 System.err.println。
    // 不引入额外依赖；headless CI 也能正确路由到配置好的 handler。
    private static final Logger LOG = System.getLogger(VocabularyRegistry.class.getName());

    private static final VocabularyRegistry INSTANCE = new VocabularyRegistry();

    /** 词汇表存储：key = "${domain}:${locale}" */
    private final Map<String, VocabularyEntry> vocabularies = new ConcurrentHashMap<>();

    /** 自定义词汇表：key = "${tenantId}:${domain}:${locale}" */
    private final Map<String, VocabularyEntry> customVocabularies = new ConcurrentHashMap<>();

    private VocabularyRegistry() {
        // 默认注册内置词汇表
        initBuiltinVocabularies();
    }

    /**
     * 获取单例实例。
     */
    public static VocabularyRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 初始化内置词汇表。
     * <p>
     * 完全依赖 SPI 插件发现领域词汇表，不再硬编码。
     */
    private synchronized void initBuiltinVocabularies() {
        discoverVocabularyPlugins();
    }

    /**
     * 通过 SPI 发现并注册 {@link VocabularyPlugin} 插件提供的领域词汇表。
     * <p>
     * SPI 插件可覆盖同 (domain, locale) 的内置词汇表，也可注册全新的领域/语言组合。
     *
     * @return 成功加载的词汇表数量
     */
    public synchronized int discoverVocabularyPlugins() {
        int loaded = 0;
        for (VocabularyPlugin plugin : ServiceLoader.load(VocabularyPlugin.class)) {
            try {
                DomainVocabulary primary = plugin.createVocabulary();
                register(primary);
                loaded++;

                for (DomainVocabulary extra : plugin.getVocabularies()) {
                    register(extra);
                    loaded++;
                }
            } catch (Exception e) {
                // R30+ audit P3：plugin load 失败用 logger，并保留 stack
                // trace（System.Logger 的 Throwable overload）。
                LOG.log(Level.ERROR,
                    "加载词汇表插件失败: " + plugin.getClass().getName(), e);
            }
        }
        return loaded;
    }

    /**
     * 生成词汇表键。
     */
    private String makeKey(String domain, String locale) {
        return domain.toLowerCase(Locale.ROOT) + ":" + locale.toLowerCase(Locale.ROOT);
    }

    /**
     * 生成租户词汇表键。
     */
    private String makeCustomKey(String tenantId, String domain, String locale) {
        return tenantId + ":" + makeKey(domain, locale);
    }

    /**
     * 注册领域词汇表。
     *
     * @param vocabulary 领域词汇表
     * @throws IllegalArgumentException 如果词汇表验证失败
     */
    public void register(DomainVocabulary vocabulary) {
        DomainVocabulary.ValidationResult result = vocabulary.validate();
        if (!result.valid()) {
            throw new IllegalArgumentException(
                "词汇表 '" + vocabulary.id() + "' 验证失败: " +
                String.join("; ", result.errors())
            );
        }

        if (!result.warnings().isEmpty()) {
            LOG.log(Level.WARNING, "词汇表 ''{0}'' 警告: {1}",
                vocabulary.id(), String.join("; ", result.warnings()));
        }

        String key = makeKey(vocabulary.id(), vocabulary.locale());
        IdentifierIndex index = IdentifierIndex.build(vocabulary);
        vocabularies.put(key, new VocabularyEntry(vocabulary, index));
    }

    /**
     * 注册自定义/租户级词汇表。
     *
     * @param tenantId   租户标识符
     * @param vocabulary 领域词汇表
     */
    public void registerCustom(String tenantId, DomainVocabulary vocabulary) {
        DomainVocabulary.ValidationResult result = vocabulary.validate();
        if (!result.valid()) {
            throw new IllegalArgumentException(
                "自定义词汇表 '" + vocabulary.id() + "' 验证失败: " +
                String.join("; ", result.errors())
            );
        }

        String key = makeCustomKey(tenantId, vocabulary.id(), vocabulary.locale());
        IdentifierIndex index = IdentifierIndex.build(vocabulary);
        customVocabularies.put(key, new VocabularyEntry(vocabulary, index));
    }

    /**
     * 获取领域词汇表。
     *
     * @param domain 领域标识符
     * @param locale 语言代码
     * @return 词汇表条目，如果不存在返回 empty
     */
    public Optional<VocabularyEntry> get(String domain, String locale) {
        return Optional.ofNullable(vocabularies.get(makeKey(domain, locale)));
    }

    /**
     * 获取领域词汇表索引。
     *
     * @param domain 领域标识符
     * @param locale 语言代码
     * @return 标识符索引，如果不存在返回 empty
     */
    public Optional<IdentifierIndex> getIndex(String domain, String locale) {
        return get(domain, locale).map(VocabularyEntry::index);
    }

    /**
     * 获取自定义词汇表（优先于内置）。
     *
     * @param tenantId 租户标识符（可为 null）
     * @param domain   领域标识符
     * @param locale   语言代码
     * @return 词汇表条目
     */
    public Optional<VocabularyEntry> getWithCustom(String tenantId, String domain, String locale) {
        // 优先查找租户自定义词汇表
        if (tenantId != null) {
            String customKey = makeCustomKey(tenantId, domain, locale);
            VocabularyEntry custom = customVocabularies.get(customKey);
            if (custom != null) {
                return Optional.of(custom);
            }
        }

        // 回退到内置词汇表
        return get(domain, locale);
    }

    /**
     * 合并多个领域的词汇表。
     *
     * @param domains 领域标识符列表
     * @param locale  语言代码
     * @return 合并后的词汇表，如果没有匹配返回 empty
     */
    public Optional<DomainVocabulary> merge(List<String> domains, String locale) {
        List<VocabularyEntry> entries = new ArrayList<>();
        for (String domain : domains) {
            get(domain, locale).ifPresent(entries::add);
        }

        if (entries.isEmpty()) {
            return Optional.empty();
        }

        // 合并所有映射
        List<IdentifierMapping> allStructs = new ArrayList<>();
        List<IdentifierMapping> allFields = new ArrayList<>();
        List<IdentifierMapping> allFunctions = new ArrayList<>();
        List<IdentifierMapping> allEnumValues = new ArrayList<>();
        List<IdentifierMapping> allLiterals = new ArrayList<>();

        for (VocabularyEntry entry : entries) {
            DomainVocabulary vocab = entry.vocabulary();
            allStructs.addAll(vocab.structs());
            allFields.addAll(vocab.fields());
            allFunctions.addAll(vocab.functions());
            allEnumValues.addAll(vocab.enumValues());
            allLiterals.addAll(vocab.literals());
        }

        String mergedId = String.join("+", domains);
        String mergedName = entries.stream()
            .map(e -> e.vocabulary().name())
            .reduce((a, b) -> a + " + " + b)
            .orElse("");

        DomainVocabulary merged = new DomainVocabulary(
            mergedId, mergedName, locale, "1.0.0",
            allStructs, allFields, allFunctions, allEnumValues, allLiterals, null
        );

        // 审计 #58：合并后的词汇表此前直接喂给 IdentifierIndex.build（Canonicalizer.buildMergedIndex）
        // 而不校验。"字面量宏触发词全局唯一" 的 P0 不变式只在**单领域** validate() 里执行，
        // 跨领域合并会产生新的触发词冲突（如 A 域的字面量宏触发词 == B 域某标识符触发词），
        // 使 translateToken 把一个标识符的规范名当作字符串字面量包裹。故此处对合并结果整体
        // validate()，跨域冲突大声失败而非静默污染。
        DomainVocabulary.ValidationResult validation = merged.validate();
        if (!validation.valid()) {
            throw new IllegalArgumentException(
                "合并词汇表 '" + mergedId + "' 校验失败（跨领域字面量宏触发词冲突等）: "
                + String.join("; ", validation.errors()));
        }

        return Optional.of(merged);
    }

    /**
     * 获取所有已注册的领域列表。
     *
     * @param locale 可选，按语言过滤
     * @return 领域标识符列表
     */
    public List<String> listDomains(String locale) {
        Set<String> domains = new HashSet<>();
        for (String key : vocabularies.keySet()) {
            String[] parts = key.split(":");
            if (parts.length >= 2) {
                String domain = parts[0];
                String loc = parts[1];
                if (locale == null || loc.equalsIgnoreCase(locale)) {
                    domains.add(domain);
                }
            }
        }
        return new ArrayList<>(domains);
    }

    /**
     * 获取指定语言的所有词汇表。
     *
     * @param locale 语言代码
     * @return 词汇表列表
     */
    public List<DomainVocabulary> listByLocale(String locale) {
        List<DomainVocabulary> result = new ArrayList<>();
        String suffix = ":" + locale.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, VocabularyEntry> entry : vocabularies.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                result.add(entry.getValue().vocabulary());
            }
        }
        return result;
    }

    /**
     * 获取所有已注册的词汇表。
     *
     * @return 所有词汇表列表（不含租户自定义）
     */
    public List<DomainVocabulary> listAll() {
        return vocabularies.values().stream()
            .map(VocabularyEntry::vocabulary)
            .toList();
    }

    /**
     * 清除所有注册的词汇表（包括内置）。
     */
    public void clear() {
        vocabularies.clear();
        customVocabularies.clear();
    }

    /**
     * 重置为初始状态（清除后重新加载内置）。
     */
    public synchronized void reset() {
        clear();
        initBuiltinVocabularies();
    }

    /**
     * 卸载指定词汇表。
     *
     * @param domain 领域标识符
     * @param locale 语言代码
     * @return 是否成功卸载
     */
    public boolean unregister(String domain, String locale) {
        return vocabularies.remove(makeKey(domain, locale)) != null;
    }

    /**
     * 卸载指定租户的自定义词汇表。
     *
     * @param tenantId 租户标识符
     * @param domain   领域标识符
     * @param locale   语言代码
     * @return 是否成功卸载
     */
    public boolean unregisterCustom(String tenantId, String domain, String locale) {
        return customVocabularies.remove(makeCustomKey(tenantId, domain, locale)) != null;
    }

    /**
     * 词汇表条目。
     */
    public record VocabularyEntry(
        DomainVocabulary vocabulary,
        IdentifierIndex index
    ) {}
}
