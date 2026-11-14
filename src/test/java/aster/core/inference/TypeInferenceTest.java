package aster.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类型推断引擎测试
 */
@DisplayName("类型推断引擎")
class TypeInferenceTest {

    @ParameterizedTest(name = "字段名 {0} 应推断为 {1}")
    @CsvSource({
        // ID 类型 → Text
        "applicantId, Text",
        "userId, Text",
        "customerId, Text",
        "orderCode, Text",
        "accessToken, Text",

        // 金额类型 → Float
        "loanAmount, Float",
        "totalAmount, Float",
        "price, Float",
        "balance, Float",
        "interestRate, Float",

        // 计数类型 → Int
        "age, Int",
        "creditScore, Int",
        "termMonths, Int",
        "itemCount, Int",
        "daysRemaining, Int",

        // 布尔类型 → Bool
        "isApproved, Bool",
        "hasPermission, Bool",
        "canEdit, Bool",
        "activeFlag, Bool",
        "isValid, Bool",

        // 日期时间类型 → DateTime
        "createdAt, DateTime",
        "updatedDate, DateTime",
        "expiryTime, DateTime",
        "birthday, DateTime",

        // 状态/分类类型 → Text
        "status, Text",
        "category, Text",
        "accountType, Text",

        // 默认类型 → Text
        "data, Text",
        "xyz, Text",
        "unknown, Text"
    })
    void shouldInferTypeFromFieldName(String fieldName, String expectedType) {
        String inferred = TypeInference.inferTypeNameFromFieldName(fieldName);
        assertThat(inferred).isEqualTo(expectedType);
    }

    @Test
    @DisplayName("空字段名应返回默认类型 Text")
    void shouldReturnDefaultTypeForEmptyFieldName() {
        assertThat(TypeInference.inferTypeNameFromFieldName(null)).isEqualTo("Text");
        assertThat(TypeInference.inferTypeNameFromFieldName("")).isEqualTo("Text");
        assertThat(TypeInference.inferTypeNameFromFieldName("  ")).isEqualTo("Text");
    }

    @Test
    @DisplayName("inferFieldType 应返回正确的 AST 类型节点")
    void shouldReturnCorrectAstTypeNode() {
        var type = TypeInference.inferFieldType("age");
        assertThat(type).isNotNull();
        assertThat(type).isInstanceOf(aster.core.ast.Type.TypeName.class);
        assertThat(((aster.core.ast.Type.TypeName) type).name()).isEqualTo("Int");
    }

    /**
     * 双引擎 parity 回归（issue #80 / #82 / #83）。
     * <p>
     * 这些字段名此前在 Java 与 TS 引擎推断出不同类型，或两侧同时误判。期望值即
     * TS `BASE_NAMING_RULES` + en-US overlay 的推断结果，两侧必须逐字一致；
     * 改动任一侧的规则都应先跑本用例。
     */
    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource({
        // #80：-age 结尾的普通单词曾被 Age$ 规则误判为 Int
        "usage, Text",
        "language, Text",
        "package, Text",
        "storage, Text",
        "voltage, Text",
        "mileage, Text",
        "coverage, Text",
        // #80：Message/Dosage 曾缺失 Text 规则（dosage 还同时踩 -age 陷阱）
        "errorMessage, Text",
        "message, Text",
        "dosage, Text",
        // Age 作为完整词或 camelCase 词段仍应是 Int（不能被上面的修复误伤）
        "age, Int",
        "userAge, Int",
        "personAge, Int",
        // #82：布尔前缀缺驼峰边界，把这些误判为 Bool
        "canceledAt, DateTime",
        "validatedAt, DateTime",
        "wasteAmount, Float",
        "isbnCode, Text",
        // 真正的布尔前缀必须仍然有效
        "isValid, Bool",
        "hasErrors, Bool",
        "canSubmit, Bool",
        "is, Bool",
        // #83：Success/Passed/Verified 后缀曾缺失
        "loginVerified, Bool",
        "taskPassed, Bool",
        "paymentSuccess, Bool",
        // *Valid 不得被 Id$ 规则吞掉（TS 侧曾因 /i 把 "Val-id" 判为 Text）
        "approvedValid, Bool",
        "expiredValid, Bool",
        // camelCase 的真 Id 仍是 Text
        "userId, Text",
        "orderIdentifier, Text",
        // snake_case 也必须走词边界（corpus hipaa-validation-demo 用 requires_consent，
        // 该字段被赋 true/false，若推成 Text 会让整份合规样本编译失败）
        "requires_consent, Bool",
        "is_active, Bool",
        "has_consent, Bool",
        "validated_at, DateTime",
        // 时间单位支持中间位置匹配
        "daysRemaining, Int",
        "termMonths, Int",
        "days, Int"
    })
    void shouldMatchTsEngineInference(String fieldName, String expectedType) {
        assertThat(TypeInference.inferTypeNameFromFieldName(fieldName)).isEqualTo(expectedType);
    }

    /**
     * Range 端点按规范十进制值判断是否带小数（ADR 0056 §3.2 第 5 步），不看字面量原文：
     * 原文 1.0 / 2.00 / 1E3 的规范串分别是 1 / 2 / 1000，不算小数；1.50、-0.5 算。ts 侧同一组用例见 type-inference.test.ts。
     */
    @ParameterizedTest(name = "Range({0}, {1}) → {2}")
    @CsvSource({
        "1.0, 2.00, Int",
        "1E3, , Int",
        ", 1.50, Float",
        "-0.5, , Float",
        "0, 1e-1, Float",
    })
    void rangeBoundsUseCanonicalDecimal(String min, String max, String expected) {
        var range = new aster.core.ast.Constraint.Range(min, max, null);
        assertThat(TypeInference.inferTypeName("nickname", java.util.List.of(range))).isEqualTo(expected);
    }
}
