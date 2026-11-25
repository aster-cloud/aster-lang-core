package aster.core.embed;

import aster.core.identifier.CaseFold;
import aster.core.identifier.VocabularyScopes;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用户自定义别名校验（ADR 0022 §11.5，方案 D；规则 v2 见 ADR 0060 §5）。
 *
 * <p>用户提交的 aliasSet 必须先过本校验才能进入编译/版本快照。本校验堵住 Codex 对抗复核
 * 挖出的 High-3 社会工程攻击面：别名映射到哪个 kind 是用户定的，若放任用户把 {@code approve}
 * 映射到 {@code RETURN} 或把误导短语映射到控制流/授权/拒绝类 kind，审批者看到的是别名源码、
 * 实际批准的却是归一后的语义。
 *
 * <p>两条铁律（第 2 条由 ADR 0060 §5.2 修订）：
 * <ol>
 *   <li><b>白名单（非黑名单）</b>：只有低风险的运算符/比较类 kind 允许用户自定义别名。
 *       用 allowlist 而非 denylist：新增 kind 默认**不**可被用户别名（deny-by-default），
 *       避免"加了新 kind 忘记拉黑"的静默放行。</li>
 *   <li><b>仅多词，zh 下的汉字单词除外</b>：单词别名占用标识符命名空间（用户拿同名词作字段/参数会被当关键词，
 *       破坏用户空间）。多词别名（含空格）天然安全：仅相邻序列匹配，子词仍可作标识符。
 *       例外（ADR 0060 §5.2）：locale 主子标签为 {@code zh}、每个码点都在汉字基本区或扩展 A、长 2–8 个码点的
 *       单词别名放行——两引擎都按整词匹配汉字（只取两侧 token 边界一致的交集），别名只属于一个版本；
 *       同形名字被软关键字静默改名的风险由 {@link AliasIdentifierGuard} 在解析后拦截。</li>
 * </ol>
 *
 * <p>别名不得含反引号 U+0060（ADR 0060 修订 2 X2）：反引号是名字转义的定界符（ADR 0064）。
 *
 * <p>另复用 core {@code LexiconValidator} 的不遮蔽语义：别名不得撞规范拼写/其它别名；
 * 也不得撞有效词汇的任一写法，含字面量宏触发词（ADR 0060 §5.3 第 1 条）。
 */
public final class UserAliasValidator {

    /**
     * 校验器版本（ADR 0022 §11.5 H6）：进 source envelope 的工具链身份。
     * 白名单/多词规则/不遮蔽/碰撞校验等任一规则变更时**必须 bump**，使旧版本 envelope
     * 与新校验逻辑可区分（"这条规则当时是用哪版校验规则接受的"可审计）。
     */
    public static final String VERSION = "2";

    /**
     * W2 DoS 上界（与 cloud policy-alias-shared 同名常量逐一对齐，跨引擎 parity）。
     * 可别名 kind 上限 19（11 运算符 + 8 结构词），故界远宽于合法用途，只兜异常/恶意
     * 巨量输入——防单条别名达 MB 级拖垮归一正则/信封哈希，或几千 kind/短语撑爆内存。
     */
    public static final int MAX_ALIAS_KINDS = 32;
    /** 单个 kind 最多别名短语数。 */
    public static final int MAX_ALIASES_PER_KIND = 8;
    /** 单条别名短语最大长度（字符）。 */
    public static final int MAX_ALIAS_LENGTH = 64;
    /** 汉字单词别名的码点数下限（ADR 0060 §5.2：单字最容易撞日常标识符）。 */
    public static final int MIN_HAN_SINGLE_TOKEN = 2;
    /** 汉字单词别名的码点数上限（ADR 0060 §5.2）。 */
    public static final int MAX_HAN_SINGLE_TOKEN = 8;

    private UserAliasValidator() {}

    /**
     * 恒允许用户自定义别名的**运算符/比较** kind 白名单（低风险，任何人可用）。
     *
     * <p>**故意排除**：导入（IMPORT*）、效果/外部调用（IO/CPU/AWAIT/WORKFLOW/STEP/…）、
     * 逻辑（AND/OR/NOT）、布尔/类型字面量。这些 kind 的别名滥用会改变可读语义/藏副作用。
     * 声明/控制流/返回等**结构词**移入 {@link #STRUCTURAL_KINDS}（需授权，见下）。
     */
    private static final Set<SemanticTokenKind> OPERATOR_KINDS = Set.of(
        SemanticTokenKind.PLUS,
        SemanticTokenKind.MINUS_WORD,
        SemanticTokenKind.TIMES,
        SemanticTokenKind.DIVIDED_BY,
        SemanticTokenKind.INTEGER_DIVIDED_BY,
        SemanticTokenKind.MODULO,
        SemanticTokenKind.LESS_THAN,
        SemanticTokenKind.GREATER_THAN,
        SemanticTokenKind.EQUALS_TO,
        SemanticTokenKind.AT_LEAST,
        SemanticTokenKind.AT_MOST
    );

