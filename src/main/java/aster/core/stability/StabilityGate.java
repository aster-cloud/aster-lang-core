package aster.core.stability;

import aster.core.ir.CoreModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * StabilityGate — 编译器强制 Stable/Experimental 边界（ADR 0031，P0-C）——权威（Java）侧。
 *
 * 必须与 aster-lang-ts 的 {@code src/stability/stability_gate.ts} 产出**同一 featureId 集**
 * （M1 exit 硬门：TS↔Java parity fixture）。扫 **Core IR** 找 5 类 Experimental 特性产 W600
 * 诊断，warn 默认 + strict surface 可拒。
 *
 * <p>为何扫 Core IR：aster-api 生产路径不走 typecheck，gate 挂 typecheck=假门禁。用显式
 * switch（sealed interface 穷尽），与 TS 的 DefaultCoreVisitor 显式遍历对齐。
 *
 * <p>5 类检测（与 TS 逐字对齐）：
 * <ul>
 *   <li>Workflow：Stmt Start/Wait/Workflow + Expr Await</li>
 *   <li>version-import：Import.version != null</li>
 *   <li>effect-capabilities：Func.effectCapsExplicit == true</li>
 *   <li>PII：PiiType 节点（递归类型树）+ Func.piiLevel 非空兜底</li>
 *   <li>deprecated-annotation：annotation.name ∈ {example, deprecated}（大小写不敏感）</li>
 * </ul>
 */
public final class StabilityGate {

    /** W600 诊断码（monorepo shared/error_codes.json 单源；本类用常量对齐 TS 本地常量）。 */
    public static final String STABILITY_EXPERIMENTAL_CODE = "W600";

    private StabilityGate() {}

    /** 机器可读特性标识（与 TS StabilityFeatureId 逐字一致）。 */
    public enum FeatureId {
        WORKFLOW("workflow"),
        VERSION_IMPORT("version-import"),
        EFFECT_CAPABILITIES("effect-capabilities"),
        PII("pii"),
        DEPRECATED_ANNOTATION("deprecated-annotation");

        private final String id;

