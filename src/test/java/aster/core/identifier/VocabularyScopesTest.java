package aster.core.identifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VocabularyScopes 构建、查找与规范 JSON（ADR 0051 §5、§12）。规范 JSON 须与 TS 共享 fixture 逐字节一致。
 */
class VocabularyScopesTest {

    private static final String VOCAB_SHA256 = "af277c81ad5fc41573ac2d2c563f164f97be38d649adc1e3fc9f1d5967d883be";
    private static final String CANONICAL_SHA256 = "b71d7146861a3b5b0db0e4fe53b3912b5a2c086d4b486cd9f0aebc6e814a419a";

    private static byte[] vocabBytes() throws Exception {
        return CaseFoldTest.resource("/identifier/scopes/sample.vocabulary.json");
    }

    private static byte[] canonicalBytes() throws Exception {
        return CaseFoldTest.resource("/identifier/scopes/sample.canonical.json");
    }

    private static DomainVocabulary sample() throws Exception {
        return VocabularyLoader.loadFromString(new String(vocabBytes(), StandardCharsets.UTF_8));
    }

    private static String expectedJson() throws Exception {
        return new String(canonicalBytes(), StandardCharsets.UTF_8);
    }

    private static DomainVocabulary base(List<IdentifierMapping> functions, List<IdentifierMapping> fields,
                                         List<IdentifierMapping> enumValues, List<IdentifierMapping> literals) {
        return new DomainVocabulary("t", "t", "de-DE", "1", List.of(), fields, functions, enumValues, literals, null);
    }

    private static DomainVocabulary functionsOnly(IdentifierMapping... fns) {
        return base(List.of(fns), List.of(), List.of(), List.of());
    }

    private static List<String> canonicals(List<Candidate> cs) {
        List<String> out = new ArrayList<>(cs.stream().map(Candidate::canonical).toList());
        Collections.sort(out);
        return out;
    }

    @Test
    void fixturesAreVerbatimCopies() throws Exception {
        assertEquals(VOCAB_SHA256, CaseFoldTest.sha256(vocabBytes()));
        assertEquals(CANONICAL_SHA256, CaseFoldTest.sha256(canonicalBytes()));
    }

    @Nested
    class Build {

        @Test
        void structAndFieldWithSameSpellingDoNotOverwrite() throws Exception {
            VocabularyScopes s = VocabularyScopes.build(sample());
            assertEquals("Level", VocabularyScopes.lookup(s.types(), "stufe").folded().get(0).canonical());
            assertEquals("level", VocabularyScopes.lookup(s.fields().get("Level"), "Stufe").folded().get(0).canonical());
        }

        @Test
        void alterAndAlterBothKept() throws Exception {
            var person = VocabularyScopes.build(sample()).fields().get("Person");
            VocabularyScopes.Lookup r = VocabularyScopes.lookup(person, "Alter");
            assertEquals(List.of("Alter", "alter"), canonicals(r.folded()));
            assertEquals(List.of("Alter"), canonicals(r.exact()));
            assertEquals(List.of("alter"), canonicals(VocabularyScopes.lookup(person, "alter").exact()));
            assertEquals(0, VocabularyScopes.lookup(person, "ALTER").exact().size());
            assertEquals(2, VocabularyScopes.lookup(person, "ALTER").folded().size());
        }

        @Test
        void aliasIsSynonymAndPrimaryIsTerm() throws Exception {
            VocabularyScopes s = VocabularyScopes.build(sample());
            Candidate syn = VocabularyScopes.lookup(s.types(), "niveau").folded().get(0);
            assertEquals(Candidate.Source.SYNONYM, syn.source());
            assertEquals("Level", syn.canonical());
            assertEquals(Candidate.Source.TERM, VocabularyScopes.lookup(s.types(), "Stufe").folded().get(0).source());
        }

