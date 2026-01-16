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
}
