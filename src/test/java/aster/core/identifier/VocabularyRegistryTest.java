package aster.core.identifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VocabularyRegistry 单元测试。
 *
 * 验证词汇表注册中心的功能。
 */
class VocabularyRegistryTest {

    private VocabularyRegistry registry;

    @BeforeEach
    void setUp() {
        registry = VocabularyRegistry.getInstance();
        registry.reset(); // 重置为初始状态
    }

    // ============================================================
    // 内置词汇表测试
    // ============================================================

    @Nested
    @DisplayName("内置词汇表")
    class BuiltinVocabulariesTests {

        @Test
        @DisplayName("汽车保险词汇表已注册")
        void insuranceAutoVocabularyRegistered() {
            Optional<VocabularyRegistry.VocabularyEntry> entry =
                registry.get("insurance.auto", "zh-CN");

            assertTrue(entry.isPresent(), "汽车保险词汇表应已注册");
            assertEquals("insurance.auto", entry.get().vocabulary().id());
            assertEquals("zh-CN", entry.get().vocabulary().locale());
            assertEquals("汽车保险", entry.get().vocabulary().name());
        }

        @Test
        @DisplayName("贷款金融词汇表已注册")
        void financeLoanVocabularyRegistered() {
            Optional<VocabularyRegistry.VocabularyEntry> entry =
                registry.get("finance.loan", "zh-CN");

            assertTrue(entry.isPresent(), "贷款金融词汇表应已注册");
            assertEquals("finance.loan", entry.get().vocabulary().id());
            assertEquals("贷款金融", entry.get().vocabulary().name());
        }

        @Test
        @DisplayName("列出所有中文领域")
        void listDomainsForZhCn() {
            List<String> domains = registry.listDomains("zh-CN");

            assertTrue(domains.contains("insurance.auto"), "应包含汽车保险领域");
            assertTrue(domains.contains("finance.loan"), "应包含贷款金融领域");
        }

        @Test
        @DisplayName("按语言列出词汇表")
        void listByLocale() {
            List<DomainVocabulary> vocabs = registry.listByLocale("zh-CN");

            assertTrue(vocabs.size() >= 2, "应至少有2个中文词汇表");
            assertTrue(vocabs.stream().anyMatch(v -> v.id().equals("insurance.auto")));
            assertTrue(vocabs.stream().anyMatch(v -> v.id().equals("finance.loan")));
        }
    }

    // ============================================================
    // 索引构建测试
    // ============================================================

    @Nested
    @DisplayName("索引构建")
    class IndexBuildingTests {

        @Test
        @DisplayName("构建双向映射索引")
        void buildBidirectionalIndex() {
            IdentifierIndex index = registry.getIndex("insurance.auto", "zh-CN").orElseThrow();

            // 测试本地化 → 规范化
            assertEquals("Driver", index.canonicalize("驾驶员"));
            assertEquals("age", index.canonicalize("年龄"));
            assertEquals("monthlyPremium", index.canonicalize("月保费"));

            // 测试规范化 → 本地化
            assertEquals("驾驶员", index.localize("Driver"));
            assertEquals("报价结果", index.localize("QuoteResult"));
        }

        @Test
        @DisplayName("支持别名映射")
        void aliasMapping() {
            IdentifierIndex index = registry.getIndex("insurance.auto", "zh-CN").orElseThrow();

            // 别名应该也能映射到规范化名称
            assertEquals("Driver", index.canonicalize("司机"));
            assertEquals("Driver", index.canonicalize("驾驶人"));
            assertEquals("plateNo", index.canonicalize("车牌"));
        }

        @Test
        @DisplayName("大小写不敏感")
        void caseInsensitive() {
            IdentifierIndex index = registry.getIndex("insurance.auto", "zh-CN").orElseThrow();

            // 规范化名称的大小写应该不敏感
            assertEquals("驾驶员", index.localize("driver"));
            assertEquals("驾驶员", index.localize("DRIVER"));
            assertEquals("驾驶员", index.localize("Driver"));
        }

