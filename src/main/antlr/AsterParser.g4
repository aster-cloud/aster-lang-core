/**
 * Aster CNL 语法规则（核心版本）
 *
 * 基于 TypeScript Parser 迁移而来，当前实现核心语法规则，
 * 足以解析 `test/cnl/examples/hello.aster` 等基础样例。
 *
 * 后续任务将补充复杂特性（类型系统、模式匹配、泛型等）。
 */
parser grammar AsterParser;

options {
    tokenVocab = AsterLexer;
}

// ============================================================
// 虚拟 tokens（由自定义 Lexer 动态生成）
// ============================================================
tokens { INDENT, DEDENT }

@parser::header {
import org.antlr.v4.runtime.atn.PredictionMode;
}

@parser::members {
    /** 由 stmt 按关键词独占分派的语句头；exprStmt 不得以它们开头。 */
    private static final java.util.Set<Integer> STATEMENT_HEADS =
        java.util.Set.of(RETURN, LET, SET, IF, MATCH, WHEN, ELSE, WORKFLOW);

    protected boolean isStatementHead(int tokenType) {
        return STATEMENT_HEADS.contains(tokenType);
    }

    /** 与 TS 一致的构造字段错误文案。 */
    public static final String EXPECTED_SET_TO_IN_CONSTRUCTION = "Expected 'set to' in construction";

    /** 当前 DOT 是否为语句结束句点（其后紧跟换行、缩进变化或文件结束），用于区分成员访问的点。 */
    protected boolean isStatementEndDot() {
        int next = _input.LA(2);
        return next == NEWLINE || next == DEDENT || next == INDENT || next == EOF;
    }

    /**
     * 构造字段缺少 `set to` 时报错。与 TS 一致定位在字段名之后应出现 `set` 的位置
     * （如 `age = x` 的 `=`）；首个记号不是名字时直接定位在该记号上。
     */
    protected void reportMalformedConstruct() {
        int first = _input.LA(1);
        Token at = (first == IDENT || first == TYPE_IDENT) ? _input.LT(2) : _input.LT(1);
        notifyErrorListeners(at, EXPECTED_SET_TO_IN_CONSTRUCTION, null);
    }

    /**
     * 下一个记号是否为无大小写文字开头的 IDENT（ADR 0051 裁决 R53）。
     * 天城文无大小写，词法上与 TS 一致归 IDENT，但须能出现在类型名位置；
     * 码点范围与 AsterLexer.g4 的 DevanagariChar 一致（不含 danda U+0964/U+0965）。
     */
    protected boolean isCaselessScriptIdent() {
        Token t = _input.LT(1);
        if (t == null || t.getType() != IDENT || t.getText().isEmpty()) {
            return false;
        }
        char c = t.getText().charAt(0);
        return (c >= '\u0900' && c <= '\u0963') || (c >= '\u0966' && c <= '\u097F');
    }

    /**
     * `X of A` 已有 {@code extra} 个追加参数时还能否再接一个（aster-lang-core#256，名字大小写不敏感，与 aster-lang-ts 一致）：
     * list / option 恰一个参数，result 至多一个失败类型，其余泛型不限。
     */
    protected static boolean acceptsOfTypeArg(String generic, int extra) {
        if ("list".equalsIgnoreCase(generic) || "option".equalsIgnoreCase(generic)) {
            return false;
        }
        return !"result".equalsIgnoreCase(generic) || extra == 0;
    }

    /**
     * 下一个记号是否为首字母大写拼写的类型别名关键词（{@code Type} / {@code TYPE} 等，aster-lang-ts#328）。
     * 小写 {@code type} 由 TYPE 记号承担；其余大小写形式在词法上仍是 TYPE_IDENT，只在 typeDecl 句首按文本判定，
     * 不升为硬关键词，{@code Type} 作类型名 / 字段类型时照旧可用（与 aster-lang-ts 按小写比较关键词一致）。
     */
    protected boolean isTypeAliasKeyword() {
        Token t = _input.LT(1);
        return t != null && t.getType() == TYPE_IDENT && "type".equalsIgnoreCase(t.getText());
    }

    /**
     * 下一个记号是否为 {@code Use} / {@code use} 以外拼写的导入关键词（{@code USE}、{@code uSe} 等，aster-lang-ts#334）。
     * 与 {@link #isTypeAliasKeyword()} 同理：只在 importDecl 句首按文本判定，不夺走这些拼写作名字的用法。
     */
    protected boolean isUseKeyword() {
        Token t = _input.LT(1);
        return t != null && (t.getType() == TYPE_IDENT || t.getType() == IDENT) && "use".equalsIgnoreCase(t.getText());
    }

    /**
     * 下一个记号是否为前缀可空类型关键词 {@code maybe}（大小写不敏感，aster-lang-ts#328）。
     * maybe 是各 lexicon 的类型构造关键词（SemanticTokenKind.MAYBE，zh「可选」、de「vielleicht」经规范化成 maybe），
     * 与 option of / result of 同类；词法上仍是 IDENT / TYPE_IDENT，只在类型位置按文本判定，不夺走 maybe 作名字的用法。
     */
    protected boolean isMaybeKeyword() {
        Token t = _input.LT(1);
        return t != null && (t.getType() == IDENT || t.getType() == TYPE_IDENT) && "maybe".equalsIgnoreCase(t.getText());
    }

    /** 小写开头、却只能读成类型名的词：内建类型关键词与 `maybe T` / `map K to V` 的引导词（与 aster-lang-ts 同表）。 */
    private static final java.util.Set<String> LOWERCASE_TYPE_WORDS =
        java.util.Set.of("text", "int", "float", "bool", "maybe", "map");

    /** 内建的小写 `X of` 泛型名：分隔符之后照旧是类型参数（ADR 0056 修订 A4 §1.3）。 */
    private static final java.util.Set<String> OF_GENERIC_WORDS = java.util.Set.of("list", "option", "result");

    /**
     * 类型位置上的裸小写拉丁名字（ADR 0056 修订 A4 §1.2，aster-lang-ts#338 第 2 项）：小写字母开头、后面不是 `of` / `<`、
     * 也不是 `maybe` / `map` 引导词。类型名以大写开头；与 ts 同码同位置报 E055。只在真正解析时报错，不参与预测。
     */
    protected boolean isBareLowercaseTypeName() {
        Token t = _input.LT(1);
        if (t == null || t.getType() != IDENT || t.getText().isEmpty()) return false;
        String text = t.getText();
        if (Character.getType(text.codePointAt(0)) != Character.LOWERCASE_LETTER) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if ("maybe".equals(lower) || "map".equals(lower)) return false;
        int after = _input.LA(2);
        return after != OF && after != LT;
    }

    protected void rejectBareLowercaseTypeName(Token t) {
        throw new aster.core.parser.ParseDiagnosticException(aster.core.typecheck.ErrorCode.PARSE_EXPECTED,
            java.util.Map.of("token", "token.typeName"), "Expected type name", t);
    }

    /**
     * `X of A` 的分隔符之后是裸的小写拉丁名字（可带前置注解，后面不是 `of` / `<`，本身也不是内建类型关键词）时报 E055（aster-lang-ts#334）。
     *
     * <p>{@code given x as Pair of Int, y, produce …} 的 {@code y} 既可读作 Pair 的下一个类型参数，也可读作下一个无类型形参；
     * 循环左边缘的谓词把分隔符判给 Pair，而类型名只收大写开头（typeIdent），于是在 ANTLR 错误恢复里失败，首条非 E063
     * 诊断随预测缓存的冷热而变（冷启动是下一行的 E052，热身后是本行的 E055）。这里在进入类型之前直接报出，码、参数、文案与位置
     * 都与 aster-lang-ts 的 isBareLowercaseName 一致。只在真正解析时执行（预测不执行动作），不改变接受集合。
     */
    protected void rejectBareLowercaseTypeArg() {
        int i = 1;
        while (_input.LA(i) == AT) {
            i += 2; // '@' 与注解名
            if (_input.LA(i) != LPAREN) continue;
            int depth = 0;
            do {
                int type = _input.LA(i);
                if (type == EOF) return;
                if (type == LPAREN) depth++;
                if (type == RPAREN) depth--;
                i++;
            } while (depth > 0);
        }
        Token t = _input.LT(i);
        if (t == null || t.getType() != IDENT || t.getText().isEmpty()) return;
        String text = t.getText();
        if (Character.getType(text.codePointAt(0)) != Character.LOWERCASE_LETTER) return;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int after = _input.LA(i + 1);
        // 歧义分隔符（ADR 0056 修订 A4 §1.3）：小写 map（第 4 项）与小写名字 + of（第 3 项，内建 list / option / result 除外）
        // 同样可能是下一个形参 / 字段，与裸小写名字同码报错
        boolean ambiguous = "map".equals(lower)
            || (after == OF && !OF_GENERIC_WORDS.contains(lower))
            || (after != OF && after != LT && !LOWERCASE_TYPE_WORDS.contains(lower));
        if (!ambiguous) return;
        throw new aster.core.parser.ParseDiagnosticException(aster.core.typecheck.ErrorCode.PARSE_EXPECTED,
            java.util.Map.of("token", "token.typeName"),
            "Expected type name: '" + text + "' would be read as another type argument; write '" + text
                + " as <Type>' or wrap the generic type in parentheses",
            t);
    }

    /** 进入类型：预测切到 SLL，返回调用方原先的模式（见 annotatedType 规则的说明）。 */
    protected PredictionMode enterTypePrediction() {
        PredictionMode outer = getInterpreter().getPredictionMode();
        getInterpreter().setPredictionMode(PredictionMode.SLL);
        return outer;
    }

    protected void exitTypePrediction(PredictionMode outer) {
        getInterpreter().setPredictionMode(outer);
    }

    // ------------------------------------------------------------
    // 解析器层嵌套守卫（aster-lang-core#255）
    // ------------------------------------------------------------
    //
    // AsterCustomLexer 的词法期守卫按 token 序列估算深度，语法错误里夹杂的 token 会把它的计数清零
    // （`not + not + …` 每个 `+` 都打断 not 链，3000 层仍在 1MB 栈上溢出）。这里在规则进入 / 退出时按
    // 真实的递归层数再数一遍，作为第二道守卫：错误恢复越过杂 token 继续递归时，层数照样累加。
    // 三种计数的单位与上限都与第一道守卫相同，合法源码在第一道守卫（或 AstBuilder 的表达式深度守卫）
    // 放行的范围内，这里不会更早拒绝：
    //   - 类型：`type` 规则的进入次数，与 TypeNestingTracker / aster-lang-ts parseType 同单位，上限 64；
    //   - 表达式：`expr` 规则的嵌套层数，与 AstBuilder.visitExpr 同单位，上限 64；
    //   - not 链：同一表达式内连续 `not` 的个数（notExpr 的嵌套层数减去末尾那层），上限 64。
    // 超限时在进入规则之前抛出，不改动解析器状态。

    private int typeNestingDepth = 0;
    private int exprNestingDepth = 0;
    /** 当前表达式内 notExpr 的嵌套层数；进入子表达式时压栈清零，退出时恢复。 */
    private int notExprDepth = 0;
    private final java.util.ArrayDeque<Integer> outerNotExprDepths = new java.util.ArrayDeque<>();

    @Override
    public void enterRule(ParserRuleContext localctx, int state, int ruleIndex) {
        if (ruleIndex == RULE_expr) {
            guardNesting(exprNestingDepth, aster.core.parser.AstBuilder.MAX_EXPR_DEPTH, "表达式");
            exprNestingDepth++;
            outerNotExprDepths.push(notExprDepth);
            notExprDepth = 0;
        } else if (ruleIndex == RULE_notExpr) {
            // notExpr 层数 = 已读的 not 个数 + 1：允许 64 个 not（第 65 层 notExpr），与词法期 not 链守卫一致
            guardNesting(notExprDepth - 1, aster.core.parser.AsterCustomLexer.MAX_NESTING_DEPTH, "not 链");
            notExprDepth++;
        }
        super.enterRule(localctx, state, ruleIndex);
    }

    @Override
    public void exitRule() {
        int ruleIndex = _ctx.getRuleIndex();
        if (ruleIndex == RULE_expr) {
            exprNestingDepth--;
            notExprDepth = outerNotExprDepths.pop();
        } else if (ruleIndex == RULE_notExpr) {
            notExprDepth--;
        }
        super.exitRule();
    }

    @Override
    public void enterRecursionRule(ParserRuleContext localctx, int state, int ruleIndex, int precedence) {
        if (ruleIndex == RULE_type) {
            guardNesting(typeNestingDepth, aster.core.parser.AsterCustomLexer.MAX_TYPE_NESTING_DEPTH, "类型");
            typeNestingDepth++;
        }
        super.enterRecursionRule(localctx, state, ruleIndex, precedence);
    }

    @Override
    public void unrollRecursionContexts(ParserRuleContext parentctx) {
        if (_ctx.getRuleIndex() == RULE_type) {
            typeNestingDepth--;
        }
        super.unrollRecursionContexts(parentctx);
    }

    /** 已有 {@code depth} 层时再进一层是否超过 {@code limit}；超过则在当前 token 上报 E054。 */
    private void guardNesting(int depth, int limit, String what) {
        if (depth + 1 > limit) {
            throw new aster.core.parser.ParseDiagnosticException(aster.core.typecheck.ErrorCode.PARSE_NESTING_TOO_DEEP,
                java.util.Map.of("limit", String.valueOf(limit)),
                what + "嵌套过深（> " + limit + "），拒绝以防栈溢出", _input.LT(1));
        }
    }
}