    /** 运算符档 kind 集合（只读）；显示排版 L7 的运算符 kind 与之同一份（ADR 0059 修订 4 §4）。 */
    public static Set<SemanticTokenKind> operatorKinds() {
        return OPERATOR_KINDS;
    }

    /**
     * **结构词** kind（声明+函数签名+控制流+返回）：**仅当 allowStructural=true（管理员按用户授权）时**
     * 可自定义别名。红队 H3 默认排除（别名滥用误导审批）；放开需三重护栏（审批显规范结构 + 审计留痕 +
     * 多词或 zh 汉字单词）。{@code FUNC_PRODUCE} 只引出返回类型、改不了控制流，与 {@code FUNC_TO} 同属
     * 函数签名关键词（ADR 0060 §5.1）。与 cloud policy-alias-shared.STRUCTURAL_KINDS 对齐（跨引擎 parity）。
     *
     * <p>仍禁（不在此集，任何授权都不放开）：IMPORT/effects/AND/OR/NOT/布尔——护栏兜不住。
     */
    private static final Set<SemanticTokenKind> STRUCTURAL_KINDS = Set.of(
        SemanticTokenKind.MODULE_DECL,
        SemanticTokenKind.FUNC_TO,
        SemanticTokenKind.FUNC_PRODUCE,
        SemanticTokenKind.IF,
        SemanticTokenKind.OTHERWISE,
        SemanticTokenKind.MATCH,
        SemanticTokenKind.WHEN,
        SemanticTokenKind.RETURN
    );

    /** 校验结果：valid + 错误清单。 */
    public record Result(boolean valid, List<String> errors) {
        public static Result ok() {
            return new Result(true, List.of());
        }
        public static Result fail(List<String> errors) {
            return new Result(false, List.copyOf(errors));
        }
    }

    /** 兼容重载：不带领域词汇作用域，不允许结构词。 */
    public static Result validate(Map<SemanticTokenKind, List<String>> aliasSet, String locale) {
        return validate(aliasSet, locale, null, false);
    }

    /** 兼容重载：带领域词汇作用域，不允许结构词。 */
    public static Result validate(Map<SemanticTokenKind, List<String>> aliasSet, String locale,
                                  VocabularyScopes vocabulary) {
        return validate(aliasSet, locale, vocabulary, false);
    }

