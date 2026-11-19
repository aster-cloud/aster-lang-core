package aster.core.embed;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR 0060 §5.4：别名校验器 v2 的跨引擎夹具 {@code cases}（core 侧）。aster-lang-ts
 * {@code alias-validation-v2-parity.test.ts} 对同一夹具断言同样的 valid 与码集合。
 */
class AliasValidationV2ParityTest {

    @Test
    void validatorVersionIsTwo() {
        assertEquals("2", UserAliasValidator.VERSION);
    }

    @Test
    void fixtureMatchesThePinnedSha256() throws Exception {
        assertEquals(AliasValidationV2Fixture.SHA256, AliasValidationV2Fixture.sha256(),
            "夹具与 aster-lang-ts shared/ 副本须逐字节相同；改夹具须两仓同改并更新两侧的 sha256 常量");
    }

    @Test
    void fixtureVersionIsTwo() throws Exception {
        assertEquals(2, AliasValidationV2Fixture.root().get("version").asInt());
    }

    @TestFactory
    List<DynamicTest> validatorCases() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : AliasValidationV2Fixture.root().get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("id").asText(), () -> {
                var result = UserAliasValidator.validate(AliasValidationV2Fixture.aliasSet(c),
                    c.get("locale").asText(), AliasValidationV2Fixture.scopes(c), c.get("allowStructural").asBoolean());
                Set<String> codes = new TreeSet<>();
                result.errors().forEach(e -> codes.add(AliasValidationV2Fixture.issueCode(e)));
                Set<String> expected = new TreeSet<>();
                c.get("codes").forEach(x -> expected.add(x.asText()));
                assertEquals(c.get("valid").asBoolean(), result.valid(), () -> "valid: " + result.errors());
                assertEquals(expected, codes, () -> "codes: " + result.errors());
            }));
        }
        return tests;
    }
}
