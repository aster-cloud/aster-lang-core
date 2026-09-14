package aster.core.parser;

import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨行 not 链仍须计入深度守卫（issue #157 的审计补强）。
 *
 * <p>★为什么单独成文件：原 {@code NotChainDepthTest} 的四条用例全部用**单行**
 * not 链，于是「缩进/换行是否打断计数」这条语义无人守卫——把整个豁免名单删掉，
 * 四条仍全绿。而豁免名单里 INDENT 那一项是 load-bearing 的：删掉它，
 * 跨行链就会在每个 INDENT 处被清零，301 个 not 照样放行。
 *
 * <p>实测（本文件在位时）：
 * <pre>
 *   删整个豁免名单      → 本用例红
 *   只删 INDENT 豁免    → 本用例红
 *   只删 NEWLINE 豁免   → 全绿（NEWLINE 到不了 trackNestingDepth，是死条款，已删）
 * </pre>
 */
class CrossLineNotTest {

    private void parse(String src) {
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(src));
        lexer.removeErrorListeners();
        AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.module();
    }

    private String crossLineNots(int n) {
        StringBuilder sb = new StringBuilder("Module m.\n\nDefine rule r:\n    Return ");
        for (int i = 0; i < n; i++) sb.append("not\n        ");
        sb.append("true.\n");
        return sb.toString();
    }

    @Test
    void 跨行的not链也必须计入深度守卫() {
        // 301 > MAX_NESTING_DEPTH：即使每个 not 之间隔着换行与缩进，
        // 也不能靠换行「重置」计数来绕过守卫。
        assertThrows(IllegalStateException.class,
            () -> parse(crossLineNots(AsterCustomLexer.MAX_NESTING_DEPTH + 1)),
            "跨行 not 链应被守卫拒绝（否则加换行即可绕过）");
    }

    @Test
    void 交替缩进的not链也必须计入深度守卫() {
        // ★钉住 **DEDENT** 豁免。上一条只产生 INDENT，于是删掉 DEDENT 豁免
        //   也不会有任何用例变红（复审实测：DEDENT 在 core 全量里命中 794 次，
        //   是**可达的活代码**，与 NEWLINE 那条已删的死条款性质不同）。
        //
        //   实测：删 DEDENT 豁免 → 本用例红、上一条仍绿，
        //   证明两条各自锁住一个方向，不是互相背书。
        StringBuilder sb = new StringBuilder("Module m.\n\nDefine rule r:\n    Return ");
        for (int i = 0; i < AsterCustomLexer.MAX_NESTING_DEPTH + 1; i++) {
            sb.append(i % 2 == 0 ? "not\n        " : "not\n    ");
        }
        sb.append("true.\n");

        assertThrows(IllegalStateException.class, () -> parse(sb.toString()),
            "交替缩进（反复 INDENT/DEDENT）不得成为绕过深度守卫的手段");
    }

    @Test
    void 跨行但未超限的not链必须放行() {
        // 反向守卫：别把上一条修成「见到 INDENT 就拒」。
        assertDoesNotThrow(() -> parse(crossLineNots(10)),
            "10 个跨行 not 远未超限，必须正常解析");
    }
}
