package aster.core.parser;

import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UTF-8 BOM（U+FEFF）剥离（issue core#158）。
 *
 * <h2>★实测修正了 issue 的描述</h2>
 *
 * <p>issue 说「首 token 被污染成 {@code ﻿Module}」或「静默生成错误的模块名」。
 * 插桩实测（未打修复时）**并非如此**：ANTLR 词法器对 U+FEFF 没有匹配规则，
 * 走的是**错误恢复**路径——
 *
 * <pre>
 *   errors=1
 *   err: line 1:0 token recognition error at: '﻿'
 *   first token col=1 text=[Module]
 * </pre>
 *
 * <p>也就是说 token **文本**是干净的，真实缺陷是另外两个：
 * <ol>
 *   <li>每个带 BOM 的源文件都会产生一条**伪词法错误**。若上层把词法错误
 *       当失败处理，整个文件就编译不了；即便只是记日志，也是噪声。</li>
 *   <li>首 token 列号从 0 变成 1——诊断位置、span、IDE 高亮全部偏移一列。
 *       本仓 ProofIR 的 span 是签字锚定的一部分，列号偏移不是小事。</li>
 * </ol>
 *
 * <p>所以断言口径是「无词法错误 + 列号不偏移」，而不是 issue 写的「文本不含 BOM」
 * ——后者在修复前**本来就成立**，拿它当判据会是一条恒真的假绿。
 *
 * <p>★这同时是一处**双引擎分叉**：TS 的 {@code src/frontend/lexer.ts} 一直在剥，
 * Java 侧从未剥过。同一份带 BOM 的源文件在两个引擎上解析结果不同。
 */
class BomStrippingTest {

    private static final String BOM = "\uFEFF";

    /** 词法分析为默认通道 token 列表。 */
    private List<Token> lex(String input) {
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(input));
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();
        List<Token> out = new ArrayList<>();
        for (Token t : stream.getTokens()) {
            if (t.getChannel() == Token.DEFAULT_CHANNEL && t.getType() != Token.EOF) {
                out.add(t);
            }
        }
        return out;
    }

    /** token 的「类型 + 文本」序列——不含位置，用于比对形状。 */
    private List<String> shapeOf(List<Token> tokens) {
        return tokens.stream()
            .map(t -> t.getType() + ":" + t.getText())
            .collect(Collectors.toList());
    }

    private static final String SRC = """
        Module demo.

        Define rule check:
            Return true.
        """;

    @Test
    void 带BOM与不带BOM必须产出逐个相同的token() {
        List<String> withBom = shapeOf(lex(BOM + SRC));
        List<String> without = shapeOf(lex(SRC));

        // 前置：夹具本身确实产出了足够多的 token，否则下面的相等断言在空表上恒真。
        assertTrue(without.size() > 5,
            "前置失败：源码只产出 " + without.size() + " 个 token，断言失去意义");

        assertEquals(without, withBom,
            "带 BOM 的源码必须与不带 BOM 的产出逐个 token 相同");
    }

    @Test
    void 带BOM的源码不得产生词法错误() {
        // ★真实缺陷之一：未剥离时 ANTLR 走错误恢复，报
        //   `line 1:0 token recognition error at: '﻿'`。
        //   上层若把词法错误当失败，带 BOM 的文件直接编译不了。
        List<String> errors = new ArrayList<>();
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(BOM + SRC));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object off, int line, int col,
                                    String msg, RecognitionException e) {
                errors.add("line " + line + ":" + col + " " + msg);
            }
        });
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();

        assertTrue(errors.isEmpty(), "带 BOM 的源码不得产生词法错误，实际：" + errors);
    }

    @Test
    void BOM不得让首token的列号偏移() {
        // ★真实缺陷之二：未剥离时首 token 列号从 0 变成 1。
        //   诊断位置、span、IDE 高亮全部偏移一列——而本仓 ProofIR 的 span
        //   是签字锚定的一部分，列号偏移不是小事。
        List<Token> withBom = lex(BOM + SRC);
        List<Token> without = lex(SRC);

        assertFalse(without.isEmpty(), "前置失败：没有产出任何 token");
        assertEquals(without.get(0).getCharPositionInLine(),
                     withBom.get(0).getCharPositionInLine(),
                     "带 BOM 时首 token 列号发生了偏移");
        // 首 token 文本本来就干净（错误恢复丢弃了 BOM），这条只作记录、不作判据。
        assertEquals("Module", withBom.get(0).getText());
    }

    @Test
    void 只有首字符的BOM被剥离_正文中的FEFF原样保留() {
        // 反向守卫：别把剥离写成「全文替换 U+FEFF」。
        // U+FEFF 出现在字符串字面量里时是**用户数据**，剥掉就是篡改内容。
        String src = "Module demo.\n\nDefine rule r:\n    Return \"a" + BOM + "b\".\n";
        List<Token> tokens = lex(src);

        boolean kept = tokens.stream().anyMatch(t -> t.getText().contains(BOM));
        assertTrue(kept, "字符串字面量内的 U+FEFF 属用户数据，不得被剥离");
    }

    @Test
    void 无BOM的源码不受影响() {
        // 反向守卫：剥离逻辑不得误吃正常源码的首字符。
        List<Token> tokens = lex(SRC);

        assertFalse(tokens.isEmpty());
        assertEquals("Module", tokens.get(0).getText(),
            "不带 BOM 时首 token 必须原样是 Module");
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c > 0x7E) sb.append(String.format("\\u%04X", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