        @Test
        void writtenIsNfcAndDecomposedSpellingHits() throws Exception {
            VocabularyScopes s = VocabularyScopes.build(sample());
            Candidate c = VocabularyScopes.lookup(s.types(), "Café").folded().get(0);
            assertEquals("Café", c.written());
            assertEquals(1, VocabularyScopes.lookup(s.types(), "CAFÉ").folded().size());
            assertEquals(1, VocabularyScopes.lookup(s.types(), "Café").exact().size());
        }

        @Test
        void enumMembersFunctionsLiteralsBucketed() throws Exception {
            VocabularyScopes s = VocabularyScopes.build(sample());
            assertEquals(IdentifierKind.ENUM_VALUE,
                VocabularyScopes.lookup(s.enumMembers().get("Level"), "hoch").folded().get(0).kind());
            assertEquals("Level", VocabularyScopes.lookup(s.enumMembers().get("Level"), "hoch").folded().get(0).parent());
            assertEquals("total", VocabularyScopes.lookup(s.functions(), "gesamt").folded().get(0).canonical());
            assertEquals("Gute Nacht", s.literals().get("nacht"));
            assertEquals(1, s.schemaVersion());
        }

        @Test
        void missReturnsEmptyLists() throws Exception {
            VocabularyScopes.Lookup r = VocabularyScopes.lookup(VocabularyScopes.build(sample()).types(), "nope");
            assertTrue(r.exact().isEmpty());
            assertTrue(r.folded().isEmpty());
        }

        @Test
        void fieldWithoutParentRejected() {
            DomainVocabulary v = base(List.of(), List.of(IdentifierMapping.field("x", "x", null)), List.of(), List.of());
            var e = assertThrows(VocabularyScopeException.class, () -> VocabularyScopes.build(v));
            assertEquals(VocabularyScopeException.Code.FIELD_PARENT_REQUIRED, e.code());
            assertEquals("field_parent_required", e.code().value());
        }

        @Test
        void enumMemberWithoutParentRejected() {
            DomainVocabulary v = base(List.of(), List.of(), List.of(new IdentifierMapping("X", "X", IdentifierKind.ENUM_VALUE, null, null, null)), List.of());
            var e = assertThrows(VocabularyScopeException.class, () -> VocabularyScopes.build(v));
            assertEquals(VocabularyScopeException.Code.MEMBER_PARENT_REQUIRED, e.code());
        }

        @Test
        void conflictingLiteralsUnderSameFoldKeyRejected() {
            DomainVocabulary v = base(List.of(), List.of(), List.of(),
                List.of(IdentifierMapping.literal("a", "X"), IdentifierMapping.literal("b", "x")));
            var e = assertThrows(VocabularyScopeException.class, () -> VocabularyScopes.build(v));
            assertEquals(VocabularyScopeException.Code.LITERAL_CONFLICT, e.code());
        }

        @Test
        void sameLiteralContentUnderSameFoldKeyAccepted() {
            DomainVocabulary v = base(List.of(), List.of(), List.of(),
                List.of(IdentifierMapping.literal("a", "X"), IdentifierMapping.literal("a", "x")));
            assertEquals("a", VocabularyScopes.build(v).literals().get("x"));
        }

        @Test
        void turkishDottedCapitalIDoesNotMatchAsciiI() {
            VocabularyScopes s = VocabularyScopes.build(functionsOnly(IdentifierMapping.function("f", "İl")));
            assertTrue(VocabularyScopes.lookup(s.functions(), "il").folded().isEmpty());
            assertTrue(VocabularyScopes.lookup(s.functions(), "IL").folded().isEmpty());
            assertEquals(1, VocabularyScopes.lookup(s.functions(), "İl").exact().size());
        }
    }

    @Nested
    class CanonicalJson {

