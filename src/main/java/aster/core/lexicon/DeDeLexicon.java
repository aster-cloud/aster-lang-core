package aster.core.lexicon;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 德语词法表 (de-DE)
 * <p>
 * 定义 Aster CNL 的德语关键词映射。
 * <p>
 * <b>设计策略</b>：
 * <ul>
 *   <li>ASCII 兼容优先：使用 ASCII 替代 umlaut（oe 代替 ö, ue 代替 ü, ae 代替 ä）</li>
 *   <li>保留英文标点：使用英文句号、逗号、冒号</li>
 *   <li>用户友好：无需德语键盘即可输入所有关键词</li>
 * </ul>
 * <p>
 * <b>标点符号</b>：
 * <ul>
 *   <li>使用英文标点：. , :</li>
 *   <li>字符串使用英文直引号："</li>
 * </ul>
 */
public final class DeDeLexicon implements Lexicon {

    public static final String ID = "de-DE";
    public static final DeDeLexicon INSTANCE = new DeDeLexicon();

    private final Map<SemanticTokenKind, String> keywords;
    private final PunctuationConfig punctuation;
    private final CanonicalizationConfig canonicalization;
    private final ErrorMessages messages;

    private DeDeLexicon() {
        this.keywords = buildKeywords();
        this.punctuation = PunctuationConfig.german();
        this.canonicalization = CanonicalizationConfig.german();
        this.messages = ErrorMessages.german();
    }

    private Map<SemanticTokenKind, String> buildKeywords() {
        Map<SemanticTokenKind, String> kw = new EnumMap<>(SemanticTokenKind.class);

        // 模块声明
        kw.put(SemanticTokenKind.MODULE_DECL, "Modul");
        kw.put(SemanticTokenKind.IMPORT, "verwende");
        kw.put(SemanticTokenKind.IMPORT_ALIAS, "als");

        // 类型定义
        kw.put(SemanticTokenKind.TYPE_DEF, "Definiere");
        kw.put(SemanticTokenKind.TYPE_WITH, "mit");
        kw.put(SemanticTokenKind.TYPE_HAS, "hat");
        kw.put(SemanticTokenKind.TYPE_ONE_OF, "als eines von");

        // 函数定义
        kw.put(SemanticTokenKind.FUNC_TO, "Regel");
        kw.put(SemanticTokenKind.FUNC_GIVEN, "gegeben");
        kw.put(SemanticTokenKind.FUNC_PRODUCE, "liefert");
        kw.put(SemanticTokenKind.FUNC_PERFORMS, "fuehrt aus");

        // 控制流
        kw.put(SemanticTokenKind.IF, "wenn");
        kw.put(SemanticTokenKind.OTHERWISE, "sonst");
        kw.put(SemanticTokenKind.MATCH, "pruefe");
        kw.put(SemanticTokenKind.WHEN, "bei");
        kw.put(SemanticTokenKind.RETURN, "gib zurueck");
        kw.put(SemanticTokenKind.RESULT_IS, "Ergebnis ist");
        kw.put(SemanticTokenKind.FOR_EACH, "fuer jedes");
        kw.put(SemanticTokenKind.IN, "in");

        // 变量操作
        kw.put(SemanticTokenKind.LET, "sei");
        kw.put(SemanticTokenKind.BE, "gleich");
        kw.put(SemanticTokenKind.SET, "setze");
        kw.put(SemanticTokenKind.TO_WORD, "auf");

        // 布尔运算
        kw.put(SemanticTokenKind.OR, "oder");
        kw.put(SemanticTokenKind.AND, "und");
        kw.put(SemanticTokenKind.NOT, "nicht");

        // 算术运算
        kw.put(SemanticTokenKind.PLUS, "plus");
        kw.put(SemanticTokenKind.MINUS_WORD, "minus");
        kw.put(SemanticTokenKind.TIMES, "mal");
        kw.put(SemanticTokenKind.DIVIDED_BY, "geteilt durch");

        // 比较运算
        kw.put(SemanticTokenKind.LESS_THAN, "kleiner als");
        kw.put(SemanticTokenKind.GREATER_THAN, "groesser als");
        kw.put(SemanticTokenKind.EQUALS_TO, "entspricht");
        kw.put(SemanticTokenKind.IS, "ist");
        kw.put(SemanticTokenKind.UNDER, "unter");
        kw.put(SemanticTokenKind.OVER, "ueber");
        kw.put(SemanticTokenKind.MORE_THAN, "mehr als");

        // 类型构造
        kw.put(SemanticTokenKind.MAYBE, "vielleicht");
        kw.put(SemanticTokenKind.OPTION_OF, "Option aus");
        kw.put(SemanticTokenKind.RESULT_OF, "Ergebnis aus");
        kw.put(SemanticTokenKind.OK_OF, "ok von");
        kw.put(SemanticTokenKind.ERR_OF, "Fehler von");
        kw.put(SemanticTokenKind.SOME_OF, "einige von");
        kw.put(SemanticTokenKind.NONE, "keines");

        // 字面量
        kw.put(SemanticTokenKind.TRUE, "wahr");
        kw.put(SemanticTokenKind.FALSE, "falsch");
        kw.put(SemanticTokenKind.NULL, "null");

        // 基础类型
        kw.put(SemanticTokenKind.TEXT, "Text");
        kw.put(SemanticTokenKind.INT_TYPE, "Ganzzahl");
        kw.put(SemanticTokenKind.FLOAT_TYPE, "Dezimal");
        kw.put(SemanticTokenKind.BOOL_TYPE, "Boolesch");

        // 效果声明
        kw.put(SemanticTokenKind.IO, "IO");
        kw.put(SemanticTokenKind.CPU, "CPU");

        // 工作流
        kw.put(SemanticTokenKind.WORKFLOW, "Arbeitsablauf");
        kw.put(SemanticTokenKind.STEP, "Schritt");
        kw.put(SemanticTokenKind.DEPENDS, "haengt ab");
        kw.put(SemanticTokenKind.ON, "von");
        kw.put(SemanticTokenKind.COMPENSATE, "kompensiere");
        kw.put(SemanticTokenKind.RETRY, "wiederhole");
        kw.put(SemanticTokenKind.TIMEOUT, "Zeitlimit");
        kw.put(SemanticTokenKind.MAX_ATTEMPTS, "maximale Versuche");
        kw.put(SemanticTokenKind.BACKOFF, "Wartezeit");

        // 异步操作
        kw.put(SemanticTokenKind.WITHIN, "innerhalb");
        kw.put(SemanticTokenKind.SCOPE, "Bereich");
        kw.put(SemanticTokenKind.START, "starte");
        kw.put(SemanticTokenKind.ASYNC, "asynchron");
        kw.put(SemanticTokenKind.AWAIT, "warte");
        kw.put(SemanticTokenKind.WAIT_FOR, "warte auf");

        // 约束声明
        kw.put(SemanticTokenKind.REQUIRED, "erforderlich");
        kw.put(SemanticTokenKind.BETWEEN, "zwischen");
        kw.put(SemanticTokenKind.AT_LEAST, "mindestens");
        kw.put(SemanticTokenKind.AT_MOST, "hoechstens");
        kw.put(SemanticTokenKind.MATCHING, "passend");
        kw.put(SemanticTokenKind.PATTERN, "Muster");

        return kw;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Deutsch";
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