        @Test
        @DisplayName("未映射标识符返回原值")
        void unmappedIdentifierReturnsOriginal() {
            IdentifierIndex index = registry.getIndex("insurance.auto", "zh-CN").orElseThrow();

            assertEquals("未知标识符", index.canonicalize("未知标识符"));
            assertEquals("UnknownIdentifier", index.localize("UnknownIdentifier"));
        }
    }

    // ============================================================
    // 标识符转换测试
    // ============================================================

    @Nested
    @DisplayName("标识符转换")
    class IdentifierConversionTests {

        @Test
        @DisplayName("汽车保险领域标识符转换")
        void insuranceAutoIdentifiers() {
            IdentifierIndex index = registry.getIndex("insurance.auto", "zh-CN").orElseThrow();

            assertEquals("Driver", index.canonicalize("驾驶员"));
            assertEquals("Vehicle", index.canonicalize("车辆"));
            assertEquals("QuoteResult", index.canonicalize("报价结果"));
            assertEquals("calculatePremium", index.canonicalize("计算保费"));
        }

        @Test
        @DisplayName("贷款金融领域标识符转换")
        void financeLoanIdentifiers() {
            IdentifierIndex index = registry.getIndex("finance.loan", "zh-CN").orElseThrow();

            assertEquals("Applicant", index.canonicalize("申请人"));
            assertEquals("LoanRequest", index.canonicalize("贷款申请"));
            assertEquals("creditScore", index.canonicalize("信用评分"));
            assertEquals("evaluateLoan", index.canonicalize("评估贷款"));
        }

        @Test
        @DisplayName("贷款领域别名")
        void financeLoanAliases() {
            IdentifierIndex index = registry.getIndex("finance.loan", "zh-CN").orElseThrow();

            assertEquals("Applicant", index.canonicalize("借款人"));
            assertEquals("creditScore", index.canonicalize("征信分"));
            assertEquals("workYears", index.canonicalize("工龄"));
        }
    }

    // ============================================================
    // 自定义词汇表测试
    // ============================================================

    @Nested
    @DisplayName("自定义词汇表")
    class CustomVocabularyTests {

        @Test
        @DisplayName("注册和使用租户自定义词汇表")
        void registerAndUseCustomVocabulary() {
            DomainVocabulary customVocab = DomainVocabulary.builder(
                    "insurance.auto", "自定义汽车保险", "zh-CN")
                .addStruct("CustomDriver", "自定义驾驶员")
                .build();

            registry.registerCustom("tenant-123", customVocab);

            // 使用租户 ID 查询时应返回自定义词汇表
            Optional<VocabularyRegistry.VocabularyEntry> entry =
                registry.getWithCustom("tenant-123", "insurance.auto", "zh-CN");
            assertEquals("自定义汽车保险", entry.orElseThrow().vocabulary().name());

            // 不使用租户 ID 时应返回内置词汇表
            Optional<VocabularyRegistry.VocabularyEntry> builtinEntry =
                registry.getWithCustom(null, "insurance.auto", "zh-CN");
            assertEquals("汽车保险", builtinEntry.orElseThrow().vocabulary().name());
        }

        @Test
        @DisplayName("卸载自定义词汇表")
        void unregisterCustomVocabulary() {
            DomainVocabulary customVocab = DomainVocabulary.builder(
                    "test.domain", "测试领域", "zh-CN")
                .addStruct("TestStruct", "测试结构体")
                .build();

            registry.registerCustom("tenant-456", customVocab);
            assertTrue(registry.getWithCustom("tenant-456", "test.domain", "zh-CN").isPresent());

            boolean removed = registry.unregisterCustom("tenant-456", "test.domain", "zh-CN");
            assertTrue(removed);
            assertFalse(registry.getWithCustom("tenant-456", "test.domain", "zh-CN").isPresent());
        }
    }

