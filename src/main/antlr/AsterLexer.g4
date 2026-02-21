/**
 * Aster CNL 词法规则
 *
 * 基于 TypeScript Lexer 迁移而来，包含完整的 token 定义。
 * 注意：缩进处理（INDENT/DEDENT）需要通过自定义 Lexer 类实现。
 */
lexer grammar AsterLexer;

// ============================================================
// 标点符号（Punctuation）
// ============================================================
DOT: '.';
COLON: ':';
COMMA: ',';
LPAREN: '(';
RPAREN: ')';
LBRACKET: '[';
RBRACKET: ']';

// ============================================================
// 运算符（Operators）
// ============================================================
// 多字符运算符必须放在单字符之前，避免优先级问题
LTE: '<=';
GTE: '>=';
NEQ: '!=';

EQUALS: '=';
PLUS: '+';
STAR: '*';
MINUS: '-';
SLASH: '/';
LT: '<';
GT: '>';
QUESTION: '?';
AT: '@';
ARROW: '->';

// ============================================================
// 自然语言运算符关键词（Natural Language Operators）
// ============================================================
// 比较运算符
LESS_THAN_WORD: 'less' [ \t]+ 'than';
GREATER_THAN_WORD: 'greater' [ \t]+ 'than';
LESS_THAN_OR_EQUAL_WORD: 'less' [ \t]+ 'than' [ \t]+ 'or' [ \t]+ 'equal' [ \t]+ 'to';
GREATER_THAN_OR_EQUAL_WORD: 'greater' [ \t]+ 'than' [ \t]+ 'or' [ \t]+ 'equal' [ \t]+ 'to';
EQUALS_TO_WORD: 'equals' [ \t]+ 'to';
NOT_EQUAL_TO_WORD: 'not' [ \t]+ 'equal' [ \t]+ 'to';

// 算术运算符
TIMES_WORD: 'times';
DIVIDED_BY_WORD: 'divided' [ \t]+ 'by';
PLUS_WORD: 'plus';
MINUS_WORD: 'minus';

// ============================================================
// 字面量（Literals）
// ============================================================

// 字符串字面量（支持转义）
STRING_LITERAL: '"' ( ~["\\\r\n] | '\\' . )* '"';

// 布尔字面量
BOOL_LITERAL: 'true' | 'false';

// null 字面量
NULL_LITERAL: 'null';

// 长整型字面量（带 L 后缀）
LONG_LITERAL: [0-9]+ [Ll];

// 浮点数字面量（包含小数点）
FLOAT_LITERAL: [0-9]+ '.' [0-9]+;

// 整数字面量
INT_LITERAL: [0-9]+;

// ============================================================
// 关键字（Keywords）
// ============================================================
// 注意：关键字必须在 IDENT 之前定义，确保优先匹配

// 模块相关
MODULE_KW: 'Module';
THIS: 'This' | 'this';
MODULE: 'module';
IS: 'is';

// 函数相关
RULE: 'Rule';
GIVEN: 'given';
TO: 'To' | 'to';
WITH: 'with';
AND: 'and';
OR: 'or';
PRODUCE: 'produce';
HAS: 'has';

// 类型定义相关
DEFINE: 'Define';
TYPE: 'type';
AS: 'as';
ONE: 'one';
OF: 'of';

// 导入相关
USE: 'Use' | 'use';

// 语句关键字
LET: 'Let';
BE: 'be';
RETURN: 'Return';
IF: 'If';
ELSE: 'Else' | 'Otherwise';
MATCH: 'Match';
WHEN: 'When';
NOT: 'not';
START: 'Start';
WAIT: 'Wait';
FOR: 'for';
ASYNC: 'async';
WORKFLOW: 'Workflow' | 'workflow';
STEP: 'Step' | 'step';
RETRY: 'Retry' | 'retry';
TIMEOUT: 'Timeout' | 'timeout';
DEPENDS: 'Depends' | 'depends';
ON: 'On' | 'on';
COMPENSATE: 'Compensate' | 'compensate';
MAX: 'Max' | 'max';
ATTEMPTS: 'Attempts' | 'attempts';
BACKOFF: 'Backoff' | 'backoff';
SECONDS: 'seconds' | 'second';

// 表达式关键字
FUNCTION: 'function';

// 集合类型关键字
MAP: 'Map';

// 能力标注关键字
IT: 'It';
PERFORMS: 'performs';

// ============================================================
// 标识符（Identifiers）
// ============================================================

// 类型标识符（Uppercase 开头，支持 Latin Extended 和 CJK 等 Unicode 字符）
TYPE_IDENT: [A-Z] IdentContinue*
          | LatinExtUpperChar IdentContinue*
          | CjkChar IdentContinue*
          ;

// 普通标识符（lowercase 或 _ 开头，支持 Latin Extended 和 CJK 等 Unicode 字符）
IDENT: [a-z_] IdentContinue*
     | LatinExtLowerChar IdentContinue*
     ;

// 标识符续字符片段（字母、数字、下划线、Latin Extended、CJK 字符）
fragment IdentContinue: [a-zA-Z0-9_] | LatinExtChar | CjkChar;

// Latin Extended 字符（德语 ü/ö/ä/ß、法语 é/è/ê 等）
fragment LatinExtChar: [\u00C0-\u00FF]     // Latin-1 Supplement (À-ÿ)
                     | [\u0100-\u024F]     // Latin Extended-A/B
                     ;

// Latin Extended 大写字符（À-Ö, Ø-Þ, 及 Extended-A/B 中的大写）
fragment LatinExtUpperChar: [\u00C0-\u00D6]  // À-Ö
                          | [\u00D8-\u00DE]  // Ø-Þ
                          | [\u0100-\u024F]  // Extended-A/B（混合大小写，ANTLR 无法区分，Canonicalizer 保证输入正确性）
                          ;

// Latin Extended 小写字符（ß-ö, ø-ÿ, 及 Extended-A/B 中的小写）
fragment LatinExtLowerChar: [\u00DF-\u00F6]  // ß-ö
                          | [\u00F8-\u00FF]  // ø-ÿ
                          ;

// CJK 字符（中文、日文汉字、韩文）
fragment CjkChar: [\u4E00-\u9FFF]    // CJK 统一汉字
               | [\u3400-\u4DBF]    // CJK 扩展 A
               | [\uF900-\uFAFF]    // CJK 兼容汉字
               | [\u3040-\u309F]    // 平假名
               | [\u30A0-\u30FF]    // 片假名
               | [\uAC00-\uD7AF]    // 韩文音节
               ;

// ============================================================
// 注释（Comments）
// ============================================================

// 单行注释（归入 HIDDEN 通道，后续通过自定义 Lexer 处理 trivia 分类）
COMMENT: '#' ~[\r\n]* -> channel(HIDDEN);

// ============================================================
// 空白符（Whitespace）
// ============================================================

// 换行符（需要单独处理以支持缩进检测）
NEWLINE: '\r'? '\n';

// 行内空白（跳过）
WS: [ \t]+ -> skip;

// ============================================================
// 缩进 tokens（INDENT/DEDENT）
// ============================================================
// 注意：ANTLR4 默认无法生成 INDENT/DEDENT token，
// 需要通过自定义 Lexer 类（AsterCustomLexer）在 nextToken() 中动态生成。
// 参考：Python ANTLR4 grammar 的缩进处理方式。
// ============================================================
