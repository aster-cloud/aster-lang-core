package aster.core.resolve;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.TypeChecker;
import aster.core.typecheck.model.Diagnostic;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 无大小写文字（中文等）的裸模式名（ADR 0051 裁决 R55，aster-lang-core#227）。
 *
 * <p>成员还是绑定由 R2 按 R12 判定（主语为枚举时查该枚举的成员，主语类型未知时查全部枚举成员的精确写法，
 * 其余为绑定），两引擎一致；R2 再按判定结果给出统一的 IR 形态：成员为无参数 PatCtor，绑定为 PatName。
 * 首字母为 ASCII 大写的名字 IR 不变；de 以变音字母开头的成员同样产出 PatCtor。
 * 用例与 aster-lang-ts 的 caseless-pattern-name.test.ts 同输入同断言。
 */
class CaselessPatternNameTest {

    private static final String ENUM_ZH = "模块 探针.样例。\n\n定义 状态 为以下之一 待处理、进行中、已删除。\n\n";

    private static NameResolution.ResolvedCore compile(String lexicon, String source) {
        String canonical = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow(lexicon), null).canonicalize(source);
        AsterParser parser = new AsterParser(new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical))));
        return NameResolution.resolveAndLower(new AstBuilder().visitModule(parser.module()), new ResolutionContext(null, null));
    }

    /** 名称解析与类型检查都没有错误，返回 Core。 */
    private static CoreModel.Module compileClean(String lexicon, String source) {
        NameResolution.ResolvedCore resolved = compile(lexicon, source);
        assertEquals(List.of(), errors(resolved.diagnostics()), "名称解析");
        assertEquals(List.of(), errors(new TypeChecker().typecheckModule(resolved.core())), "类型检查");
        return resolved.core();
    }

    private static List<String> errors(List<Diagnostic> ds) {
        return ds.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR)
            .map(d -> d.code().code() + " " + d.message()).toList();
    }

    private static List<CoreModel.Pattern> patterns(CoreModel.Module core, String func) {
        CoreModel.Func fn = core.decls.stream().filter(d -> d instanceof CoreModel.Func f && f.name.equals(func))
            .map(CoreModel.Func.class::cast).findFirst().orElseThrow();
        CoreModel.Match match = fn.body.statements.stream().filter(CoreModel.Match.class::isInstance)
            .map(CoreModel.Match.class::cast).findFirst().orElseThrow();
        return match.cases.stream().map(c -> c.pattern).toList();
    }

    private static void assertVariant(String name, CoreModel.Pattern p) {
        CoreModel.PatCtor ctor = assertInstanceOf(CoreModel.PatCtor.class, p, "成员应为无参数 PatCtor：" + name);
        assertEquals(name, ctor.typeName);
        assertEquals(List.of(), ctor.names == null ? List.of() : ctor.names);
        assertEquals(List.of(), ctor.args == null ? List.of() : ctor.args);
    }

    private static void assertBinding(String name, CoreModel.Pattern p) {
        assertEquals(name, assertInstanceOf(CoreModel.PatName.class, p, "绑定应为 PatName：" + name).name);
    }

    @Test
    void memberOfTheSubjectEnumIsAVariantAndOtherNamesBind() {
        CoreModel.Module core = compileClean("zh-CN", ENUM_ZH
            + "规则 描述 给定 s 作为 状态，产出 整数：\n  匹配于 s：\n    当 待处理，返回 1。\n    当 进行中，返回 2。\n"
            + "    当 其余，返回 9。\n");
        List<CoreModel.Pattern> ps = patterns(core, "描述");
        assertVariant("待处理", ps.get(0));
        assertVariant("进行中", ps.get(1));
        assertBinding("其余", ps.get(2));
    }

    @Test
    void nameUnderANonEnumSubjectIsABinding() {
        CoreModel.Module core = compileClean("zh-CN", ENUM_ZH
            + "规则 分类 给定 n 作为 整数，产出 整数：\n  匹配于 n：\n    当 0，返回 100。\n    当 其他，返回 其他 加上 1。\n");
        assertBinding("其他", patterns(core, "分类").get(1));
    }

    @Test
    void sameNameAsAnEnumMemberStillBindsUnderANonEnumSubject() {
        // 主语类型已知且不是枚举：即使名字与某枚举成员同名也是绑定（R12，与 TS 一致）
        CoreModel.Module core = compileClean("zh-CN", ENUM_ZH
            + "规则 分类 给定 n 作为 整数，产出 整数：\n  匹配于 n：\n    当 待处理，返回 待处理 加上 1。\n");
        assertBinding("待处理", patterns(core, "分类").get(0));
    }

    @Test
    void unknownSubjectTypeAdoptsAnExactMemberAndBindsTheRest() {
        CoreModel.Module core = compileClean("zh-CN", ENUM_ZH
            + "规则 选择 给定 s，产出 整数：\n  匹配于 s：\n    当 已删除，返回 1。\n    当 别的，返回 2。\n");
        List<CoreModel.Pattern> ps = patterns(core, "选择");
        assertVariant("已删除", ps.get(0));
        assertBinding("别的", ps.get(1));
    }

    private static final String LAUNE_DE = "Modul probe.de.\n\nDefiniere Laune als eines von Übel, Gut, Ärger.\n\n";

    @Test
    void germanUmlautInitialMemberIsAVariantAndNonMemberBinds() {
        // 变音字母开头不是 ASCII 大写：成员产出 PatCtor，非成员（整数主语下的 Ärger）产出 PatName
        CoreModel.Module core = compileClean("de-DE", LAUNE_DE
            + "Regel wert gegeben l als Laune, liefert Ganzzahl:\n  pruefe l:\n    bei Übel, gib zurueck 1.\n"
            + "    bei Gut, gib zurueck 2.\n    bei rest, gib zurueck 9.\n\n"
            + "Regel bind gegeben n als Ganzzahl, liefert Ganzzahl:\n  pruefe n:\n    bei 0, gib zurueck 100.\n"
            + "    bei Ärger, gib zurueck Ärger.\n");
        List<CoreModel.Pattern> ps = patterns(core, "wert");
        assertVariant("Übel", ps.get(0));
        assertVariant("Gut", ps.get(1));
        assertBinding("rest", ps.get(2));
        assertBinding("Ärger", patterns(core, "bind").get(1));
    }

    @Test
    void casedNamesKeepTheirExistingShapes() {
        // en：裸 TYPE_IDENT 仍为无参数 PatCtor，小写绑定仍为 PatName，IR 不变
        CoreModel.Module core = compileClean("en-US", "Module demo.cased.\n\nDefine Color as one of Red, Green.\n\n"
            + "Rule f given c as Color, produce Int:\n  Match c:\n    When Red, Return 1.\n    When other, Return 2.\n");
        List<CoreModel.Pattern> ps = patterns(core, "f");
        assertVariant("Red", ps.get(0));
        assertBinding("other", ps.get(1));
    }
}