    // ============================================================
    // 词汇表合并测试
    // ============================================================

    @Nested
    @DisplayName("词汇表合并")
    class VocabularyMergeTests {

        @Test
        @DisplayName("合并多个领域词汇表")
        void mergeDomains() {
            Optional<DomainVocabulary> merged = registry.merge(
                List.of("insurance.auto", "finance.loan"), "zh-CN");

            assertTrue(merged.isPresent());
            assertEquals("insurance.auto+finance.loan", merged.get().id());

            // 验证合并后包含两个领域的结构体
            List<String> structNames = merged.get().structs().stream()
                .map(IdentifierMapping::canonical)
                .toList();
            assertTrue(structNames.contains("Driver"), "应包含 Driver");
            assertTrue(structNames.contains("Applicant"), "应包含 Applicant");
        }

        @Test
        @DisplayName("合并不存在的领域返回空")
        void mergeNonexistentDomains() {
            Optional<DomainVocabulary> merged = registry.merge(
                List.of("nonexistent.domain"), "zh-CN");

            assertFalse(merged.isPresent());
        }
    }

    // ============================================================
    // 词汇表验证测试
    // ============================================================

    @Nested
    @DisplayName("词汇表验证")
    class VocabularyValidationTests {

        @Test
        @DisplayName("验证内置词汇表")
        void validateBuiltinVocabularies() {
            DomainVocabulary.ValidationResult result =
                BuiltinVocabularies.insuranceAutoZhCn().validate();

            assertTrue(result.valid(), "汽车保险词汇表应通过验证");
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("检测无效的规范化名称")
        void detectInvalidCanonicalName() {
            DomainVocabulary invalidVocab = DomainVocabulary.builder(
                    "test", "测试", "zh-CN")
                .addStruct("无效名称", "测试") // 非 ASCII
                .build();

            DomainVocabulary.ValidationResult result = invalidVocab.validate();
            assertFalse(result.valid(), "应检测到无效的规范化名称");
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("无效名称")));
        }

        @Test
        @DisplayName("注册无效词汇表应抛出异常")
        void registerInvalidVocabularyThrows() {
            DomainVocabulary invalidVocab = DomainVocabulary.builder(
                    "test", "测试", "zh-CN")
                .addStruct("123Invalid", "测试") // 以数字开头
                .build();

            assertThrows(IllegalArgumentException.class, () -> registry.register(invalidVocab));
        }
    }

    // ============================================================
    // 按类型索引测试
    // ============================================================

    @Nested
    @DisplayName("按类型索引")
    class ByKindIndexTests {

        @Test
        @DisplayName("按类型分类映射")
        void indexByKind() {
            IdentifierIndex index = registry.getIndex("insurance.auto", "zh-CN").orElseThrow();

            var structs = index.getByKind(IdentifierKind.STRUCT);
            assertTrue(structs.containsKey("驾驶员"));
            assertTrue(structs.containsKey("车辆"));

            var fields = index.getByKind(IdentifierKind.FIELD);
            assertTrue(fields.containsKey("年龄"));
            assertTrue(fields.containsKey("驾龄"));

            var functions = index.getByKind(IdentifierKind.FUNCTION);
            assertTrue(functions.containsKey("生成报价"));
        }

        @Test
        @DisplayName("按父结构体索引字段")
        void indexFieldsByParent() {
            IdentifierIndex index = registry.getIndex("insurance.auto", "zh-CN").orElseThrow();

            var driverFields = index.getFieldsByParent("Driver");
            assertTrue(driverFields.containsKey("年龄"));
            assertTrue(driverFields.containsKey("驾龄"));

            var vehicleFields = index.getFieldsByParent("Vehicle");
            assertTrue(vehicleFields.containsKey("车牌号"));
            assertTrue(vehicleFields.containsKey("安全评分"));
        }
    }
}
