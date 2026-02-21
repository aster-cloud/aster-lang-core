package aster.core.canonicalizer;

import aster.core.identifier.IdentifierIndex;
import aster.core.identifier.VocabularyRegistry;
import aster.core.lexicon.CanonicalizationConfig;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CNL 源码规范化器
 * <p>
 * Canonicalizer 将 CNL 源代码规范化为标准格式，是 Aster 编译管道的第一步。
 * <p>
 * <b>功能</b>：
 * <ul>
 *   <li>规范化换行符为 {@code \n}</li>
 *   <li>将制表符转换为 2 个空格（Aster 使用 2 空格缩进）</li>
 *   <li>移除行注释（{@code //} 和 {@code #}）</li>
 *   <li>规范化引号（智能引号 → 直引号）</li>
 *   <li>规范化多词关键字大小写</li>
 *   <li><b>翻译关键词</b>：将非英语关键词翻译为英语关键词，以便 ANTLR 解析器处理</li>
 *   <li>去除冠词（根据词法表配置），但保留字符串字面量内的冠词</li>
 *   <li>规范化空白符（折叠多余空格，保持缩进）</li>
 *   <li>全角字符转半角（用于中文 CNL）</li>
 * </ul>
 * <p>
 * <b>多语言支持</b>：
 * <ul>
 *   <li>通过 Lexicon 指定源语言（如 zh-CN 词法表）</li>
 *   <li>自动将源语言关键词翻译为英语（如 "若" → "if"，"模块" → "Module"）</li>
 *   <li>保留字符串字面量内的原始内容不变</li>
 * </ul>
 * <p>
 * <b>注意</b>：
 * <ul>
 *   <li>缩进具有语法意义，必须保持精确</li>
 *   <li>字符串字面量内的内容不受影响</li>
 *   <li>标识符的大小写保持原样</li>
 * </ul>
 *
 * @see <a href="https://github.com/anthropics/aster-lang">Aster Lang 文档</a>
 */
public final class Canonicalizer {

    private final Lexicon lexicon;
    private final CanonicalizationConfig config;
    private final List<String> multiWordKeywords;
    private final Pattern articlePattern;
    /** 关键词翻译映射：源语言关键词 → 英语关键词（用于非英语 Lexicon） */
    private final Map<String, String> keywordTranslationMap;
    /** 预排序的翻译条目（按关键词长度降序，最长匹配优先） */
    private final List<Map.Entry<String, String>> sortedTranslationEntries;
    /** 预编译的关键词匹配 Pattern 缓存（避免每次翻译时重新编译） */
    private final Map<String, Pattern> keywordPatternCache;
    /** 字符串开始引号（从词法表配置获取） */
    private final String stringQuoteOpen;
    /** 字符串结束引号（从词法表配置获取） */
    private final String stringQuoteClose;
    /** 字符串分段器（供 SyntaxTransformer 使用） */
    private final StringSegmenter stringSegmenter;
    /** 预编译的多词关键字 Pattern 缓存（避免每次规范化时重新编译） */
    private final List<Map.Entry<String, Pattern>> multiWordKeywordPatterns;
    /** 标识符索引（用于领域词汇翻译，如 "驾驶员" → "Driver"） */
    private final IdentifierIndex identifierIndex;

    /**
     * 空白规范化所需的正则表达式
     */
    private static final Pattern SPACE_RUN_RE = Pattern.compile("[ \\t]+");
    private static final Pattern PUNCT_NORMAL_RE = Pattern.compile("\\s+([.,:。：，])");
    private static final Pattern PUNCT_FINAL_RE = Pattern.compile("\\s+([.,:!;?。：，！；？])");
    private static final Pattern TRAILING_SPACE_RE = Pattern.compile("\\s+$");

    /**
     * 使用默认词法表（en-US）创建规范化器
     */
    public Canonicalizer() {
        this(LexiconRegistry.getInstance().getDefault(), null);
    }

    /**
     * 使用指定词法表创建规范化器
     *
     * @param lexicon 词法表（定义关键词、标点符号和规范化规则）
     */
    public Canonicalizer(Lexicon lexicon) {
        this(lexicon, null);
    }

    /**
     * 使用指定词法表和领域词汇表创建规范化器
     *
     * @param lexicon 词法表（定义关键词、标点符号和规范化规则）
     * @param domain  领域标识符（如 "insurance.auto"），从 VocabularyRegistry 加载词汇表
     * @param locale  语言代码（如 "zh-CN"）
     */
    public Canonicalizer(Lexicon lexicon, String domain, String locale) {
        this(lexicon, VocabularyRegistry.getInstance()
            .getIndex(domain, locale)
            .orElse(null));
    }

    /**
     * 使用指定词法表和多领域词汇表创建规范化器
     *
     * @param lexicon 词法表（定义关键词、标点符号和规范化规则）
     * @param domains 领域标识符列表（将合并多个领域的词汇）
     * @param locale  语言代码（如 "zh-CN"）
     */
    public Canonicalizer(Lexicon lexicon, List<String> domains, String locale) {
        this(lexicon, buildMergedIndex(domains, locale));
    }

    /**
     * 使用指定词法表和标识符索引创建规范化器
     *
     * @param lexicon         词法表（定义关键词、标点符号和规范化规则）
     * @param identifierIndex 标识符索引（可为 null，表示不进行标识符翻译）
     */
    public Canonicalizer(Lexicon lexicon, IdentifierIndex identifierIndex) {
        this.lexicon = lexicon;
        this.config = lexicon.getCanonicalization();
        this.multiWordKeywords = lexicon.getMultiWordKeywords();
        this.articlePattern = buildArticlePattern();
        this.keywordTranslationMap = buildKeywordTranslationMap(lexicon);
        // 预排序翻译条目（按关键词长度降序，最长匹配优先）
        List<Map.Entry<String, String>> entries = new ArrayList<>(keywordTranslationMap.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        this.sortedTranslationEntries = List.copyOf(entries);  // 不可变视图
        // 预编译所有关键词的 Pattern（避免每次翻译时重复编译，提升性能）
        Map<String, Pattern> patternCache = new HashMap<>();
        for (String keyword : keywordTranslationMap.keySet()) {
            Pattern pattern = Pattern.compile(
                    Pattern.quote(keyword),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            );
            patternCache.put(keyword, pattern);
        }
        this.keywordPatternCache = Map.copyOf(patternCache);  // 不可变视图
        // 从词法表获取字符串引号配置
        var punctuation = lexicon.getPunctuation();
        this.stringQuoteOpen = punctuation.stringQuoteOpen();
        this.stringQuoteClose = punctuation.stringQuoteClose();
        this.stringSegmenter = new StringSegmenter(stringQuoteOpen, stringQuoteClose);
        // 预编译多词关键字的 Pattern（大小写不敏感，支持 Unicode）
        List<Map.Entry<String, Pattern>> mwkPatterns = new ArrayList<>();
        for (String keyword : multiWordKeywords) {
            Pattern pattern = Pattern.compile(
                    Pattern.quote(keyword),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            );
            mwkPatterns.add(Map.entry(keyword, pattern));
        }
        this.multiWordKeywordPatterns = List.copyOf(mwkPatterns);  // 不可变视图
        // 设置标识符索引（用于领域词汇翻译）
        this.identifierIndex = identifierIndex;
    }

    /**
     * 从多领域构建合并的标识符索引
     *
     * @param domains 领域标识符列表
     * @param locale  语言代码
     * @return 合并后的标识符索引，如果没有匹配返回 null
     */
    private static IdentifierIndex buildMergedIndex(List<String> domains, String locale) {
        if (domains == null || domains.isEmpty()) {
            return null;
        }
        return VocabularyRegistry.getInstance()
            .merge(domains, locale)
            .map(IdentifierIndex::build)
            .orElse(null);
    }

    /**
     * 需要翻译为符号的比较/算术运算符映射（用于 ANTLR 兼容）
     * <p>
     * ANTLR 解析器只支持符号运算符（如 {@code <}, {@code >}），不支持关键词形式（如 "less than", "小于"）。
     * 这些关键词需要直接翻译为符号，而非翻译为英语关键词。
     */
    private static final Map<SemanticTokenKind, String> OPERATOR_SYMBOL_MAP = Map.ofEntries(
        Map.entry(SemanticTokenKind.LESS_THAN, "<"),
        Map.entry(SemanticTokenKind.GREATER_THAN, ">"),
        Map.entry(SemanticTokenKind.EQUALS_TO, "=="),
        Map.entry(SemanticTokenKind.PLUS, "+"),
        Map.entry(SemanticTokenKind.MINUS_WORD, "-"),
        Map.entry(SemanticTokenKind.TIMES, "*"),
        Map.entry(SemanticTokenKind.DIVIDED_BY, "/"),
        Map.entry(SemanticTokenKind.UNDER, "<"),
        Map.entry(SemanticTokenKind.OVER, ">"),
        Map.entry(SemanticTokenKind.MORE_THAN, ">")
    );

    /**
     * 构建关键词翻译映射
     * <p>
     * 用于将 CNL 关键词翻译为 ANTLR 解析器可识别的形式：
     * <ul>
     *   <li>运算符关键词（如 "小于", "less than"）翻译为符号（如 {@code <}）</li>
     *   <li>非英语关键词（如 "若", "模块"）翻译为英语关键词（如 "if", "Module"）</li>
     * </ul>
     *
     * @param sourceLexicon 源语言词法表
     * @return 关键词翻译映射
     */
    private Map<String, String> buildKeywordTranslationMap(Lexicon sourceLexicon) {
        Map<String, String> translationMap = new HashMap<>();
        Lexicon targetLexicon = LexiconRegistry.getInstance().getOrThrow("en-US");

        // 1. 首先处理运算符关键词 -> 符号的翻译（适用于所有语言）
        // 例如：中文 "小于" -> "<"，英文 "less than" -> "<"
        for (Map.Entry<SemanticTokenKind, String> symbolEntry : OPERATOR_SYMBOL_MAP.entrySet()) {
            SemanticTokenKind kind = symbolEntry.getKey();
            String symbol = symbolEntry.getValue();

            // 添加源语言关键词到符号的映射
            String sourceKeyword = sourceLexicon.getKeywords().get(kind);
            if (sourceKeyword != null && !sourceKeyword.equals(symbol)) {
                translationMap.put(applyCustomRulesToKey(sourceKeyword), symbol);
            }

            // 如果源语言不是英语，也需要添加英语关键词到符号的映射
            // 因为规范化后可能保留英语运算符关键词，需要再翻译为符号
            if (!"en-US".equals(sourceLexicon.getId())) {
                String englishKeyword = targetLexicon.getKeywords().get(kind);
                if (englishKeyword != null && !englishKeyword.equals(symbol) && !translationMap.containsKey(englishKeyword)) {
                    translationMap.put(englishKeyword, symbol);
                }
            }
        }

        // 2. 如果是英语词法表，只需要运算符符号翻译，不需要关键词翻译
        if ("en-US".equals(sourceLexicon.getId())) {
            return translationMap;
        }

        // 3. 遍历其他关键词，建立到英语关键词的映射（排除已处理的运算符）
        for (Map.Entry<SemanticTokenKind, String> entry : sourceLexicon.getKeywords().entrySet()) {
            SemanticTokenKind kind = entry.getKey();
            String sourceKeyword = entry.getValue();

            // 跳过已处理的运算符
            if (OPERATOR_SYMBOL_MAP.containsKey(kind)) {
                continue;
            }

            String targetKeyword = targetLexicon.getKeywords().get(kind);
            if (targetKeyword != null && !sourceKeyword.equals(targetKeyword)) {
                // 对关键词应用 customRules 变换，使翻译表匹配 customRules 处理后的文本
                // 例如：德语 "gib zurueck" 经 customRules 后变为 "gib zurück"
                translationMap.put(applyCustomRulesToKey(sourceKeyword), targetKeyword);
            }
        }

        return translationMap;
    }

    /**
     * 对关键词应用 customRules 变换
     * <p>
     * 确保翻译表中的关键词与 customRules 处理后的源码文本匹配。
     * 例如：德语 {@code "gib zurueck"} 经 umlaut 规则后变为 {@code "gib zurück"}。
     */
    private String applyCustomRulesToKey(String keyword) {
        if (config.customRules().isEmpty()) {
            return keyword;
        }
        String result = keyword;
        for (CanonicalizationConfig.CanonicalizationRule rule : config.customRules()) {
            result = result.replaceAll(rule.pattern(), rule.replacement());
        }
        return result;
    }

    /**
     * 根据配置构建冠词正则表达式
     * <p>
     * 使用 UNICODE_CASE 和 UNICODE_CHARACTER_CLASS 支持：
     * - 含变音符的冠词（如法语、德语等）
     * - 非 ASCII 字母的词边界（如希腊语、西里尔语等）
     * - 句末和标点前的冠词（如 "the." "una," "一个。"）
     * <p>
     * 安全处理：使用 Pattern.quote 转义冠词中可能包含的正则元字符
     */
    private Pattern buildArticlePattern() {
        if (!config.removeArticles() || config.articles() == null || config.articles().isEmpty()) {
            return null;
        }
        // 对每个冠词使用 Pattern.quote 转义正则元字符
        String quotedArticles = config.articles().stream()
                .map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
        // 使用 Unicode 兼容的词边界：
        // (?<!\p{L}) - 前面不是字母（支持 Unicode 字母）
        // (?=\s|$|[\p{Punct}\u3001-\u303F\uFF00-\uFF65]) - 后面是空白、行尾、ASCII 标点或中日韩标点
        String pattern = "(?<!\\p{L})(" + quotedArticles + ")(?=\\s|$|[\\p{Punct}\\u3001-\\u303F\\uFF00-\\uFF65])\\s?";
        return Pattern.compile(pattern,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    /**
     * 获取当前使用的词法表
     */
    public Lexicon getLexicon() {
        return lexicon;
    }

    /**
     * 获取当前使用的标识符索引
     *
     * @return 标识符索引，如果未配置则返回 null
     */
    public IdentifierIndex getIdentifierIndex() {
        return identifierIndex;
    }

    /**
     * 检查是否启用了标识符翻译
     *
     * @return 如果配置了标识符索引则返回 true
     */
    public boolean hasIdentifierTranslation() {
        return identifierIndex != null;
    }

    /**
     * 规范化 CNL 源代码为标准格式
     *
     * @param input 原始 CNL 源代码字符串
     * @return 规范化后的 CNL 源代码
     */
    public String canonicalize(String input) {
        // 1. 规范化换行符为 \n
        String s = input.replaceAll("\\r\\n?", "\n");

        // 2. 将制表符转换为 2 个空格（缩进具有 2 空格语法意义）
        s = s.replace("\t", "  ");

        // 3. 移除行注释（// 和 #）
        s = removeLineComments(s);

        // 4. 规范化智能引号为直引号（同时保留中文直角引号「」）
        s = s.replace("\u201C", "\"")  // 左双引号 "
             .replace("\u201D", "\"")  // 右双引号 "
             .replace("\u2018", "'")   // 左单引号 '
             .replace("\u2019", "'");  // 右单引号 '

        // 4.5-4.8 执行关键词翻译前的语法变换器链
        // （包括属格转换、标点翻译、运算符翻译、函数语法重排等）
        for (SyntaxTransformer transformer : config.preTranslationTransformers()) {
            s = transformer.transform(s, config, stringSegmenter);
        }

        // 5. 全角转半角（如果配置启用）
        if (config.fullWidthToHalf()) {
            s = fullWidthToHalfWidth(s);
        }

        // 6. 折叠多余空格，保持缩进
        s = normalizeWhitespace(s);

        // 7. 规范化多词关键字大小写
        s = normalizeMultiWordKeywords(s);

        // 7.8 执行自定义规范化规则（如德语 umlaut 替换：oe → ö）
        // 必须在关键词翻译之前执行，确保翻译表中的关键词能匹配规范化后的文本
        if (!config.customRules().isEmpty()) {
            s = applyCustomRules(s);
        }

        // 8. 翻译关键词（非英语 → 英语，用于 ANTLR 解析）
        if (!keywordTranslationMap.isEmpty()) {
            s = translateKeywords(s);
        }

        // 8.5 翻译领域标识符（如 "驾驶员" → "Driver"，用于多语言 CNL）
        // 必须在关键词翻译之后执行，确保关键词不会被误识别为标识符
        if (identifierIndex != null) {
            s = translateIdentifiers(s);
        }

        // 8.6-8.9 执行关键词翻译后的语法变换器链
        // （包括 "The result is" → "Return"、"Set X to" → "Let X be" 等句式重写）
        for (SyntaxTransformer transformer : config.postTranslationTransformers()) {
            s = transformer.transform(s, config, stringSegmenter);
        }

        // 9. 去除冠词（保留字符串字面量内的冠词，根据词法表配置）
        if (config.removeArticles() && articlePattern != null) {
            s = removeArticles(s);
        }

        // 9.5 中文字符串引号转英文引号（ANTLR 词法器只识别 ASCII 双引号）
        // 必须在所有字符串分段操作完成后执行（因为分段依赖 「」 识别字符串边界）
        s = s.replace("\u300C", "\"")  // 「 → "
             .replace("\u300D", "\""); // 」 → "

        // 10. 最终空白符规范化（确保幂等性）
        s = finalWhitespaceNormalization(s);

        return s;
    }

    // ============================================================
    // 自定义规范化规则执行
    // ============================================================

    /**
     * 执行自定义规范化规则
     * <p>
     * 遍历 {@link CanonicalizationConfig#customRules()} 中定义的规则，
     * 对字符串字面量外的代码段执行正则替换。
     * <p>
     * 典型用例：德语 ASCII umlaut 替换（oe → ö, ue → ü, ae → ä）
     */
    private String applyCustomRules(String s) {
        for (CanonicalizationConfig.CanonicalizationRule rule : config.customRules()) {
            Pattern pattern = Pattern.compile(rule.pattern());
            List<Segment> segments = segmentString(s);
            StringBuilder result = new StringBuilder(s.length());

            for (Segment segment : segments) {
                if (segment.inString()) {
                    result.append(segment.text());
                } else {
                    result.append(pattern.matcher(segment.text()).replaceAll(rule.replacement()));
                }
            }
            s = result.toString();
        }
        return s;
    }

    /**
     * 翻译关键词（源语言 → 英语）
     * <p>
     * 将非英语关键词翻译为英语关键词，以便 ANTLR 解析器能够处理。
     * 翻译时保留字符串字面量内的内容不变。
     *
     * @param s 输入字符串
     * @return 翻译后的字符串
     */
    private String translateKeywords(String s) {
        // 将源码分段为字符串内和字符串外的片段
        List<Segment> segments = segmentString(s);

        StringBuilder result = new StringBuilder();
        for (Segment segment : segments) {
            if (segment.inString) {
                // 字符串内，保持原样
                result.append(segment.text);
            } else {
                // 字符串外，翻译关键词
                String translated = translateSegment(segment.text);
                result.append(translated);
            }
        }

        return result.toString();
    }

    /**
     * 翻译领域标识符（如 "驾驶员" → "Driver"）
     * <p>
     * 使用 VocabularyRegistry 提供的标识符索引，将本地化标识符翻译为规范化标识符。
     * 翻译时保留字符串字面量内的内容不变。
     * <p>
     * <b>词边界规则</b>：
     * <ul>
     *   <li>标识符前必须是行首、空格、标点或非标识符字符</li>
     *   <li>标识符后必须是空格、标点、字符串结尾或非标识符字符</li>
     * </ul>
     *
     * @param s 输入字符串
     * @return 翻译后的字符串
     */
    private String translateIdentifiers(String s) {
        // 将源码分段为字符串内和字符串外的片段
        List<Segment> segments = segmentString(s);

        StringBuilder result = new StringBuilder();
        for (Segment segment : segments) {
            if (segment.inString) {
                // 字符串内，保持原样
                result.append(segment.text);
            } else {
                // 字符串外，翻译标识符
                String translated = translateIdentifiersInSegment(segment.text);
                result.append(translated);
            }
        }

        return result.toString();
    }

    /**
     * 翻译单个代码片段中的标识符
     * <p>
     * 识别代码片段中的标识符并使用词汇表翻译。
     * 标识符定义：以字母或下划线开头，后跟字母、数字或下划线的连续字符序列。
     * 支持中文标识符（Unicode 字母）。
     *
     * @param text 代码片段
     * @return 翻译后的代码片段
     */
    private String translateIdentifiersInSegment(String text) {
        StringBuilder result = new StringBuilder();
        StringBuilder currentToken = new StringBuilder();
        boolean inIdentifier = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (isIdentifierStart(ch)) {
                // 开始一个新的标识符
                if (!inIdentifier) {
                    inIdentifier = true;
                }
                currentToken.append(ch);
            } else if (inIdentifier && isIdentifierPart(ch)) {
                // 继续当前标识符
                currentToken.append(ch);
            } else {
                // 标识符结束，翻译并输出
                if (inIdentifier) {
                    String token = currentToken.toString();
                    String translated = identifierIndex.canonicalize(token);
                    result.append(translated);
                    currentToken.setLength(0);
                    inIdentifier = false;
                }
                // 输出非标识符字符
                result.append(ch);
            }
        }

        // 处理末尾的标识符
        if (inIdentifier) {
            String token = currentToken.toString();
            String translated = identifierIndex.canonicalize(token);
            result.append(translated);
        }

        return result.toString();
    }

    /**
     * 判断字符是否可以作为标识符的开始
     * <p>
     * 包括：Unicode 字母、下划线
     */
    private boolean isIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    /**
     * 判断字符是否可以作为标识符的一部分
     * <p>
     * 包括：Unicode 字母、数字、下划线
     */
    private boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    /**
     * 翻译单个代码片段中的关键词
     * <p>
     * 使用最长匹配策略替换关键词，并进行词边界检测防止破坏中文标识符。
     * 当翻译后的关键词需要与后续标识符分隔时，自动添加空格。
     * <p>
     * <b>词边界规则</b>：
     * <ul>
     *   <li>中文关键词（如 "若"）前必须是行首、空格、标点或非中文字符</li>
     *   <li>中文关键词后必须是空格、标点、字符串结尾或非中文字符</li>
     *   <li>英文关键词的前后必须是非标识符字符</li>
     * </ul>
     * <p>
     * <b>Unicode 安全</b>：使用 {@code Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE}
     * 进行大小写不敏感匹配，避免 {@code toLowerCase} 可能改变字符串长度的问题
     * （如土耳其语 İ → i\u0307）。
     *
     * @param text 代码片段
     * @return 翻译后的代码片段
     */
    private String translateSegment(String text) {
        String result = text;

        // 使用预排序的翻译条目（按长度降序），确保最长匹配优先
        for (Map.Entry<String, String> entry : sortedTranslationEntries) {
            String sourceKeyword = entry.getKey();
            String targetKeyword = entry.getValue();

            // 使用预编译的 Pattern（在构造函数中已缓存，避免重复编译）
            Pattern pattern = keywordPatternCache.get(sourceKeyword);
            java.util.regex.Matcher matcher = pattern.matcher(result);

            StringBuilder sb = new StringBuilder();
            int lastEnd = 0;

            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();

                // 词边界检测：防止破坏中文标识符
                if (!isWordBoundaryForMatch(result, start, end, sourceKeyword)) {
                    continue;  // 跳过非词边界的匹配
                }

                // 添加匹配前的文本
                sb.append(result, lastEnd, start);

                // 检查是否需要在翻译后的关键词后添加空格
                boolean needsSpace = false;
                if (end < result.length()) {
                    char nextChar = result.charAt(end);
                    char lastChar = targetKeyword.charAt(targetKeyword.length() - 1);
                    if (Character.isLetter(lastChar) &&
                        (Character.isLetter(nextChar) || Character.isDigit(nextChar))) {
                        needsSpace = true;
                    }
                }

                // 添加替换文本
                sb.append(targetKeyword);
                if (needsSpace) {
                    sb.append(' ');
                }

                lastEnd = end;
            }

            // 如果有匹配发生，添加剩余文本并更新结果
            if (lastEnd > 0) {
                sb.append(result, lastEnd, result.length());
                result = sb.toString();
            }
        }

        return result;
    }

    /**
     * 检查匹配位置是否在词边界上（用于 Matcher 版本）
     * <p>
     * 词边界规则：
     * <ul>
     *   <li>关键词前后不能是标识符字符（保护标识符完整性）</li>
     *   <li>例如：「若何」是一个词（意为"如何"），不应将「若」翻译为 if</li>
     * </ul>
     *
     * @param text          源文本
     * @param start         匹配起始位置
     * @param end           匹配结束位置
     * @param sourceKeyword 源关键词（用于特殊标记检测）
     * @return 如果在词边界上返回 true
     */
    private boolean isWordBoundaryForMatch(String text, int start, int end, String sourceKeyword) {
        boolean isChineseMode = config.whitespaceMode() == CanonicalizationConfig.WhitespaceMode.CHINESE;
        // 检测关键词是否属于无空格书写系统（中文/日文/韩文）
        boolean isSpacelessKeyword = !sourceKeyword.isEmpty() && isSpacelessScript(sourceKeyword.charAt(0));

        // 检查前一个字符：必须是词边界
        // 无论中文还是英文模式，关键词前面如果是标识符字符就不匹配
        if (start > 0) {
            char prevChar = text.charAt(start - 1);
            if (isIdentifierChar(prevChar) || isSpacelessScript(prevChar)) {
                return false;
            }
        }

        // 检查后一个字符
        if (end < text.length()) {
            char nextChar = text.charAt(end);
            if (isChineseMode && isSpacelessKeyword) {
                // 中文模式下，无空格书写系统的关键词后面如果紧跟标识符字符，
                // 则认为关键词是标识符的一部分，不进行翻译。
                // 例如：「若何」是一个词（意为"如何"），不应将「若」翻译为 if。
                // 这与英文模式的处理保持一致，保护标识符完整性。
                if (isIdentifierChar(nextChar)) {
                    return false;
                }
            } else {
                // 英文模式或英文关键词：后面不能是标识符字符或无空格书写系统字符
                if (isIdentifierChar(nextChar) || isSpacelessScript(nextChar)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * 判断字符是否为标识符字符
     * <p>
     * 包括：ASCII 字母、数字、下划线、无空格书写系统字符（中文/日文/韩文，非标点）
     */
    private boolean isIdentifierChar(char ch) {
        // 下划线始终是标识符字符（优先判断，避免被 CONNECTOR_PUNCTUATION 误判）
        if (ch == '_') {
            return true;
        }
        // ASCII 字母、数字
        if (Character.isLetterOrDigit(ch)) {
            // 排除标点
            if (isPunctuation(ch)) {
                return false;
            }
            return true;
        }
        // 无空格书写系统字符（排除标点）
        if (isSpacelessScript(ch) && !isPunctuation(ch)) {
            return true;
        }
        return false;
    }

    /**
     * 判断字符是否属于无空格书写系统
     * <p>
     * 包括：
     * <ul>
     *   <li>汉字（Han）- 中文、日文汉字</li>
     *   <li>平假名（Hiragana）- 日文</li>
     *   <li>片假名（Katakana）- 日文</li>
     *   <li>韩文（Hangul）- 韩文音节和字母</li>
     * </ul>
     * <p>
     * 这些书写系统中单词之间不需要空格分隔。
     */
    private boolean isSpacelessScript(char ch) {
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return script == Character.UnicodeScript.HAN
            || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA
            || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * 判断字符是否为标点符号
     * <p>
     * 包括：中文标点、ASCII 标点、Unicode 通用标点
     */
    private boolean isPunctuation(char ch) {
        // 常见中文标点（使用 Unicode 转义避免编译问题）
        // 。，、；：？！""''（）【】《》「」『』
        if ("\u3002\uFF0C\u3001\uFF1B\uFF1A\uFF1F\uFF01\u201C\u201D\u2018\u2019\uFF08\uFF09\u3010\u3011\u300A\u300B\u300C\u300D\u300E\u300F".indexOf(ch) >= 0) {
            return true;
        }
        // ASCII 标点
        if (".,;:!?()[]{}\"'`~@#$%^&*-+=<>/\\|".indexOf(ch) >= 0) {
            return true;
        }
        // Unicode 通用标点类别
        int type = Character.getType(ch);
        return type == Character.CONNECTOR_PUNCTUATION
            || type == Character.DASH_PUNCTUATION
            || type == Character.START_PUNCTUATION
            || type == Character.END_PUNCTUATION
            || type == Character.INITIAL_QUOTE_PUNCTUATION
            || type == Character.FINAL_QUOTE_PUNCTUATION
            || type == Character.OTHER_PUNCTUATION;
    }

    /**
     * 全角字符转半角（用于中文 CNL 规范化）
     * <p>
     * 转换规则：
     * <ul>
     *   <li>全角数字（０-９）→ 半角数字（0-9）</li>
     *   <li>全角字母（Ａ-Ｚ，ａ-ｚ）→ 半角字母（A-Z，a-z）</li>
     *   <li>全角空格（　）→ 半角空格（ ）</li>
     * </ul>
     * <p>
     * 注意：
     * <ul>
     *   <li>不转换中文标点（保留 。，：等中文标点）</li>
     *   <li>不转换字符串字面量内的内容</li>
     * </ul>
     */
    private String fullWidthToHalfWidth(String s) {
        // 使用 segmentString 保护字符串字面量
        List<Segment> segments = segmentString(s);
        StringBuilder result = new StringBuilder(s.length());

        for (Segment segment : segments) {
            if (segment.inString) {
                // 字符串内，保持原样
                result.append(segment.text);
            } else {
                // 字符串外，执行全角转半角
                result.append(fullWidthToHalfWidthImpl(segment.text));
            }
        }
        return result.toString();
    }

    /**
     * 全角转半角的实际实现
     */
    private String fullWidthToHalfWidthImpl(String s) {
        StringBuilder result = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // 全角空格
            if (ch == '\u3000') {
                result.append(' ');
            }
            // 全角 ASCII 字符（！到～对应全角！到～）
            else if (ch >= '\uFF01' && ch <= '\uFF5E') {
                result.append((char) (ch - 0xFEE0));
            }
            else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    /**
     * 移除行注释（// 和 #）
     * <p>
     * 感知字符串上下文：不会删除字符串字面量内部的注释标记。
     * 支持 ASCII 直引号 " 和智能引号 "" 以及中文引号 「」。
     * <p>
     * 处理行尾注释：如 {@code 若 条件 // 说明} 会被截断为 {@code 若 条件 }
     */
    private String removeLineComments(String s) {
        // 使用 segmentString 保护字符串字面量（复用词法表引号配置）
        List<Segment> segments = segmentString(s);

        StringBuilder result = new StringBuilder();
        for (Segment segment : segments) {
            if (segment.inString) {
                // 字符串内，保持原样
                result.append(segment.text);
            } else {
                // 字符串外，移除注释
                result.append(removeCommentsFromCode(segment.text));
            }
        }

        return result.toString();
    }

    /**
     * 从代码片段中移除行注释（// 和 #）
     * <p>
     * 仅处理字符串外的代码片段，每行查找第一个注释标记并截断。
     *
     * @param code 代码片段（已确保不在字符串内）
     * @return 移除注释后的代码
     */
    private String removeCommentsFromCode(String code) {
        StringBuilder result = new StringBuilder();
        String[] lines = code.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append('\n');
            }

            String line = lines[i];
            int commentIdx = -1;

            // 查找第一个注释标记
            int slashSlash = line.indexOf("//");
            int hash = line.indexOf('#');

            if (slashSlash >= 0 && hash >= 0) {
                commentIdx = Math.min(slashSlash, hash);
            } else if (slashSlash >= 0) {
                commentIdx = slashSlash;
            } else if (hash >= 0) {
                commentIdx = hash;
            }

            // 截断注释部分
            if (commentIdx >= 0) {
                result.append(line, 0, commentIdx);
            } else {
                result.append(line);
            }
        }

        return result.toString();
    }

    /**
     * 规范化空白符：折叠多余空格，保持缩进
     * <p>
     * 对每一行：
     * - 保留前导空格（缩进）
     * - 折叠多余空格为单个空格
     * - 移除标点前的空格
     * <p>
     * 注意：跟踪字符串状态跨行，确保跨行字符串内容不被规范化。
     */
    private String normalizeWhitespace(String s) {
        return normalizeWhitespaceWithState(s, PUNCT_NORMAL_RE, false);
    }

    /**
     * 带状态跟踪的空白规范化（用于跨行字符串处理）
     *
     * @param s                  输入字符串
     * @param punctuationPattern 标点规范化正则
     * @param trimTrailing       是否移除行尾空格
     */
    private String normalizeWhitespaceWithState(String s, Pattern punctuationPattern, boolean trimTrailing) {
        StringBuilder result = new StringBuilder();
        String[] lines = s.split("\n", -1);

        // 跟踪字符串状态跨行
        boolean inString = false;
        String expectedClose = null;

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append('\n');
            }

            String line = lines[i];

            // 使用状态感知的行规范化
            // 注意：trimTrailing 直接传递给每行，行内逻辑会判断是否在最后一个代码段
            LineNormalizeResult lineResult = normalizeLineWithState(
                    line, punctuationPattern, trimTrailing, inString, expectedClose);

            result.append(lineResult.normalized);
            inString = lineResult.endsInString;
            expectedClose = lineResult.expectedClose;
        }

        return result.toString();
    }

    /**
     * 行规范化结果（包含状态信息）
     */
    private record LineNormalizeResult(String normalized, boolean endsInString, String expectedClose) {}

    /**
     * 带状态的单行规范化
     *
     * @param line               输入行
     * @param punctuationPattern 标点规范化正则
     * @param trimTrailing       是否移除行尾空格
     * @param startInString      行开始时是否在字符串内
     * @param startExpectedClose 行开始时期望的关闭引号
     * @return 规范化结果及结束状态
     */
    private LineNormalizeResult normalizeLineWithState(String line, Pattern punctuationPattern,
                                                        boolean trimTrailing, boolean startInString,
                                                        String startExpectedClose) {
        if (line.isEmpty()) {
            return new LineNormalizeResult(line, startInString, startExpectedClose);
        }

        // 提取缩进
        int indentEnd = 0;
        while (indentEnd < line.length() && Character.isWhitespace(line.charAt(indentEnd))) {
            indentEnd++;
        }
        String indent = line.substring(0, indentEnd);
        String rest = line.substring(indentEnd);

        if (rest.isEmpty()) {
            return new LineNormalizeResult(indent, startInString, startExpectedClose);
        }

        // 带状态分段并规范化
        SegmentResult segResult = segmentStringWithState(rest, startInString, startExpectedClose);

        StringBuilder builder = new StringBuilder();
        List<Segment> segments = segResult.segments;

        for (int j = 0; j < segments.size(); j++) {
            Segment segment = segments.get(j);
            if (segment.inString) {
                builder.append(segment.text);
            } else {
                String normalized = collapseSpaces(segment.text);
                normalized = punctuationPattern.matcher(normalized).replaceAll("$1");

                // 只在行尾的代码段（最后一个非字符串段）trimTrailing
                boolean isLastCodeSegment = true;
                for (int k = j + 1; k < segments.size(); k++) {
                    if (!segments.get(k).inString) {
                        isLastCodeSegment = false;
                        break;
                    }
                }
                if (trimTrailing && isLastCodeSegment && j == segments.size() - 1) {
                    normalized = TRAILING_SPACE_RE.matcher(normalized).replaceAll("");
                }

                builder.append(normalized);
            }
        }

        return new LineNormalizeResult(indent + builder.toString(),
                segResult.endsInString, segResult.expectedClose);
    }

    /**
     * 分段结果（包含状态信息）
     */
    private record SegmentResult(List<Segment> segments, boolean endsInString, String expectedClose) {}

    /**
     * 带状态的字符串分段
     *
     * @param s                  输入字符串
     * @param startInString      开始时是否在字符串内
     * @param startExpectedClose 开始时期望的关闭引号
     * @return 分段结果及结束状态
     */
    private SegmentResult segmentStringWithState(String s, boolean startInString, String startExpectedClose) {
        List<Segment> segments = new ArrayList<>();
        boolean inString = startInString;
        String expectedClose = startExpectedClose;
        StringBuilder current = new StringBuilder();

        // 支持的引号对
        Map<String, String> quotePairs = new HashMap<>();
        quotePairs.put(stringQuoteOpen, stringQuoteClose);
        if (!stringQuoteOpen.equals("\"")) {
            quotePairs.put("\"", "\"");
        }
        quotePairs.put("\u201C", "\u201D");

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            current.append(ch);

            if (!inString) {
                String chStr = String.valueOf(ch);
                if (quotePairs.containsKey(chStr) && !isEscaped(s, i)) {
                    String before = current.substring(0, current.length() - 1);
                    if (!before.isEmpty()) {
                        segments.add(new Segment(before, false));
                    }
                    current = new StringBuilder(chStr);
                    inString = true;
                    expectedClose = quotePairs.get(chStr);
                }
            } else if (expectedClose != null && expectedClose.equals(String.valueOf(ch)) && !isEscaped(s, i)) {
                segments.add(new Segment(current.toString(), true));
                current = new StringBuilder();
                inString = false;
                expectedClose = null;
            }
        }

        if (current.length() > 0) {
            segments.add(new Segment(current.toString(), inString));
        }

        return new SegmentResult(segments, inString, expectedClose);
    }

    /**
     * 规范化多词关键字大小写
     * <p>
     * 将多词关键字（如 "This Module Is"）转换为小写（"this module is"），
     * 以便词法分析器正确识别。
     * <p>
     * 使用词法表中定义的多词关键字列表。
     * <b>注意</b>：字符串字面量内的内容不受影响。
     */
    private String normalizeMultiWordKeywords(String s) {
        // 将源码分段为字符串内和字符串外的片段
        List<Segment> segments = segmentString(s);

        StringBuilder result = new StringBuilder();
        for (Segment segment : segments) {
            if (segment.inString) {
                // 字符串内，保持原样
                result.append(segment.text);
            } else {
                // 字符串外，使用预编译的 Pattern 规范化多词关键字
                String normalized = segment.text;
                for (Map.Entry<String, Pattern> entry : multiWordKeywordPatterns) {
                    normalized = entry.getValue().matcher(normalized).replaceAll(entry.getKey());
                }
                result.append(normalized);
            }
        }

        return result.toString();
    }

    /**
     * 去除冠词（根据词法表配置），但保留字符串字面量内的冠词
     * <p>
     * 算法：
     * 1. 将源码分段为字符串内和字符串外的片段
     * 2. 仅在字符串外的片段应用冠词移除正则
     * 3. 重新拼接所有片段
     */
    private String removeArticles(String s) {
        if (articlePattern == null) {
            return s;
        }

        List<Segment> segments = segmentString(s);

        StringBuilder result = new StringBuilder();
        for (Segment segment : segments) {
            if (segment.inString) {
                // 字符串内，保持原样
                result.append(segment.text);
            } else {
                // 字符串外，移除冠词
                String withoutArticles = articlePattern.matcher(segment.text).replaceAll("");
                result.append(withoutArticles);
            }
        }

        return result.toString();
    }

    /**
     * 将源码分段为字符串内和字符串外的片段
     * <p>
     * 支持从词法表配置的引号以及 ASCII 双引号（作为通用回退）
     */
    private List<Segment> segmentString(String s) {
        List<Segment> segments = new ArrayList<>();
        boolean inString = false;
        String expectedClose = null;
        StringBuilder current = new StringBuilder();

        // 支持的引号对：词法表配置的引号 + 通用回退引号
        // 使用 Map 存储开始引号到结束引号的映射
        Map<String, String> quotePairs = new HashMap<>();
        quotePairs.put(stringQuoteOpen, stringQuoteClose);
        // 始终支持 ASCII 双引号作为通用回退
        if (!stringQuoteOpen.equals("\"")) {
            quotePairs.put("\"", "\"");
        }
        // 始终支持智能双引号（removeLineComments 在规范化前调用）
        quotePairs.put("\u201C", "\u201D");  // 智能双引号 "..."

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            current.append(ch);

            // 检查字符串开始标记
            if (!inString) {
                String chStr = String.valueOf(ch);
                if (quotePairs.containsKey(chStr) && !isEscaped(s, i)) {
                    // 字符串开始
                    String before = current.substring(0, current.length() - 1);
                    if (!before.isEmpty()) {
                        segments.add(new Segment(before, false));
                    }
                    current = new StringBuilder(chStr);
                    inString = true;
                    expectedClose = quotePairs.get(chStr);
                }
            }
            // 检查字符串结束标记
            else if (expectedClose != null && expectedClose.equals(String.valueOf(ch)) && !isEscaped(s, i)) {
                // 字符串结束
                segments.add(new Segment(current.toString(), true));
                current = new StringBuilder();
                inString = false;
                expectedClose = null;
            }
        }

        // 添加剩余内容
        if (current.length() > 0) {
            segments.add(new Segment(current.toString(), inString));
        }

        return segments;
    }

    /**
     * 判断指定位置的引号是否被转义
     * <p>
     * 算法：向前扫描反斜杠数量，奇数个反斜杠表示转义。
     */
    private boolean isEscaped(String str, int index) {
        int slashCount = 0;
        for (int i = index - 1; i >= 0 && str.charAt(i) == '\\'; i--) {
            slashCount++;
        }
        return slashCount % 2 == 1;
    }

    /**
     * 最终空白符规范化（确保幂等性）
     * <p>
     * - 移除仅包含空白的行（但保留字符串字面量内的空白行）
     * - 对每行重新规范化空白符
     * <p>
     * 注意：使用状态跟踪处理，确保跨行字符串内容不被规范化。
     */
    private String finalWhitespaceNormalization(String s) {
        // 第一步：使用 segmentString 保护字符串字面量，只在代码部分清空空白行
        List<Segment> segments = segmentString(s);
        StringBuilder withoutBlankLines = new StringBuilder();
        for (Segment segment : segments) {
            if (segment.inString) {
                withoutBlankLines.append(segment.text);
            } else {
                String cleaned = segment.text.replaceAll("(?m)^[ \\t]+$", "");
                withoutBlankLines.append(cleaned);
            }
        }

        // 第二步：使用状态跟踪的空白规范化
        return normalizeWhitespaceWithState(withoutBlankLines.toString(), PUNCT_FINAL_RE, true);
    }

    /**
     * 将连续空白折叠为单个空格
     */
    private String collapseSpaces(String text) {
        return SPACE_RUN_RE.matcher(text).replaceAll(" ");
    }

    /**
     * 源码片段（字符串内或字符串外）
     */
    private static record Segment(String text, boolean inString) {}
}
