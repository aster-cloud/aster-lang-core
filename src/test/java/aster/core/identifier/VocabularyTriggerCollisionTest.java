package aster.core.identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词汇表校验：按角色与作用域判定冲突（ADR 0051；与 TS validateVocabulary 同一组用例）。
 *
 * <p>名称按位置解析后，同一写法只在同角色同作用域内对应多个规范名才是冲突；不同父类型下的同名字段、
 * 同义词与仅大小写不同的写法都合法，使用点由解析器判定。字面量宏触发词仍须全局唯一。
 */
@DisplayName("领域词汇按作用域的冲突校验")
class VocabularyTriggerCollisionTest {

    @Test
    @DisplayName("不同父类型下的同写法字段 → 合法")
    void sameWritingUnderDifferentParentsIsValid() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "作用域", "zh-CN")
            .addField("status", "状态", "Applicant")
            .addField("state", "状态", "Order")
            .build();

        var result = vocab.validate();
        assertTrue(result.valid(), "不同作用域不冲突，实际：" + result.errors());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("同角色同作用域内写法相同但规范名不同 → error")
    void sameScopeConflictingCanonicalsAreRejected() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "冲突域", "de-DE")
            .addField("age", "alter", "Driver")
            .addField("years", "alter", "Driver")
            .build();

        var result = vocab.validate();
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("写法 \"alter\" 在 fields[Driver] 中对应多个规范名")),
            "实际：" + result.errors());
        assertThrows(IllegalArgumentException.class,
            () -> VocabularyRegistry.getInstance().registerCustom("tenant-scope", vocab));
    }

    @Test
    @DisplayName("同一写法映射到相同规范名 → 放行")
    void identicalCanonicalsAreAllowed() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "重复域", "zh-CN")
            .addField("status", "状态", "Applicant")
            .addField("status", "状态", "Order")
            .build();

        assertTrue(vocab.validate().valid(), "指向同一 canonical 的重复声明应放行，实际：" + vocab.validate().errors());
    }

    @Test
    @DisplayName("同义词（同规范名多写法）与仅大小写不同的写法合法；不再校验结构体规范名全局唯一")
    void synonymsAndCaseOnlyVariantsAreValid() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "大小写", "de-DE")
            .addStruct("Level", "Stufe")
            .addStruct("Grade", "STUFE")
            .addField("level", "stufe", "Driver")
            .addField("level", "niveau", "Driver")
            .build();

        assertEquals(List.of(), vocab.validate().errors());
    }

    @Test
    @DisplayName("别名与同作用域另一条映射的写法相同且规范名不同 → error")
    void aliasCollidingInSameScopeIsRejected() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "别名域", "zh-CN")
            .addFunction("computeScore", "计算分数")
            .addFunction("computeRating", "计算评级", "计算分数")
            .build();

        assertFalse(vocab.validate().valid());
    }

    @Test
    @DisplayName("NFC 等价写法视为相同写法")
    void nfcEquivalentWritingsConflict() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "NFC", "de-DE")
            .addFunction("approve", "caf\u00e9")
            .addFunction("reject", "cafe\u0301")
            .build();

        assertFalse(vocab.validate().valid());
    }

    @Test
    @DisplayName("字段与枚举成员缺 parent → error")
    void missingParentIsError() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "缺父", "de-DE")
            .addField("age", "alter", null)
            .addEnumValue("High", "Hoch", null)
            .build();

        var result = vocab.validate();
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("字段 \"alter\" 没有指定父结构体")), "实际：" + result.errors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("枚举成员 \"Hoch\" 没有指定父枚举")), "实际：" + result.errors());
    }

    @Test
    @DisplayName("字面量宏触发词唯一性检查不受本次改动影响")
    void literalTriggerUniquenessStillEnforced() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "字面量域", "zh-CN")
            .addLiteral("静夜思", "思故乡")
            .addFunction("someFunc", "思故乡")
            .build();

        var result = vocab.validate();

        assertFalse(result.valid(), "字面量宏触发词须全局唯一");
        assertTrue(
            result.errors().stream().anyMatch(e -> e.contains("字面量宏触发词须全局唯一")),
            "应命中字面量宏专属错误而非通用冲突错误，实际：" + result.errors());
    }

    @Test
    @DisplayName("无冲突的正常词汇表仍然通过")
    void cleanVocabularyStillValidates() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "正常域", "zh-CN")
            .addStruct("Applicant", "申请人")
            .addField("age", "年龄", "Applicant")
            .addFunction("approve", "批准")
            .build();

        var result = vocab.validate();
        assertTrue(result.valid(), "无冲突词汇表不应被误拒，实际：" + result.errors());
        assertEquals(0, result.errors().size());
    }
}