// ============================================================
// 模块结构（顶层）
// ============================================================

/**
 * 模块入口规则
 * 语法示例:
 *   Module app.
 *   Rule helloMessage:
 *     Return "Hello, world!".
 */
module
    : NEWLINE* (moduleHeader NEWLINE* profileDecl?)? NEWLINE* (topLevelDecl NEWLINE*)* EOF
    ;

/**
 * ADR 0046：治理档案声明，紧跟模块头、至多一条:
 *   Profile "eu-ai-act-high-risk".
 * 不引入硬词法记号——TYPE_IDENT 文本须为 Profile，id 形态由 AstBuilder 校验；
 * 顶层声明无以 TYPE_IDENT 开头者，故不夺走用户的 profile 参数名或 Profile 类型名。
 */
profileDecl
    : TYPE_IDENT STRING_LITERAL DOT
    ;

/**
 * 模块头:
 *   Module app.
 */
moduleHeader
    : MODULE_KW qualifiedName DOT
    ;

/**
 * 限定名称（用于模块路径）: io.Http 或 data.List
 */
qualifiedName
    : qualifiedSegment (DOT qualifiedSegment)*
    ;

/**
 * 限定名称片段，允许普通标识符或类型标识符
 * <p>
 * 软关键字：AND/OR/NOT/WITH 在限定名（模块名 Module x.y.z. / Use x.y.z.）的段位置
 * 当普通标识符用——段之间由 DOT 分隔，不与表达式里的逻辑运算符、列表分隔符或
 * Define/construct 的 WITH 冲突。沿用 nameIdent 已有的 TYPE/VERSION 软关键字模式，
 * 避免改 lexer 引入全局风险。解决 lexer 把 `boolean.and`/`let.with.call` 等段
 * 误当 AND/OR/WITH token 而拒绝的问题。
 */