    /**
     * 校验用户 aliasSet（针对给定 locale 的基础 lexicon + 可选领域词汇索引）。
     *
     * <p>白名单两档（deny-by-default）：OPERATOR_KINDS 恒允许；STRUCTURAL_KINDS 仅当
     * {@code allowStructural=true}（管理员按用户授权，调用方从 entitlement 权威传入）时允许；
     * 其余高危 kind 恒拒。其余校验（多词/规范形/不遮蔽/不撞领域词汇）不变。
     *
     * @param aliasSet        用户别名，null/空视为合法（无别名）
     * @param locale          基础语言代码（取规范拼写/别名做不遮蔽校验）
     * @param vocabulary      领域词汇作用域，null 表示不做别名↔标识符碰撞校验
     * @param allowStructural 是否放开结构词别名（管理员按用户授权，权威传入，默认 false）
     */
    public static Result validate(Map<SemanticTokenKind, List<String>> aliasSet, String locale,
                                  VocabularyScopes vocabulary, boolean allowStructural) {
        if (aliasSet == null || aliasSet.isEmpty()) {
            return Result.ok();
        }

        // W2：kind 总数上界（DoS 兜底），超限直接判失败不再逐条处理。
        if (aliasSet.size() > MAX_ALIAS_KINDS) {
            return Result.fail(List.of(
                "别名集 kind 数量 " + aliasSet.size() + " 超过上限 " + MAX_ALIAS_KINDS));
        }

        List<String> errors = new ArrayList<>();
        Lexicon base = resolveBase(locale);

        // 占用集（归一后）：base 规范拼写 + base 已有别名。别名不得遮蔽其中任一。
        Set<String> reserved = new HashSet<>();
        for (String kw : base.getKeywords().values()) {
            if (kw != null && !kw.isBlank()) {
                reserved.add(normalize(kw));
            }
        }
        Map<SemanticTokenKind, List<String>> baseAliases = base.getAliases();
        if (baseAliases != null) {
            for (List<String> list : baseAliases.values()) {
                for (String a : list) {
                    if (a != null && !a.isBlank()) {
                        reserved.add(normalize(a));
                    }
                }
            }
        }

        Set<String> seenAlias = new HashSet<>();
        for (Map.Entry<SemanticTokenKind, List<String>> e : aliasSet.entrySet()) {
            SemanticTokenKind kind = e.getKey();

            // 铁律 1：白名单（deny-by-default）两档——运算符恒允；结构词需授权；其余高危恒拒。
            boolean isOperator = OPERATOR_KINDS.contains(kind);
            boolean isStructural = STRUCTURAL_KINDS.contains(kind);
            if (!isOperator && !(isStructural && allowStructural)) {
                if (isStructural) {
                    errors.add("不允许为结构词 " + kind + " 自定义别名（需管理员授予结构词别名权限；"
                        + "审批时会显示规范化真实结构并留痕）");
                } else {
                    errors.add("不允许为 " + kind + " 自定义别名（仅运算符/比较类，或经授权的结构词"
                        + "可自定义，防止误导审批的语义滥用）");
                }
                continue;
            }

            List<String> aliases = e.getValue();
            if (aliases == null || aliases.isEmpty()) {
                continue;
            }
            // W2：单 kind 别名数量上界（DoS 兜底）。
            if (aliases.size() > MAX_ALIASES_PER_KIND) {
                errors.add(kind + " 的别名数量 " + aliases.size() + " 超过上限 " + MAX_ALIASES_PER_KIND);
                continue;
            }
            for (String alias : aliases) {
                if (alias == null || alias.isBlank()) {
                    errors.add(kind + " 的别名不能为空");
                    continue;
                }
                // W2：单条别名长度上界（DoS 兜底）。先于归一，避免对超长串做正则。
                if (alias.length() > MAX_ALIAS_LENGTH) {
                    errors.add(kind + " 的别名长度 " + alias.length() + " 超过上限 " + MAX_ALIAS_LENGTH);
                    continue;
                }
                String norm = normalize(alias);

                // 铁律 0：提交值必须已是规范空白形（trim + 单 ASCII 空格分词）。
                // 否则 Canonicalizer 把源码空白折叠成单空格，而 overlay 注入的 raw 别名
                // （如 "scaled  by" 双空格）做 translation key 匹配不到 → 校验过但编译不生效。
                // 要求 alias == normalize(alias)，保证注入值即匹配值（可审计、无隐藏分歧）。
                if (!alias.equals(norm)) {
                    errors.add("别名 '" + alias + "'（" + kind + "）含非规范空白/大小写；"
                        + "请提交规范形 '" + norm + "'（前后无空格、词间单个空格、小写）");
                    continue;
                }

                // 别名不得含反引号（ADR 0060 修订 2 X2）：规范化器把反引号到反引号当作名字转义的不透明片段，
                // 含反引号的别名可能被一个转义片段整体覆盖，读法不确定
                if (norm.indexOf('`') >= 0) {
                    errors.add("别名 '" + alias + "'（" + kind + "）含反引号 `，与名字转义冲突，禁止");
                    continue;
                }

                // 铁律 2：多词（按归一后的真实分词，≥2 个非空 token），或 zh 下 2–8 个汉字的单词（ADR 0060 §5.2）
                if (norm.indexOf(' ') < 0 && !isHanSingleToken(norm, locale)) {
                    errors.add("别名 '" + alias + "'（" + kind + "）必须是多词短语，或 zh 下 2–8 个汉字的单词；"
                        + "其余单词别名会占用标识符命名空间，破坏用户空间");
                }
                // 不遮蔽 base 规范拼写 / base 别名
                if (reserved.contains(norm)) {
                    errors.add("别名 '" + alias + "'（" + kind + "）与某规范关键词或已有别名同形，禁止遮蔽");
                }
                // 用户别名间不重复（跨 kind）
                if (!seenAlias.add(norm)) {
                    errors.add("别名 '" + alias + "' 在多个 kind 间重复定义");
                }
                // 不撞领域词汇标识符（关键词翻译先于标识符翻译 → 别名会抢赢用户字段名）
                if (vocabularyHasWritten(vocabulary, norm)) {
                    errors.add("别名 '" + alias + "'（" + kind + "）与领域词汇标识符同形，"
                        + "会让关键词抢赢用户标识符，禁止");
                }
                // 不撞字面量宏触发词（ADR 0060 §5.3 第 1 条）：触发词与关键词同形时两引擎的展开顺序不同
                if (vocabularyHasLiteral(vocabulary, norm)) {
                    errors.add("别名 '" + alias + "'（" + kind + "）与字面量宏触发词同形，"
                        + "两引擎对触发词与关键词的处理顺序不同，禁止");
                }
            }
        }

        return errors.isEmpty() ? Result.ok() : new Result(false, errors);
    }

