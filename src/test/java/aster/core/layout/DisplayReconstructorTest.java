package aster.core.layout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 反向映射（ADR 0059 修订 4 D7）的共享向量与性能配方（aster-lang-test {@code display-reconstruct.json}）。 */
class DisplayReconstructorTest {

    private static PolicyLayout parse(String layoutText) {
        return LayoutFormat.parseStrict(layoutText.getBytes(StandardCharsets.UTF_8));
    }

    private static String kind(DisplayReconstructor.Reconstruction r) {
        return switch (r) {
            case DisplayReconstructor.Unique u -> "unique";
            case DisplayReconstructor.Unmappable u -> "unmappable";
            case DisplayReconstructor.Ambiguous a -> "ambiguous";
        };
    }

    @TestFactory
    Stream<DynamicTest> reconstructVectors() throws Exception {
        JsonNode doc = LayoutVectorsTest.vectors("display-reconstruct.json");
        assertTrue(doc.path("cases").size() > 25, "反向映射向量过少");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : doc.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("id").asText() + " → " + c.path("expect").asText(), () -> {
                DisplayReconstructor.Reconstruction r = DisplayReconstructor.reconstruct(parse(c.path("layoutText").textValue()), c.path("edited").textValue());
                assertEquals(c.path("expect").asText(), kind(r));
                if (r instanceof DisplayReconstructor.Unique u) {
                    assertEquals(c.path("canonical").textValue(), u.derived().toCanonical());
                }
            }));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> performanceRecipes() throws Exception {
        JsonNode doc = LayoutVectorsTest.vectors("display-reconstruct.json");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode p : doc.path("performance")) {
            tests.add(DynamicTest.dynamicTest(p.path("id").asText(), () -> {
                List<LayoutSpan> spans = new ArrayList<>();
                for (int u = 0; u < p.path("units").asInt(); u++) {
                    for (JsonNode s : p.path("unit")) {
                        spans.add(s.has("text") ? new LayoutSpan.Content(s.path("text").textValue())
                            : new LayoutSpan.Structural(s.path("canonical").textValue(), s.path("display").textValue()));
                    }
                }
                PolicyLayout layout = new PolicyLayout(spans);
                String edited = p.path("editUnit").textValue().repeat(p.path("editRepeat").asInt()) + p.path("editTail").textValue();
                assertTrue(LayoutLimits.utf8Length(edited) <= LayoutLimits.DISPLAY_BYTES);
                DisplayReconstructor.Reconstruction r = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> DisplayReconstructor.reconstruct(layout, edited));
                assertEquals(p.path("expect").asText(), kind(r));
                if (p.has("canonicalUnit")) {
                    assertEquals(p.path("canonicalUnit").textValue().repeat(p.path("units").asInt()),
                        assertInstanceOf(DisplayReconstructor.Unique.class, r).derived().toCanonical());
                }
            }));
        }
        assertEquals(3, tests.size());
        return tests.stream();
    }

    @Test
    void crlfHintOnlyWhenLayoutHasNoCrlf() throws Exception {
        PolicyLayout poem = parse(LayoutVectorsTest.vectors("display-reconstruct.json").path("cases").get(0).path("layoutText").textValue());
        DisplayReconstructor.Reconstruction r = DisplayReconstructor.reconstruct(poem, poem.toDisplay().replace("\n", "\r\n"));
        assertTrue(assertInstanceOf(DisplayReconstructor.Unmappable.class, r).crlfHint());
        DisplayReconstructor.Reconstruction r2 = DisplayReconstructor.reconstruct(poem, poem.toDisplay().replace("明月光", "明月照"));
        assertInstanceOf(DisplayReconstructor.Unique.class, r2);
    }
    /**
     * 反向映射的定义（修订 D7）与算法无关：在随机小排版上穷举全部切分作独立判官，结论（0 / 1 / ≥ 2）与唯一解必须
     * 与 DP 相同。种子固定，结果可复现。
     */
    @Test
    void agreesWithExhaustiveDefinitionOnRandomSmallLayouts() {
        java.util.Random rnd = new java.util.Random(0x0059A4L);
        String[] alpha = {"a", "b", "，", " "};
        for (int n = 0; n < 3000; n++) {
            List<LayoutSpan> spans = new ArrayList<>();
            if (rnd.nextInt(3) == 0) {
                spans.add(new LayoutSpan.Structural("#", rnd.nextBoolean() ? "" : alpha[rnd.nextInt(4)]));
            }
            int k = 1 + rnd.nextInt(4);
            for (int j = 0; j < k; j++) {
                spans.add(new LayoutSpan.Content(word(rnd, alpha, 1, 2)));
                if (j < k - 1 || rnd.nextBoolean()) {
                    int m = 1 + rnd.nextInt(2);
                    for (int q = 0; q < m; q++) {
                        spans.add(new LayoutSpan.Structural("_", rnd.nextBoolean() ? "" : alpha[rnd.nextInt(4)]));
                    }
                }
            }
            PolicyLayout layout = new PolicyLayout(spans);
            String edited = rnd.nextInt(3) == 0 ? layout.toDisplay() : word(rnd, alpha, 1, 7);
            List<List<String>> sols = new ArrayList<>();
            brute(spans, edited, 0, 0, new ArrayList<>(), null, false, sols);
            String want = sols.isEmpty() ? "unmappable" : sols.size() == 1 ? "unique" : "ambiguous";
            DisplayReconstructor.Reconstruction r = DisplayReconstructor.reconstruct(layout, edited);
            assertEquals(want, kind(r), spans + " / " + edited);
            if (r instanceof DisplayReconstructor.Unique u) {
                List<String> texts = new ArrayList<>();
                u.derived().spans().forEach(s -> {
                    if (s instanceof LayoutSpan.Content c) {
                        texts.add(c.text());
                    }
                });
                assertEquals(sols.get(0), texts);
            }
        }
    }

    private static String word(java.util.Random rnd, String[] alpha, int min, int max) {
        StringBuilder sb = new StringBuilder();
        int len = min + rnd.nextInt(max - min + 1);
        for (int i = 0; i < len; i++) {
            sb.append(alpha[rnd.nextInt(alpha.length)]);
        }
        return sb.toString();
    }

    /** 按定义穷举：prevChanged 为上一个内容是否改过（null 表示还没有内容），hiddenOnly 表示上一个内容之后只经过隐藏 span。 */
    private static void brute(List<LayoutSpan> spans, String e, int i, int at, List<String> ts, Boolean prevChanged, boolean hiddenOnly, List<List<String>> sols) {
        if (sols.size() > 2) {
            return;
        }
        if (i == spans.size()) {
            if (at == e.length()) {
                sols.add(new ArrayList<>(ts));
            }
            return;
        }
        LayoutSpan s = spans.get(i);
        if (s instanceof LayoutSpan.Structural st) {
            if (e.startsWith(st.display(), at)) {
                brute(spans, e, i + 1, at + st.display().length(), ts, prevChanged, st.display().isEmpty() && hiddenOnly, sols);
            }
            return;
        }
        String o = ((LayoutSpan.Content) s).text();
        for (int end = at + 1; end <= e.length(); end++) {
            String t = e.substring(at, end);
            boolean changed = !t.equals(o);
            if (hiddenOnly && Boolean.TRUE.equals(prevChanged) && changed) {
                continue;
            }
            ts.add(t);
            brute(spans, e, i + 1, end, ts, changed, true, sols);
            ts.remove(ts.size() - 1);
        }
    }
}