qualifiedSegment
    : IDENT
    | TYPE_IDENT
    | AND
    | OR
    | NOT
    | WITH
    // ADR 0019 G1：结构关键词改为大小写不敏感后，小写形式会把模块路径段误当
    // token（如 `dual.engine.let.binding` 的 `let` 段）。复用 structKeywordName
    // 软关键字集（与 nameIdent 等其它标识符位置一致）。
    | structKeywordName
    ;

/**
 * 顶层声明（函数、类型定义、导入）
 */
topLevelDecl
    : funcDecl
    | dataDecl
    | enumDecl
    | typeDecl
    | importDecl
    ;

// ============================================================
// 声明规则
// ============================================================

/**
 * 函数声明
 * 语法示例:
 *   Rule greet:
 *     Return "Hello".
 *
 *   Rule add given x: Int and y: Int:
 *     Return x + y.
 *
 *   Rule generateQuote given driver, vehicle:
 *     ...
 */
funcDecl
    : (annotation NEWLINE*)* RULE nameIdent typeParamList? effectParamList? givenParamList? COMMA? (PRODUCE annotatedType?)? (DOT capabilityAnnotation? funcBody | funcBody | DOT)
    ;

/**
 * 效应参数列表（ADR 0055 #5、#6；与 aster-lang-ts decl-parser parseEffectParams 同语法）
 * 语法: Rule relay <E> given x ...  /  Rule f <E, F> ...  /  Rule f <E and F> ...
 * 写在类型参数之后、形参之前；效应参数名是大写标识符。
 */
effectParamList
    : LT TYPE_IDENT ((COMMA | AND) TYPE_IDENT)* GT
    ;

// ADR 0028：函数体形态。缩进块（现有）走 `COLON NEWLINE block`；显式块（新增）走
// `COLON explicitBlock`——COLON 后语句立即开始、以 BLOCK_END（方言词如「毕」经 Canonicalizer
// 翻成 \0BLOCK_END sentinel）收尾。抽成 funcBody 让普通 body 与 capability body 都复用，
// 避免带 capability 的函数不能用显式块（双引擎不一致点，Codex 设计审）。
funcBody
    // 规则体与 If / Otherwise 的块头冒号之后可以先空行再写块（aster-lang-ts#357 第 2 行，ADR 0056 修订 A4 §2），与 ts 一致；
    // Match / When / lambda / workflow 的块头 ts 同样不收空行，两引擎维持原状
    : COLON NEWLINE+ block
    | COLON explicitBlock
    ;

/**
 * 类型参数列表
 * 语法: of T and U / of T, U
 */
typeParamList
    : OF typeParam ((AND | COMMA) typeParam)*
    ;

/**
 * 类型参数定义（支持类型标识符或普通标识符）
 */
typeParam
    : TYPE_IDENT
    | IDENT
    | structKeywordName
    ;

/**
 * 参数列表
 * 语法: given x: Int and y: Text
 *       given x: Int, y: Text
 */
givenParamList
    : GIVEN param ((AND | COMMA) param)*
    ;

/**
 * 参数定义
 * 语法: x: Int（显式类型）
 *       x（隐式类型，基于参数名推断）
 */
param
    : annotation* nameIdent (AS annotatedType)? fieldConstraint*
    ;

/**
 * 能力标注
 * 语法: It performs io [Http, Sql, Time]
 *       It performs io with Http and E      （与 aster-lang-ts parseEffectList 同形）
 *       It performs E                       （E 为已声明的效应参数）
 * effect 位置与列表中的名字统一由 AstBuilder 分类：io / cpu（大小写不敏感）是授权型效应，pure 不授权，
 * 已声明的效应参数是效应变量，其余大写名字是能力。
 */
capabilityAnnotation
    : IT PERFORMS effect=(IDENT | TYPE_IDENT)
      ( LBRACKET caps+=TYPE_IDENT (COMMA caps+=TYPE_IDENT)* RBRACKET
      | (WITH | AND) caps+=(IDENT | TYPE_IDENT) (AND caps+=(IDENT | TYPE_IDENT))*
      )?
    ;

/**
 * 数据类型定义
 * 语法: Define User has name: Text and age: Int.
 *       A User has name: Text and age: Int.
 *       User has name, age.
 * 多行格式:
 * Define User has
 *   name: Text,
 *   age: Int.
 */
dataDecl
    : DEFINE article? typeIdent (HAS | WITH) fieldList DOT (NEWLINE* DEDENT)?
    // 省略 Define 的写法是 Java 独有（TS 报 P005），R53 不扩展到这里：仍只收 TYPE_IDENT
    | article? TYPE_IDENT (HAS | WITH) fieldList DOT (NEWLINE* DEDENT)?
    ;

/**
 * 类型名（声明名、枚举值、类型引用、构造模式名）：TYPE_IDENT，或无大小写文字（天城文）开头的 IDENT。
 * R53：无大小写文字的作者无法“写成大写”，故在类型名位置放行；只放宽语法、不改词法，
 * 天城文在表达式位置仍是普通名字引用。小写拉丁 IDENT 不在放宽范围内。
 */