        @Test
        void byteIdenticalToSharedFixture() throws Exception {
            String actual = VocabularyScopes.build(sample()).canonicalJson();
            assertEquals(expectedJson(), actual);
            assertEquals(CANONICAL_SHA256, CaseFoldTest.sha256(actual.getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        void deterministicAcrossBuilds() throws Exception {
            assertEquals(VocabularyScopes.build(sample()).canonicalJson(), VocabularyScopes.build(sample()).canonicalJson());
        }

        @Test
        void inputOrderDoesNotAffectOutput() throws Exception {
            DomainVocabulary s = sample();
            List<IdentifierMapping> structs = new ArrayList<>(s.structs());
            List<IdentifierMapping> fields = new ArrayList<>(s.fields());
            Collections.reverse(structs);
            Collections.reverse(fields);
            DomainVocabulary rev = new DomainVocabulary(s.id(), s.name(), s.locale(), s.version(),
                structs, fields, s.functions(), s.enumValues(), s.literals(), null);
            assertEquals(expectedJson(), VocabularyScopes.build(rev).canonicalJson());
        }

        @Test
        void topLevelShape() throws Exception {
            String json = expectedJson();
            assertTrue(json.startsWith("{\"enumMembers\":"));
            assertTrue(json.contains("\"schemaVersion\":1"));
            assertTrue(json.indexOf("\"canonical\":\"Alter\"") < json.indexOf("\"canonical\":\"alter\""));
        }

        @Test
        void greekNfcClosureSharesOneBucketAndKeepsBoth() throws Exception {
            String json = VocabularyScopes.build(functionsOnly(
                IdentifierMapping.function("a", "Ϊ́"),
                IdentifierMapping.function("b", "ΐ"))).canonicalJson();
            JsonNode fns = new ObjectMapper().readTree(json).get("functions");
            assertEquals(1, fns.size());
            assertTrue(json.contains("\"canonical\":\"a\"") && json.contains("\"canonical\":\"b\""));
        }

        @Test
        void tabEscapedAndLineSeparatorRaw() {
            String json = VocabularyScopes.build(functionsOnly(IdentifierMapping.function("c", "a\tb c"))).canonicalJson();
            assertTrue(json.contains("\"written\":\"a\\tb c\""));
            assertTrue(json.contains("\"a\\tb c\":["));
        }

        @Test
        void controlAndQuoteEscapesMatchJsonStringify() {
            String json = VocabularyScopes.build(functionsOnly(
                IdentifierMapping.function("c", "q\"\\\b\f\n\r\u0001\u001f\u007f"))).canonicalJson();
            assertTrue(json.contains("\"written\":\"q\\\"\\\\\\b\\f\\n\\r\\u0001\\u001f\u007f\""), json);
        }

        @Test
        void paragraphSeparatorRawInKeyAndValue() {
            // 与 JSON.stringify 一致：U+2029（同 U+2028）不转义，键与 written 值都原样输出
            String json = VocabularyScopes.build(functionsOnly(IdentifierMapping.function("c", "a\u2029b"))).canonicalJson();
            assertTrue(json.contains("\"a\u2029b\":["), json);
            assertTrue(json.contains("\"written\":\"a\u2029b\""), json);
            assertFalse(json.contains("\\u2029"), json);
            // TS 1.1.0 canonicalScopesJson 对同一词汇的输出（逐字节对拍）
            assertEquals("{\"enumMembers\":{},\"fields\":{},\"functions\":{\"a\u2029b\":[{\"canonical\":\"c\","
                + "\"kind\":\"function\",\"source\":\"term\",\"written\":\"a\u2029b\"}]},\"literals\":{},"
                + "\"schemaVersion\":1,\"types\":{}}", json);
        }

        @Test
        void deleteRawInKeyAndValue() {
            // U+007F 不属于 JSON 必须转义的 U+0000–U+001F，键位置同样原样输出
            String json = VocabularyScopes.build(functionsOnly(IdentifierMapping.function("c", "a\u007fb"))).canonicalJson();
            assertTrue(json.contains("\"a\u007fb\":["), json);
            assertTrue(json.contains("\"written\":\"a\u007fb\""), json);
            assertFalse(json.contains("\\u007f"), json);
            // TS 1.1.0 canonicalScopesJson 对同一词汇的输出（逐字节对拍）
            assertEquals("{\"enumMembers\":{},\"fields\":{},\"functions\":{\"a\u007fb\":[{\"canonical\":\"c\","
                + "\"kind\":\"function\",\"source\":\"term\",\"written\":\"a\u007fb\"}]},\"literals\":{},"
                + "\"schemaVersion\":1,\"types\":{}}", json);
        }

        @Test
        void loneSurrogateWrittenAsLowercaseEscape() {
            String json = VocabularyScopes.build(functionsOnly(IdentifierMapping.function("c", "x\uD83Dy"))).canonicalJson();
            assertTrue(json.contains("\"written\":\"x\\ud83dy\""), json);
        }

        @Test
        void objectKeysSortedByCodePointNotUtf16() {
            String astral = new String(Character.toChars(0x10000));
            String json = VocabularyScopes.build(functionsOnly(
                IdentifierMapping.function("b", astral),
                IdentifierMapping.function("a", "～"))).canonicalJson();
            assertTrue(json.indexOf("\"～\":") < json.indexOf("\"" + astral + "\":"), json);
        }

        @Test
        void noWhitespaceOutsideStrings() throws Exception {
            String stripped = VocabularyScopes.build(sample()).canonicalJson().replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
            assertFalse(stripped.chars().anyMatch(Character::isWhitespace), stripped);
        }
    }

    @Nested
    class Registry {

        @Test
        void entriesCarryScopes() {
            VocabularyRegistry reg = VocabularyRegistry.getInstance();
            DomainVocabulary v = DomainVocabulary.builder("scope.test", "作用域", "zh-CN")
                .addStruct("Level", "等级")
                .addField("rank", "级别", "Level")
                .addEnumValue("High", "高", "Tier")
                .build();
            try {
                reg.registerCustom("tenant-scope", v);
                VocabularyScopes s = reg.getWithCustom("tenant-scope", "scope.test", "zh-CN").orElseThrow().scopes();
                assertEquals(List.of("Level"), List.copyOf(s.fields().keySet()));
                assertEquals(List.of("Tier"), List.copyOf(s.enumMembers().keySet()));
                assertEquals(List.of("Level"), canonicals(VocabularyScopes.lookup(s.types(), "等级").exact()));
            } finally {
                reg.unregisterCustom("tenant-scope", "scope.test", "zh-CN");
            }
        }

        @Test
        void parentlessFieldsAndMembersRejectedByRegisterCustom() {
            VocabularyRegistry reg = VocabularyRegistry.getInstance();
            DomainVocabulary v = DomainVocabulary.builder("scope.test", "作用域", "zh-CN")
                .addStruct("Level", "等级")
                .addField("rank", "级别", "Level")
                .addField("orphan", "孤立", null)
                .addEnumValue("Low", "低", null)
                .build();
            var e = assertThrows(IllegalArgumentException.class, () -> reg.registerCustom("tenant-scope", v));
            assertTrue(e.getMessage().contains("字段 \"孤立\" 没有指定父结构体"), e.getMessage());
            assertTrue(e.getMessage().contains("枚举成员 \"低\" 没有指定父枚举"), e.getMessage());
            assertTrue(reg.getWithCustom("tenant-scope", "scope.test", "zh-CN").isEmpty());
        }

        @Test
        void builtinVocabulariesBuildScopes() {
            for (DomainVocabulary v : VocabularyRegistry.getInstance().listAll()) {
                var entry = VocabularyRegistry.getInstance().get(v.id(), v.locale()).orElseThrow();
                assertTrue(entry.scopes() != null, v.id());
            }
        }
    }
}
