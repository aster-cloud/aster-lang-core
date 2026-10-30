package aster.core.dualengine;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.identifier.VocabularyLoader;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreModel;
import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.resolve.NameResolution;
import aster.core.resolve.NameResolutionException;
import aster.core.resolve.ResolutionContext;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Core IR fingerprint emitter for the dual-engine parity gate.
 *
 * <p>This is not a JUnit assertion — it's a CLI shim. The {@code @Test} method
 * checks for a {@code -Dparity.ir.input=...} system property; when absent the
 * test is a trivial no-op so ordinary {@code ./gradlew test} runs aren't
 * affected. When present, the system property points at a stdin-like input
 * file whose lines are absolute paths to {@code .aster} samples; the runner
 * loads each one through the full Java pipeline
 * (Canonicalize → ANTLR Parse → AstBuilder → R1 → CoreLowering → R2), then emits a
 * compact "fingerprint" JSON object per sample to {@code parity.ir.output}.
 *
 * <p>The fingerprint is intentionally structural, not field-by-field:
 * <ul>
 *   <li>{@code declCount} — number of top-level decls in the module</li>
 *   <li>{@code declKinds} — sorted map of {@code kind → count}</li>
 *   <li>{@code declNames} — sorted list of declared symbol names</li>
 *   <li>{@code moduleName} — the module's declared name</li>
 * </ul>
 *
 * <p>Why a fingerprint and not raw JSON parity? The Java and TS Core IRs
 * use different field names in places (e.g. {@code Import.path/alias} vs
 * {@code Import.name/asName}) even though their {@code kind} discriminators
 * align. Aligning every field requires an ADR-level cross-team coordination;
 * the fingerprint catches the gross structural drifts (decl count mismatches,
 * kinds present on one side but not the other, missing names) the Phase B
 * gate cares about today. Field-level parity is Phase B v2.
 *
 * <p>Invocation:
 * <pre>
 *   ./gradlew test --tests CoreIrFingerprintCli \
 *     -Dparity.ir.input=/tmp/samples.txt \
 *     -Dparity.ir.output=/tmp/java-fp.jsonl
 * </pre>
 *
 * <p>The output is JSONL (one fingerprint per line) — easier to stream and
 * parse than a single huge JSON array.
 *
 * <p>输入行的两种形式（ADR 0051 语料自带词汇）：
 * <ul>
 *   <li>纯路径：按 en-US 默认词法、无词汇处理（既有语料）；</li>
 *   <li>JSON 对象 {@code {"path":…,"lexicon":"de-DE","vocabulary":"/abs/x.vocab.json"}}：
 *       按声明的词法表规范化关键词，并以该词汇（DomainVocabulary JSON）做名称解析。</li>
 * </ul>
 * 输出记录另带 {@code diagnostics}（成功时的警告码，如 W800）或 {@code codes}（名称解析失败时的错误码），
 * 以及与之一一对应的 {@code locs: [{code, line, col}]}（诊断起始位置），
 * 供 parity 门比较两引擎的诊断码；语法错误记为 {@code stage: "syntax"} 失败，不再静默按 ANTLR 恢复结果继续。
 */
@Tag("parity-ir")
class CoreIrFingerprintCli {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emitFingerprintsWhenInvoked() throws Exception {
        String inputProp = System.getProperty("parity.ir.input");
        String outputProp = System.getProperty("parity.ir.output");
        if (inputProp == null || outputProp == null) {
            // No-op when run as part of the regular test suite. The parity
            // runner sets both properties before invoking gradle test.
            return;
        }