typeIdent
    : TYPE_IDENT
    | {isCaselessScriptIdent()}? IDENT
    ;

/**
 * 字段列表
 * 语法: name: Text and age: Int
 * 或多行格式:
 *   name: Text,
 *   age: Int
 */
fieldList
    : NEWLINE* INDENT? field (NEWLINE* (AND | COMMA) NEWLINE* field)*
    ;

/**
 * 字段定义
 * 语法: name: Text（显式类型）
 *       name（隐式类型，基于字段名推断）
 */
field
    : annotation* nameIdent (AS annotatedType)? fieldConstraint*
    ;

/**
 * 字段/参数约束修饰符（与 TS constraint-parser 对齐，ADR 0055 #4）。
 * required：必填；between X and Y：范围，两端只能是数值字面量（可带负号），规范十进制串由 lowering 从原文求出；
 * at least X / at most Y：单边范围（另一端为 null）；matching "…" 或 matching pattern "…"：正则。
 * 都降级为 Core IR 的 Field / Param.constraints。at least / at most 复用比较运算的多词 token，
 * 其中只有 at least / at most 两种写法是约束，AstBuilder 拒收其它写法（如 greater than or equal to）。
 */
fieldConstraint
    : REQUIRED
    | BETWEEN rangeBound AND rangeBound
    | GREATER_THAN_OR_EQUAL_WORD rangeBound
    | LESS_THAN_OR_EQUAL_WORD rangeBound
    | MATCHING IDENT? STRING_LITERAL
    ;

rangeBound
    : MINUS? (INT_LITERAL | FLOAT_LITERAL)
    ;

nameIdent
    : IDENT
    | TYPE_IDENT
    | TYPE
    | VERSION
    | REQUIRED
    | BETWEEN
    | structKeywordName
    ;

/**
 * ADR 0019 G1：结构关键词大小写不敏感后，其小写形式被 lexer 提升为硬 token
 * （LET/IF/RETURN/...），而改动前小写 `let`/`if`/`return` 是普通 IDENT，可作
 * 变量名、参数名、字段名、成员名、构造字段名等。为不引入相对 TS（上下文关键字
 * 模型，结构词在非语句位置仍是普通名字）的回归，把这些 token 在所有"标识符位置"
 * 当软关键字放行。这些位置（名字声明、变量引用、DOT 成员、construct 字段）都不
 * 会与语句起点的关键字用法冲突——语句分派靠语句起点的 token，名字位置由前驱
 * token（given/Let/DOT/with 等）确定，ANTLR ALL(*) 前瞻可消歧。
 */
structKeywordName
    : LET
    | MATCH
    | IF
    | RETURN
    | RULE
    | DEFINE
    | WHEN
    | START
    | WAIT
    | ELSE
    // ADR 0019 G2a：THEN 也是结构关键词 token，同样在标识符位置当软关键字
    // （如 `Http.get(...).then(handle)` 的 .then 方法名）。
    | THEN
    // MAX/ATTEMPTS 是 workflow retry 语法 `max attempts: N.` 的 lexer 硬 token
    // （AsterLexer.g4），但在标识符位置（DOT 成员、变量名、参数、字段）应当软关键字——
    // 否则 stdlib `List.max(xs)` / `List.attempts(xs)` 的成员名被卡（生产实测
    // "extraneous 'max'"）。retryDirective 起点仍按 MAX/ATTEMPTS token 分派，不受影响。
    // 同 ADR 0019 G1 的 LET/IF/RETURN 软关键字范式（ADR 0024 §poker 纯 CNL 前置修复）。
    | MAX
    | ATTEMPTS
    // ADR 0027：APPLY 是无括号调用引入词的硬 token，但在标识符位置当软关键字——
    // 否则 `Rule apply given …`（函数名叫 apply）被卡。applyExpr 起点仍按 APPLY 分派。
    | APPLY
    // issue #136：workflow 语法的硬 token 在标识符位置同样应当软关键字。
    // 实测 `Return config.timeout.` 的 token 流是
    //   RETURN IDENT(config) DOT TIMEOUT(timeout) DOT
    // ——`timeout` 被词法成 TIMEOUT 而非 IDENT，而 MemberSuffix 只收
    // `IDENT | TYPE_IDENT | structKeywordName`，于是 `.timeout` 匹配不上：
    // 解析停在 `config`，尾部 token 落到块外被**静默丢弃**，既不报错也无诊断。
    // 这与 MAX/ATTEMPTS 当年因 `List.max(xs)` 被卡而加入本集合是同一类问题
    // （字段名撞语法关键词）。各自的语法起点仍按对应 token 分派，不受影响。
    | WORKFLOW
    | STEP
    | RETRY
    | TIMEOUT
    | DEPENDS
    | COMPENSATE
    | BACKOFF
    | SECONDS
    // ADR 0055 #4：约束关键词 matching 是 lexer 硬 token，在标识符位置（成员名、变量名、字段、参数）
    // 同样软关键字，`obj.matching` 等写法不受影响；约束位置仍按 MATCHING token 分派。
    | MATCHING
    ;

/**
 * 枚举类型定义
 * 语法: Define Status as one of Success, Failure, Pending.
 */
enumDecl
    : DEFINE article? typeIdent AS ONE OF variantList DOT
    ;

article
    : IDENT
    ;

/**
 * 变体列表
 * 语法: Success, Failure, Pending
 */
variantList
    : typeIdent ((COMMA | OR | AND) typeIdent)*
    ;

/**
 * 导入声明
 * 语法:
 *   Use io.Http.
 *   Use io.Http as HttpClient.
 *   Use io.Http version 2 as HttpClient.
 */
importDecl
    : importKeyword qualifiedName (VERSION INT_LITERAL)? (AS importAlias)? DOT
    ;

/**
 * 导入关键词大小写不敏感（aster-lang-ts#334）：`Use` / `use` 由 USE 记号承担，`USE`、`uSe` 等其余写法在词法上仍是
 * TYPE_IDENT / IDENT，只在 importDecl 句首按文本判定，不升为硬关键词，作名字时照旧可用（与 typeAliasKeyword 同法）。
 */
importKeyword
    : USE
    | {isUseKeyword()}? (TYPE_IDENT | IDENT)
    ;

/**
 * 导入别名，允许类型或普通标识符
 */
importAlias
    : TYPE_IDENT
    | IDENT
    | structKeywordName
    ;

/**
 * 类型别名声明
 * 语法: @pii type Email as Text.  /  Type Email as Text.
 * 关键词大小写不敏感，与 Define / Rule / Use 等其它声明关键词、以及 aster-lang-ts 一致（aster-lang-ts#328）。
 */
typeDecl
    : annotation* typeAliasKeyword typeAliasName AS annotatedType DOT
    ;

