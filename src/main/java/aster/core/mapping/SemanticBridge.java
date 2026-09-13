package aster.core.mapping;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.nodeid.NodeIdMap;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 可验证语义桥的<b>端到端接线</b>（ADR 0037 §7）—— <b>Java 侧</b>。
 *
 * <p>与 TS 侧 {@code src/mapping/pipeline.ts} 的 {@code runSemanticBridge} 对等。
 *
 * <h2>★为什么两侧都要有主链路</h2>
 *
 * §7 只要求 <b>verifier 的判定</b>一致，那说的是「同一候选两侧判一样」。
 * 但<b>能力</b>是另一回事：只用 Java 引擎的用户同样需要语义桥。
 * 只在 TS 侧接线，等于这部分用户拿不到该能力。
 *
 * <h2>链路</h2>
 *
 * <pre>
 *   人类文档
 *     ├─ SourceIr.parse ─────────► 文档结构
 *     ├─ QuantityIr.extract ─────► 数量实体（确定性，零 AI）
 *     └─ canonicalize→lex→parse→lower
 *           ├─ NodeIdMap.compute ──► 稳定节点 ID
 *           ├─ CandidateGenerator ─► 候选映射（机械切片，不猜）
 *           └─ MappingIr.verify ───► VERIFIED / REVIEW_REQUIRED / REJECTED
 * </pre>
 *
 * <p>★<b>分层降级</b>：前两层对<b>任意文本</b>都成立（只依赖形态特征），
 * 第 3 层需要可编译源码。编译失败时前两层结果<b>保留</b>，
 * 并在 {@link BridgeResult#diagnostics()} 里说明第 3 层为何跳过。
 *
 * <h2>★本类刻意不做的事</h2>
 *
 * <ul>
 *   <li><b>不调 LLM</b> —— Entity 提出是三段式第①段，由调用方显式驱动。
 *       把 LLM 埋进主链路会让一条本该确定性的流水线变得不可复现。</li>
 *   <li><b>不产出 Proof</b> —— {@code REVIEW_REQUIRED} 必须由<b>人</b>给出
 *       （{@code ProofSubject.DOMAIN_EXPERT}），机器不得代签（ADR §3）。</li>
 *   <li><b>不吞错</b> —— 任何跳过/失败都进 diagnostics。</li>
 * </ul>
 */
public final class SemanticBridge {

    /** 一条候选及其判定。 */
    public record VerifiedCandidate(MappingIr.CandidateMapping mapping,
                                    MappingIr.VerificationResult result) {}

    /** 三类判定的计数——UI 的复核队列直接用它。 */
    public record Summary(int verified, int reviewRequired, int rejected) {}

    /**
     * 端到端结果。
     *
     * @param sourceIr    文档结构；解析失败时为 {@code null}
     * @param quantities  确定性抽取的数量实体
     * @param verified    候选映射及其判定
     * @param summary     按判定分组的计数
     * @param diagnostics 所有「没做成」的事。★必须如实报告——静默跳过会让
     *                    调用方以为「全都生成了」，而某些节点其实已从双向导航里消失
     */
    public record BridgeResult(SourceIr.SourceNode sourceIr,
                               List<QuantityIr.Quantity> quantities,
                               List<VerifiedCandidate> verified,
                               Summary summary,
                               List<String> diagnostics) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SemanticBridge() {}

    /** 跑通「人类文档 → 结构化 → 抽取 → 候选 → 验证」全链路。 */
    public static BridgeResult run(String source) {
        List<String> diagnostics = new ArrayList<>();

        // ── 第 1 层：人类文档结构（对任意文本都成立）────────────────
        SourceIr.SourceNode sourceIr = null;
        try {
            sourceIr = SourceIr.parse(source);
        } catch (RuntimeException e) {
            diagnostics.add("SourceIR 解析失败：" + e.getMessage());
        }

        // ── 第 2 层：确定性数量抽取（对任意文本都成立，零 AI）───────
        List<QuantityIr.Quantity> quantities = List.of();
        try {
            quantities = QuantityIr.extract(source);
        } catch (RuntimeException e) {
            diagnostics.add("QuantityIR 抽取失败：" + e.getMessage());
        }

        // ── 第 3 层：候选映射 + 验证（需要源码可编译）───────────────
        // ★这一层**会失败**，且失败是常态（任意文字不是合法 Aster 源码）。
        //   失败时前两层结果仍然有效——这正是分层降级的意义。
        List<VerifiedCandidate> verified = new ArrayList<>();
        try {
            String canonical = new Canonicalizer().canonicalize(source);
            AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
            lexer.removeErrorListeners();
            AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            CoreModel.Module core =
                new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));
            JsonNode ir = MAPPER.valueToTree(core);

            Map<String, NodeIdMap.NodeIdentity> identities = NodeIdMap.compute(ir);
            List<CandidateGenerator.OriginatedNode> nodes =
                CandidateGenerator.collectLiteralNodes(ir);
            if (nodes.isEmpty()) {
                diagnostics.add("IR 里没有带 origin 的字面量节点——无候选可生成。");
            }

            CandidateGenerator.GenerationResult gen =
                CandidateGenerator.generate(canonical, nodes);
            for (CandidateGenerator.Skipped s : gen.skipped()) {
                diagnostics.add("跳过节点 " + s.nodeId() + "：" + s.why());
            }

            // ★resolve 的节点必须从 **IR 本身**取值，不能从 NodeIdentity 取。
            //   NodeIdentity 只有 {nodeId, contentHash, kind}，**没有 value**；
            //   用它做 resolve 会让 verify 永远比不出值、把候选全判 REJECTED。
            //   ★TS 侧接线时我正是踩了这个坑：两侧模块单测全绿，错的是那根线。
            Map<String, MappingIr.VerifiableNode> byId = collectVerifiableNodes(ir);
            for (String id : byId.keySet()) {
                if (!identities.containsKey(id)) {
                    diagnostics.add("节点 " + id + " 不在 NodeIdMap 中——路径口径可能漂移。");
                }
            }

            for (MappingIr.CandidateMapping m : gen.candidates()) {
                verified.add(new VerifiedCandidate(m, MappingIr.verify(m, byId::get)));
            }
        } catch (RuntimeException e) {
            // ★不把编译失败当成错误——"任意文字"本就编译不了。
            //   如实记录，让调用方知道第 3 层没跑，而不是以为跑了但没结果。
            diagnostics.add("源码无法编译成 Core IR，候选映射层已跳过："
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }

        int v = 0, r = 0, j = 0;
        for (VerifiedCandidate c : verified) {
            switch (c.result().verdict()) {
                case VERIFIED -> v++;
                case REVIEW_REQUIRED -> r++;
                case REJECTED -> j++;
            }
        }

        return new BridgeResult(sourceIr, quantities, List.copyOf(verified),
            new Summary(v, r, j), List.copyOf(diagnostics));
    }

    /**
     * 按与 {@link CandidateGenerator#collectLiteralNodes} <b>相同</b>的路径规则
     * 遍历 IR，产出 {@code nodeId → VerifiableNode}（带 value/name）。
     *
     * <p>★路径规则必须与 {@code CandidateGenerator} / {@code NodeIdMap}
     * <b>三方一致</b>，否则候选的 nodeId 与这里的键对不上。
     */
    private static Map<String, MappingIr.VerifiableNode> collectVerifiableNodes(JsonNode ir) {
        Map<String, MappingIr.VerifiableNode> out = new HashMap<>();
        walkVerifiable(ir, "$", out);
        return out;
    }

    private static void walkVerifiable(JsonNode node, String path,
                                       Map<String, MappingIr.VerifiableNode> out) {
        if (node == null || !node.isObject()) return;

        JsonNode kind = node.get("kind");
        if (kind != null && kind.isTextual()) {
            JsonNode value = node.get("value");
            JsonNode name = node.get("name");
            out.put(path, new MappingIr.VerifiableNode(
                kind.asText(),
                value == null || value.isNull() ? null : toPlain(value),
                name != null && name.isTextual() ? name.asText() : null));
        }

        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if ("origin".equals(e.getKey())) continue;
            JsonNode v = e.getValue();
            if (v.isArray()) {
                for (int i = 0; i < v.size(); i++) {
                    walkVerifiable(v.get(i), path + "." + e.getKey() + segmentOf(v.get(i), i), out);
                }
            } else {
                walkVerifiable(v, path + "." + e.getKey(), out);
            }
        }
    }

    /** ★与 CandidateGenerator / NodeIdMap 同规则（三方必须一致）。 */
    private static String segmentOf(JsonNode element, int index) {
        if (element != null && element.isObject()) {
            JsonNode name = element.get("name");
            if (name != null && name.isTextual() && !"_".equals(name.asText())) {
                return "{" + name.asText() + "}";
            }
            JsonNode p = element.get("path");
            if (p != null && p.isTextual()) return "{" + p.asText() + "}";
        }
        return "[" + index + "]";
    }

    /** JSON → Java 值。★口径须与 TS 侧一致，否则"值比较"两边不同。 */
    private static Object toPlain(JsonNode v) {
        if (v.isBoolean()) return v.asBoolean();
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isFloatingPointNumber()) return v.asDouble();
        return v.asText();
    }
}