        Path inputPath = Paths.get(inputProp);
        Path outputPath = Paths.get(outputProp);
        if (!Files.exists(inputPath)) {
            throw new IllegalStateException("parity.ir.input not found: " + inputPath);
        }

        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8);
             var writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                writer.write(MAPPER.writeValueAsString(emitOne(Request.parse(trimmed))));
                writer.newLine();
            }
        }
    }

    /** 单个样本的输入：路径、可选词法表 id、可选词汇文件。 */
    record Request(String path, String lexicon, String vocabulary) {
        static Request parse(String line) throws Exception {
            if (!line.startsWith("{")) return new Request(line, null, null);
            JsonNode n = MAPPER.readTree(line);
            return new Request(n.path("path").asText(""), textOrNull(n.path("lexicon")), textOrNull(n.path("vocabulary")));
        }

        private static String textOrNull(JsonNode n) {
            return n.isTextual() && !n.asText().isEmpty() ? n.asText() : null;
        }
    }

    static ObjectNode emitOne(Request req) {
        ObjectNode record = MAPPER.createObjectNode();
        record.put("path", req.path());
        try {
            String source = Files.readString(Paths.get(req.path()), StandardCharsets.UTF_8);
            VocabularyScopes scopes = req.vocabulary() == null ? null
                : VocabularyScopes.build(VocabularyLoader.loadFromFile(Paths.get(req.vocabulary())));
            // 未声明词法表时保持既有行为（默认构造 = en-US、无词汇）
            Canonicalizer canonicalizer = req.lexicon() == null && scopes == null
                ? new Canonicalizer()
                : new Canonicalizer(LexiconRegistry.getInstance().getOrThrow(req.lexicon() == null ? "en-US" : req.lexicon()), scopes);
            String canonical = canonicalizer.canonicalize(source);

            List<String> syntaxErrors = new ArrayList<>();
            BaseErrorListener collect = new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
                    syntaxErrors.add("L" + line + ":" + col + " " + msg);
                }
            };
            AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
            lexer.removeErrorListeners();
            lexer.addErrorListener(collect);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            tokens.fill();
            tokens.seek(0);

            AsterParser parser = new AsterParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(collect);
            AsterParser.ModuleContext moduleCtx = parser.module();
            if (!syntaxErrors.isEmpty()) {
                record.put("ok", false);
                record.put("stage", "syntax");
                record.put("error", truncate(syntaxErrors.get(0)));
                return record;
            }

            aster.core.ast.Module ast = new AstBuilder().visitModule(moduleCtx);

            // 名称解析 R1 → lower → R2（ADR 0051）；E8xx 时该样本记为失败、不产出 IR
            NameResolution.ResolvedCore resolved =
                NameResolution.resolveAndLowerChecked(ast, new ResolutionContext(scopes, null));
            JsonNode coreJson = MAPPER.valueToTree(resolved.core());
            record.put("ok", true);
            record.set("diagnostics", codesOf(resolved.diagnostics()));
            record.set("locs", locsOf(resolved.diagnostics()));
            record.set("fingerprint", fingerprint(coreJson));
            // ADR 0016 阶段1：-Dparity.ir.full=true 时附带完整 Core IR JSON，
            // 供 parity-tier1.mjs 的归一化字段级比较器消费（默认仍只发指纹，
            // 保持现有 IR-mode 行为不变）。
            if (Boolean.getBoolean("parity.ir.full")) {
                record.set("ir", coreJson);
            }
        } catch (NameResolutionException e) {
            record.put("ok", false);
            record.put("stage", "resolve");
            record.set("codes", codesOf(e.diagnostics()));
            record.set("locs", locsOf(e.diagnostics()));
            record.put("error", truncate(e.getMessage()));
        } catch (Throwable t) {
            // A failure on one sample shouldn't abort the whole batch.
            // The parity runner needs every sample's verdict so it can
            // report which one regressed.
            record.put("ok", false);
            record.put("error", truncate(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
        }
        return record;
    }

    private static ArrayNode codesOf(List<Diagnostic> diagnostics) {
        ArrayNode out = MAPPER.createArrayNode();
        diagnostics.forEach(d -> out.add(d.code().code()));
        return out;
    }

    /**
     * 诊断位置 {@code [{code, line, col}]}，与 {@link #codesOf} 一一对应、顺序相同；无位置的诊断 line/col 为 null。
     * 与 aster-lang-test parity-tier1.mjs 中 TS 侧 {@code locsOf} 同形，供 {@code compareDiagnosticLines} 比较起始行。
     */
    private static ArrayNode locsOf(List<Diagnostic> diagnostics) {
        ArrayNode out = MAPPER.createArrayNode();
        for (Diagnostic d : diagnostics) {
            ObjectNode loc = out.addObject();
            loc.put("code", d.code().code());
            CoreModel.Position start = d.span().map(o -> o.start).orElse(null);
            if (start == null) {
                loc.putNull("line");
                loc.putNull("col");
            } else {
                loc.put("line", start.line);
                loc.put("col", start.col);
            }
        }
        return out;
    }

    private static String truncate(String msg) {
        return msg.length() > 240 ? msg.substring(0, 240) : msg;
    }

    /**
     * Build a compact structural fingerprint of a lowered module. Operates
     * on {@code JsonNode} so the same logic can be ported to the TS side
     * with minimal divergence (just swap MAPPER calls for plain object
     * lookups).
     */
    private static JsonNode fingerprint(JsonNode core) {
        ObjectNode fp = MAPPER.createObjectNode();
        fp.put("moduleName", core.path("name").asText(""));

        JsonNode decls = core.path("decls");
        int declCount = decls.isArray() ? decls.size() : 0;
        fp.put("declCount", declCount);

        TreeMap<String, Integer> kindCounts = new TreeMap<>();
        List<String> names = new ArrayList<>();
        if (decls.isArray()) {
            for (JsonNode decl : decls) {
                String kind = decl.path("kind").asText("Unknown");
                kindCounts.merge(kind, 1, Integer::sum);
                // ★Import 在两引擎里字段名不同：TS 用 `name`/`asName`，
                //   Java 用 `path`/`alias`（见类注释）。此处**只取名字**做指纹，
                //   故必须把两种写法都认出来，否则 Java 侧 Import 恒不入
                //   declNames，浅层指纹会**凭空报出 tsOnly 分歧**。
                //
                //   ★这正是 2026-09-13 审计里 eff_alias_import /
                //   eff_alias_unmapped 两个样本"分歧"的**全部成因**——
                //   底层 IR 其实一致（字段级 `--full` 口径 223/223 identical，
                //   因为 ir-normalize.ts 已有 `Import.name→path` 别名表）。
                //   是**量具**没跟上，不是引擎分叉。
                JsonNode name = decl.path("name");
                if (!name.isTextual()) name = decl.path("path");   // Java 侧 Import
                if (name.isTextual()) names.add(name.asText());
            }
        }
        names.sort(String::compareTo);

        ObjectNode kindsNode = MAPPER.createObjectNode();
        for (var e : kindCounts.entrySet()) kindsNode.put(e.getKey(), e.getValue());
        fp.set("declKinds", kindsNode);

        ArrayNode namesNode = MAPPER.createArrayNode();
        for (String n : names) namesNode.add(n);
        fp.set("declNames", namesNode);

        return fp;
    }
}