typeAliasKeyword
    : TYPE
    | {isTypeAliasKeyword()}? TYPE_IDENT
    ;

typeAliasName
    : TYPE_IDENT
    | IDENT
    | structKeywordName
    ;

/**
 * 注解标注
 * 语法: @pii
 */
annotation
    : AT (IDENT | TYPE_IDENT | structKeywordName) annotationArgs?
    ;

annotationArgs
    : LPAREN (annotationArg (COMMA annotationArg)*)? RPAREN
    ;

annotationArg
    : (IDENT | structKeywordName) COLON annotationValue  # NamedAnnotationArg
    | annotationValue                    # PositionalAnnotationArg
    ;

annotationValue
    : STRING_LITERAL
    | INT_LITERAL
    // FLOAT / LONG / NULL 只为给出 E067 而接受（ADR 0055 #3：注解值只能是 string / Int32 / boolean），见 AstBuilder
    | FLOAT_LITERAL
    | LONG_LITERAL
    | NULL_LITERAL
    | BOOL_LITERAL
    | IDENT
    | TYPE_IDENT
    | structKeywordName
    ;

// ============================================================
// 类型规则（简化版本）
// ============================================================

/**
 * 类型表达式（按优先级从低到高）
 * 支持：基础类型、泛型、Maybe、Result、函数类型等
 *
 * <p>类型内的预测一律用 SLL（aster-lang-core#254）：类型文法本身有歧义（`result of result of A and B and C` 的 and、
 * `list of Int?` 的 ?），ANTLR 按「贪婪归最内层」裁决，与 aster-lang-ts 的递归下降一致。LL 模式下每遇到这类冲突
 * 都要做全上下文预测，result 成功 / 失败分量交替嵌套时耗时随层数指数增长（修复前 24 层约 0.7 秒、32 层约 9 秒；
 * 只合并分支而不切 SLL 时 20 层仍约 0.3 秒且逐层翻倍）；SLL 遇到冲突直接取最小分支，也就是贪婪的那一个，
 * 结果相同而耗时线性。只在类型内切换，类型之外仍按调用方设定的模式（aster-api 全程 SLL 不受影响）。
 */
annotatedType
    @init { PredictionMode outerPredictionMode = enterTypePrediction(); }
    : annotation* type
    ;
    finally { exitTypePrediction(outerPredictionMode); }

// ★两处结构决定了预测代价（aster-lang-core#254）：
//   1. 函数类型与括号类型共用一个分支、在 `)` 之后用一个 token（有无 `->`）区分。分成两个以 `(` 开头的
//      分支时，ANTLR 要越过整段括号内容才能选分支，括号里的类型再各自嵌套括号时预测配置随层数指数膨胀
//      （aster-api 的 sll-pathological-types.aster，4.6KB 在 SLL 下约 46 秒）。
//      `(A, B)` 没有 `->`、或带注解的 `(@pii(…) T)` 没有 `->` 由 AstBuilder 报 E056，与 aster-lang-ts 同码同文案。
//   2. `X of` 按名字分三种元数（aster-lang-core#256，与 aster-lang-ts parseTypePrimary 一致）：
//      list / option 恰一个参数；result 一个成功类型加可选的失败类型；其余名字是可变参数的泛型。
//      此前一律可变参数，`result of list of Int and Text` 的 `and Text` 被内层 list 贪婪吞掉后静默丢弃。
//      list / option / result 是软关键字，不能升为独立 token，元数由 ofTypeArgs 的语义谓词按名字判定。
//      三种元数不能拆成三个以 `X of` 开头的分支：那样选分支要先越过整个参数类型，预测又回到指数级。
type
    : type QUESTION                       # MaybeType
    | LPAREN typeList RPAREN (ARROW type)?  # FuncType
    | MAP annotatedType TO_WORD annotatedType  # MapType
    // 前缀可空 `maybe T`（≡ `T?`），T 可带注解：`maybe @pii(L1, name) Text`。与 aster-lang-ts parseTypePrimary 一致
    | {isMaybeKeyword()}? name=(IDENT | TYPE_IDENT) annotatedType  # PrefixMaybeType
    | name=(TYPE_IDENT | IDENT) OF annotatedType ofTypeArgs[$name.text]  # OfGenericType
    // 尖括号泛型不是 Aster 语法，保留分支只为让 AstBuilder 报 E056 并提示 `X of A and B`（aster-lang-ts#328）；
    // 小写名字同样收（ts 对任何类型名后的 `<` 都报 E056）
    | (TYPE_IDENT | IDENT) LT typeList GT  # GenericType
    | typeIdent                           # TypeName
    // 裸小写类型名（`as text`、`as foo`）：两引擎都在名字上报 E055（ADR 0056 修订 A4 §1.2），见 isBareLowercaseTypeName
    | {isBareLowercaseTypeName()}? bare=IDENT {rejectBareLowercaseTypeName($bare);}  # BareLowercaseTypeName
    ;

/**
 * 类型列表（用于泛型参数和函数参数类型）
 * 语法: Int, Text, Bool
 */
typeList
    : annotatedType (COMMA annotatedType)*
    ;

/**
 * `X of A` 之后的追加参数：`and` / `,` 分隔，个数上限由泛型名决定（acceptsOfTypeArg）。
 * 谓词在循环决策的左边缘，与「退出循环」冲突时由它裁决：名字不再接受参数时 `and` / `,` 留给外层。
 */
ofTypeArgs[String generic]
    locals [int extra = 0]
    : ( {acceptsOfTypeArg($generic, $extra)}? (AND | COMMA) {rejectBareLowercaseTypeArg();} annotatedType {$extra++;} )*
    ;

// ============================================================
// 语句规则
// ============================================================

/**
 * 语句块（缩进敏感）
 * 语法:
 *   INDENT
 *     stmt1
 *     stmt2
 *   DEDENT
 */
block
    : INDENT stmt ((NEWLINE+ stmt) | stmt)* NEWLINE* DEDENT
    ;

/**
 * ADR 0028：显式块（explicit block）——脱离缩进，以显式块结束词收尾。
 * 语法（COLON 后语句立即开始，无 INDENT/DEDENT）：
 *   stmt1. stmt2. BLOCK_END
 * 与 block 同构（stmt 靠 DOT 分隔、可同行连续也可换行连续），仅结束方式不同：
 * block 靠缩进消失的 DEDENT，explicitBlock 靠显式 BLOCK_END（方言词如「毕」）。
 * 两者编译到同一 Block AST（见 AstBuilder.visitExplicitBlock）。
 */
explicitBlock
    : stmt ((NEWLINE+ stmt) | stmt)* NEWLINE* BLOCK_END
    ;

/**
 * 语句
 */
