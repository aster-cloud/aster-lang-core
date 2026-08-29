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
    : NEWLINE* moduleHeader? NEWLINE* (topLevelDecl NEWLINE*)* EOF
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
    : (annotation NEWLINE*)* RULE nameIdent typeParamList? givenParamList? COMMA? (PRODUCE annotatedType?)? (DOT capabilityAnnotation? funcBody | funcBody | DOT)
    ;

// ADR 0028：函数体形态。缩进块（现有）走 `COLON NEWLINE block`；显式块（新增）走
// `COLON explicitBlock`——COLON 后语句立即开始、以 BLOCK_END（方言词如「毕」经 Canonicalizer
// 翻成 \0BLOCK_END sentinel）收尾。抽成 funcBody 让普通 body 与 capability body 都复用，
// 避免带 capability 的函数不能用显式块（双引擎不一致点，Codex 设计审）。
funcBody
    : COLON NEWLINE block
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
 */
capabilityAnnotation
    : IT PERFORMS IDENT (LBRACKET TYPE_IDENT (COMMA TYPE_IDENT)* RBRACKET)?
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
    : DEFINE article? TYPE_IDENT (HAS | WITH) fieldList DOT (NEWLINE* DEDENT)?
    | article? TYPE_IDENT (HAS | WITH) fieldList DOT (NEWLINE* DEDENT)?
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
 * 字段/参数约束修饰符（与 TS constraint-parser 对齐）。
 * required：必填；between X and Y：范围。当前仅 parse 接受以对齐双引擎语法，
 * 约束语义（校验执行）作为后续阶段，不进 Core IR fingerprint（结构级比较）。
 */
fieldConstraint
    : REQUIRED
    | BETWEEN expr AND expr
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
    ;

/**
 * 枚举类型定义
 * 语法: Define Status as one of Success, Failure, Pending.
 */
enumDecl
    : DEFINE article? TYPE_IDENT AS ONE OF variantList DOT
    ;

article
    : IDENT
    ;

/**
 * 变体列表
 * 语法: Success, Failure, Pending
 */
variantList
    : TYPE_IDENT ((COMMA | OR | AND) TYPE_IDENT)*
    ;

/**
 * 导入声明
 * 语法:
 *   Use io.Http.
 *   Use io.Http as HttpClient.
 *   Use io.Http version 2 as HttpClient.
 */
importDecl
    : USE qualifiedName (VERSION INT_LITERAL)? (AS importAlias)? DOT
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
 * 语法: @pii type Email as Text.
 */
typeDecl
    : annotation* TYPE (TYPE_IDENT | IDENT | structKeywordName) AS annotatedType DOT
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
    | FLOAT_LITERAL
    | LONG_LITERAL
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
 */
annotatedType
    : annotation* type
    ;

type
    : type QUESTION                       # MaybeType
    | LPAREN typeList RPAREN ARROW type   # FuncType
    | MAP annotatedType TO_WORD annotatedType  # MapType
    | (TYPE_IDENT | IDENT) OF annotatedType ((AND | COMMA) annotatedType)*  # OfGenericType
    | TYPE_IDENT LT typeList GT           # GenericType
    | TYPE_IDENT                          # TypeName
    | LPAREN type RPAREN                  # ParenType
    ;

/**
 * 类型列表（用于泛型参数和函数参数类型）
 * 语法: Int, Text, Bool
 */
typeList
    : annotatedType (COMMA annotatedType)*
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
    | defineStmt
    | startStmt
    | waitStmt
    | workflowStmt
    | returnStmt
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

startStmt
    : START nameIdent AS ASYNC? expr DOT
    ;

waitStmt
    : WAIT FOR nameIdent (AND nameIdent)* DOT
    ;

workflowStmt
    : WORKFLOW COLON NEWLINE INDENT workflowBody DEDENT DOT
    ;

workflowBody
    : workflowStep (NEWLINE+ workflowStep)*
      (NEWLINE+ retrySection)?
      (NEWLINE+ timeoutSection)?
      NEWLINE*
    ;

workflowStep
    : STEP nameIdent stepDependencies? COLON NEWLINE block (NEWLINE+ compensateSection)?
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
 * If 语句
 * 语法:
 *   If condition:
 *     Return "yes".
 */
ifStmt
    : IF expr (COMMA | COLON)? NEWLINE block (NEWLINE? ELSE (COMMA | COLON)? NEWLINE block)?
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
    : MATCH expr COLON NEWLINE INDENT matchCase (NEWLINE+ matchCase)* NEWLINE* DEDENT
    ;

/**
 * Match 分支
 * 语法: When pattern, body.
 */
matchCase
    : WHEN pattern COMMA (returnStmt | block)
    ;

/**
 * 模式（用于 Match 语句）
 */
pattern
    : NULL_LITERAL                         # PatternNull
    | TYPE_IDENT (LPAREN pattern (COMMA pattern)* RPAREN)?  # PatternCtor
    | INT_LITERAL                         # PatternInt
    | IDENT                               # PatternName
    // ADR 0019 G1：match 绑定名可为软关键字（小写结构词，如 `when return, ...`）。
    | structKeywordName                   # PatternStructKeywordName
    ;

/**
 * 表达式语句
 * 语法: someFunction x y.
 */
exprStmt
    : expr DOT
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

postfixSuffix
    : LPAREN argumentList? RPAREN          # CallSuffix
    | (WITH | HAS) argumentList             # WithCallSuffix
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

constructExpr
    : TYPE_IDENT (WITH | HAS) constructFieldList
    ;

constructFieldList
    : constructField ((AND | COMMA) constructField)*
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
