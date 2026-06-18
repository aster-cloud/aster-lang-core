package aster.core.parser;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0019 G1：结构关键词大小写不敏感（裸 parser 路径）。
 *
 * <p>背景：文档/playground 现代写法用小写结构关键词（{@code rule}/{@code return}/
 * {@code if}/{@code else}/{@code let}/{@code define}/{@code match}/{@code when}/
 * {@code start}/{@code wait}），但 Java lexer 此前只接受 PascalCase 形式
 * （{@code Rule}/{@code Return}/...），与 TS 引擎不对称——TS 的 {@code tokLowerAt}
 * 在比较前 {@code toLowerCase()}，本就大小写不敏感。dual-engine parse-parity
 * 因此被破（只是无 fixture 暴露）。
 *
 * <p>修复：把这些 token 的 lexer 规则改为逐字母字符集（{@code [Rr][Uu][Ll][Ee]}）
 * 接受任意大小写。这些 token 在 PascalCase 形式下本就是保留字（不在 nameIdent
 * 软关键词列表），加小写拼写不引入新的标识符碰撞——小写 {@code rule}/{@code return}/
 * {@code if} 当前都不是合法标识符。本测试在裸 parser 路径（与 dual-engine inventory
 * gate 一致，不经 canonicalize）上锁住该行为。
 */
class LowercaseKeywordParserTest {

    /** 裸 parser 路径（与 dual-engine inventory gate 一致，不经 canonicalize）。 */
    private static boolean parsesClean(String source) {
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
        parser.module();
        return errors.isEmpty();
    }

    @Test
    void lowercase_rule_return_parse() {
        // 小写 rule + return：现代写法核心。
        assertTrue(parsesClean("Module m.\nrule add given x, y:\n  return x plus y."),
                "小写 rule/return 应解析通过");
    }

    @Test
    void lowercase_if_else_parse() {
        // 小写 if/else 语句块。
        String src = "Module m.\n"
                + "rule classify given n, produce Text:\n"
                + "  if n is greater than 0:\n"
                + "    return \"positive\".\n"
                + "  else:\n"
                + "    return \"nonpositive\".";
        assertTrue(parsesClean(src), "小写 if/else 应解析通过");
    }

    @Test
    void lowercase_let_parse() {
        assertTrue(parsesClean("Module m.\nrule r given x, produce:\n  let y be x plus 1.\n  return y."),
                "小写 let 应解析通过");
    }

    @Test
    void lowercase_define_enum_parse() {
        assertTrue(parsesClean("Module m.\ndefine Status as one of Success, Failure."),
                "小写 define 枚举应解析通过");
    }

    @Test
    void lowercase_match_when_parse() {
        // matchCase 语法 = When pattern, body（逗号 + 内联 body）；pattern 为
        // TYPE_IDENT/INT_LITERAL/IDENT/NULL（非字符串字面量）。
        String src = "Module m.\n"
                + "rule r given s, produce Text:\n"
                + "  match s:\n"
                + "    when 1, return \"one\".\n"
                + "    when other, return \"many\".";
        assertTrue(parsesClean(src), "小写 match/when 应解析通过");
    }

    @Test
    void mixed_case_and_allcaps_also_parse() {
        // 逐字母字符集接受任意大小写（与之前 PascalCase-only 不同）。
        assertTrue(parsesClean("Module m.\nRULE add given x, y:\n  RETURN x plus y."),
                "全大写 RULE/RETURN 应解析通过");
        assertTrue(parsesClean("Module m.\nRuLe add given x, y:\n  ReTuRn x plus y."),
                "混合大小写应解析通过");
    }

    @Test
    void pascal_case_still_parses() {
        // 向后兼容：原 PascalCase 写法不变。
        assertTrue(parsesClean("Module m.\nRule add given x, y:\n  Return x plus y."),
                "原 PascalCase 写法必须继续解析");
    }

    @Test
    void lowercase_keywords_survive_full_canonicalize_path() {
        // 互补验证：生产路径先经 Canonicalizer 再喂 lexer。确认 canonicalize 不会
        // 破坏小写关键词（不做 PascalCase 归一），canonicalize 后仍解析通过。
        Canonicalizer canon = new Canonicalizer(LexiconRegistry.getInstance().getDefault());
        String out = canon.canonicalize("Module m.\nrule add given x, y:\n  return x plus y.");
        assertTrue(parsesClean(out), "canonicalize 后的小写关键词源码应解析通过，得到:\n" + out);
    }

    @Test
    void otherwise_branch_case_insensitive() {
        // ELSE token 含 Else|Otherwise 两拼写，都改为大小写不敏感。锁住 otherwise
        // 分支（Codex 审查指出原测试只覆盖 else）。
        String pascalOtherwise = "Module m.\n"
                + "Rule r given n, produce Text:\n"
                + "  If n is greater than 0:\n"
                + "    Return \"p\".\n"
                + "  Otherwise:\n"
                + "    Return \"n\".";
        String lowerOtherwise = "Module m.\n"
                + "rule r given n, produce Text:\n"
                + "  if n is greater than 0:\n"
                + "    return \"p\".\n"
                + "  otherwise:\n"
                + "    return \"n\".";
        assertTrue(parsesClean(pascalOtherwise), "PascalCase Otherwise 应解析通过");
        assertTrue(parsesClean(lowerOtherwise), "小写 otherwise 应解析通过");
    }

    // ============================================================
    // ADR 0019 G1 回归守卫（Codex 审查发现）：结构关键词的小写形式被 lexer 提升为
    // 硬 token 后，若不在标识符位置当软关键字，会破坏"小写词作普通标识符"的能力
    // （改动前小写 let/if/return 是 IDENT 可作名字，TS 引擎用上下文关键字模型同样
    // 接受）。下列测试锁住各标识符位置（参数名/变量引用/成员名/构造字段名/限定段）
    // 仍接受这些词作名字，防止回归。
    // ============================================================

    @Test
    void struct_keyword_as_param_name() {
        assertTrue(parsesClean("Module m.\nrule r given let, produce:\n  return let."),
                "参数名 let（软关键字）应解析通过");
        assertTrue(parsesClean("Module m.\nrule r given match, when, produce:\n  return match."),
                "多个软关键字参数名应解析通过");
    }

    @Test
    void struct_keyword_as_field_name() {
        assertTrue(parsesClean("Module m.\ndefine User has return as Text."),
                "字段名 return（软关键字）应解析通过");
    }

    @Test
    void struct_keyword_as_member_access() {
        assertTrue(parsesClean("Module m.\nrule r given x, produce:\n  return x.let."),
                "成员名 x.let（软关键字）应解析通过");
    }

    @Test
    void struct_keyword_as_let_var_name() {
        assertTrue(parsesClean("Module m.\nrule r given x, produce:\n  let match be x.\n  return match."),
                "let 变量名 match（软关键字）应解析通过");
    }

    @Test
    void struct_keyword_as_construct_field() {
        assertTrue(parsesClean("Module m.\nrule r given x, produce User:\n  return User with let set to x."),
                "construct 字段名 let（软关键字）应解析通过");
    }

    @Test
    void struct_keyword_in_qualified_module_name() {
        // 模块路径段含结构词（最初暴露问题的场景：dual.engine.let.binding）。
        assertTrue(parsesClean("Module dual.engine.let.binding.\nrule r given x, produce:\n  return x."),
                "限定模块名含 let 段应解析通过");
    }
}