    /** 别名与任一角色的词汇写法折叠后相同（字面量宏由 {@link #vocabularyHasLiteral} 单独检查）。 */
    private static boolean vocabularyHasWritten(VocabularyScopes scopes, String phrase) {
        if (scopes == null) {
            return false;
        }
        String key = CaseFold.foldKey(phrase);
        return scopes.types().containsKey(key) || scopes.functions().containsKey(key)
            || scopes.fields().values().stream().anyMatch(m -> m.containsKey(key))
            || scopes.enumMembers().values().stream().anyMatch(m -> m.containsKey(key));
    }

    /** 别名与某个字面量宏触发词折叠后相同（ADR 0060 §5.3 第 1 条）。 */
    private static boolean vocabularyHasLiteral(VocabularyScopes scopes, String phrase) {
        return scopes != null && scopes.literals().containsKey(CaseFold.foldKey(phrase));
    }

    /**
     * 汉字单词别名判定（ADR 0060 §5.2）：locale 的主语言子标签为 {@code zh}，{@code norm} 的每个码点都在
     * 汉字基本区 U+4E00–U+9FFF 或扩展 A U+3400–U+4DBF，且码点数在 2–8 之间。
     *
     * <p>只取这两个区是因为两引擎的 token 边界只在这里一致：core 把假名、谚文、扩展 B 也当标识符字符，
     * ts 的 zh 词法不当（ADR 0060 §1.3、T4）。按码点而非 UTF-16 码元计数，与 ts {@code [...s].length} 一致。
     */
    static boolean isHanSingleToken(String norm, String locale) {
        if (norm == null || !isZhLocale(locale)) {
            return false;
        }
        int count = norm.codePointCount(0, norm.length());
        if (count < MIN_HAN_SINGLE_TOKEN || count > MAX_HAN_SINGLE_TOKEN) {
            return false;
        }
        return norm.codePoints().allMatch(UserAliasValidator::isHanBasicOrExtA);
    }

    /** 汉字基本区或扩展 A。 */
    static boolean isHanBasicOrExtA(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF);
    }

    /** locale 主语言子标签为 zh（{@code zh}、{@code zh-CN}、{@code zh_TW}…，与 {@link #resolveBase} 的 zh 回退同口径）。 */
    static boolean isZhLocale(String locale) {
        if (locale == null) {
            return false;
        }
        String norm = locale.toLowerCase(Locale.ROOT).replace('_', '-');
        return norm.equals("zh") || norm.startsWith("zh-");
    }

    /** 统一归一：trim + 折叠所有空白为单个 ASCII 空格 + lowercase(ROOT)。 */
    private static String normalize(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * 确定性序列化 aliasSet 为规范 JSON（ADR 0022 §11.5，Codex 持久化复核 High）。
     *
     * <p>kind 按枚举名排序、每个 kind 的别名列表排序、别名值归一——保证同一别名集**总是**
     * 产出逐字节相同的 JSON → 同一 source envelope（可复现、跨租户一致）。这是 createVersion
     * 应使用的 aliasSetJson 来源，杜绝"同语义别名集因 key 顺序/空白不同产生不同 envelope"。
     * 空/null → null（无别名）。
     */
    public static String canonicalJson(Map<SemanticTokenKind, List<String>> aliasSet) {
        if (aliasSet == null || aliasSet.isEmpty()) {
            return null;
        }
        // TreeMap 按 kind 枚举名排序；每个列表归一+排序+去重。
        java.util.TreeMap<String, List<String>> sorted = new java.util.TreeMap<>();
        for (Map.Entry<SemanticTokenKind, List<String>> e : aliasSet.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            java.util.TreeSet<String> vals = new java.util.TreeSet<>();
            for (String a : e.getValue()) {
                if (a != null && !a.isBlank()) {
                    vals.add(normalize(a));
                }
            }
            if (!vals.isEmpty()) {
                sorted.put(e.getKey().name(), new ArrayList<>(vals));
            }
        }
        if (sorted.isEmpty()) {
            return null;
        }
        try {
            // Jackson 对 TreeMap 保持 key 顺序；值已是有序 List。
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sorted);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize aliasSet", e);
        }
    }

    private static Lexicon resolveBase(String locale) {
        LexiconRegistry registry = LexiconRegistry.getInstance();
        if (locale == null || locale.isBlank()) {
            return registry.getDefault();
        }
        String norm = locale.toLowerCase(Locale.ROOT).replace('_', '-');
        if (registry.has(norm)) {
            return registry.getOrThrow(norm);
        }
        // 与 InProcessCnlParser.getLexiconForLocale 一致的前缀 fallback（避免校验用错 locale）
        if (norm.startsWith("zh") && registry.has("zh-cn")) {
            return registry.getOrThrow("zh-cn");
        }
        if (norm.startsWith("de") && registry.has("de-de")) {
            return registry.getOrThrow("de-de");
        }
        return registry.getDefault();
    }
}
