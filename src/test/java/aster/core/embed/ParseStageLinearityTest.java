package aster.core.embed;

import aster.core.ast.Module;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierMapping;
import aster.core.identifier.VocabularyScopes;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.resolve.ImportedDeclarations;
import aster.core.resolve.NameResolution;
import aster.core.resolve.ResolutionContext;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.atn.PredictionMode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析各阶段的线性度模糊测试（wontlost-ltd/aster-api#594，#489 第 4 项）。
 *
 * <p>{@link ParseBudget} 只在语法分析的预测里协作式检查；规范化、词法 {@code tokens.fill()}、构建 AST 与名称解析
 * （R1 → lower → R2）不检查预算。它们只要随源码长度线性增长，在 aster-api 的 16 KiB 源码上限内就只占毫秒级，
 * 阶段边界上的一次 {@code check()} 足以把「最后一个阶段超时」及时归为 E054；若某阶段呈超线性，边界检查拦不住
 * 它本身，需要在其循环里检查。本测试按阶段计时：规范化、词法、语法分析、构建 AST、名称解析与端到端都断言源码长度
 * 每翻倍一次耗时增长不超过 {@value #MAX_DOUBLING_RATIO} 倍（线性为 2，二次为 4；取两次翻倍的几何平均）。
 * 名称解析的局部名与源码声明查找按名字 / 折叠键索引（aster-lang-core#312），大量短语句与大量词汇条目两种形态也是线性。
 *
 * <p>阶段与 {@link InProcessCnlParser#parseUnsafeWithAliases} 的步骤相同（别名叠加 → 规范化 → 词法 → SLL 语法分析
 * → 构建 AST → 名称解析），但只走合法输入的主路径：不挂错误监听器、不用带预算的预测器、不做嵌套守卫与
 * {@code requireModule}；另测真实入口的端到端耗时作对照。生成器覆盖 #594 列出的形态：长标识符、大量别名 /
 * 词汇条目、混合文字、长字符串、大量短语句、汉字别名堆叠。jqwik 随机选取每种形态的内容（字母表、片段），
 * 规模按翻倍取，最大一档不超过 16 KiB（UTF-8）。
 *
 * <p>标为 {@code slow}：每例做多轮计时取中位数，总耗时约十几秒。
 */
@Tag("slow")
class ParseStageLinearityTest {

    /** aster-api 的源码长度上限（UTF-8 字节）。 */
    static final int SOURCE_CAP_BYTES = 16 * 1024;
    /** 翻倍时允许的最大增长倍数：线性为 2，二次为 4；留出计时噪声的余量。 */
    static final double MAX_DOUBLING_RATIO = 2.5;
    /** 低于此耗时（纳秒）的阶段不比增长率：比值由计时噪声主导，而这种量级对 2 秒预算无影响。 */
    static final long NOISE_FLOOR_NANOS = 2_000_000L;
    private static final int WARMUP = 3;
    private static final int RUNS = 7;

    /** 一份待测输入：源码、locale、词汇与用户别名。 */
    record Input(String source, String locale, VocabularyScopes scopes, Map<SemanticTokenKind, List<String>> aliases) {
        Input(String source, String locale) {
            this(source, locale, null, null);
        }
    }

    /** 各阶段耗时（纳秒）。 */
    record StageTimes(long canonicalize, long lex, long parse, long ast, long resolve, long endToEnd) {
        long[] asArray() {
            return new long[] {canonicalize, lex, parse, ast, resolve, endToEnd};
        }
    }

    static final String[] STAGES = {"canonicalize", "lex", "parse", "ast", "resolve", "end-to-end"};

    // ---- 生成器：scale 为规模参数，返回一份可通过解析的输入 ----

    /** 长标识符：形参名与返回值是一个 scale 个字母的标识符。 */
    static IntFunction<Input> longIdentifier(String letters) {
        return scale -> {
            String id = "a" + repeat(letters, scale);
            return new Input("Module m.\n\nRule f given " + id + " as Int, produce Int:\n  Return " + id + ".\n", "en-US");
        };
    }

    /** 大量短语句：scale 条 Let。 */
    static IntFunction<Input> manyStatements(String stem) {
        return scale -> {
            StringBuilder sb = new StringBuilder("Module m.\n\nRule f given x as Int, produce Int:\n");
            for (int i = 0; i < scale; i++) {
                sb.append("  Let ").append(stem).append(i).append(" be x plus ").append(i).append(".\n");
            }
            return new Input(sb.append("  Return x.\n").toString(), "en-US");
        };
    }

    /** 长字符串：scale 个字符的字符串字面量（含转义与多文字）。 */
    static IntFunction<Input> longString(String chunk) {
        return scale -> new Input("Module m.\n\nRule f, produce Text:\n  Return \"" + repeat(chunk, scale) + "\".\n", "en-US");
    }

    /** 混合文字：zh-CN 源码，scale 个由汉字、拉丁字母与天城文拼成的形参。 */
    static IntFunction<Input> mixedScripts(String han) {
        return scale -> {
            StringBuilder params = new StringBuilder();
            for (int i = 0; i < scale; i++) {
                if (i > 0) params.append("，");
                params.append(han).append("a").append("क").append(i).append(" 作为 整数");
            }
            return new Input("模块 m。\n\n规则 f 给定 " + params + "，产出 整数：\n  返回 1。\n", "zh-CN");
        };
    }

    /** 大量词汇条目：scale 个类型与字段映射，源码逐个引用（名称解析按词汇查找）。 */
    static IntFunction<Input> vocabularyTerms(String stem) {
        return scale -> {
            List<IdentifierMapping> structs = new ArrayList<>();
            List<IdentifierMapping> fields = new ArrayList<>();
            StringBuilder sb = new StringBuilder("Module m.\n\n");
            for (int i = 0; i < scale; i++) {
                structs.add(IdentifierMapping.struct("T" + i, stem + "T" + i));
                fields.add(IdentifierMapping.field("v" + i, stem + "v" + i, "T" + i));
                sb.append("Define T").append(i).append(" with v").append(i).append(" as Int.\n");
            }
            sb.append("\nRule f given x as ").append(stem).append("T0, produce Int:\n  Return x.").append(stem).append("v0.\n");
            DomainVocabulary vocabulary = new DomainVocabulary("t", "t", "en-US", "1", structs, fields, List.of(), List.of(),
                List.of(), null);
            return new Input(sb.toString(), "en-US", VocabularyScopes.build(vocabulary), null);
        };
    }

    /**
     * 汉字别名堆叠（#589 之后）：别名表给 返回 配满 {@link UserAliasValidator#MAX_ALIASES_PER_KIND} 个汉字别名，
     * scale 个 如果 分支轮流用这些别名返回。
     */
    static IntFunction<Input> hanAliasSoup(String han) {
        List<String> returnAliases = new ArrayList<>();
        for (int i = 0; i < UserAliasValidator.MAX_ALIASES_PER_KIND; i++) {
            returnAliases.add("交还" + han + toHan(i));
        }
        Map<SemanticTokenKind, List<String>> aliases = new EnumMap<>(SemanticTokenKind.class);
        aliases.put(SemanticTokenKind.RETURN, List.copyOf(returnAliases));
        return scale -> {
            StringBuilder sb = new StringBuilder("模块 m。\n\n规则 f 给定 x 作为 整数，产出 整数：\n");
            for (int i = 0; i < scale; i++) {
                sb.append("  如果 x 大于 ").append(i).append("：\n    ")
                    .append(returnAliases.get(i % returnAliases.size())).append(' ').append(i).append("。\n");
            }
            return new Input(sb.append("  返回 x。\n").toString(), "zh-CN", null, aliases);
        };
    }

    // ---- 计时 ----

    /** 与 InProcessCnlParser.parseUnsafeWithAliases 相同的步骤，逐阶段计时。 */
    static StageTimes measure(Input in) {
        Lexicon lexicon = AliasOverlayLexicon.wrap(LexiconRegistry.getInstance().getOrThrow(in.locale()), in.aliases());
        long t0 = System.nanoTime();
        String canonical = new Canonicalizer(lexicon, in.scopes()).canonicalize(in.source());
        long t1 = System.nanoTime();
        CommonTokenStream tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical)));
        tokens.fill();
        long t2 = System.nanoTime();
        tokens.seek(0);
        AsterParser parser = new AsterParser(tokens);
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
        AsterParser.ModuleContext tree = parser.module();
        long t3 = System.nanoTime();
        assertThat(parser.getNumberOfSyntaxErrors()).as("生成器产出的源码须能解析").isZero();
        Module module = new AstBuilder().visitModule(tree);
        long t4 = System.nanoTime();
        var resolved = NameResolution.resolveAndLower(module, new ResolutionContext(in.scopes(), ImportedDeclarations.NONE));
        long t5 = System.nanoTime();
        assertThat(NameResolution.hasErrors(resolved.diagnostics())).as("生成器产出的源码须通过名称解析").isFalse();
        InProcessCnlParser.parseUnsafeWithAliases(in.source(), in.locale(), in.scopes(), in.aliases());
        long t6 = System.nanoTime();
        return new StageTimes(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, t6 - t5);
    }

    /** 多轮取每个阶段的中位数。 */
    static long[] medianTimes(Input in) {
        for (int i = 0; i < WARMUP; i++) {
            measure(in);
        }
        long[][] runs = new long[RUNS][];
        for (int i = 0; i < RUNS; i++) {
            runs[i] = measure(in).asArray();
        }
        long[] out = new long[STAGES.length];
        for (int s = 0; s < STAGES.length; s++) {
            long[] col = new long[RUNS];
            for (int i = 0; i < RUNS; i++) {
                col[i] = runs[i][s];
            }
            Arrays.sort(col);
            out[s] = col[RUNS / 2];
        }
        return out;
    }

    /** 最大一档不超过上限的规模：从 1 起翻倍，直到下一档超出 16 KiB。 */
    static int largestScale(IntFunction<Input> gen) {
        int scale = 1;
        while (bytes(gen.apply(scale * 2).source()) <= SOURCE_CAP_BYTES) {
            scale *= 2;
        }
        return scale;
    }

    /**
     * 断言：取最大一档与其 1/4（两次翻倍），每个阶段每次翻倍的平均增长 √(t(4n)/t(n)) 不超过 {@value #MAX_DOUBLING_RATIO}
     * （跨两次翻倍取几何平均，抗单次计时噪声；最大一档低于噪声下限的阶段跳过）。
     * 返回逐阶段的报告，便于失败时定位。
     */
    static Map<String, String> assertLinear(String name, IntFunction<Input> gen) {
        int big = largestScale(gen);
        int small = big / 4;
        assertThat(small).as("%s：规模须能翻倍两次", name).isGreaterThanOrEqualTo(1);
        long[] a = medianTimes(gen.apply(small));
        long[] b = medianTimes(gen.apply(big));
        double[] perDoubling = new double[STAGES.length];
        Map<String, String> report = new LinkedHashMap<>();
        for (int s = 0; s < STAGES.length; s++) {
            perDoubling[s] = Math.sqrt((double) b[s] / Math.max(a[s], 1));
            report.put(STAGES[s], String.format("%.2fms→%.2fms 每次翻倍 ×%.2f", a[s] / 1e6, b[s] / 1e6, perDoubling[s]));
        }
        System.out.printf("[parse-stage-linearity] %s scale %d→%d (%d→%d bytes): %s%n", name, small, big,
            bytes(gen.apply(small).source()), bytes(gen.apply(big).source()), report);
        for (int s = 0; s < STAGES.length; s++) {
            if (b[s] < NOISE_FLOOR_NANOS) {
                continue;
            }
            assertThat(perDoubling[s]).as("%s 阶段 %s 翻倍增长 %s", name, STAGES[s], report).isLessThanOrEqualTo(MAX_DOUBLING_RATIO);
        }
        return report;
    }

    // ---- 性质 ----

    @Property(tries = 2)
    void longIdentifiersStayLinear(@ForAll("latinLetters") String letters) {
        assertLinear("长标识符", longIdentifier(letters));
    }

    @Property(tries = 2)
    void manyShortStatementsStayLinear(@ForAll("latinLetters") String stem) {
        assertLinear("大量短语句", manyStatements("v" + stem));
    }

    @Property(tries = 2)
    void longStringsStayLinear(@ForAll("stringChunks") String chunk) {
        assertLinear("长字符串", longString(chunk));
    }

    @Property(tries = 2)
    void mixedScriptsStayLinear(@ForAll("hanText") String han) {
        assertLinear("混合文字", mixedScripts(han));
    }

    @Property(tries = 2)
    void vocabularyTermsStayLinear(@ForAll("latinLetters") String stem) {
        assertLinear("大量词汇条目", vocabularyTerms("Z" + stem));
    }

    @Property(tries = 2)
    void hanAliasSoupStaysLinear(@ForAll("hanText") String han) {
        assertLinear("汉字别名堆叠", hanAliasSoup(han));
    }

    @Provide
    Arbitrary<String> latinLetters() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(3);
    }

    @Provide
    Arbitrary<String> stringChunks() {
        return Arbitraries.of("ab", "a\\\"b", "中文", "a b", "e\\n", "x'y");
    }

    @Provide
    Arbitrary<String> hanText() {
        return Arbitraries.strings().withCharRange('丐', '丫').ofMinLength(1).ofMaxLength(2);
    }

    // ---- 工具 ----

    private static String repeat(String s, int n) {
        return s.repeat(Math.max(n, 0));
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 非负整数写成汉字数字串（零一二…九），让别名只含汉字。 */
    private static String toHan(int n) {
        String digits = "零一二三四五六七八九";
        StringBuilder sb = new StringBuilder();
        for (char c : Integer.toString(n).toCharArray()) {
            sb.append(digits.charAt(c - '0'));
        }
        return sb.toString();
    }
}
