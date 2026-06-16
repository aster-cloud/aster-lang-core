package aster.core.lexicon;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * hi-IN（Hindi / 天城文）端到端编译测试 —— ADR 0017 Phase 2 的 2a，Java 引擎侧。
 *
 * <p>验证：用内嵌注册的 hi-IN 词法表，把真实 Hindi CNL 源码经完整管线
 * （Canonicalizer 翻译 → ANTLR 词法/语法 → AstBuilder → CoreLowering）
 * 编译到 Core IR。这三段策略源码与 aster-lang-ts 的
 * {@code hi-IN.test.ts} 冒烟测试逐字一致，从而构成 TS↔Java 的
 * <b>parse-parity</b> 证据：两个引擎对同一份 Hindi 源码都能编译成
 * 结构合理的 Core IR 模块。
 *
 * <p>比较用 {@code से अधिक}(greater than) / {@code से कम}(less than)
 * 这类已实现关键词，不依赖 {@code है}(is) 的裸比较语义。
 */
class HindiCompileTest {

    private final LexiconRegistry registry = LexiconRegistry.getInstance();

    /** 完整编译一段 Hindi 源码到 Core IR；任何阶段失败都抛出，由 JUnit 记为 fail。 */
    private CoreModel.Module compileHindi(String source) {
        Lexicon hi = registry.getOrThrow("hi-IN");
        // 词法表感知的 Canonicalizer：把天城文关键词翻成 canonical English，
        // 并把 danda「।」归一成句末符，再交给标准 ANTLR 管线。
        String canonical = new Canonicalizer(hi).canonicalize(source);

        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        tokens.seek(0);

        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e) {
                throw new IllegalStateException(
                        "Hindi 源码语法错误 @" + line + ":" + charPositionInLine + " " + msg
                                + "\n--- canonical ---\n" + canonical);
            }
        });
        AsterParser.ModuleContext moduleCtx = parser.module();

        AstBuilder builder = new AstBuilder();
        aster.core.ast.Module ast = builder.visitModule(moduleCtx);

        return new CoreLowering().lowerModule(ast);
    }

    /** 模块里是否含一个指定名字的函数声明（Func）。 */
    private static boolean hasFunction(CoreModel.Module module, String funcName) {
        List<CoreModel.Decl> decls = module.decls;
        return decls != null && decls.stream()
                .filter(d -> d instanceof CoreModel.Func)
                .map(d -> ((CoreModel.Func) d).name)
                .anyMatch(funcName::equals);
    }

    @Test
    void pricingRuleCompilesToCoreIr() {
        // 定价规则：greater than 比较 + 算术（与 hi-IN.test.ts 同源）。
        String source = String.join("\n",
                "मॉड्यूल pricing।",
                "",
                "नियम discountedPrice दिया गया amount रूप में पूर्णांक, उत्पन्न पूर्णांक:",
                "  यदि amount से अधिक 100",
                "    लौटाएं amount गुणा 80 भाग 100।",
                "  लौटाएं amount।");

        CoreModel.Module module = compileHindi(source);
        assertNotNull(module, "应产出 Core IR 模块");
        assertEquals("pricing", module.name, "模块名应保留拉丁标识符 pricing");
        assertTrue(hasFunction(module, "discountedPrice"), "应含函数 discountedPrice");
    }

    @Test
    void loanRuleWithStructCompilesToCoreIr() {
        // 信贷规则：struct 定义 + 字段访问 + 布尔返回。
        String source = String.join("\n",
                "मॉड्यूल loan।",
                "",
                "परिभाषित Applicant रखता है creditScore रूप में पूर्णांक, income रूप में पूर्णांक।",
                "",
                "नियम approve दिया गया a रूप में Applicant, उत्पन्न बूलियन:",
                "  यदि a.creditScore से अधिक 700",
                "    लौटाएं सत्य।",
                "  लौटाएं असत्य।");

        CoreModel.Module module = compileHindi(source);
        assertNotNull(module, "应产出 Core IR 模块");
        assertEquals("loan", module.name);
        assertTrue(hasFunction(module, "approve"), "应含函数 approve");
    }

    @Test
    void arithmeticRuleWithLetCompilesToCoreIr() {
        // 算术规则：let 绑定 + 减法 + less than。
        String source = String.join("\n",
                "मॉड्यूल calc।",
                "",
                "नियम net दिया गया gross रूप में पूर्णांक, tax रूप में पूर्णांक, उत्पन्न पूर्णांक:",
                "  मानें result हो gross घटा tax।",
                "  यदि result से कम 0",
                "    लौटाएं 0।",
                "  लौटाएं result।");

        CoreModel.Module module = compileHindi(source);
        assertNotNull(module, "应产出 Core IR 模块");
        assertEquals("calc", module.name);
        assertTrue(hasFunction(module, "net"), "应含函数 net");
    }
}
