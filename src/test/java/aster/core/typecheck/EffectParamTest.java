package aster.core.typecheck;

import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreModel;
import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.ParseDiagnostics;
import aster.core.resolve.NameResolution;
import aster.core.resolve.ResolutionContext;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 支持效应参数 {@code Rule f <E>}（ADR 0055 #5、#6；plan Task C4）。语法与 aster-lang-ts {@code parseEffectParams}
 * 相同：效应参数列表写在类型参数之后、形参之前，名字为大写标识符。效应变量在 {@code It performs} 子句中引用，
 * 写在效应位置（{@code It performs E}）或能力位置（{@code io with E}、{@code io and E}、{@code io [Http, E]}）。
 */
class EffectParamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void effect_param_lowered() throws Exception {
        CoreModel.Module m = compile("Module m.\n\nRule f <E> given x as Int, produce Int. It performs E:\n  Return x.\n");
        CoreModel.Func f = func(m, "f");
        assertEquals(List.of("E"), f.effectParams);
        assertEquals(List.of("E"), f.effects);
        assertEquals(List.of(), CoreIrJson.validate(MAPPER.readTree(CoreIrJson.serialize(m))));
    }

    @Test
    void effect_vars_in_capability_position() throws Exception {
        CoreModel.Module m = compile("Module m.\n\n"
            + "Rule a <E> given x as Int, produce Int. It performs io with E:\n  Return x.\n\n"
            + "Rule b <E, F> given x as Int, produce Int. It performs io [Http, F, E]:\n  Return x.\n\n"
            + "Rule c given x as Int, produce Int. It performs IO and CPU:\n  Return x.\n");
        assertEquals(List.of("io", "E"), func(m, "a").effects);
        assertEquals(List.of(), func(m, "a").effectCaps);
        assertEquals(List.of("io", "F", "E"), func(m, "b").effects);
        assertEquals(List.of("E", "F"), func(m, "b").effectParams);
        assertEquals(List.of("Http"), func(m, "b").effectCaps);
        assertEquals(List.of("io", "cpu"), func(m, "c").effects);
        assertEquals(List.of(), CoreIrJson.validate(MAPPER.readTree(CoreIrJson.serialize(m))));
    }

    @Test
    void effect_param_shadowing_builtin_rejected() {
        for (String name : List.of("IO", "Cpu", "Pure", "Async", "ASYNC")) {
            List<Diagnostic> diags = typecheck("Module m.\n\nRule g <" + name + "> given x as Int, produce Int:\n  Return x.\n");
            assertTrue(codes(diags).contains(ErrorCode.EFFECT_PARAM_SHADOWS_BUILTIN), name + "：" + diags);
        }
        List<Diagnostic> ok = typecheck("Module m.\n\nRule f <E> given x as Int, produce Int. It performs io with E:\n  Return x.\n");
        assertFalse(codes(ok).contains(ErrorCode.EFFECT_PARAM_SHADOWS_BUILTIN), ok.toString());
    }

    @Test
    void shadowing_param_keeps_builtin_meaning_in_effects() {
        CoreModel.Func f = func(compile("Module m.\n\nRule g <IO> given x as Int, produce Int. It performs IO:\n  Return x.\n"), "g");
        assertEquals(List.of("IO"), f.effectParams);
        assertEquals(List.of("io"), f.effects, "内建名优先于效应参数");
    }

    @Test
    void undeclared_effect_var_e210() {
        var parsed = ParseDiagnostics.parse("Module m.\n\nRule f given x as Int, produce Int. It performs E:\n  Return x.\n",
            LexiconRegistry.getInstance().getOrThrow("en-US"));
        assertNull(parsed.module());
        assertEquals(List.of(ErrorCode.EFFECT_VAR_UNDECLARED), parsed.diagnostics().stream().map(d -> d.code()).toList());
        assertEquals(3, parsed.diagnostics().get(0).line());
    }

    @Test
    void unresolved_effect_var_e211() {
        List<Diagnostic> diags = typecheck("Module m.\n\nRule f <E> given x as Int, produce Int. It performs E:\n  Return x.\n");
        assertEquals(List.of(ErrorCode.EFFECT_VAR_UNRESOLVED), codes(diags));
        assertTrue(diags.get(0).message().contains("E"), diags.toString());
    }

    @Test
    void effect_var_binds_to_body_effect() {
        String src = "Module m.\n\n"
            + "Rule fetch given x as Int, produce Int. It performs io:\n  Return x.\n\n"
            + "Rule relay <E> given x as Int, produce Int. It performs E:\n  Return fetch(x).\n\n";
        assertEquals(List.of(), codes(typecheck(src)), "效应变量绑定到函数体的实际效应，不报缺少 io，也不报 E211");
        List<Diagnostic> pureCaller = typecheck(src + "Rule caller given x as Int, produce Int:\n  Return relay(x).\n");
        assertTrue(codes(pureCaller).contains(ErrorCode.EFF_MISSING_IO), "调用方看到效应变量的绑定结果：" + pureCaller);
        List<Diagnostic> ioCaller = typecheck(src + "Rule caller given x as Int, produce Int. It performs io:\n  Return relay(x).\n");
        assertFalse(codes(ioCaller).contains(ErrorCode.EFF_MISSING_IO), ioCaller.toString());
    }

    @Test
    void effect_param_list_accepts_and_separator() {
        CoreModel.Func f = func(compile("Module m.\n\nRule f <E and F> given x as Int, produce Int. It performs io with E and F:\n  Return x.\n"), "f");
        assertEquals(List.of("E", "F"), f.effectParams);
        assertEquals(List.of("io", "E", "F"), f.effects);
    }

    @Test
    void effect_param_list_accepts_comma_separator() {
        CoreModel.Func f = func(compile("Module m.\n\nRule f <E, F> given x as Int, produce Int. It performs F:\n  Return x.\n"), "f");
        assertEquals(List.of("E", "F"), f.effectParams);
        assertEquals(List.of("F"), f.effects);
    }

    /** 同一函数的效应参数共享一个绑定（与 TS updateBindingsWithEffect 一致）：有具体效应即全部绑定，否则一并列出。 */
    @Test
    void all_effect_params_of_a_function_share_one_binding() {
        assertEquals(List.of(), codes(typecheck("Module m.\n\nRule f <E, F> given x as Int, produce Int. It performs io with E:\n  Return x.\n")));
        List<Diagnostic> unresolved = typecheck("Module m.\n\nRule f <E, F> given x as Int, produce Int. It performs E:\n  Return x.\n");
        assertEquals(List.of(ErrorCode.EFFECT_VAR_UNRESOLVED), codes(unresolved));
        assertTrue(unresolved.get(0).message().contains("E, F"), unresolved.toString());
    }

    @Test
    void bodyless_declaration_is_checked_for_e211() {
        List<Diagnostic> diags = typecheck("Module m.\n\nRule f <E> given x as Int, produce Int.\n");
        assertEquals(List.of(ErrorCode.EFFECT_VAR_UNRESOLVED), codes(diags));
    }

    /** 效应参数名大小写敏感：小写的 e 不是 <E>，按未知效应名处理（不写入），E 因此绑定不到具体效应。 */
    @Test
    void effect_param_reference_is_case_sensitive() {
        CoreModel.Func f = func(compile("Module m.\n\nRule f <E> given x as Int, produce Int. It performs e:\n  Return x.\n"), "f");
        assertEquals(List.of(), f.effects);
        assertEquals(List.of(ErrorCode.EFFECT_VAR_UNRESOLVED),
            codes(typecheck("Module m.\n\nRule f <E> given x as Int, produce Int. It performs e:\n  Return x.\n")));
    }

    // ---------- 工具 ----------

    private static CoreModel.Module compile(String source) {
        var parsed = ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow("en-US"));
        assertEquals(List.of(), parsed.diagnostics(), "解析");
        return NameResolution.resolveAndLowerChecked(parsed.module(), ResolutionContext.none()).core();
    }

    private static List<Diagnostic> typecheck(String source) {
        return new TypeChecker().typecheckModule(compile(source)).stream()
            .filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList();
    }

    private static List<ErrorCode> codes(List<Diagnostic> ds) {
        return ds.stream().map(Diagnostic::code).toList();
    }

    private static CoreModel.Func func(CoreModel.Module m, String name) {
        return m.decls.stream().filter(d -> d instanceof CoreModel.Func f && f.name.equals(name))
            .map(CoreModel.Func.class::cast).findFirst().orElseThrow();
    }
}
