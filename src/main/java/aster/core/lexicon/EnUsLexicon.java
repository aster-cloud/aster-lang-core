package aster.core.lexicon;

import java.util.EnumMap;
import java.util.Map;

/**
 * 英文词法表 (en-US)
 * <p>
 * 定义 Aster CNL 的英文关键词映射。
 */
public final class EnUsLexicon implements Lexicon {

    public static final String ID = "en-US";
    public static final EnUsLexicon INSTANCE = new EnUsLexicon();

    private final Map<SemanticTokenKind, String> keywords;
    private final PunctuationConfig punctuation;
    private final CanonicalizationConfig canonicalization;
    private final ErrorMessages messages;

    private EnUsLexicon() {
        this.keywords = buildKeywords();
        this.punctuation = PunctuationConfig.english();
        this.canonicalization = CanonicalizationConfig.english();
        this.messages = ErrorMessages.english();
    }

    private Map<SemanticTokenKind, String> buildKeywords() {
        Map<SemanticTokenKind, String> kw = new EnumMap<>(SemanticTokenKind.class);

        // 模块声明
        kw.put(SemanticTokenKind.MODULE_DECL, "Module");
        kw.put(SemanticTokenKind.IMPORT, "use");
        kw.put(SemanticTokenKind.IMPORT_ALIAS, "as");

        // 类型定义（注意：大小写必须与 ANTLR 词法器匹配）
        kw.put(SemanticTokenKind.TYPE_DEF, "Define");
        kw.put(SemanticTokenKind.TYPE_WITH, "with");
        kw.put(SemanticTokenKind.TYPE_HAS, "has");
        kw.put(SemanticTokenKind.TYPE_ONE_OF, "as one of");

        // 函数定义
        kw.put(SemanticTokenKind.FUNC_TO, "Rule");
        kw.put(SemanticTokenKind.FUNC_GIVEN, "given");
        kw.put(SemanticTokenKind.FUNC_PRODUCE, "produce");
        kw.put(SemanticTokenKind.FUNC_PERFORMS, "it performs");

        // 控制流（注意：大小写必须与 ANTLR 词法器匹配）
        kw.put(SemanticTokenKind.IF, "If");
        kw.put(SemanticTokenKind.OTHERWISE, "Otherwise");
        kw.put(SemanticTokenKind.MATCH, "Match");
        kw.put(SemanticTokenKind.WHEN, "When");
        kw.put(SemanticTokenKind.RETURN, "Return");
        kw.put(SemanticTokenKind.RESULT_IS, "the result is");
        kw.put(SemanticTokenKind.FOR_EACH, "for each");
        kw.put(SemanticTokenKind.IN, "in");

        // 变量操作（注意：大小写必须与 ANTLR 词法器匹配）
        kw.put(SemanticTokenKind.LET, "Let");
        kw.put(SemanticTokenKind.BE, "be");
        kw.put(SemanticTokenKind.SET, "set");
        kw.put(SemanticTokenKind.TO_WORD, "to");

        // 布尔运算
        kw.put(SemanticTokenKind.OR, "or");
        kw.put(SemanticTokenKind.AND, "and");
        kw.put(SemanticTokenKind.NOT, "not");

        // 算术运算
        kw.put(SemanticTokenKind.PLUS, "plus");
        kw.put(SemanticTokenKind.MINUS_WORD, "minus");
        kw.put(SemanticTokenKind.TIMES, "times");
        kw.put(SemanticTokenKind.DIVIDED_BY, "divided by");

        // 比较运算
        kw.put(SemanticTokenKind.LESS_THAN, "less than");
        kw.put(SemanticTokenKind.GREATER_THAN, "greater than");
        kw.put(SemanticTokenKind.EQUALS_TO, "equals to");
        kw.put(SemanticTokenKind.IS, "is");
        kw.put(SemanticTokenKind.UNDER, "under");
        kw.put(SemanticTokenKind.OVER, "over");
        kw.put(SemanticTokenKind.MORE_THAN, "more than");

        // 类型构造
        kw.put(SemanticTokenKind.MAYBE, "maybe");
        kw.put(SemanticTokenKind.OPTION_OF, "option of");
        kw.put(SemanticTokenKind.RESULT_OF, "result of");
        kw.put(SemanticTokenKind.OK_OF, "ok of");
        kw.put(SemanticTokenKind.ERR_OF, "err of");
        kw.put(SemanticTokenKind.SOME_OF, "some of");
        kw.put(SemanticTokenKind.NONE, "none");

        // 字面量
        kw.put(SemanticTokenKind.TRUE, "true");
        kw.put(SemanticTokenKind.FALSE, "false");
        kw.put(SemanticTokenKind.NULL, "null");

        // 基础类型（必须使用大写开头，以匹配 ANTLR 的 TYPE_IDENT）
        kw.put(SemanticTokenKind.TEXT, "Text");
        kw.put(SemanticTokenKind.INT_TYPE, "Int");
        kw.put(SemanticTokenKind.FLOAT_TYPE, "Float");
        kw.put(SemanticTokenKind.BOOL_TYPE, "Bool");

        // 效果声明
        kw.put(SemanticTokenKind.IO, "io");
        kw.put(SemanticTokenKind.CPU, "cpu");

        // 工作流
        kw.put(SemanticTokenKind.WORKFLOW, "workflow");
        kw.put(SemanticTokenKind.STEP, "step");
        kw.put(SemanticTokenKind.DEPENDS, "depends");
        kw.put(SemanticTokenKind.ON, "on");
        kw.put(SemanticTokenKind.COMPENSATE, "compensate");
        kw.put(SemanticTokenKind.RETRY, "retry");
        kw.put(SemanticTokenKind.TIMEOUT, "timeout");
        kw.put(SemanticTokenKind.MAX_ATTEMPTS, "max attempts");
        kw.put(SemanticTokenKind.BACKOFF, "backoff");

        // 异步操作
        kw.put(SemanticTokenKind.WITHIN, "within");
        kw.put(SemanticTokenKind.SCOPE, "scope");
        kw.put(SemanticTokenKind.START, "start");
        kw.put(SemanticTokenKind.ASYNC, "async");
        kw.put(SemanticTokenKind.AWAIT, "await");
        kw.put(SemanticTokenKind.WAIT_FOR, "wait for");

        // 约束声明
        kw.put(SemanticTokenKind.REQUIRED, "required");
        kw.put(SemanticTokenKind.BETWEEN, "between");
        kw.put(SemanticTokenKind.AT_LEAST, "at least");
        kw.put(SemanticTokenKind.AT_MOST, "at most");
        kw.put(SemanticTokenKind.MATCHING, "matching");
        kw.put(SemanticTokenKind.PATTERN, "pattern");

        return kw;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "English (US)";
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
