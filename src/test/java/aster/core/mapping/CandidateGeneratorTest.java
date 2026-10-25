package aster.core.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 候选生成与重定位（与 TS 侧 candidate-generator.test.ts 同用例，aster-lang-ts#238）。 */
class CandidateGeneratorTest {

    private static MappingIr.CandidateMapping cand(int start, int end, String text, String nodeId) {
        return new MappingIr.CandidateMapping(new MappingIr.TextSpan(start, end), text, nodeId);
    }

    @Test
    @DisplayName("★列号越过该行行尾时跳过，不得切进下一行")
    void columnPastLineEndIsSkipped() {
        CandidateGenerator.GenerationResult r = CandidateGenerator.generate("ab\ncd\n", List.of(
            new CandidateGenerator.OriginatedNode("$.overflow", "Int", 1, 2, 1, 5),
            new CandidateGenerator.OriginatedNode("$.lineEnd", "Int", 1, 2, 1, 3)));
        assertEquals(List.of(cand(1, 2, "b", "$.lineEnd")), r.candidates());
        assertEquals(1, r.skipped().size());
        assertTrue(r.skipped().get(0).why().contains("超出源码范围"), r.skipped().toString());
    }

    @Test
    @DisplayName("★重定位：canonical 删了冠词，候选区间仍指向原文里的同一段文本")
    void relocatesPastRemovedArticles() {
        String source = "Let the total be 42 plus the 42.";
        String canonical = "Let total be 42 plus 42.";
        CandidateGenerator.GenerationResult r = CandidateGenerator.relocateToSource(source, canonical,
            new CandidateGenerator.GenerationResult(
                List.of(cand(13, 15, "42", "$.a"), cand(21, 23, "42", "$.b")), List.of()));
        assertEquals(List.of(cand(17, 19, "42", "$.a"), cand(29, 31, "42", "$.b")), r.candidates());
        assertEquals(List.of(), r.skipped());
    }

    @Test
    @DisplayName("★重定位：原文同一行找不到候选文本、或行数不同，都跳过并报告")
    void relocationFailuresAreReported() {
        CandidateGenerator.GenerationResult notFound = CandidateGenerator.relocateToSource("Return ４２.", "Return 42.",
            new CandidateGenerator.GenerationResult(List.of(cand(7, 9, "42", "$.x")), List.of()));
        assertEquals(List.of(), notFound.candidates());
        assertTrue(notFound.skipped().get(0).why().contains("出现次数不同（原文 0，canonical 1）"), notFound.skipped().toString());

        CandidateGenerator.GenerationResult lines = CandidateGenerator.relocateToSource("a\nb", "a",
            new CandidateGenerator.GenerationResult(List.of(cand(0, 1, "a", "$.y")), List.of()));
        assertEquals(List.of(), lines.candidates());
        assertTrue(lines.skipped().get(0).why().contains("行数不同"), lines.skipped().toString());
    }

    @Test
    @DisplayName("★重定位：canonicalize 增删了同样的子串时不按序号硬配，跳过并报告")
    void occurrenceCountMismatchIsSkipped() {
        CandidateGenerator.GenerationResult r = CandidateGenerator.relocateToSource(
            "Return “x” plus \"x\".", "Return \"x\" plus \"x\".",
            new CandidateGenerator.GenerationResult(
                List.of(cand(7, 10, "\"x\"", "$.a"), cand(16, 19, "\"x\"", "$.b")), List.of()));
        assertEquals(List.of(), r.candidates());
        assertEquals(List.of("$.a", "$.b"), r.skipped().stream().map(CandidateGenerator.Skipped::nodeId).toList());
        assertTrue(r.skipped().stream().allMatch(s -> s.why().contains("出现次数不同")), r.skipped().toString());
    }

    @Test
    @DisplayName("★起点不得落在行尾换行符上（终点可以，它是开区间）")
    void startAtLineEndIsSkipped() {
        CandidateGenerator.GenerationResult r = CandidateGenerator.generate("ab\ncd\n", List.of(
            new CandidateGenerator.OriginatedNode("$.startAtEol", "Int", 1, 3, 2, 2)));
        assertEquals(List.of(), r.candidates());
        assertTrue(r.skipped().get(0).why().contains("超出源码范围"), r.skipped().toString());
    }
}