stmt
    : letStmt
    | setStmt
    | defineStmt
    | startStmt
    | waitStmt
    | workflowStmt
    | returnStmt
    | whenStmt
    | otherwiseStmt
    | inlineIfStmt
    | ifStmt
    | matchStmt
    | exprStmt
    ;

/**
 * Let 语句
 * 语法: Let x be 42.
 * 支持中文变量名: Let 贷款决定 be true.
 */
letStmt
    : LET nameIdent BE lambdaExpr          # LetLambdaStmt
    | LET nameIdent BE expr DOT            # LetExprStmt
    ;

defineStmt
    : DEFINE nameIdent AS expr DOT
    ;

/**
 * Set 语句（ADR 0052）：给已有的 Let 绑定赋值，写到该名字最近的那层绑定。
 * 语法: Set total to total plus 1.
 * 与 TS parseStatement 的 `Set x to <expr>.` 一致；目标是否可赋值由类型检查判定。
 */
setStmt
    : SET nameIdent TO_WORD expr DOT
    ;

startStmt
    : START nameIdent AS ASYNC? expr DOT
    ;

waitStmt
    : WAIT FOR nameIdent (AND nameIdent)* DOT
    ;

workflowStmt
    : WORKFLOW COLON NEWLINE INDENT workflowBody DEDENT DOT
    ;

/**
 * workflow 主体：step+，其后可选 retry、timeout（顺序固定）。
 * 元素之间用 NEWLINE*（而非 NEWLINE+）：前一元素若以 block/retrySection 收尾，
 * 其 `NEWLINE* DEDENT` 已吞掉行尾换行，词法器在下一元素前不会再发 NEWLINE（issue #218）。
 */
workflowBody
    : workflowStep (NEWLINE* workflowStep)*
      (NEWLINE* retrySection)?
      (NEWLINE* timeoutSection)?
      NEWLINE*
    ;

workflowStep
    : STEP nameIdent stepDependencies? COLON NEWLINE block (NEWLINE* compensateSection)?
    ;

stepDependencies
    : DEPENDS ON LBRACKET stringList? RBRACKET
    ;

stringList
    : STRING_LITERAL (COMMA STRING_LITERAL)*
    ;

compensateSection
    : COMPENSATE COLON NEWLINE block
    ;

retrySection
    : RETRY COLON NEWLINE INDENT retryDirective (NEWLINE+ retryDirective)* NEWLINE* DEDENT
    ;

retryDirective
    : MAX ATTEMPTS COLON INT_LITERAL DOT
    | BACKOFF COLON (IDENT | TYPE_IDENT) DOT
    ;

timeoutSection
    : TIMEOUT COLON INT_LITERAL SECONDS DOT
    ;

/**
 * Return 语句
 * 语法: Return "Hello".
 */
returnStmt
    : RETURN expr DOT
    ;

/**
 * ADR 0046：语句级 When 简写，降糖为 If + Return Verdict.*:
 *   When amount at least 100, deny "too_large".
 * 与 matchCase 的 WHEN 互不干扰：matchCase 只出现在 matchStmt 内部。
 */
whenStmt
    : WHEN expr COMMA sugarOutcome DOT
    ;

/**
 * ADR 0046：Otherwise 简写，降糖为 Return Verdict.*:
 *   Otherwise allow.
 * 与 ifStmt 的 else 分支区别在于 ELSE 之后直接是结论词（else 分支 ELSE 后须换行），
 * ALL(*) 前瞻据此消歧，无需改动 ifStmt。
 */
otherwiseStmt
    : ELSE sugarOutcome DOT
    ;

/**
 * 结论词经 Canonicalizer 规范化为英文后以普通标识符出现（allow / deny / escalate /
 * require approval by / because 均无词法记号）。语法层收下句点前的任意记号（不跨行），
 * 词序列统一由 AstBuilder 校验，使数字、运算符等非单词记号也得到同一条结论错误而非 ANTLR 语法错误。
 * 结论可为空（`Otherwise.`），同样交由 AstBuilder 报结论错误。
 * 首个记号不得是冒号或逗号：`Otherwise:` / `Otherwise,` 属于 If 的 else 分支写法，写错时应得到普通语法错误。
 * 句点是语句终结符，语句在结论中的第一个句点处结束：`When c, deny x.y.` 的结论只剩 `deny x`，
 * 得到结论错误；`Otherwise deny x.y.` 之后的 `y.` 成为下一条语句，得到 "Otherwise must be the last statement"。
 */
sugarOutcome
    : (~(DOT | NEWLINE | INDENT | DEDENT | EOF | COLON | COMMA) ~(DOT | NEWLINE | INDENT | DEDENT | EOF)*)?
    ;

/**
 * If 语句
 * 语法:
 *   If condition:
 *     Return "yes".
 */
ifStmt
    : IF expr (COMMA | COLON)? NEWLINE+ block (NEWLINE? ELSE (COMMA | COLON)? NEWLINE+ block)?
    ;

/**
 * ADR 0019 G2a：内联 if 语句（语句级，复用 statement-If 降级路径，不新增 Core 节点）。
 * 语法（与文档一致，支持 else-if 链）:
 *   if cond then return X
 *   else if cond2 then return Y
 *   else return Z.
 * then 前可换行（文档里 `if ... \n then return ...`）。整个构造以单个 DOT 收尾，
 * 由最末 returnStmt 的 DOT 承担；中间分支的 return 不带 DOT（inlineReturn）。
 * THEN token 是与 block-if 的消歧点（block-if 走 NEWLINE block，无 THEN）。
 */
inlineIfStmt
    : IF expr inlineThen inlineThenBranch
    ;

// then 前的可选布局：同一行（无）、换行（NEWLINE）、或换行且缩进（NEWLINE INDENT，
// 文档里 `if ... \n    then return ...`）。缩进时配套的 DEDENT 由 else/末尾前的
// inlineLayout 吸收。
inlineThen
    : NEWLINE? INDENT? THEN
    ;

inlineThenBranch
    : inlineReturn inlineElseSep (inlineElseIf | returnStmt)
    | returnStmt
    ;

// else 前的可选布局（吸收 then 分支缩进产生的 DEDENT / 分支间换行）。
inlineElseSep
    : NEWLINE? DEDENT? ELSE
    ;

inlineElseIf
    : IF expr inlineThen inlineThenBranch
    ;

// 中间分支的 return（不带 DOT；DOT 由整个 inlineIfStmt 末尾的 returnStmt 承担）。
inlineReturn
    : RETURN expr
    ;

/**
 * Match 语句
 * 语法:
 *   Match x:
 *     When null, Return d.
 *     When v, Return v.
 */
matchStmt
    // 分支间用 NEWLINE*：块体分支以 block 的 `NEWLINE* DEDENT` 收尾，下一个 When 前不再有 NEWLINE（同 #218）
    : MATCH expr COLON NEWLINE INDENT matchCase (NEWLINE* matchCase)* NEWLINE* DEDENT
    ;

