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
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.HashMap;
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

    /**
     * 一条候选及其判定。
     *
     * @param contentHash 目标节点在本次判定时的内容指纹（NodeIdMap 的 contentHash）。人工复核记录
     *                    Proof 时用它锚定「当时看到的是哪一版内容」；与 TS 侧同名字段对等。
     *                    节点不在 NodeIdMap 中时为 {@code null}（diagnostics 会说明路径口径漂移）。
     */
    public record VerifiedCandidate(MappingIr.CandidateMapping mapping,
                                    MappingIr.VerificationResult result,
                                    String contentHash) {}

    /** 第 3 层失败诊断的前缀：两引擎一致，跨引擎语料按前缀断言（具体消息允许不同）。 */
    public static final String SYNTAX_ERROR = "源码有语法错误，无法编译成 Core IR，候选映射层已跳过：";
    public static final String COMPILE_FAILED = "源码无法编译成 Core IR，候选映射层已跳过：";
    public static final String INTERNAL_ERROR = "候选映射层内部错误（不是源码问题）：";

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
        List<VerifiedCandidate> verified = candidateLayer(source, diagnostics);

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
     * 第 3 层：canonicalize → lex → parse → lower → 候选 → 重定位 → 验证。
     *
     * <p>失败分三类，各自如实报告（与 TS 侧 {@code candidateLayer} 同前缀）：
     * <ul>
     *   <li>语法错误：ANTLR 带错误恢复，照样会返回残缺的树；在残缺树上生成的候选不可信，整层跳过。
     *       词法错误同样归入此类。</li>
     *   <li>编译失败：AST 构建或 lowering 以 {@link IllegalStateException} /
     *       {@link IllegalArgumentException} 拒绝源码。</li>
     *   <li>内部错误：其余异常（如 NPE），以及 IR 已经得到之后任何步骤抛出的异常。它们是本链路的
     *       缺陷，不得伪装成「源码编译不了」。</li>
     * </ul>
     */
    private static List<VerifiedCandidate> candidateLayer(String source, List<String> diagnostics) {
        String canonical;
        JsonNode ir;
        try {
            canonical = new Canonicalizer().canonicalize(source);
            List<String> syntaxErrors = new ArrayList<>();
            BaseErrorListener collect = new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> recognizer, Object symbol, int line, int col,
                                        String msg, RecognitionException e) {
                    syntaxErrors.add("L" + line + ":" + (col + 1) + " " + msg);
                }
            };
            AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
            lexer.removeErrorListeners();
            lexer.addErrorListener(collect);
            AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(collect);
            AsterParser.ModuleContext tree = parser.module();
            if (!syntaxErrors.isEmpty()) {
                diagnostics.add(SYNTAX_ERROR + syntaxErrors.get(0));
                return List.of();
            }
            CoreModel.Module core = new CoreLowering().lowerModule(new AstBuilder().visitModule(tree));
            ir = MAPPER.valueToTree(core);
        } catch (IllegalStateException | IllegalArgumentException e) {
            diagnostics.add(COMPILE_FAILED + describe(e));
            return List.of();
        } catch (RuntimeException e) {
            diagnostics.add(INTERNAL_ERROR + describe(e));
            return List.of();
        }

        try {
            Map<String, NodeIdMap.NodeIdentity> identities = NodeIdMap.compute(ir);
            List<CandidateGenerator.OriginatedNode> nodes = CandidateGenerator.collectLiteralNodes(ir);
            if (nodes.isEmpty()) {
                diagnostics.add("IR 里没有带 origin 的字面量节点——无候选可生成。");
            }

            CandidateGenerator.GenerationResult gen = CandidateGenerator.relocateToSource(
                source, canonical, CandidateGenerator.generate(canonical, nodes));
            for (CandidateGenerator.Skipped s : gen.skipped()) {
                diagnostics.add("跳过节点 " + s.nodeId() + "：" + s.why());
            }

            // ★resolve 的节点必须从 **IR 本身**取值，不能从 NodeIdentity 取。
            //   NodeIdentity 只有 {nodeId, contentHash, kind}，**没有 value**；
            //   用它做 resolve 会让 verify 永远比不出值、把候选全判 REJECTED。
            Map<String, MappingIr.VerifiableNode> byId = collectVerifiableNodes(ir);
            List<VerifiedCandidate> out = new ArrayList<>();
            for (MappingIr.CandidateMapping m : gen.candidates()) {
                NodeIdMap.NodeIdentity identity = identities.get(m.nodeId());
                out.add(new VerifiedCandidate(m, MappingIr.verify(m, byId::get),
                    identity == null ? null : identity.contentHash()));
            }
            return out;
        } catch (RuntimeException e) {
            diagnostics.add(INTERNAL_ERROR + describe(e));
            return List.of();
        }
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName()
            : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /**
     * 按 nodeId 路径规则产出 {@code nodeId → VerifiableNode}（带 value/name）。
     * 路径与 nodeId 共用 {@link NodeIdMap#forEachIrNode}，不会漂移。
     */
    private static Map<String, MappingIr.VerifiableNode> collectVerifiableNodes(JsonNode ir) {
        Map<String, MappingIr.VerifiableNode> out = new HashMap<>();
        NodeIdMap.forEachIrNode(ir, (node, nodeId) -> {
            JsonNode value = node.get("value");
            JsonNode name = node.get("name");
            out.put(nodeId, new MappingIr.VerifiableNode(
                node.get("kind").asText(),
                value == null || value.isNull() ? null : toPlain(value),
                name != null && name.isTextual() ? name.asText() : null));
        });
        return out;
    }

    private static Object toPlain(JsonNode v) {
        if (v.isBoolean()) return v.asBoolean();
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isFloatingPointNumber()) return v.asDouble();
        return v.asText();
    }
}
