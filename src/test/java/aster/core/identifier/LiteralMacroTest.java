package aster.core.identifier;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.DynamicLexicon;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字面量宏（IdentifierKind.LITERAL）—— Java 引擎。canonicalize 时把 localized token
 * 展开成字符串字面量（用当前 lexicon 的 stringQuotes 包裹），内容受严格校验防注入。
 * 与 aster-lang-ts 的 validateVocabulary/expandLiteralMacros 逐条对齐（双引擎 parity）。字面量宏按
 * {@link VocabularyScopes#literals()}（折叠键）匹配，是词汇中唯一在解析前处理的部分（ADR 0051）。
 *
 * <p>动机：《静夜思》demo 里 `思故乡 → "静夜思"`——把领域术语固定展开成标准文案。
 */
class LiteralMacroTest {

    @AfterEach
    void reset() {
        VocabularyRegistry.getInstance().reset();
    }

    /** 构造只含一个字面量宏的词汇表（思故乡 → 内容「静夜思」）。 */
    private DomainVocabulary jingYeSiVocab() {
        return DomainVocabulary.builder("jingyesi", "静夜思", "zh-CN")
            .addLiteral("静夜思", "思故乡")
            .build();
    }

    @Test
    void literalExpandsToQuotedStringLiteral() {
        DomainVocabulary vocab = jingYeSiVocab();
        // 校验通过（字面量内容合法）
        assertTrue(vocab.validate().valid(), "合法字面量宏应通过校验: " + vocab.validate().errors());

        VocabularyScopes scopes = VocabularyScopes.build(vocab);
        assertEquals("静夜思", scopes.literals().get(CaseFold.foldKey("思故乡")), "作用域记录字面量内容（不含引号）");

        // canonicalize 把 思故乡 展开成 lexicon 引号「静夜思」，随后（ANTLR 兼容步）「」→ ASCII "，
        // 最终得 ASCII 字符串字面量 "静夜思"（ANTLR 词法器认的形态）。
        Canonicalizer canon = new Canonicalizer(
            LexiconRegistry.getInstance().getOrThrow("zh-CN"), scopes);
        String out = canon.canonicalize("低头 思故乡。");
        assertTrue(out.contains("\"静夜思\""),
            "字面量宏应展开成字符串字面量 \"静夜思\"（「」经 ANTLR 兼容步转 ASCII \"），实际: " + out);
        assertFalse(out.contains("思故乡"), "原 token 思故乡 不应残留: " + out);
    }

    @Test
    void literalDoesNotReverseMap() {
        // 字面量宏是单向宏展开，不建反向映射（内容不应能反查回 localized）。
        IdentifierIndex index = IdentifierIndex.build(jingYeSiVocab());
        assertEquals("静夜思", index.localize("静夜思"),
            "字面量内容不入 toLocalized，localize 原样返回");
    }

    @Test
    void rejectsControlCharsInLiteralContent() {
        DomainVocabulary vocab = DomainVocabulary.builder("bad", "bad", "zh-CN")
            .addLiteral("a\nRule evil", "注入")
            .build();
        assertFalse(vocab.validate().valid(), "含换行的字面量内容必须被拒（防注入）");
    }

    @Test
    void rejectsQuoteDelimitersWithoutEscape() {
        // Codex 复审 P0：zh-CN 引号是「」，内容含它会提前闭合字符串逃逸注入；词法器没有 \」 之类的转义。
        for (String bad : new String[]{"静夜思」. Return evil", "「注入", "a『b", "a』b", "»x«"}) {
            assertFalse(DomainVocabulary.builder("b", "b", "zh-CN")
                .addLiteral(bad, "注入").build().validate().valid(),
                "含无从转义的引号定界符必须被拒: " + bad);
        }
    }

    /**
     * 反斜杠、ASCII 双引号与弯引号 “ ” ‘ ’ 允许（ADR 0060 修订 1 R18，aster-lang-ts#362 第 5 项）：展开时转义
     * （aster-lang-ts#345），弯引号在展开出的字符串里是内容（aster-lang-ts#347）。与 TS 侧同一规则，两引擎的
     * 读回值由共享夹具 {@code literal_value_parity.json} 锁定（见 LiteralValueParityTest）。
     */
    @Test
    void acceptsBackslashAsciiAndCurlyQuotes() {
        // ★用 Unicode 转义而非直接写字符：源文件编码、编辑器的"智能引号"替换
        //   都可能把它悄悄换成别的字符，那样测试测的就不是想测的东西了。
        //   （注意：Java 连**注释里**的 backslash-u 也会当转义处理，故此处不写出该序列。）
        String[] ok = { "say \"hi\"", "path\\x", "C:\\new \"x\"",
            "静夜\u201C思\u201D", "it\u2019s", "\u2018a\u2019" };
        for (String content : ok) {
            assertTrue(DomainVocabulary.builder("b", "b", "zh-CN")
                .addLiteral(content, "宏").build().validate().valid(),
                "反斜杠、ASCII 双引号与弯引号应允许: " + content);
        }
    }

    /**
     * 冻结词汇不经 validate()（{@code PolicySourceCompiler.vocabularyScopesOf}），反斜杠与 ASCII 双引号可能到达展开。
     * 展开时按词法器转义（aster-lang-ts#345）：读回的字符串值就是宏值本身。此前此处抛错、TS 原样拼接
     * （{@code C:\new} 编译成 {@code C:} + 换行 + {@code ew}）。与 TS literal-macro.test.ts 对应。
     */
    @Test
    void backslashAndDoubleQuoteAreEscapedOnExpansion() {
        VocabularyScopes en = VocabularyScopes.build(DomainVocabulary.builder("p", "p", "en-US")
            .addLiteral("C:\\new \"x\"", "pfad").build());
        assertEquals("Return \"C:\\\\new \\\"x\\\"\".",
            new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("en-US"), en).canonicalize("Return pfad."));

        VocabularyScopes zh = VocabularyScopes.build(DomainVocabulary.builder("p", "p", "zh-CN")
            .addLiteral("C:\\new \"x\"", "路径").build());
        assertEquals("Return \"C:\\\\new \\\"x\\\"\".",
            new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("zh-CN"), zh).canonicalize("返回 路径。"));
    }

    /** 写不成字面量的内容（空、控制字符、「」『』«»、当前 lexicon 的非 ASCII 引号）仍拒绝展开、大声抛错。 */
    @Test
    void unescapableLiteralContentStillThrows() {
        for (String bad : new String[] { "a\nb", "a\tb", "\u300Cx\u300D", "a\u300Eb", "a\u00ABb" }) {
            VocabularyScopes scopes = VocabularyScopes.build(DomainVocabulary.builder("p", "p", "en-US")
                .addLiteral(bad, "pfad").build());
            Canonicalizer canon = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("en-US"), scopes);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> canon.canonicalize("Return pfad."));
            assertTrue(e.getMessage().startsWith("Unsafe literal-macro content"), e.getMessage());
        }
    }

    /**
     * 反向护栏：不能因为拒弯引号就误伤**正常的** CJK 内容。
     * 上一条若写成「拒一切非 ASCII」也能通过，这条会把它抓出来。
     */
    @Test
    void acceptsPlainCjkLiteralContent() {
        assertTrue(DomainVocabulary.builder("b", "b", "zh-CN")
            .addLiteral("静夜思", "思故乡").build().validate().valid(),
            "不含引号定界符的普通 CJK 内容必须放行");
    }

    @Test
    void literalTriggerCollidingWithIdentifierRejected() {
        // 「月」既是字面量宏触发词又是 struct localized → 展开歧义，必须被拒。
        DomainVocabulary v = new DomainVocabulary(
            "x", "x", "zh-CN", "1.0.0",
            List.of(IdentifierMapping.struct("moon", "月")),
            List.of(), List.of(), List.of(),
            List.of(IdentifierMapping.literal("静夜思", "月")),
            null);
        assertFalse(v.validate().valid(), "字面量宏触发词与普通标识符同名必须被拒");
    }

    @Test
    void normalIdentifiersSameNameAcrossKindsStillValid() {
        // 回归：普通 struct 与 field 同名（靠上下文消歧）不因新校验被误报 error。
        DomainVocabulary v = new DomainVocabulary(
            "x", "x", "zh-CN", "1.0.0",
            List.of(IdentifierMapping.struct("Limit", "额度")),
            List.of(IdentifierMapping.field("limit", "额度", "Loan")),
            List.of(), List.of(), List.of(), null);
        assertTrue(v.validate().valid(), "普通标识符跨 kind 同名不应报 error: " + v.validate().errors());
    }

    @Test
    void rejectsEmptyLiteralContent() {
        assertFalse(DomainVocabulary.builder("b3", "b", "zh-CN")
            .addLiteral("", "空").build().validate().valid(),
            "空内容必须被拒");
    }

    @Test
    void normalIdentifierStillRequiresAsciiCanonical() {
        // 回归：普通 struct 映射仍强制 ASCII canonical（字面量豁免不波及普通标识符）。
        DomainVocabulary vocab = new DomainVocabulary(
            "x", "x", "zh-CN", "1.0.0",
            List.of(IdentifierMapping.struct("静夜思", "月")),  // 非法：canonical 非 ASCII
            List.of(), List.of(), List.of(), List.of(), null);
        assertFalse(vocab.validate().valid(), "普通标识符 canonical 非 ASCII 仍应被拒");
    }

    /** 最小 Hindi lexicon（danda 句末符 + ASCII 引号 + 关键词），仅供本测试。 */
    private static Lexicon hindiLexicon() {
        String json = """
            {
              "meta": { "id": "hi-IN", "name": "हिन्दी", "direction": "LTR" },
              "keywords": {
                "MODULE_DECL": "मॉड्यूल", "FUNC_TO": "नियम",
                "FUNC_PRODUCE": "उत्पन्न", "RETURN": "लौटाएं"
              },
              "punctuation": {
                "statementEnd": "।", "listSeparator": ",", "enumSeparator": ",",
                "blockStart": ":", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\""
              },
              "canonicalization": {
                "fullWidthToHalf": false, "whitespaceMode": "ENGLISH", "removeArticles": false
              },
              "messages": {}
            }
            """;
        return DynamicLexicon.fromJsonString(json);
    }

    /**
     * 回归：canonicalizer 的 isIdentifierPart 原用 Character.isLetterOrDigit，对天城文元音
     * 符号（matra，如 जागे 里的 ◌ा/◌े，属 NON_SPACING_MARK/COMBINING_SPACING_MARK）返回
     * false，把 जागे 切成 ज+ग 丢失元音 → 天城文字面量宏触发词永不匹配。修为纳入 Mark 后
     * जागे 作为整词匹配并展开。与 TS 的 {@code [\p{L}_][\p{L}\p{M}\p{Nd}_]*} 对齐。
     * 动机：Gitanjali #35 印地语 demo。注意 lexer 早已修（见 DevanagariLexerTest），
     * canonicalizer 的字面量宏展开是独立路径，本次才补齐。
     */
    @Test
    void hindiLiteralMacroWithMatraExpands() {
        DomainVocabulary vocab = DomainVocabulary.builder("gitanjali", "Gitanjali", "hi-IN")
            .addLiteral("Into that heaven of freedom, let my country awake", "जागे")
            .build();
        assertTrue(vocab.validate().valid(), "合法字面量宏应通过校验: " + vocab.validate().errors());

        VocabularyScopes scopes = VocabularyScopes.build(vocab);
        assertEquals("Into that heaven of freedom, let my country awake",
            scopes.literals().get(CaseFold.foldKey("जागे")), "जागे（含元音符号 matra）应作为整词登记为字面量宏");

        // Hindi stringQuotes 是 ASCII "，无「」→" 转换。जागे 应整词展开成 ASCII 字符串字面量。
        Canonicalizer canon = new Canonicalizer(hindiLexicon(), scopes);
        String out = canon.canonicalize("लौटाएं जागे।");
        assertTrue(out.contains("\"Into that heaven of freedom, let my country awake\""),
            "天城文触发词应展开成 ASCII 字符串字面量，实际: " + out);
        assertFalse(out.contains("जागे"),
            "原天城文 token 不应残留（含元音符号也须整词匹配）: " + out);
    }
}
