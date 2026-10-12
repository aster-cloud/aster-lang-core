package aster.core.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 语句头关键词（Return / Let / If / Match / When / Otherwise / Workflow）开头的一行
 * 只能按对应语句解析，不得退回成「以该关键词为变量名」的表达式语句。
 *
 * <p>ADR 0019 G1 让结构关键词在标识符位置充当软关键字（{@code Let y be return.}），
 * 但 {@code exprStmt} 的表达式也能以它开头：{@code Return and(a).} 在 returnStmt
 * 失败后被解析成变量 {@code Return} 与 {@code (a)} 的逻辑与，并降为丢弃式语句——
 * 函数静默地不再返回任何值。TS 按语句头关键词分派、从不回退，两引擎因此分叉。
 */
class StatementHeadKeywordTest {

    private static List<String> syntaxErrors(String statement) {
        String source = "Module m.\n\nRule r given a as Bool, x as Int, produce Bool:\n  " + statement + "\n";
        List<String> errors = new ArrayList<>();
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col,
                                    String msg, RecognitionException e) {
                errors.add("L" + line + ":" + col + " " + msg);
            }
        });
        parser.module();
        return errors;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Return and(a).",
        "Return or(a).",
        "return and(a).",
        "Return plus(x).",
        "Return and a.",
        "Return plus x.",
        "Return.",
        "Let.",
        "Let plus x.",
        "If and(a).",
        "Match and(a).",
        "When and(a).",
        "Workflow and a.",
    })
    void statementHeadKeywordIsNeverAVariableAtStatementStart(String statement) {
        List<String> errors = syntaxErrors(statement);
        assertFalse(errors.isEmpty(), "应报语法错误：" + statement);
        assertEquals(4, Integer.parseInt(errors.get(0).substring(1, errors.get(0).indexOf(':'))),
            "首个错误应在语句所在的第 4 行：" + errors);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Return (a).",
        "Return return.",
        "Let y be return.",
        "Return a and (a).",
        "a and (a).",
        "Start and a.",
    })
    void keywordsStayUsableOutsideTheStatementHead(String statement) {
        assertEquals(List.of(), syntaxErrors(statement), "应解析通过：" + statement);
    }
}