/**
 * Match 分支
 * 语法: When pattern, body.
 */
matchCase
    // 块体：`When pat,` 后换行再缩进（与 TS 一致，产出 Block 分支体）；词法器在行尾先发 NEWLINE 再发 INDENT
    : WHEN pattern COMMA (returnStmt | NEWLINE block)
    ;

/**
 * 模式（用于 Match 语句）
 */
pattern
    : NULL_LITERAL                         # PatternNull
    // PatternName 须排在 PatternCtor 之前：无括号的天城文 IDENT 两者皆可匹配，取先列者，
    // 与 TS 一致产出 PatternName（按位置解析为枚举成员）；后随 `(` 时只有 PatternCtor 可行。
    | IDENT                               # PatternName
    | typeIdent (LPAREN pattern (COMMA pattern)* RPAREN)?  # PatternCtor
    | INT_LITERAL                         # PatternInt
    // ADR 0019 G1：match 绑定名可为软关键字（小写结构词，如 `when return, ...`）。
    | structKeywordName                   # PatternStructKeywordName
    ;

/**
 * 表达式语句
 * 语法: someFunction x y.
 */
exprStmt
    // 语句头关键词开头的一行只能是对应语句（与 TS parseStatement 按关键词分派一致）。
    // structKeywordName 的软关键字放行只针对标识符位置；若让表达式语句以它开头，
    // `Return and(a).` 会在 returnStmt 失败后被当成变量 Return 与 (a) 的逻辑与，函数静默不再返回。
    : {!isStatementHead(_input.LA(1))}? expr DOT
    ;

// ============================================================
// 表达式规则
// ============================================================

/**
 * 表达式（多级优先级）
 * 优先级（低→高）：or < and < not < 比较 < 加减 < 乘除 < 函数调用 < 基本表达式
 */
// ADR 0019 G2b：表达式级 if（`if cond then a else b`）作为完整表达式。锚在 expr 顶层
// 而非进运算符优先级链：if-expr 是自带边界的完整表达式（then/else 划定子表达式范围），
// 不参与 +/*/比较 的结合，从根本上避开 dangling-else 与运算符优先级歧义。else 必需
// （表达式两个方向都必须有值）。thenExpr/elseExpr 递归用 expr → 支持嵌套 if-expr。
expr
    : ifExpr
    | orExpr
    ;

ifExpr
    : IF cond=orExpr inlineThen thenE=expr inlineElseSep elseE=expr
    ;

// 逻辑或（最低优先级，左结合）。and/or 在表达式上下文是逻辑运算符；
// 在 givenParamList / fieldList / constructFields / typeList / waitStmt 等
// 列表上下文，AND 由各自规则显式消费，不进入此表达式链。
// ADR 0026：等缩进多行表达式续行——运算符前后可有零或多个 NEWLINE（只吞 NEWLINE，
// 绝不含 INDENT/DEDENT，故块的缩进栈不受影响）。与 fieldList 里 `NEWLINE* (AND|COMMA) NEWLINE*`
// 同构。ALL(*) 靠运算符 first-set 与「NEWLINE 后接 DEDENT/语句引导」区分续行 vs 块边界。
nlOpt
    : NEWLINE*
    ;

orExpr
    : andExpr (nlOpt OR nlOpt andExpr)*
    ;

// 逻辑与（高于 or，低于 not，左结合）
andExpr
    : notExpr (nlOpt AND nlOpt notExpr)*
    ;

// 逻辑非（高于 and，低于比较——`not x greater than y` = `not(x greater than y)`）。
// ★与 TS 引擎对齐（parseAnd→parseNot→parseComparison）+ 兑现本文件头声明的
//   `and < not < 比较` 优先级。此前 NOT 误置于 unaryExpr（紧于比较），使
//   `not x greater than y` 被解析为 `(not x) greater than y`——两引擎产生不同 AST
//   （Java 紧绑定、TS 松绑定），是审计发现的双引擎 parity 缺陷。右结合以支持 `not not x`。
notExpr
    : NOT notExpr                        # LogicalNot
    | comparisonExpr                     # NotFallthrough
    ;

// 比较表达式。除符号/多词比较词 token 外，`under` / `over` 作为**软关键字**
// 在此上下文匹配（仅比较位置当运算符，其他位置仍是普通标识符，与 TS 一致）。
// 软关键字前可带一个可选的 `is` 连接词（`is under` / `is over`），由语义谓词
// 识别 IDENT 文本，避免在 lexer 无条件保留这些常见英文单词。
comparisonExpr
    : additiveExpr ( nlOpt (
          op=(LT | GT | LTE | GTE | NEQ | EQ | EQUALS
              | LESS_THAN_WORD | GREATER_THAN_WORD
              | LESS_THAN_OR_EQUAL_WORD | GREATER_THAN_OR_EQUAL_WORD
              | EQUALS_TO_WORD | NOT_EQUAL_TO_WORD) nlOpt additiveExpr
        | softCmp=softComparator nlOpt additiveExpr
      ) )?
    ;

// 软比较词：`under` / `over`，前置可选 `is`。用语义谓词匹配 IDENT 文本，使
// 这些词只在比较位置充当运算符；其余位置（变量名、字段名）仍是普通标识符。
softComparator
    : ( {_input.LT(1).getText().equals("is")}? isKw=IDENT )?
      word=IDENT
      { $word.getText().equals("under") || $word.getText().equals("over") }?
    ;

additiveExpr
    : multiplicativeExpr (nlOpt op=(PLUS | MINUS | PLUS_WORD | MINUS_WORD) nlOpt multiplicativeExpr)*
    ;

multiplicativeExpr
    : unaryExpr (nlOpt op=(STAR | SLASH | TIMES_WORD | DIVIDED_BY_WORD | INTEGER_DIVIDED_BY_WORD | MODULO_WORD) nlOpt unaryExpr)*
    ;

unaryExpr
    // NOT 已上移到 notExpr（比较之上，见上）——不再在此层。此前 `NOT unaryExpr` 使
    // not 紧于比较，与 TS 分歧且违背头注释优先级，已修。
    // ADR 0027：无括号单参调用 `apply <fn> to <arg>`，lower 成 Call(fn,[arg])，零新 IR 节点。
    // 放在 unaryExpr 层（而非 primaryExpr）以免 postfixExpr 对 apply 结果接调用/成员后缀；
    // arg 取顶层 expr（贪婪），故 `apply gather to stars less 1` = `gather(stars less 1)`。
    : applyExpr                          # ApplyCallExpr
    | postfixExpr                        # PostfixUnary
    ;

/**
 * ADR 0027：无括号单参调用。fn 用专门的 callTarget（裸名/限定名点链，不含调用后缀），
 * 与 TS 引擎 parseCallTargetName 对齐——否则 `apply f(y) to x` 会一引擎接受一引擎拒，破坏 parity。
 */