        FeatureId(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record Options(boolean strict, boolean allowExperimental) {
        public static Options warnMode() {
            return new Options(false, false);
        }

        public static Options strictMode() {
            return new Options(true, false);
        }
    }

    /** 稳定性诊断（W600）。severity 恒 warning；strict 语义走 blocking。 */
    public record Diagnostic(
            String code,
            String severity,
            FeatureId featureId,
            String moduleName,
            boolean strict,
            boolean blocking,
            String nodeKind,
            CoreModel.Origin origin) {}

    /** 遍历状态：收集诊断 + 去重（有 origin 用位置，无 origin 用递增序号）。 */
    private static final class ScanState {
        final boolean strict;
        final String moduleName;
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        int seq = 0;

        ScanState(boolean strict, String moduleName) {
            this.strict = strict;
            this.moduleName = moduleName;
        }
    }

    /**
     * 扫 Core Module 找 5 类 Experimental 特性。每个触发节点一条诊断。
     * 返回按 decls 顺序 + 每 decl 内 DFS 顺序（与 TS 一致，parity 稳定）。
     */
    public static List<Diagnostic> scan(CoreModel.Module module, Options options) {
        if (options.allowExperimental()) {
            return List.of();
        }
        ScanState st = new ScanState(options.strict(), module == null ? null : module.name);
        if (module != null && module.decls != null) {
            for (CoreModel.Decl decl : module.decls) {
                scanDecl(st, decl);
            }
        }
        return st.diagnostics;
    }

    /** strict surface 判断是否应拒绝（有 W600 且 strict）。 */
    public static boolean shouldReject(List<Diagnostic> diagnostics, boolean strict) {
        return strict && diagnostics.stream().anyMatch(d -> STABILITY_EXPERIMENTAL_CODE.equals(d.code()));
    }

    private static void emit(ScanState st, FeatureId featureId, CoreModel.Origin origin, String nodeKind) {
        String posKey;
        if (origin != null && origin.start != null && origin.end != null) {
            posKey = origin.start.line + ":" + origin.start.col + "-" + origin.end.line + ":" + origin.end.col;
        } else {
            posKey = "seq" + (st.seq++);
        }
        String key = featureId.id() + "|" + nodeKind + "|" + posKey;
        if (!st.seen.add(key)) {
            return;
        }
        st.diagnostics.add(new Diagnostic(
                STABILITY_EXPERIMENTAL_CODE,
                "warning",
                featureId,
                st.moduleName,
                st.strict,
                st.strict,
                nodeKind,
                origin));
    }

    private static void scanDecl(ScanState st, CoreModel.Decl decl) {
        switch (decl) {
            case CoreModel.Import imp -> {
                // 2. version-import：Import.version != null。
                if (imp.version != null) {
                    emit(st, FeatureId.VERSION_IMPORT, imp.origin, "Import");
                }
            }
            case CoreModel.Data data -> {
                // 4. PII：字段类型树。
                if (data.fields != null) {
                    for (CoreModel.Field f : data.fields) {
                        scanType(st, f.type);
                    }
                }
            }
            case CoreModel.Func func -> scanFunc(st, func);
            case CoreModel.Enum ignored -> {
                // Enum 无 Experimental 信号。
            }
        }
    }

    private static void scanFunc(ScanState st, CoreModel.Func func) {
        // 3. effect-capabilities：effectCapsExplicit == true（裸 @io 是 false）。
        if (func.effectCapsExplicit) {
            emit(st, FeatureId.EFFECT_CAPABILITIES, func.origin, "Func");
        }

        // 5. deprecated-annotation：annotation.name ∈ {example, deprecated}（大小写不敏感）。
        // ★严格镜像 TS：只扫 Func.annotations，不扫 retAnnotations（TS 无 retAnnotations 概念，
        // 扫它会 Java-only 误报；@pii 返回值走 ret 的 PiiType 不受影响，Codex 复审）。
        for (CoreModel.Annotation anno : func.annotations) {
            if (isExperimentalAnnotation(anno.name)) {
                emit(st, FeatureId.DEPRECATED_ANNOTATION, func.origin, "Annotation");
            }
        }

        // 4. PII 源信号：params/ret 类型树里的 PiiType。
        if (func.params != null) {
            for (CoreModel.Param p : func.params) {
                scanType(st, p.type);
            }
        }
        scanType(st, func.ret);
        // 兜底：签名类型树无 PiiType 但 piiLevel 非空 → 报一条（Java piiLevel 默认 ""）。
        if (!signatureHasPii(func) && func.piiLevel != null && !func.piiLevel.isEmpty()) {
            emit(st, FeatureId.PII, func.origin, "Func");
        }

        // 1. Workflow：函数体递归。
        scanBlock(st, func.body);
    }

    private static boolean isExperimentalAnnotation(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.equals("example") || lower.equals("deprecated");
    }

    /** 4. PII：递归类型树找 PiiType，命中即 emit。 */
    private static void scanType(ScanState st, CoreModel.Type type) {
        if (type == null) {
            return;
        }
        if (type instanceof CoreModel.PiiType pii) {
            emit(st, FeatureId.PII, pii.origin, "PiiType");
        }
        for (CoreModel.Type child : childTypes(type)) {
            scanType(st, child);
        }
    }

    /** Func 签名（params + ret，不含 body）类型树是否含 PiiType（与 TS funcSignatureHasPii 对齐）。 */
    private static boolean signatureHasPii(CoreModel.Func func) {
        if (func.params != null) {
            for (CoreModel.Param p : func.params) {
                if (containsPii(p.type)) {
                    return true;
                }
            }
        }
        return containsPii(func.ret);
    }

    private static boolean containsPii(CoreModel.Type type) {
        if (type == null) {
            return false;
        }
        if (type instanceof CoreModel.PiiType) {
            return true;
        }
        for (CoreModel.Type child : childTypes(type)) {
            if (containsPii(child)) {
                return true;
            }
        }
        return false;
    }

    /** 取类型节点的直接子类型（字段名与 TS childTypes 逐字对齐）。 */
    private static List<CoreModel.Type> childTypes(CoreModel.Type t) {
        List<CoreModel.Type> out = new ArrayList<>();
        switch (t) {
            case CoreModel.PiiType pii -> add(out, pii.baseType);
            case CoreModel.Maybe m -> add(out, m.type);
            case CoreModel.Option o -> add(out, o.type);
            case CoreModel.ListT l -> add(out, l.type);
            case CoreModel.Result r -> {
                add(out, r.ok);
                add(out, r.err);
            }
            case CoreModel.MapT mp -> {
                add(out, mp.key);
                add(out, mp.val);
            }
            case CoreModel.TypeApp app -> {
                if (app.args != null) {
                    out.addAll(app.args);
                }
            }
            case CoreModel.FuncType ft -> {
                if (ft.params != null) {
                    out.addAll(ft.params);
                }
                add(out, ft.ret);
            }
            // TypeName / TypeVar 是叶子。
            default -> {
            }
        }
        return out;
    }

    private static void add(List<CoreModel.Type> out, CoreModel.Type t) {
        if (t != null) {
            out.add(t);
        }
    }

    /** 递归扫语句块找 Workflow 信号。 */
    private static void scanBlock(ScanState st, CoreModel.Block block) {
        if (block == null || block.statements == null) {
            return;
        }
        for (CoreModel.Stmt stmt : block.statements) {
            scanStatement(st, stmt);
        }
    }

    private static void scanStatement(ScanState st, CoreModel.Stmt stmt) {
        switch (stmt) {
            case CoreModel.Start start -> {
                emit(st, FeatureId.WORKFLOW, start.origin, "Start");
                // ★递归 Start.expr（与 TS super.visitStatement 一致）——内嵌 Await 等须检出，
                // 否则 Start(expr=Await) 时 Java 只报 Start、TS 报 Start+Await（Codex 复审）。
                scanExpression(st, start.expr);
            }
            case CoreModel.Wait wait -> emit(st, FeatureId.WORKFLOW, wait.origin, "Wait");
            case CoreModel.Workflow wf -> {
                emit(st, FeatureId.WORKFLOW, wf.origin, "workflow");
                if (wf.steps != null) {
                    for (CoreModel.Step step : wf.steps) {
                        scanBlock(st, step.body);
                        scanBlock(st, step.compensate);
                    }
                }
            }
            case CoreModel.If iff -> {
                scanExpression(st, iff.cond);
                scanBlock(st, iff.thenBlock);
                scanBlock(st, iff.elseBlock);
            }
            case CoreModel.Match match -> {
                scanExpression(st, match.expr);
                if (match.cases != null) {
                    for (CoreModel.Case c : match.cases) {
                        // Java Case.body 是 Stmt（可能是 Return / Block / 其它语句）。
                        scanStatement(st, c.body);
                    }
                }
            }
            case CoreModel.Scope scope -> {
                if (scope.statements != null) {
                    for (CoreModel.Stmt s : scope.statements) {
                        scanStatement(st, s);
                    }
                }
            }
            case CoreModel.Block block -> scanBlock(st, block);
            case CoreModel.Let let -> scanExpression(st, let.expr);
            case CoreModel.Set set -> scanExpression(st, set.expr);
            case CoreModel.Return ret -> scanExpression(st, ret.expr);
        }
    }

    private static void scanExpression(ScanState st, CoreModel.Expr expr) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case CoreModel.Await await -> {
                emit(st, FeatureId.WORKFLOW, await.origin, "Await");
                scanExpression(st, await.expr);
            }
            case CoreModel.Call call -> {
                scanExpression(st, call.target);
                if (call.args != null) {
                    for (CoreModel.Expr a : call.args) {
                        scanExpression(st, a);
                    }
                }
            }
            case CoreModel.Construct ctor -> {
                if (ctor.fields != null) {
                    for (CoreModel.FieldInit f : ctor.fields) {
                        scanExpression(st, f.expr);
                    }
                }
            }
            case CoreModel.Ok ok -> scanExpression(st, ok.expr);
            case CoreModel.Err err -> scanExpression(st, err.expr);
            case CoreModel.Some some -> scanExpression(st, some.expr);
            case CoreModel.Lambda lam -> {
                // Lambda 参数/返回类型可能含 PiiType。
                if (lam.params != null) {
                    for (CoreModel.Param p : lam.params) {
                        scanType(st, p.type);
                    }
                }
                scanType(st, lam.ret);
                scanBlock(st, lam.body);
            }
            case CoreModel.IfE ife -> {
                scanExpression(st, ife.cond);
                scanExpression(st, ife.thenE);
                scanExpression(st, ife.elseE);
            }
            case CoreModel.ListE lit -> {
                if (lit.elements != null) {
                    for (CoreModel.Expr el : lit.elements) {
                        scanExpression(st, el);
                    }
                }
            }
            // 叶子表达式（Name/Bool/IntE/StringE/NullE/NoneE/Err 等）无子节点或无 Experimental 信号。
            default -> {
            }
        }
    }
}
