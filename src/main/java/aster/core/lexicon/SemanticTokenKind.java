package aster.core.lexicon;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 语义化 Token 类型枚举 - Aster 多语言词法架构的单一真源。
 * <p>
 * <b>设计原则</b>：
 * <ul>
 *   <li>语言无关：所有枚举值代表抽象语义概念，不含任何自然语言</li>
 *   <li>单一真源：所有 Lexicon 实现必须映射到此枚举</li>
 *   <li>类型安全：使用 Java enum 提供编译时检查</li>
 * </ul>
 * <p>
 * <b>使用方式</b>：
 * <ul>
 *   <li>Lexicon 定义：{@code keywords.put(SemanticTokenKind.IF, "若")}</li>
 *   <li>Lexer 输出：{@code new Token(TokenKind.KEYWORD, SemanticTokenKind.IF, ...)}</li>
 *   <li>Parser 匹配：{@code expectKeyword(SemanticTokenKind.IF)}</li>
 * </ul>
 */
public enum SemanticTokenKind {
    // ============================================================
    // 模块声明
    // ============================================================

    /** 模块声明 - "Module" / "【模块】" */
    MODULE_DECL,

    /** 导入声明 - "use" / "引用" */
    IMPORT,

    /** 导入别名 - "as" / "作为" */
    IMPORT_ALIAS,

    // ============================================================
    // 类型定义
    // ============================================================

    /** 类型定义 - "define" / "【定义】" */
    TYPE_DEF,

    /** 类型字段（旧语法，已由 TYPE_HAS 替代） - "with" / "包含" */
    TYPE_WITH,

    /** 类型包含 - "has" / "包含" */
    TYPE_HAS,

    /** 枚举类型 - "as one of" / "为以下之一" */
    TYPE_ONE_OF,

    // ============================================================
    // 函数定义
    // ============================================================

    /** 函数入参（旧语法，已由 FUNC_GIVEN 替代） - "to" / "入参" */
    FUNC_TO,

    /** 函数参数 - "given" / "给定" */
    FUNC_GIVEN,

    /** 函数产出 - "produce" / "产出" */
    FUNC_PRODUCE,

    /** 函数效果声明 - "it performs" / "执行" */
    FUNC_PERFORMS,

    // ============================================================
    // 控制流
    // ============================================================

    /** 条件判断 - "if" / "若" */
    IF,

    /** 否则分支 - "otherwise" / "否则" */
    OTHERWISE,

    /** 模式匹配 - "match" / "把" */
    MATCH,

    /** 匹配分支 - "when" / "当" */
    WHEN,

    /** 返回语句 - "return" / "返回" */
    RETURN,

    /** 结果是（同义词） - "the result is" / "结果为" */
    RESULT_IS,

    /** 循环遍历 - "for each" / "对每个" */
    FOR_EACH,

    /** 集合成员 - "in" / "在" */
    IN,

    // ============================================================
    // 变量操作
    // ============================================================

    /** 变量声明 - "let" / "令" */
    LET,

    /** 变量初始化 - "be" / "为" */
    BE,

    /** 变量赋值 - "set" / "将" */
    SET,

    /** 赋值目标 - "to" (set ... to) / "设为" */
    TO_WORD,

    // ============================================================
    // 布尔运算
    // ============================================================

    /** 逻辑或 - "or" / "或" */
    OR,

    /** 逻辑与 - "and" / "且" */
    AND,

    /** 逻辑非 - "not" / "非" */
    NOT,

    // ============================================================
    // 算术运算
    // ============================================================

    /** 加法 - "plus" / "加" */
    PLUS,

    /** 减法 - "minus" / "减" */
    MINUS_WORD,

    /** 乘法 - "times" / "乘" */
    TIMES,

    /** 除法 - "divided by" / "除以" */
    DIVIDED_BY,

    // ============================================================
    // 比较运算
    // ============================================================

    /** 小于 - "less than" / "小于" */
    LESS_THAN,

    /** 大于 - "greater than" / "大于" */
    GREATER_THAN,

    /** 等于 - "equals to" / "等于" */
    EQUALS_TO,

    /** 判断 - "is" / "是" */
    IS,

    /** 低于（同义词） - "under" / "不足" */
    UNDER,

    /** 超过（同义词） - "over" / "超过" */
    OVER,

    /** 多于（同义词） - "more than" / "多于" */
    MORE_THAN,

    // ============================================================
    // 类型构造
    // ============================================================

    /** 可选类型 - "maybe" / "可选" */
    MAYBE,

    /** Option 类型 - "option of" */
    OPTION_OF,

    /** Result 类型 - "result of" */
    RESULT_OF,

    /** 成功值 - "ok of" / "成功" */
    OK_OF,

    /** 错误值 - "err of" / "失败" */
    ERR_OF,

    /** 有值 - "some of" / "有值" */
    SOME_OF,