applyExpr
    : APPLY callTarget TO_WORD expr
    ;

callTarget
    : callTargetSegment (DOT callTargetSegment)*
    ;

// 每段放行集对齐 TS parseCallTargetName（TS 无 MAP token，`Map` 处处是 TYPE_IDENT）——
// MAP 须出现在**任意段**而非仅首段，否则 `apply Foo.Map to x` 一引擎收一引擎拒
// （Codex 审查 019f1639 致命问题 #1）。
callTargetSegment
    : IDENT | TYPE_IDENT | MAP | structKeywordName
    ;

/**
 * 后缀表达式，支持函数调用和点号访问
 */
postfixExpr
    : primaryExpr postfixSuffix*
    ;

/**
 * 调用只有括号形式 `f(x, y)`；与 TS 一致，不存在 `f with x, y` 的自然语言调用后缀，
 * `X with …` 只能是构造表达式（见 constructExpr）。
 */
postfixSuffix
    : LPAREN argumentList? RPAREN          # CallSuffix
    | DOT (IDENT | TYPE_IDENT | structKeywordName)  # MemberSuffix
    ;

argumentList
    : expr (COMMA expr)*
    ;

/**
 * 基本表达式
 * 注：VarExpr 和 TypeIdentExpr 都可以用于变量引用，
 * 中文变量名会匹配 TYPE_IDENT（因为 CJK 字符按大写处理）
 */
primaryExpr
    : lambdaExpr                           # LambdaExprAlt
    | operatorCall                         # OperatorCallExpr
    | constructExpr                        # ConstructExprAlt
    | wrapExpr                             # WrapExprAlt
    | listLiteral                          # ListLiteralExpr
    | IDENT                                # VarExpr
    // ADR 0019 G1：结构关键词的小写形式在表达式位置（如 `Return let.`）当变量引用，
    // 与改动前小写词是 IDENT 的行为及 TS 上下文关键字模型一致。
    | structKeywordName                    # StructKeywordVarExpr
    | TYPE_IDENT                           # TypeIdentExpr
    | MAP                                  # MapIdentExpr
    | STRING_LITERAL                       # StringExpr
    | INT_LITERAL                          # IntExpr
    | FLOAT_LITERAL                        # FloatExpr
    | DECIMAL_LITERAL                      # DecimalExpr
    | LONG_LITERAL                         # LongExpr
    | BOOL_LITERAL                         # BoolExpr
    | NULL_LITERAL                         # NullExpr
    | LPAREN expr RPAREN                   # ParenExpr
    ;

/**
 * 构造表达式：`Type with field set to expr (and|, field set to expr)*`。
 *
 * 与 TS 解析器对齐（aster-lang-ts#244）：字段只接受 `set to`。首个字段名后不是 `set`
 * 时（`X with f = v`、`X with a, b`）走 MalformedConstructBody：只报告一条
 * "Expected 'set to' in construction"，并吞掉本语句余下记号，不产生级联诊断。
 *
 * 引导词仍接受 `has`：zh-CN 的 `包含` 同时映射 TYPE_WITH/TYPE_HAS（等长歧义表面），
 * Canonicalizer 取哪个取决于词表 map 的迭代顺序，随 JVM 进程变化（见 aster-lang-zh
 * ZhCnCanonicalizerTest 审计 #25）。只收 `with` 会让合法的中文构造随机失败。
 * 代价是英文 `X has f set to v` 在 Java 上仍被接受而 TS 拒绝，待规范化确定后再收紧。
 */
constructExpr
    : constructHead (WITH | HAS) constructBody
    ;

/**
 * 良构与畸形在第二个记号处即可区分：良构字段以 `name set` 开头；畸形字段的第二个
 * 记号不得是 `set`，故 `X with f set to` 之后的错误（缺值、尾随多余记号）仍按良构
 * 构造报告。后续字段同理（`X with a set to 1, b = 2` 在 `=` 处报错）。
 */
constructBody
    : constructFieldList                   # WellFormedConstructBody
    | malformedConstructField              # MalformedConstructBody
    ;

constructFieldList
    : constructField ((AND | COMMA) constructField)* ((AND | COMMA) malformedConstructField)?
    ;

/**
 * 缺 `set to` 的字段：报告一条诊断后吞到语句结束句点为止（不含），不产生级联。
 */
malformedConstructField
    : { reportMalformedConstruct(); }
      ~(DOT | NEWLINE | INDENT | DEDENT | EOF)
      ( ~(SET | DOT | NEWLINE | INDENT | DEDENT | EOF) malformedConstructTail )?
    ;

/**
 * 畸形构造的残余：成员访问里的点（`person.age`）后面不是语句边界，照常吞掉。
 */
malformedConstructTail
    : ( {!isStatementEndDot()}? DOT | ~(DOT | NEWLINE | INDENT | DEDENT | EOF) )*
    ;

/**
 * 构造类型名：TYPE_IDENT，或后随 `with` 的 IDENT（TS 同样按此进入构造分支，
 * 小写名后跟 `with` 一律视为构造，不存在 `f with x` 调用形式）。
 */
constructHead
    : TYPE_IDENT
    | IDENT
    ;


constructField
    : (IDENT | TYPE_IDENT | structKeywordName) SET TO_WORD expr
    ;

operatorCall
    : op=(LT | GT | LTE | GTE | NEQ | EQ | EQUALS | PLUS | MINUS | STAR | SLASH)
      LPAREN argumentList RPAREN
    ;

wrapExpr
    : IDENT OF expr
    ;

listLiteral
    : LBRACKET (expr (COMMA expr)*)? RBRACKET
    ;

/**
 * Lambda 表达式
 * 语法: function given x: Text, produce Text: Return x.
 *       function with x, produce: ...（with 引导参数；produce 类型可省，推断为 Unknown）
 * <p>
 * 参数引导词 with/given 同义（与 TS parseParamList 对齐）；produce 后类型可选，
 * 省略时 lower 为 Type.Name("Unknown")（与 TS Node.TypeName('Unknown') 对齐）。
 */
lambdaExpr
    : FUNCTION lambdaParamList? COMMA? PRODUCE annotatedType? COLON (returnStmt | (NEWLINE block))
    ;

/**
 * Lambda 参数列表：with 或 given 引导（同义）
 */
lambdaParamList
    : (GIVEN | WITH) param ((AND | COMMA) param)*
    ;

// ============================================================
// 关键字定义（虚拟规则，用于 Lexer 识别）
// ============================================================

// 以下关键字将在 Lexer 中通过 IDENT/TYPE_IDENT 识别，
// Parser 中通过语义检查区分。ANTLR4 不强制关键字保留。

// 关键字列表（参考）:
// - MODULE, RULE, GIVEN, WITH, AND, PRODUCE, DEFINE, HAS, AS, ONE, OF
// - USE, LET, BE, RETURN, IF, ELSE
