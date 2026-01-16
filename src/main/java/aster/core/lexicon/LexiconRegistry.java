package aster.core.lexicon;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 词法表注册中心 - 管理所有已注册的 Lexicon 实现。
 * <p>
 * 使用单例模式，确保全局唯一的词法表注册表。
 */
public final class LexiconRegistry {

    private static final LexiconRegistry INSTANCE = new LexiconRegistry();

    private final Map<String, Lexicon> lexicons = new HashMap<>();
    private String defaultLexiconId = "en-US";

    private LexiconRegistry() {
        // 注册内置词法表
        registerBuiltin(EnUsLexicon.INSTANCE);
        registerBuiltin(ZhCnLexicon.INSTANCE);
    }

    /**
     * 注册内置词法表（跳过验证，因为内置词法表已在代码中保证正确性）
     *
     * @param lexicon 内置词法表
     */
    private void registerBuiltin(Lexicon lexicon) {
        // 使用归一化 ID 注册，确保大小写一致性
        lexicons.put(normalizeId(lexicon.getId()), lexicon);
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
        ValidationResult result = validate(lexicon);
        if (!result.isValid()) {
            throw new IllegalArgumentException(
                "Invalid lexicon '" + lexicon.getId() + "': " + String.join("; ", result.errors())
            );
        }

        String normalizedId = normalizeId(lexicon.getId());
        if (lexicons.containsKey(normalizedId)) {
            throw new IllegalArgumentException("Lexicon '" + lexicon.getId() + "' already registered");
        }

        // 使用归一化 ID 注册，确保大小写一致性
        lexicons.put(normalizedId, lexicon);
    }

    /**
     * 获取指定 ID 的 Lexicon
     *
     * @param id 词法表 ID（支持大小写不敏感和下划线格式）
     * @return 对应的 Lexicon，如果不存在则返回 empty
     */
    public Optional<Lexicon> get(String id) {
        return Optional.ofNullable(lexicons.get(normalizeId(id)));
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
        return lexicons.containsKey(normalizeId(id));
    }

    /**
     * 列出所有已注册的 Lexicon ID
     * <p>
     * 返回词法表声明的原始 ID（保留原始大小写），而非归一化后的 ID。
     *
     * @return Lexicon ID 列表
     */
    public List<String> list() {
        // 返回词法表的原始 ID（从 Lexicon.getId() 获取），而非存储的归一化 ID
        List<String> originalIds = new ArrayList<>();
        for (Lexicon lexicon : lexicons.values()) {
            originalIds.add(lexicon.getId());
        }
        return originalIds;
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
     * 设置默认 Lexicon ID
     *
     * @param id 新的默认词法表 ID（支持大小写不敏感和下划线格式）
     * @throws IllegalArgumentException 如果词法表不存在
     */
    public void setDefault(String id) {
        String normalizedId = normalizeId(id);
        if (!lexicons.containsKey(normalizedId)) {
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

        // 2. 验证所有必需关键词都已定义
        Set<SemanticTokenKind> definedKinds = lexicon.getKeywords().keySet();
        Set<SemanticTokenKind> allKinds = EnumSet.allOf(SemanticTokenKind.class);

        Set<SemanticTokenKind> missing = new HashSet<>(allKinds);
        missing.removeAll(definedKinds);

        if (!missing.isEmpty()) {
            errors.add("Missing keywords for: " + missing);
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
     * 清除所有注册的 Lexicon（仅用于测试）
     */
    public void clear() {
        lexicons.clear();
        defaultLexiconId = "en-US";
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