    /** 空值 - "none" / "无" */
    NONE,

    // ============================================================
    // 字面量
    // ============================================================

    /** 布尔真 - "true" / "真" */
    TRUE,

    /** 布尔假 - "false" / "假" */
    FALSE,

    /** 空值 - "null" / "空" */
    NULL,

    // ============================================================
    // 基础类型
    // ============================================================

    /** 文本类型 - "text" / "文本" */
    TEXT,

    /** 整数类型 - "int" / "整数" */
    INT_TYPE,

    /** 浮点类型 - "float" / "小数" */
    FLOAT_TYPE,

    /** 布尔类型 - "bool" / "布尔" */
    BOOL_TYPE,

    // ============================================================
    // 效果声明
    // ============================================================

    /** IO 效果 - "io" / "输入输出" */
    IO,

    /** CPU 效果 - "cpu" / "计算" */
    CPU,

    // ============================================================
    // 工作流
    // ============================================================

    /** 工作流定义 - "workflow" / "【流程】" */
    WORKFLOW,

    /** 步骤定义 - "step" / "【步骤】" */
    STEP,

    /** 依赖声明 - "depends" / "依赖" */
    DEPENDS,

    /** 依赖目标 - "on" / "于" */
    ON,

    /** 补偿操作 - "compensate" / "补偿" */
    COMPENSATE,

    /** 重试策略 - "retry" / "重试" */
    RETRY,

    /** 超时设置 - "timeout" / "超时" */
    TIMEOUT,

    /** 最大尝试次数 - "max attempts" / "最多尝试" */
    MAX_ATTEMPTS,

    /** 退避策略 - "backoff" / "退避" */
    BACKOFF,

    // ============================================================
    // 异步操作
    // ============================================================

    /** 作用域 - "within" / "范围" */
    WITHIN,

    /** 作用域块 - "scope" / "域" */
    SCOPE,

    /** 启动异步 - "start" / "启动" */
    START,

    /** 异步标记 - "async" / "异步" */
    ASYNC,

    /** 等待结果 - "await" / "等待" */
    AWAIT,

    /** 等待完成 - "wait for" / "等候" */
    WAIT_FOR,

    // ============================================================
    // 约束声明
    // ============================================================

    /** 必填约束 - "required" / "必填" */
    REQUIRED,

    /** 范围约束 - "between" / "介于" */
    BETWEEN,

    /** 最小约束 - "at least" / "至少" */
    AT_LEAST,

    /** 最大约束 - "at most" / "至多" */
    AT_MOST,

    /** 匹配约束 - "matching" / "匹配" */
    MATCHING,

    /** 模式约束 - "pattern" / "模式" */
    PATTERN;

    /**
     * SemanticTokenKind 分类映射，用于文档和验证。
     */
    public static final Map<String, List<SemanticTokenKind>> CATEGORIES = Map.ofEntries(
        Map.entry("module", Arrays.asList(MODULE_DECL, IMPORT, IMPORT_ALIAS)),
        Map.entry("type", Arrays.asList(TYPE_DEF, TYPE_WITH, TYPE_HAS, TYPE_ONE_OF)),
        Map.entry("function", Arrays.asList(FUNC_TO, FUNC_GIVEN, FUNC_PRODUCE, FUNC_PERFORMS)),
        Map.entry("control", Arrays.asList(IF, OTHERWISE, MATCH, WHEN, RETURN, RESULT_IS, FOR_EACH, IN)),
        Map.entry("variable", Arrays.asList(LET, BE, SET, TO_WORD)),
        Map.entry("boolean", Arrays.asList(OR, AND, NOT)),
        Map.entry("arithmetic", Arrays.asList(PLUS, MINUS_WORD, TIMES, DIVIDED_BY)),
        Map.entry("comparison", Arrays.asList(LESS_THAN, GREATER_THAN, EQUALS_TO, IS, UNDER, OVER, MORE_THAN)),
        Map.entry("typeConstruct", Arrays.asList(MAYBE, OPTION_OF, RESULT_OF, OK_OF, ERR_OF, SOME_OF, NONE)),
        Map.entry("literal", Arrays.asList(TRUE, FALSE, NULL)),
        Map.entry("primitiveType", Arrays.asList(TEXT, INT_TYPE, FLOAT_TYPE, BOOL_TYPE)),
        Map.entry("effect", Arrays.asList(IO, CPU)),
        Map.entry("workflow", Arrays.asList(WORKFLOW, STEP, DEPENDS, ON, COMPENSATE, RETRY, TIMEOUT, MAX_ATTEMPTS, BACKOFF)),
        Map.entry("async", Arrays.asList(WITHIN, SCOPE, START, ASYNC, AWAIT, WAIT_FOR)),
        Map.entry("constraint", Arrays.asList(REQUIRED, BETWEEN, AT_LEAST, AT_MOST, MATCHING, PATTERN))
    );
}
