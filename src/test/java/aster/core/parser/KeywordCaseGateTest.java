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
        "Return Not(x). | TYPE_IDENT",
        "Return Not (x). | TYPE_IDENT",
        "Return Not + (x). | TYPE_IDENT",
        "Return Not.x. | TYPE_IDENT",
        "Return Not under 5. | TYPE_IDENT",
        "Return Not and x. | TYPE_IDENT",
        "Return `Not` x. | TYPE_IDENT",
        "It performs Not [Http]: | TYPE_IDENT",
    })
    void prefixNotIsRetypedOnlyWhenTheNameReadingCannotParse(String source, String expected) {
        Token t = tokenWithText(source, "Not", "NOT", "nOT");
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

    private static Token tokenWithText(String source, String... texts) {
        CommonTokenStream tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(source)));
        tokens.fill();
        return tokens.getTokens().stream()
            .filter(t -> Arrays.asList(texts).contains(t.getText()))
            .findFirst().orElseThrow();
    }
}
