package aster.core.canonicalizer;

import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 英语规范拼写的运算符**不得**在 canonicalize 时被翻译成符号——那会移动列位。
 *
 * <h2>缺陷</h2>
 *
 * Canonicalizer 曾把 {@code plus} 翻成 {@code +}，使行**变短 3 个字符}：
 * <pre>
 *   原文      "  Return x plus y."    y 在第 17 列
 *   canonical "  Return x + y."       → Java 报 y 在第 14 列
 * </pre>
 * 而 Core IR 的 {@code origin.col} 记的是 canonical 文本的列，于是 Java 报的列与
 * <b>用户原文</b>不符。TS 不做这个翻译，一直报 17（正确）。
 *
 * <h2>为什么这是冗余的</h2>
 *
 * grammar 本就认词形 token（{@code AsterParser.g4:643} 的 {@code PLUS_WORD}、
 * {@code :647} 的 {@code TIMES_WORD}/{@code DIVIDED_BY_WORD}/{@code MODULO_WORD} 等）。
 * 已实测 8 个运算符「词形 vs 符号」的 Core IR <b>逐字节相同</b>——翻译不影响能否解析，
 * 也不影响语义，唯一效果就是缩短行、移动列。
 *
 * <h2>为什么要钉住</h2>
 *
 * ADR 0032 要把执行 trace 锚到源码位置、ADR 0037 要建 OriginMap（文本 span ↔ IR 节点
 * 双向导航）。列偏会让「点击某个 token 高亮到精确字符」落在错误位置——不报错，只是指错。
 *
 * <p>★非英语不在本契约内：中文「加上」等没有对应的 lexer 词规则，必须翻译。
 */
class OperatorColumnPreservationTest {

    /** 每个词形运算符：canonicalize 后该行长度必须不变（即列位未被移动）。 */
    @Test
    @DisplayName("英语词形运算符经 canonicalize 后行长度不变（列位未被移动）")
    void wordOperatorsMustNotShortenTheLine() {
        Canonicalizer canon = new Canonicalizer();
        // ★含可选 `is` 前缀：曾由 IsComparatorTransformer 去掉 `is`（同样缩 3 字符、
        //   同样移动列位）。实测 lexer 本就吸收该前缀（7 个比较词带/不带 is 的 IR
        //   逐字节相同），故该 transformer 已从 en-US 链上移除；此处一并钉住。
        String[] bodies = {
            "x plus y", "x minus y", "x times y", "x divided by y",
            "x greater than y", "x less than y",
            "x at least y", "x at most y",
            "x is greater than y", "x is less than y",
            "x is at least y", "x is at most y",
            "x is more than y", "x is under y", "x is over y",
        };
        for (String body : bodies) {
            String line = "  Return " + body + ".";
            String src = "Module m.\n\nRule f given x, y, produce:\n" + line + "\n";
            String[] out = canon.canonicalize(src).split("\n", -1);
            assertEquals(line.length(), out[3].length(),
                "`" + body + "` 经 canonicalize 后行长度变了：\n  原 [" + line + "]\n  规 [" + out[3] + "]"
                    + "\n★行一旦变短，其后所有 token 的 origin.col 都会左移，"
                    + "\n  使 ADR 0032 的 trace 锚点与 ADR 0037 的 OriginMap 指向错误的字符。");
        }
    }

    /** 端到端：走完整链路后，最右操作数的列必须等于它在**用户原文**里的列。 */
    @Test
    @DisplayName("origin.col 必须等于 token 在用户原文中的真实列")
    void originColumnMustMatchUserSource() {
        String line = "  Return x plus y.";
        // 用户原文里 `y` 的 1-based 列号——直接从文本算，不依赖任何实现。
        int expectedCol = line.indexOf('y') + 1;

        String src = "Module m.\n\nRule f given x, y, produce:\n" + line + "\n";
        String canonical = new Canonicalizer().canonicalize(src);
        var parser = new AsterParser(new CommonTokenStream(
            new AsterCustomLexer(CharStreams.fromString(canonical))));
        CoreModel.Module core = new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));

        CoreModel.Func func = (CoreModel.Func) core.decls.get(0);
        CoreModel.Return ret = (CoreModel.Return) func.body.statements.get(0);
        CoreModel.Call call = (CoreModel.Call) ret.expr;
        CoreModel.Name rightOperand = (CoreModel.Name) call.args.get(1);

        assertEquals(expectedCol, rightOperand.origin.start.col,
            "右操作数 `y` 的 origin.start.col 应等于它在用户原文中的列（" + expectedCol + "）。"
                + "\n实际 " + rightOperand.origin.start.col
                + "。若偏小，说明 canonicalize 缩短了该行（例如把 plus 翻成 +）。");
    }

    @Test
    @DisplayName("`!=` 前的空格不得被标点归一化吃掉（它是运算符，不是句末标点）")
    void notEqualOperatorMustKeepItsLeadingSpace() {
        // ★PUNCT_FINAL_RE 的本意是「去掉句末标点前的空白」（`Wait ! now` → `Wait! now`）。
        //   但它的字符类里含 `!`，于是 `x != y` 也被当成「空白 + 标点」，改写成 `x!= y`
        //   —— 缩短一个字符、移动其后所有 token 的列位。
        //   实证：语料中**裸** `!`（不在字符串字面量内）只以 `!=` 形态出现；真正作
        //   感叹号用的 `!` 全在字符串里，已由 segmenter 保护。
        Canonicalizer canon = new Canonicalizer();
        String line = "  Return x != y.";
        String src = "Module m.\n\nRule f given x, y, produce:\n" + line + "\n";
        String out = canon.canonicalize(src).split("\n", -1)[3];

        assertEquals(line, out,
            "`!=` 前的空格被吃掉了：\n  原 [" + line + "]\n  规 [" + out + "]"
                + "\n★这会让 `y` 及其后所有 token 的 origin.col 左移一位。");
    }

    @Test
    @DisplayName("真正的句末标点仍被归一化（本修复未放宽该行为）")
    void realSentencePunctuationIsStillNormalized() {
        // 反向守卫：确认修复只排除了 `!=`，没有顺手关掉整条标点归一化规则。
        //
        // ★必须用 `;` `?` `!` —— 这三个字符**只**由 PUNCT_FINAL_RE 处理。
        //   `.` `,` `:` 在更早的 PUNCT_NORMAL_RE（本文件另一处）就已归一化，
        //   拿它们做断言，即使把 PUNCT_FINAL_RE 整条删掉本测试也照样绿
        //   （实测：从字符类里去掉 `.` 后本用例仍通过 = 结构上无法变红的假门禁）。
        Canonicalizer canon = new Canonicalizer();
        record Case(String input, String expected) {}
        for (Case c : new Case[]{
                new Case("  Return x ;", "  Return x;"),
                new Case("  Return x ?", "  Return x?"),
                new Case("  Return x !", "  Return x!"),
        }) {
            String src = "Module m.\n\nRule f given x, produce:\n" + c.input() + "\n";
            String out = canon.canonicalize(src).split("\n", -1)[3];
            assertEquals(c.expected(), out,
                "标点前的空格应被归一化掉：原 [" + c.input() + "] 实际 [" + out + "]。"
                    + "\n★若本用例变红，说明修复把整条标点归一化规则也削弱了。");
        }
    }
}
