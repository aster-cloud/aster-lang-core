package aster.core.parser;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K-b 的左边缘门控（AsterParser.g4 的 kwGate* 规则）与词法器的前缀关键词判定（AsterCustomLexer.retypePrefixKeyword）。
 *
 * <p>门控只收窄 K-b 新增的读法：在 K 之前的规范拼写上恒为真，KeywordCaseCompat 的旧 ATN 才能把它们换成 ε 转移。
 * 这里在规范拼写的记号序列上逐个求值门控方法（解析器停在门控所在的位置），再在 K-b 的其余拼写与名字读法上验证取舍。
 *
 * <p>K-c 的运算符短语（AsterCustomLexer.mergeOperatorPhrase）与 not 后接左括号（产品裁决 D3，isNotCall）也在词法器里判定：
 * 改写后的记号序列与小写拼写逐个相同，名字读法照旧的位置不改。K-d 的约束词（retypeConstraintKeyword）同理。
 */
class KeywordCaseGateTest {

    /** 把解析器的输入定位到 {@code source} 里第 {@code at} 个记号（0 起，跳过隐藏通道），再求值门控。 */
    private static boolean gate(String source, int at, Predicate<AsterParser> check) {
        CommonTokenStream tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(source)));
        tokens.fill();
        AsterParser parser = new AsterParser(tokens);
        int seen = 0;
        for (Token t : tokens.getTokens()) {
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (seen++ == at) {
                tokens.seek(t.getTokenIndex());
                return check.test(parser);
            }
        }
        throw new IllegalArgumentException("记号不足: " + source);
    }

    @ParameterizedTest(name = "规范拼写 {0}")
    @CsvSource(delimiter = '|', value = {
        // 门控名 | 源码 | 门控所在记号序号 | 期望
        "and 续行 | Return a and b. | 2 | true",
        "and 续行（换行之后） | Return a\\nand b. | 2 | true",
        "or 续行 | Return a or b. | 2 | true",
        "比较分支（软比较） | Return a under b. | 2 | true",
        "比较分支（运算符） | Return a < b. | 2 | true",
        "Let | Let x be 1. | 0 | true",
        "Wait | Wait for a. | 0 | true",
        "Set | Set x to 1. | 0 | true",
        "lambda | Let f be function given v, produce: Return v. | 3 | true",
        "构造首个字段 | Return S with p set to 1. | 3 | true",
        "构造后续字段（and） | Return S with p set to 1 and q set to 2. | 7 | true",
        "构造后续字段（逗号） | Return S with p set to 1, q set to 2. | 7 | true",
    })
    void gatesHoldOnCanonicalSpelling(String gate, String body, int at, boolean expected) {
        String source = body.replace("\\n", "\n");
        assertThat(evaluate(gate, source, at)).as(gate).isEqualTo(expected);
    }

    @ParameterizedTest(name = "其余拼写与名字读法 {0}: {1}")
    @CsvSource(delimiter = '|', value = {
        "and 续行 | Return a AND b. | 2 | true",
        "and 续行 | Return a aND b. | 2 | true",
        "and 续行（构造的下一个字段） | Return S with p set to 1 and q sET to 2. | 7 | false",
        "比较分支（软比较） | Return a aND b. | 2 | false",
        "比较分支（软比较） | Return a oR b. | 2 | false",
        "Let | Let x BE 1. | 0 | true",
        "Let | Let x AS 1. | 0 | false",
        "Wait | Wait FOR a. | 0 | true",
        "Wait | Wait `for` a. | 0 | false",
        "Set | SET x TO 1. | 0 | true",
        "Set | SET under TO 1. | 0 | false",
        "lambda | Return FUNCTION given v, produce: Return v. | 1 | true",
        "lambda | Return Function with p set to 1. | 1 | true",
        "lambda | Return Function. | 1 | false",
        "构造首个字段 | Return S with p sET to 1. | 3 | true",
        "构造首个字段 | Return S with p = 1. | 3 | false",
        "构造后续字段（and） | Return S with p set to 1 and q Set to 2. | 7 | true",
        "构造后续字段（and） | Return S with p set to 1 and q SET to 2. | 7 | false",
        "构造后续字段（逗号） | Return S with p set to 1, q SET to 2. | 7 | true",
    })
    void gatesOnOtherSpellings(String gate, String body, int at, boolean expected) {
        assertThat(evaluate(gate, body, at)).as(gate + ": " + body).isEqualTo(expected);
    }

    private static boolean evaluate(String gate, String source, int at) {
        return gate(source, at, switch (gate.replaceAll("（.*）", "")) {
            case "and 续行" -> p -> p.isKwAfterNewlines("and") && !p.isConstructFieldAt();
            case "or 续行" -> p -> p.isKwAfterNewlines("or");
            case "比较分支" -> p -> !p.isLogicalKwAhead();
            case "Let" -> p -> p.isKwAt(3, "be");
            case "Wait" -> p -> p.isKwAt(2, "for");
            case "Set" -> AsterParser::isSetStmtAhead;
            case "lambda" -> AsterParser::isLambdaAhead;
            case "构造首个字段" -> p -> p.isKwAt(2, "set");
            case "构造后续字段" -> AsterParser::isConstructFieldAt;
            default -> throw new IllegalArgumentException(gate);
        });
    }

    /**
     * 能接在名字之后的关键词表必须覆盖每一条关键词辅助规则的关键词：否则 `Not <名字> <该关键词>` 一类写法里，词法器会把名字
     * Not 误改成 NOT（K-c / K-d 新增辅助规则时须同步这张表）。
     */
    @Test
    void wordsAfterNameCoverEveryKeywordRule() {
        Arrays.stream(AsterParser.ruleNames)
            .filter(n -> n.matches("kw[A-Z].*") && !n.startsWith("kwGate"))
            .map(n -> n.substring(2).replaceAll("Lower$", "").toLowerCase(Locale.ROOT))
            .forEach(word -> assertThat(AsterCustomLexer.WORDS_AFTER_NAME).as(word).contains(word));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "Return NOT x. | NOT",
        "Return Not Not x. | NOT",
        "Return nOT \"a\". | NOT",
        "Return Not(x). | NOT",
        "Return Not (x). | NOT",
        "Return NOT (x > 1). | NOT",
        "Return Not Not(x). | NOT",
        "When Not(x), deny \"no\". | NOT",
        "Start t as async Not(x). | NOT",
        "Return H.Not(x). | TYPE_IDENT",
        "Return `Not`(x). | TYPE_IDENT",
        "@Not(x) Rule f given x as Int, produce Int: | TYPE_IDENT",
        "Match s:\\n  When Not(a), Return a. | TYPE_IDENT",
        "Match b:\\n  When Box(Not(a)), Return a. | TYPE_IDENT",
        "Match x:\\n  When 1,\\n    When Not(x), deny \"no\". | NOT",
        "Match s:\\n  When Bb, Return 1.\\nReturn Not(x). | NOT",
        "Match t:\\n  When 1,\\n    Match s:\\n      When Not(a), Return a. | TYPE_IDENT",
        "Match s:\\n  When Bb(a), Return Not(a). | NOT",
        "Return Not + (x). | TYPE_IDENT",
        "Return Not.x. | TYPE_IDENT",
        "Return Not under 5. | TYPE_IDENT",
        "Return Not and x. | TYPE_IDENT",
        "Return `Not` x. | TYPE_IDENT",
        "It performs Not [Http]: | TYPE_IDENT",
    })
    void prefixNotIsRetypedOnlyWhenTheNameReadingCannotParse(String source, String expected) {
        Token t = tokenWithText(source.replace("\\n", "\n"), "Not", "NOT", "nOT");
        assertThat(AsterParser.VOCABULARY.getSymbolicName(t.getType())).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "Start t as Async f(). | ASYNC",
        "Start t as ASYNC Svc.load(1). | ASYNC",
        "Start t as Async(x). | TYPE_IDENT",
        "Return Async f(). | TYPE_IDENT",
    })
    void asyncIsRetypedOnlyAfterAs(String source, String expected) {
        Token t = tokenWithText(source, "Async", "ASYNC");
        assertThat(AsterParser.VOCABULARY.getSymbolicName(t.getType())).isEqualTo(expected);
    }

    /** K-c：运算符短语的其余大小写拼写改写成与小写拼写逐个相同的记号序列（软比较词是小写文本的 IDENT）。 */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "Return x Times y. | Return x times y.",
        "Return x TIMES (y) PLUS 1. | Return x times (y) plus 1.",
        "Return x mINUS -1. | Return x minus -1.",
        "Return f(x) Modulo 2.08m. | Return f(x) modulo 2.08m.",
        "Return s.tIMES Times 2. | Return s.tIMES times 2.",
        "Return s.type Plus apply f to x. | Return s.type plus apply f to x.",
        "Return x Is less than y. | Return x is less than y.",
        "Return x IS AT LEAST y. | Return x is at least y.",
        "Return x Is More Than y. | Return x is more than y.",
        "Return x Is Equal To y. | Return x is equal to y.",
        "Return x IS NOT EQUAL TO y. | Return x is not equal to y.",
        "Return x not Equal to y. | Return x not equal to y.",
        "Return x Under y. | Return x under y.",
        "Return x iS OVER y. | Return x is over y.",
        "Return a\\n    Times 2. | Return a\\n    times 2.",
    })
    void operatorPhrasesLexLikeLowercase(String source, String lowercase) {
        assertThat(tokenTypesAndTexts(source.replace("\\n", "\n")))
            .isEqualTo(tokenTypesAndTexts(lowercase.replace("\\n", "\n")));
    }

    /** K-c：名字读法照旧的位置，运算符同形词仍是名字记号。 */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "Return Times. | Times | TYPE_IDENT",
        "Let Plus be 1. | Plus | TYPE_IDENT",
        "Set Plus to 1. | Plus | TYPE_IDENT",
        "Return Is less than 5. | Is | TYPE_IDENT",
        "If Is less than 5: | Is | TYPE_IDENT",
        "Return x and Is less than 5. | Is | TYPE_IDENT",
        "Return x `Times` y. | Times | TYPE_IDENT",
        "Return x `Is` under y. | Is | TYPE_IDENT",
        "Return max Times 2. | Times | TYPE_IDENT",
        "Return x Times. | Times | TYPE_IDENT",
        "Return x Times\\n  y. | Times | TYPE_IDENT",
        "Let x be 1.\\nTimes(y). | Times | TYPE_IDENT",
        "Return x Is Less Than Or Equal To y. | Equal | TYPE_IDENT",
    })
    void operatorHomographsStayNames(String source, String text, String expected) {
        Token t = tokenWithText(source.replace("\\n", "\n"), text);
        assertThat(AsterParser.VOCABULARY.getSymbolicName(t.getType())).isEqualTo(expected);
    }

    /**
     * K-d：约束词 required / between / matching 的其余大小写拼写在约束位置（前一个记号结束名字 / 类型 / 上一个约束，下一个记号
     * 只能接在该约束之后）改成 REQUIRED / BETWEEN / MATCHING 记号；名字位置与条件不成立时照旧是名字。
     */
    @ParameterizedTest(name = "{0} → {2}")
    @CsvSource(delimiter = '|', value = {
        "Define S with a as Int Required. | Required | REQUIRED",
        "Define S with a as Int REQUIRED, b as Text. | REQUIRED | REQUIRED",
        "Define S with a as Int rEQUIRED and b as Text. | rEQUIRED | REQUIRED",
        "Define S with a as Int? Required. | Required | REQUIRED",
        "Define S with a as Int at least 1 Required. | Required | REQUIRED",
        "Define S with a as Text matching \"x\" Required. | Required | REQUIRED",
        "Define S with a as Int Required Between 1 and 2. | Between | BETWEEN",
        "Define S with a as Int BETWEEN -5 and 5. | BETWEEN | BETWEEN",
        "Define S with a as Float Between 0.5 and 1.5. | Between | BETWEEN",
        "Define S with a as Text MATCHING \"^x$\". | MATCHING | MATCHING",
        "Define S with a as Text Matching pattern \"^x$\". | Matching | MATCHING",
        "Rule f given p as Int Required, produce Int: | Required | REQUIRED",
        "Rule f given p as Int Required produce Int: | Required | REQUIRED",
        "Rule f given n Required, produce Int: | Required | REQUIRED",
        // 名字位置与条件不成立：照旧是名字
        "Define S with a as Int, rEQUIRED as Int. | rEQUIRED | IDENT",
        "Define S with a as Int, rEQUIRED. | rEQUIRED | IDENT",
        "Define a Required with x as Int. | Required | TYPE_IDENT",
        "Define REQUIRED with x as Int. | REQUIRED | TYPE_IDENT",
        "Type Required as Int. | Required | TYPE_IDENT",
        "Return x.Required. | Required | TYPE_IDENT",
        "Define S with a as Int REQUIRED 18. | REQUIRED | TYPE_IDENT",
        "Define S with a as Int BETWEEN and 5. | BETWEEN | TYPE_IDENT",
        "Define S with a as Text MATCHING. | MATCHING | TYPE_IDENT",
        "Define S with a as Int `REQUIRED`. | REQUIRED | TYPE_IDENT",
        "Define S with a as Int\\n  Required. | Required | TYPE_IDENT",
        "Rule f given x As Required, produce Int: | Required | TYPE_IDENT",
        "Let x Be Required. | Required | TYPE_IDENT",
        // 字段 / 形参列表之外（语句里）不改：出错时诊断照旧显示原文
        "Return x == \"a\" Required. | Required | TYPE_IDENT",
        "Return g(x) Required. | Required | TYPE_IDENT",
        // 注解参数里的冒号不结束列表；限定类型名里的点不结束列表；语句末尾的句点结束列表
        "Define S has @source(form: \"loan\") a as Int Between 1 and 100. | Between | BETWEEN",
        "Define S has a as io.Money Required. | Required | REQUIRED",
        "Define S with a as Int.\\nReturn x Required. | Required | TYPE_IDENT",
    })
    void constraintKeywordsAreRetypedOnlyInConstraintPosition(String source, String text, String expected) {
        Token t = tokenWithText(source.replace("\\n", "\n"), text);
        assertThat(AsterParser.VOCABULARY.getSymbolicName(t.getType())).isEqualTo(expected);
    }

    /** K-d：约束词改写后的记号序列与小写拼写逐个相同（只比较记号种类；名字记号连同文本）。 */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "Define S has a as Int REQUIRED BETWEEN 1 and 9, b as Text Required Matching pattern \"x\". | Define S has a as Int required between 1 and 9, b as Text required matching pattern \"x\".",
        "Rule f given a as Int Required and b as Int Between 1 and 2, produce Int: | Rule f given a as Int required and b as Int between 1 and 2, produce Int:",
    })
    void constraintKeywordsMatchLowercaseTokens(String mixed, String lower) {
        assertThat(tokenTypes(mixed)).isEqualTo(tokenTypes(lower));
    }

    private static java.util.List<String> tokenTypes(String source) {
        CommonTokenStream tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(source)));
        tokens.fill();
        return tokens.getTokens().stream()
            .filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL)
            .map(t -> {
                String name = AsterParser.VOCABULARY.getSymbolicName(t.getType());
                return t.getType() == AsterParser.IDENT || t.getType() == AsterParser.TYPE_IDENT ? name + ":" + t.getText() : name;
            })
            .toList();
    }

    /**
     * K-c 的短语判定只看有界窗口：一行里成千上万个候选词（Plus / Is less than）时，词法耗时与小写拼写同一量级，不随行长平方增长。
     */
    @Test
    void operatorPhrasesOnLongLinesStayLinear() {
        StringBuilder mixed = new StringBuilder("Return x");
        StringBuilder lower = new StringBuilder("Return x");
        for (int i = 0; i < 4000; i++) {
            mixed.append(i % 2 == 0 ? " Plus 1" : " TIMES y");
            lower.append(i % 2 == 0 ? " plus 1" : " times y");
        }
        mixed.append(" Is Less Than 5.");
        lower.append(" is less than 5.");
        assertThat(tokenTypesAndTexts(mixed.toString())).isEqualTo(tokenTypesAndTexts(lower.toString()));
        long t0 = System.nanoTime();
        tokenTypesAndTexts(mixed.toString());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(elapsedMs).as("4000 个候选词的一行（宽松上界，只防平方级退化）").isLessThan(10_000);
    }

    /** 短语之后是越出判定窗口的长字符串时，仍与小写拼写同记号。 */
    @Test
    void operatorPhraseBeforeLongStringOperand() {
        String text = "\"" + "a".repeat(400) + "\"";
        assertThat(tokenTypesAndTexts("Return x Is Equal To " + text + "."))
            .isEqualTo(tokenTypesAndTexts("Return x is equal to " + text + "."));
        assertThat(tokenTypesAndTexts("Return x PLUS " + text + "."))
            .isEqualTo(tokenTypesAndTexts("Return x plus " + text + "."));
    }

    private static java.util.List<String> tokenTypesAndTexts(String source) {
        CommonTokenStream tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(source)));
        tokens.fill();
        return tokens.getTokens().stream()
            .filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL)
            .map(t -> {
                String name = AsterParser.VOCABULARY.getSymbolicName(t.getType());
                // 多词运算符记号的文本保留原文（AstBuilder.normalizeOperator 按小写归一），只比较记号种类
                return t.getType() == AsterParser.IDENT || t.getType() == AsterParser.TYPE_IDENT ? name + ":" + t.getText() : name;
            })
            .toList();
    }

    private static Token tokenWithText(String source, String... texts) {
        CommonTokenStream tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(source)));
        tokens.fill();
        return tokens.getTokens().stream()
            .filter(t -> Arrays.asList(texts).contains(t.getText()))
            .findFirst().orElseThrow();
    }
}
