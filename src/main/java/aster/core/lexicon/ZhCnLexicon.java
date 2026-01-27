package aster.core.lexicon;

import java.util.EnumMap;
import java.util.Map;

/**
 * 简体中文词法表 (zh-CN)
 * <p>
 * 定义 Aster CNL 的中文关键词映射。
 * <p>
 * <b>设计策略</b>：混合策略
 * <ul>
 *   <li>法律文书风格：声明类关键词使用【】标记，如【模块】【定义】</li>
 *   <li>把字句结构：模式匹配使用"把 X 分为"结构</li>
 *   <li>直觉自然：控制流关键词使用日常中文，如"若"、"否则"、"返回"</li>
 * </ul>
 * <p>
 * <b>标点符号</b>：
 * <ul>
 *   <li>使用中文标点：。，、：</li>
 *   <li>字符串使用直角引号：「」</li>
 *   <li>标记使用方括号：【】</li>
 * </ul>
 */
public final class ZhCnLexicon implements Lexicon {

    public static final String ID = "zh-CN";
    public static final ZhCnLexicon INSTANCE = new ZhCnLexicon();

    private final Map<SemanticTokenKind, String> keywords;
    private final PunctuationConfig punctuation;
    private final CanonicalizationConfig canonicalization;
    private final ErrorMessages messages;

    private ZhCnLexicon() {
        this.keywords = buildKeywords();
        this.punctuation = PunctuationConfig.chinese();
        this.canonicalization = CanonicalizationConfig.chinese();
        this.messages = ErrorMessages.chinese();
    }

    private Map<SemanticTokenKind, String> buildKeywords() {
        Map<SemanticTokenKind, String> kw = new EnumMap<>(SemanticTokenKind.class);

        // 模块声明（使用【】标记增强辨识度）
        kw.put(SemanticTokenKind.MODULE_DECL, "【模块】");
        kw.put(SemanticTokenKind.IMPORT, "引用");
        kw.put(SemanticTokenKind.IMPORT_ALIAS, "作为");

        // 类型定义（使用【】标记）
        kw.put(SemanticTokenKind.TYPE_DEF, "【定义】");
        kw.put(SemanticTokenKind.TYPE_WITH, "包含");
        kw.put(SemanticTokenKind.TYPE_ONE_OF, "为以下之一");

        // 函数定义（与 TypeScript 前端保持一致）
        kw.put(SemanticTokenKind.FUNC_TO, "【函数】");
        kw.put(SemanticTokenKind.FUNC_PRODUCE, "产出");
        kw.put(SemanticTokenKind.FUNC_PERFORMS, "执行");

        // 控制流（与 TypeScript 前端保持一致）
        kw.put(SemanticTokenKind.IF, "如果");
        kw.put(SemanticTokenKind.OTHERWISE, "否则");
        kw.put(SemanticTokenKind.MATCH, "若");
        kw.put(SemanticTokenKind.WHEN, "为");
        kw.put(SemanticTokenKind.RETURN, "返回");
        kw.put(SemanticTokenKind.FOR_EACH, "对每个");
        kw.put(SemanticTokenKind.IN, "在");

        // 变量操作
        kw.put(SemanticTokenKind.LET, "令");
        kw.put(SemanticTokenKind.BE, "为");
        kw.put(SemanticTokenKind.SET, "将");
        kw.put(SemanticTokenKind.TO_WORD, "设为");

        // 布尔运算
        kw.put(SemanticTokenKind.OR, "或");
        kw.put(SemanticTokenKind.AND, "且");
        kw.put(SemanticTokenKind.NOT, "非");

        // 算术运算
        kw.put(SemanticTokenKind.PLUS, "加");
        kw.put(SemanticTokenKind.MINUS_WORD, "减");
        kw.put(SemanticTokenKind.TIMES, "乘");
        kw.put(SemanticTokenKind.DIVIDED_BY, "除以");

        // 比较运算
        kw.put(SemanticTokenKind.LESS_THAN, "小于");
        kw.put(SemanticTokenKind.GREATER_THAN, "大于");
        kw.put(SemanticTokenKind.EQUALS_TO, "等于");
        kw.put(SemanticTokenKind.IS, "是");

        // 类型构造
        kw.put(SemanticTokenKind.MAYBE, "可选");
        kw.put(SemanticTokenKind.OPTION_OF, "选项");
        kw.put(SemanticTokenKind.RESULT_OF, "结果");
        kw.put(SemanticTokenKind.OK_OF, "成功");
        kw.put(SemanticTokenKind.ERR_OF, "失败");
        kw.put(SemanticTokenKind.SOME_OF, "有值");
        kw.put(SemanticTokenKind.NONE, "无");

        // 字面量
        kw.put(SemanticTokenKind.TRUE, "真");
        kw.put(SemanticTokenKind.FALSE, "假");
        kw.put(SemanticTokenKind.NULL, "空");

        // 基础类型
        kw.put(SemanticTokenKind.TEXT, "文本");
        kw.put(SemanticTokenKind.INT_TYPE, "整数");
        kw.put(SemanticTokenKind.FLOAT_TYPE, "小数");
        kw.put(SemanticTokenKind.BOOL_TYPE, "布尔");

        // 效果声明
        kw.put(SemanticTokenKind.IO, "输入输出");
        kw.put(SemanticTokenKind.CPU, "计算");

        // 工作流（使用【】标记）
        kw.put(SemanticTokenKind.WORKFLOW, "【流程】");
        kw.put(SemanticTokenKind.STEP, "【步骤】");
        kw.put(SemanticTokenKind.DEPENDS, "依赖");
        kw.put(SemanticTokenKind.ON, "于");
        kw.put(SemanticTokenKind.COMPENSATE, "补偿");
        kw.put(SemanticTokenKind.RETRY, "重试");
        kw.put(SemanticTokenKind.TIMEOUT, "超时");
        kw.put(SemanticTokenKind.MAX_ATTEMPTS, "最多尝试");
        kw.put(SemanticTokenKind.BACKOFF, "退避");

        // 异步操作
        kw.put(SemanticTokenKind.WITHIN, "范围");
        kw.put(SemanticTokenKind.SCOPE, "域");
        kw.put(SemanticTokenKind.START, "启动");
        kw.put(SemanticTokenKind.ASYNC, "异步");
        kw.put(SemanticTokenKind.AWAIT, "等待");
        kw.put(SemanticTokenKind.WAIT_FOR, "等候");

        return kw;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "简体中文";
    }

    @Override
    public Direction getDirection() {
        return Direction.LTR;
    }

    @Override
    public Map<SemanticTokenKind, String> getKeywords() {
        return keywords;
    }

    @Override
    public PunctuationConfig getPunctuation() {
        return punctuation;
    }

    @Override
    public CanonicalizationConfig getCanonicalization() {
        return canonicalization;
    }

    @Override
    public ErrorMessages getMessages() {
        return messages;
    }
}
