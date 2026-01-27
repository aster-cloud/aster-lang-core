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
 *   This module is app.
 *   To helloMessage produce Text:
 *     Return "Hello, world!".
 */
module
    : NEWLINE* moduleHeader? NEWLINE* (topLevelDecl NEWLINE*)* EOF
    ;

/**
 * 模块头: This module is app.
 */
moduleHeader
    : THIS MODULE IS qualifiedName DOT
    ;

/**
 * 限定名称（用于模块路径）: io.Http 或 data.List
 */
qualifiedName
    : qualifiedSegment (DOT qualifiedSegment)*
    ;

/**
 * 限定名称片段，允许普通标识符或类型标识符
 */
qualifiedSegment
    : IDENT
    | TYPE_IDENT
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
 *   To greet produce Text:
 *     Return "Hello".
 *
 *   To add with x: Int and y: Int, produce Int:
 *     Return x + y.
 *
 *   To generateQuote with driver, vehicle, produce:
 *     ...（隐式返回类型，基于函数名推断）
 *
 *   To ping, produce Text. It performs io [Http, Sql]:
 *     Return "ok".
 */
funcDecl
    : TO (IDENT | TYPE_IDENT) typeParamList? paramList? COMMA? PRODUCE annotatedType? (DOT capabilityAnnotation? COLON NEWLINE block | COLON NEWLINE block | DOT)
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
    ;

/**
 * 参数列表
 * 语法: with x: Int and y: Text (推荐)
 *       with x: Int, y: Text (兼容)
 */
paramList
    : WITH param ((AND | COMMA) param)*
    ;

/**
 * 参数定义
 * 语法: x: Int（显式类型）
 *       x（隐式类型，基于参数名推断）
 */
param
    : annotation* nameIdent (COLON annotatedType)?
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
 * 语法: Define User with name: Text and age: Int.
 * 多行格式:
 * Define User with
 *   name: Text,
 *   age: Int.
 */
dataDecl
    : DEFINE article? TYPE_IDENT WITH fieldList DOT (NEWLINE* DEDENT)?
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
    : annotation* nameIdent (COLON annotatedType)?
    ;

nameIdent
    : IDENT
    | TYPE_IDENT
    | TYPE
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
 */
importDecl
    : USE qualifiedName (AS importAlias)? DOT
    ;

/**
 * 导入别名，允许类型或普通标识符
 */
importAlias
    : TYPE_IDENT
    | IDENT
    ;

/**
 * 类型别名声明
 * 语法: @pii type Email as Text.
 */
typeDecl
    : annotation* TYPE (TYPE_IDENT | IDENT) AS annotatedType DOT
    ;

/**
 * 注解标注
 * 语法: @pii
 */
annotation
    : AT (IDENT | TYPE_IDENT) annotationArgs?
    ;

annotationArgs
    : LPAREN (annotationArg (COMMA annotationArg)*)? RPAREN
    ;

annotationArg
    : IDENT COLON annotationValue        # NamedAnnotationArg
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
    | MAP annotatedType TO annotatedType  # MapType
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
 * 语句
 */
stmt
    : letStmt
    | defineStmt
    | startStmt
    | waitStmt
    | workflowStmt
    | returnStmt
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
    : IF expr COMMA? COLON NEWLINE block (NEWLINE? ELSE COMMA? COLON NEWLINE block)?
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
 * 使用优先级爬升技术：比较 < 加减 < 乘除 < 函数调用 < 基本表达式
 */
expr
    : comparisonExpr
    ;

comparisonExpr
    : additiveExpr (op=(LT | GT | LTE | GTE | NEQ | LESS_THAN_WORD | GREATER_THAN_WORD | LESS_THAN_OR_EQUAL_WORD | GREATER_THAN_OR_EQUAL_WORD | EQUALS_TO_WORD | NOT_EQUAL_TO_WORD) additiveExpr)?
    ;

additiveExpr
    : multiplicativeExpr (op=(PLUS | MINUS | PLUS_WORD | MINUS_WORD) multiplicativeExpr)*
    ;

multiplicativeExpr
    : unaryExpr (op=(STAR | SLASH | TIMES_WORD | DIVIDED_BY_WORD) unaryExpr)*
    ;

unaryExpr
    : NOT unaryExpr                      # NotExpr
    | postfixExpr                        # PostfixUnary
    ;

/**
 * 后缀表达式，支持函数调用和点号访问
 */
postfixExpr
    : primaryExpr postfixSuffix*
    ;

postfixSuffix
    : LPAREN argumentList? RPAREN          # CallSuffix
    | WITH argumentList                    # WithCallSuffix
    | DOT (IDENT | TYPE_IDENT)             # MemberSuffix
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
    | TYPE_IDENT                           # TypeIdentExpr
    | MAP                                  # MapIdentExpr
    | STRING_LITERAL                       # StringExpr
    | INT_LITERAL                          # IntExpr
    | FLOAT_LITERAL                        # FloatExpr
    | LONG_LITERAL                         # LongExpr
    | BOOL_LITERAL                         # BoolExpr
    | NULL_LITERAL                         # NullExpr
    | LPAREN expr RPAREN                   # ParenExpr
    ;

constructExpr
    : TYPE_IDENT WITH constructFieldList
    ;

constructFieldList
    : constructField ((AND | COMMA) constructField)*
    ;

constructField
    : (IDENT | TYPE_IDENT) EQUALS expr
    ;

operatorCall
    : op=(LT | GT | LTE | GTE | NEQ | EQUALS | PLUS | MINUS | STAR | SLASH)
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
 * 语法: function with x: Text, produce Text: Return x.
 */
lambdaExpr
    : FUNCTION paramList? COMMA? PRODUCE annotatedType COLON (returnStmt | (NEWLINE block))
    ;

// ============================================================
// 关键字定义（虚拟规则，用于 Lexer 识别）
// ============================================================

// 以下关键字将在 Lexer 中通过 IDENT/TYPE_IDENT 识别，
// Parser 中通过语义检查区分。ANTLR4 不强制关键字保留。

// 关键字列表（参考）:
// - THIS, MODULE, IS, TO, WITH, AND, PRODUCE, DEFINE, AS, ONE, OF
// - USE, LET, BE, RETURN, IF, ELSE
