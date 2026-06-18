package aster.core.parser;

import aster.core.ast.Block;
import aster.core.ast.Decl;
import aster.core.ast.Module;
import aster.core.ast.Stmt;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.LexiconRegistry;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0019 G2a：语句级内联 if（{@code if cond then return X else return Y}）。
 *
 * <p>降级目标：与块式 {@code ifStmt} 相同的 {@link Stmt.If}——单语句分支包成单元素
 * {@link Block}，{@code else if} 链右递归成嵌套 If 的 else 分支，**不引入新 Core 节点**。
 *
 * <p>文档（aster-lang.dev）大量使用这种写法（含 {@code then} 换行缩进、else-if 链），
 * 但部署后端 ANTLR 此前只有块式 if。本测试锁住四种形态解析 + 降级结构正确。
 */
class InlineIfStmtTest {

    private static String firstError = null;

    /** 裸 parser 路径（与 dual-engine inventory gate 一致，不经 canonicalize）。 */
    private static Module parse(String source) {
        firstError = null;
        List<String> errors = new ArrayList<>();
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        tokens.seek(0);
        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col,
                                    String msg, RecognitionException e) {
                errors.add("L" + line + ":" + col + " " + msg);
            }
        });
        AsterParser.ModuleContext tree = parser.module();
        if (!errors.isEmpty()) {
            firstError = errors.get(0);
            return null;
        }
        return new AstBuilder().visitModule(tree);
    }

    private static boolean parsesClean(String source) {
        return parse(source) != null;
    }

    /** 取规则体的唯一语句（断言它是 Stmt.If）。 */
    private static Stmt.If firstIf(Module m) {
        Decl.Func func = (Decl.Func) m.decls().get(0);
        Block body = func.body();
        assertNotNull(body, "规则体不应为空");
        assertEquals(1, body.statements().size(), "内联 if 规则体应只有一个 If 语句");
        return assertInstanceOf(Stmt.If.class, body.statements().get(0), "应降级为 Stmt.If");
    }

    @Test
    void simple_if_then_else_parses() {
        assertTrue(parsesClean("Module m.\nRule r given amount:\n"
                + "  if amount is greater than 10000 then return \"large\"\n"
                + "  else return \"small\"."), () -> "解析失败: " + firstError);
    }

    @Test
    void if_then_without_else_parses() {
        assertTrue(parsesClean("Module m.\nRule r given amount:\n"
                + "  if amount is greater than 10000 then return \"large\"."),
                () -> "解析失败: " + firstError);
    }

    @Test
    void else_if_chain_parses() {
        assertTrue(parsesClean("Module m.\nRule r given amount:\n"
                + "  if amount is greater than 10000 then return \"large\"\n"
                + "  else if amount is greater than 1000 then return \"medium\"\n"
                + "  else return \"small\"."), () -> "解析失败: " + firstError);
    }

    @Test
    void same_line_if_then_else_parses() {
        // 同一行完整 inline-if（then/else 都不换行）。
        assertTrue(parsesClean("Module m.\nRule r given n:\n"
                + "  if n is greater than 0 then return \"p\" else return \"z\"."),
                () -> "解析失败: " + firstError);
    }

    @Test
    void then_as_member_name_still_parses() {
        // THEN 加入 structKeywordName 后，.then(...) 方法名/成员名仍可用（Codex 审查
        // 建议的软关键字回归守卫，与 AstBuilderTest.testChainedMethodCall 呼应）。
        assertTrue(parsesClean("Module m.\nRule r produce Text:\n"
                + "  Return Http.get(\"https://example.com\").then(handle)."),
                () -> "解析失败: " + firstError);
        // then 作 let 变量名。
        assertTrue(parsesClean("Module m.\nRule r given x, produce:\n"
                + "  let then be x.\n  return then."),
                () -> "解析失败: " + firstError);
    }

    @Test
    void then_on_next_line_indented_parses() {
        // 文档 overview/deployment/reference 的写法：then 换行且缩进。
        assertTrue(parsesClean("Module m.\nRule r given age:\n"
                + "  if age is less than 25\n"
                + "    then return 500\n"
                + "  else return 300."), () -> "解析失败: " + firstError);
    }

    @Test
    void indented_then_with_else_if_chain_parses() {
        // 最复杂：then 换行缩进 + else-if 链（doc_overview 形态）。
        assertTrue(parsesClean("Module m.\nRule r given amount, tier:\n"
                + "  if tier is equal to \"gold\"\n"
                + "    then return amount times 80 divided by 100\n"
                + "  else if amount is greater than 100\n"
                + "    then return amount times 90 divided by 100\n"
                + "  else return amount."), () -> "解析失败: " + firstError);
    }

    @Test
    void lowers_to_if_with_single_stmt_blocks() {
        Module m = parse("Module m.\nRule r given amount:\n"
                + "  if amount is greater than 10000 then return \"large\"\n"
                + "  else return \"small\".");
        assertNotNull(m, () -> "解析失败: " + firstError);
        Stmt.If ifStmt = firstIf(m);
        // then/else 分支各应是单语句 Block，内含一个 Return。
        assertEquals(1, ifStmt.thenBlock().statements().size());
        assertInstanceOf(Stmt.Return.class, ifStmt.thenBlock().statements().get(0));
        assertNotNull(ifStmt.elseBlock(), "应有 else 分支");
        assertEquals(1, ifStmt.elseBlock().statements().size());
        assertInstanceOf(Stmt.Return.class, ifStmt.elseBlock().statements().get(0));
    }

    @Test
    void no_else_lowers_to_null_else_block() {
        Module m = parse("Module m.\nRule r given amount:\n"
                + "  if amount is greater than 10000 then return \"large\".");
        assertNotNull(m, () -> "解析失败: " + firstError);
        Stmt.If ifStmt = firstIf(m);
        assertNull(ifStmt.elseBlock(), "无 else 时 elseBlock 应为 null");
    }

    @Test
    void inline_if_survives_full_canonicalize_path() {
        // 生产路径先经 Canonicalizer 再喂 lexer。确认 canonicalize 不破坏内联 if
        // 的 then 连接词（then 非 lexicon 关键词，但 ANTLR lexer 有 THEN token），
        // canonicalize 后仍解析为单个 Stmt.If。
        Canonicalizer canon = new Canonicalizer(LexiconRegistry.getInstance().getDefault());
        String out = canon.canonicalize("Module m.\nRule r given amount:\n"
                + "  if amount is greater than 10000 then return \"large\"\n"
                + "  else return \"small\".");
        Module m = parse(out);
        assertNotNull(m, () -> "canonicalize 后解析失败: " + firstError + "\ncanon 输出:\n" + out);
        firstIf(m); // 断言降级为单个 Stmt.If
    }

    @Test
    void else_if_chain_lowers_to_nested_if() {
        Module m = parse("Module m.\nRule r given amount:\n"
                + "  if amount is greater than 10000 then return \"large\"\n"
                + "  else if amount is greater than 1000 then return \"medium\"\n"
                + "  else return \"small\".");
        assertNotNull(m, () -> "解析失败: " + firstError);
        Stmt.If outer = firstIf(m);
        // 外层 else 分支应是单语句 Block，内含嵌套的 Stmt.If。
        assertNotNull(outer.elseBlock());
        assertEquals(1, outer.elseBlock().statements().size());
        Stmt.If nested = assertInstanceOf(Stmt.If.class, outer.elseBlock().statements().get(0),
                "else if 应降级为嵌套 Stmt.If");
        // 嵌套 If 的 else 分支应是 "small" 的 return。
        assertNotNull(nested.elseBlock());
        assertInstanceOf(Stmt.Return.class, nested.elseBlock().statements().get(0));
    }
}
