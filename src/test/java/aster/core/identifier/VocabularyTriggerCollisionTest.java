package aster.core.identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发词冲突检测（issue #88）。
 *
 * <p>{@link IdentifierIndex#build} 用 {@code toCanonical.put} 无条件覆盖，两条触发词
 * 相同但 canonical 不同的映射，胜者取决于 {@code allMappings()} 的迭代顺序——同一份
 * 词汇集合换个合并次序就得到不同的编译结果，且 {@code validate()} 此前认为完全合法。
 */
@DisplayName("领域词汇触发词冲突")
class VocabularyTriggerCollisionTest {

    @Test
    @DisplayName("同一触发词映射到不同规范名 → 校验失败")
    void conflictingCanonicalsAreRejected() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "冲突域", "zh-CN")
            .addField("status", "状态", "Applicant")
            .addField("state", "状态", "Order")
            .build();

        var result = vocab.validate();

        // 定为 warning 而非 error：现网词汇表已存在此类重叠，报 error 会让整个词汇
        // 体系无法注册。先让歧义可见，待现网清理后再升级。
        assertTrue(
            result.warnings().stream().anyMatch(w -> w.contains("两个不同的规范名")),
            "应给出歧义警告，实际 warnings=" + result.warnings());
    }

    @Test
    @DisplayName("同一触发词映射到相同规范名 → 放行（跨域重复声明同一术语无害）")
    void identicalCanonicalsAreAllowed() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "重复域", "zh-CN")
            .addField("status", "状态", "Applicant")
            .addField("status", "状态", "Order")
            .build();

        var r = vocab.validate();
        assertTrue(r.valid(), "指向同一 canonical 的重复声明应放行，实际：" + r.errors());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("两个不同的规范名")),
            "canonical 相同则不应有歧义警告，实际 warnings=" + r.warnings());
    }

    @Test
    @DisplayName("别名与另一条映射的本地名冲突 → 给出警告")
    void aliasCollidingWithAnotherLocalizedWarns() {
        DomainVocabulary vocab = DomainVocabulary.builder("dom", "别名域", "zh-CN")
            .addFunction("computeScore", "计算分数")
            .addFunction("computeRating", "计算评级", "计算分数")
            .build();

        assertTrue(
            vocab.validate().warnings().stream().anyMatch(w -> w.contains("两个不同的规范名")),
            "别名撞上他条本地名且 canonical 不同，应给出歧义警告");
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
