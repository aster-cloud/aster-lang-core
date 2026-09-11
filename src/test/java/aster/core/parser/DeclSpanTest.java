package aster.core.parser;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 声明级 span 不得吞掉尾随的布局（NEWLINE / INDENT / DEDENT）token。
 *
 * <h2>缺陷</h2>
 *
 * {@code spanFrom(ctx)} 原本直接用 {@code ctx.getStop()}。对一个声明而言，stop 往往是
 * 尾随的 NEWLINE / DEDENT，而这些 token 位于声明**之后**的行上（空行、注释行同样产生
 * NEWLINE）。于是 span 一路吞到下一个声明的起点。
 *
 * <p>实测 {@code hipaa-validation-demo.aster}：{@code Define AccessLevel} 真实占
 * canonical 第 9–14 行，却报成 <b>9–17</b>，而 17 正是下一个声明
 * {@code Define PHICategory} 的**起始行** —— 相邻声明的 span 互相**重叠**。
 *
 * <h2>为什么这不只是数字难看</h2>
 *
 * ADR 0032 要把执行 trace 锚定到源码位置，ADR 0037 要在其上建 OriginMap
 * （文本 span ↔ IR 节点双向导航）。span 重叠会让「按位置反查这是哪个声明」出现
 * **歧义**——同一行同时属于两个声明，而这类错误不会报错，只会静默给出错误答案。
 *
 * <p>TS 引擎一直是对的（报 14）；本测试钉住 Java 与之对齐。
 */
class DeclSpanTest {

    /** 两个声明之间隔着空行与注释——最容易触发「吞掉尾随布局」的形态。 */
    private static final String SOURCE = """
        Module decl.span.sample.

        # 注释行也会产生 NEWLINE
        Define Alpha has
          alpha_first,
          alpha_second.


        # 再来一段注释

        Define Beta has
          beta_only.
        """;

    @Test
    @DisplayName("声明的 end.line 必须落在其最后一行实义代码上，不得延伸到后续空行/注释")
    void declarationSpanMustNotSwallowTrailingLayout() {
        List<CoreModel.Data> datas = dataDecls(SOURCE);
        assertEquals(2, datas.size(), "样本应产出两个 Data 声明，否则本测试失去被测对象");

        CoreModel.Data alpha = datas.get(0);
        CoreModel.Data beta = datas.get(1);

        // Alpha: "Define Alpha has" 在第 4 行，"two." 在第 6 行。
        assertEquals(4, alpha.origin.start.line, "Alpha 起始行");
        assertEquals(6, alpha.origin.end.line,
            "Alpha 结束行应为最后一个字段所在行（6），实际 " + alpha.origin.end.line
                + "。若大于 6，说明 span 吞掉了其后的空行/注释行。");

        // Beta: "Define Beta has" 在第 11 行，"three." 在第 12 行。
        assertEquals(11, beta.origin.start.line, "Beta 起始行");
        assertEquals(12, beta.origin.end.line, "Beta 结束行");
    }

    @Test
    @DisplayName("相邻声明的 span 不得重叠（前一个的 end 必须严格早于后一个的 start）")
    void adjacentDeclarationSpansMustNotOverlap() {
        List<CoreModel.Data> datas = dataDecls(SOURCE);
        for (int i = 0; i + 1 < datas.size(); i++) {
            CoreModel.Data cur = datas.get(i);
            CoreModel.Data next = datas.get(i + 1);
            assertTrue(cur.origin.end.line < next.origin.start.line,
                "声明 " + cur.name + " 的 end.line(" + cur.origin.end.line + ") 必须严格小于 "
                    + next.name + " 的 start.line(" + next.origin.start.line + ")。"
                    + "\n★span 重叠会让「按位置反查是哪个声明」出现歧义——同一行同时属于两个声明，"
                    + "\n  而 ADR 0032 的 trace 锚点与 ADR 0037 的 OriginMap 都依赖这个反查。");
        }
    }

    private static List<CoreModel.Data> dataDecls(String source) {
        String canonical = new Canonicalizer().canonicalize(source);
        var charStream = CharStreams.fromString(canonical);
        var lexer = new AsterCustomLexer(charStream);
        var parser = new AsterParser(new CommonTokenStream(lexer));
        var ast = new AstBuilder().visitModule(parser.module());
        CoreModel.Module core = new CoreLowering().lowerModule(ast);
        List<CoreModel.Data> out = new ArrayList<>();
        for (Object d : core.decls) {
            if (d instanceof CoreModel.Data data) {
                out.add(data);
            }
        }
        return out;
    }
}
