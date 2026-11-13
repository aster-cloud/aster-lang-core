package aster.core.typecheck.governance;

import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreModel;
import aster.core.resolve.DeclarationResolverCorpusTest;
import aster.core.resolve.NameResolution;
import aster.core.resolve.ResolutionContext;
import aster.core.typecheck.TypeChecker;
import aster.core.typecheck.model.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注解读方经有序的 {@code Annotation.args} 取参数（ADR 0055 #3；plan Task C3）。
 * IR 先按 v2 规范序列化、再经 {@link CoreIrJson#read} 严格读回，读方在读回的模块上必须得到与内存模块相同的结果。
 */
class GovernanceCheckerArgsTest {

    private static final String SRC = "Module probe.\n\n"
        + "@control(\"A\")\n@id(\"R-1\")\n@control(\"B\")\n"
        + "Rule decide given amount as Int, produce Verdict:\n  Return Verdict.allow().\n";

    @Test
    void reads_dollar0_from_args() throws Exception {
        CoreModel.Func f = func(roundTrip(GovernanceTestSupport.resolveSource(SRC, null, null)), "decide");
        assertEquals(List.of("control", "id", "control"), f.annotations.stream().map(a -> a.name).toList());
        assertEquals(Optional.of("R-1"), GovernanceChecker.ruleId(f));
        assertEquals(List.of("A", "B"), GovernanceChecker.controls(f));
    }

    @Test
    void named_argument_is_not_dollar0() throws Exception {
        CoreModel.Func f = func(roundTrip(GovernanceTestSupport.resolveSource(
            SRC.replace("@id(\"R-1\")", "@id(name: \"R-1\")"), null, null)), "decide");
        assertEquals(Optional.empty(), GovernanceChecker.ruleId(f));
    }

    @Test
    void extra_argument_makes_annotation_invalid() throws Exception {
        CoreModel.Func f = func(roundTrip(GovernanceTestSupport.resolveSource(
            SRC.replace("@id(\"R-1\")", "@id(\"R-1\", \"R-2\")"), null, null)), "decide");
        assertEquals(Optional.empty(), GovernanceChecker.ruleId(f));
    }

    @Test
    void pii_annotation_args_by_position_and_name() throws Exception {
        CoreModel.Module m = roundTrip(GovernanceTestSupport.resolveSource("Module probe.\n\n"
            + "Rule mask given a as @pii(L2, email) Text, b as @pii(level: L3, category: phone) Text, produce Text:\n"
            + "  Return a.\n", null, null));
        CoreModel.Func f = func(m, "mask");
        CoreModel.PiiType a = (CoreModel.PiiType) f.params.get(0).type;
        CoreModel.PiiType b = (CoreModel.PiiType) f.params.get(1).type;
        assertEquals(List.of("L2", "email"), List.of(a.sensitivity, a.category));
        assertEquals(List.of("L3", "phone"), List.of(b.sensitivity, b.category));
    }

    /**
     * 全语料回归：同一 Core 在内存中与经 v2 规范序列化、严格读回之后，类型检查（含治理、PII、效应）的诊断
     * 与 @id / @control 读取结果完全相同。
     */
    @Test
    void corpus_typecheck_is_unchanged_after_v2_round_trip() throws Exception {
        List<String> diffs = new ArrayList<>();
        int checked = 0;
        for (DeclarationResolverCorpusTest.Parsed p : DeclarationResolverCorpusTest.parseCorpus("args")) {
            NameResolution.ResolvedCore resolved = NameResolution.resolveAndLower(p.module(), ResolutionContext.none());
            if (NameResolution.hasErrors(resolved.diagnostics())) continue;
            CoreModel.Module memory = resolved.core();
            CoreModel.Module back = roundTrip(memory);
            checked++;
            if (!describe(new TypeChecker().typecheckModule(memory)).equals(describe(new TypeChecker().typecheckModule(back)))) {
                diffs.add(p.file() + "：类型检查诊断不同");
            }
            for (int i = 0; i < memory.decls.size(); i++) {
                if (memory.decls.get(i) instanceof CoreModel.Func f) {
                    CoreModel.Func g = (CoreModel.Func) back.decls.get(i);
                    if (!GovernanceChecker.ruleId(f).equals(GovernanceChecker.ruleId(g))
                        || !GovernanceChecker.controls(f).equals(GovernanceChecker.controls(g))) {
                        diffs.add(p.file() + " " + f.name + "：@id / @control 读取不同");
                    }
                }
            }
        }
        assertEquals(List.of(), diffs);
        assertTrue(checked > 100, "纳入的语料过少：" + checked);
    }

    private static List<String> describe(List<Diagnostic> ds) {
        return ds.stream().map(d -> d.code().code() + "@" + d.span().map(o -> o.start.line + ":" + o.start.col).orElse("-")
            + " " + d.message()).toList();
    }

    private static CoreModel.Module roundTrip(CoreModel.Module m) throws Exception {
        return CoreIrJson.read(CoreIrJson.serialize(m));
    }

    private static CoreModel.Func func(CoreModel.Module m, String name) {
        return m.decls.stream().filter(d -> d instanceof CoreModel.Func f && f.name.equals(name))
            .map(CoreModel.Func.class::cast).findFirst().orElseThrow();
    }
}
