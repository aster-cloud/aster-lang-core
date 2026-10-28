package aster.core.resolve;

import aster.core.ast.ResolvedRole;
import aster.core.identifier.CaseFold;
import aster.core.identifier.Candidate;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreModel;
import aster.core.resolve.ResolutionChoice.Ambiguous;
import aster.core.resolve.ResolutionChoice.Choice;
import aster.core.resolve.ResolutionChoice.Entry;
import aster.core.resolve.ResolutionChoice.Hit;
import aster.core.resolve.ResolutionChoice.Miss;
import aster.core.resolve.ResolutionChoice.VocabMap;
import aster.core.typecheck.DiagnosticBuilder;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.governance.VerdictSignatures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 名称解析阶段 R2（ADR 0051 §4、§6.2）：在 Core 上解析依赖类型的位置——
 * (a) 点分路径 {@code x.f.g} 的字段段与 {@code Set} 目标（FIELD_REF）；
 * (b) 期望类型为枚举时的裸单段名（含期望枚举下用作成员的模式名）；
 * (c) 主语类型在 R1 未知的 PatCtor。另对 R1 未命中的无期望类型裸名报 E802。
 *
 * <p>只处理 lower 登记了 {@link CoreModel.ResolutionMark} 的位置。类型只来自 §4 的显式声明规则（参数 /
 * lambda 参数、构造 {@code X with …}、对源码函数的直接调用、已知类型的点分路径），其余一律未知；不调用类型检查器。
 * 输入 Core 不被修改（标记只读），返回新的 Core 与诊断；同一输入重复运行结果相同。行为与诊断须与 TS
 * {@code resolveTypedReferences} 一致。
 *
 * <p>Java 解析器与 TS 的两处结构差异在此对齐：类型参数在 Java Core 中是 TypeName（TS 为 TypeVar），按所在函数的
 * typeParams 视作类型变量；裸 {@code When Hoch,} 是无参数的 PatCtor（TS 为 PatName），按裸名模式处理（裁决 R12）。
 */
public final class TypedReferenceResolver {

    /** 内置运算符、await 与关键词构造器：不是待解析的名字。 */
    private static final Set<String> BUILTIN_VALUES = Stream.concat(
        Stream.of("+", "-", "*", "/", "//", "%", ">", "<", ">=", "<=", "==", "!=", "and", "or", "not", "await"),
        ResolutionChoice.KEYWORD_CTORS.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * 无字段结构、点分段只能是动态键的类型名（白名单）：内置标量与 Map / Json / Any。容器类型
     * （Result / Option / List / Set / Workflow）、类型别名与未声明的类型名均不在其中，按未知处理。
     */
    private static final Set<String> OPAQUE_TYPE_NAMES = Set.of(
        "Text", "Int", "Bool", "Float", "Long", "Double", "Unit", "Decimal", "DateTime", "Map", "Verdict", "Json", "Any");

    // ---- R2 静态类型：声明类型、动态值（未声明类型的字段，其后的点分段是动态键）或未知 ----

    private sealed interface Static permits Known, Dynamic, Unknown {}

    private record Known(CoreModel.Type type) implements Static {}

    private record Dynamic() implements Static {}

    private record Unknown() implements Static {}

    private static final Static DYNAMIC = new Dynamic();
    private static final Static UNKNOWN = new Unknown();

    private static CoreModel.Type asType(Static t) {
        return t instanceof Known k ? k.type() : null;
    }

    private static Static known(CoreModel.Type t) {
        return t == null ? UNKNOWN : new Known(t);
    }

    /** 声明类型：所在函数的类型参数在 Java Core 中是同名 TypeName，按类型变量处理（与 TS TypeVar 一致）。 */
    private static Static declared(CoreModel.Type t, Set<String> typeParams) {
        if (!(t instanceof CoreModel.TypeName tn) || !typeParams.contains(tn.name)) return known(t);
        CoreModel.TypeVar v = new CoreModel.TypeVar();
        v.name = tn.name;
        return new Known(v);
    }

    /** 参数的声明类型；未标注类型（type 为按名推断的占位）时为 null，即类型未知。 */
    private static CoreModel.Type declaredType(CoreModel.Param p) {
        return p.typeInferred ? null : p.type;
    }

    private static Set<String> typeParamsOf(CoreModel.Func f) {
        return f.typeParams == null ? Set.of() : Set.copyOf(f.typeParams);
    }

    // ---- 状态 ----

    private final VocabularyScopes scopes;
    private final ResolutionChoice choice = new ResolutionChoice();
    private final DiagnosticBuilder diags = new DiagnosticBuilder();

    /** Data 规范名 → 字段规范名 → 字段声明（导入的 Data 与 Verdict 无字段声明，值为 null）。 */
    private final Map<String, Map<String, CoreModel.Field>> datas = new LinkedHashMap<>();
    /** 枚举规范名 → 成员规范名。 */
    private final Map<String, List<String>> enums = new LinkedHashMap<>();
    /** 本模块函数（规范名）。 */
    private final Map<String, CoreModel.Func> funcs = new HashMap<>();
    /** 值位置第 2 步：函数与类型声明（原写法取自 origin.written）。 */
    private final List<Entry> valueDecls = new ArrayList<>();
    /** PatCtor 的 Data 构造器（本模块 Data 与导入类型）。 */
    private final List<Entry> ctorDecls = new ArrayList<>();

    private TypedReferenceResolver(VocabularyScopes scopes) {
        this.scopes = scopes;
    }

    /**
     * R2：解析依赖类型的名字位置。返回新的 Core（只含规范名，原写法记在 origin.written）与诊断
     * （E801/E805 字段、E802 值名、E803/E806 模式、E804 期望枚举下的歧义、W800 遮蔽、W801 无类型采用）。
     * 任一 error 时调用方不得发出 IR。
     */
    public static ResolutionResult<CoreModel.Module> resolve(CoreModel.Module core, ResolutionContext ctx) {
        TypedReferenceResolver r = new TypedReferenceResolver(ctx.scopes());
        r.buildTables(core, ctx.imports());
        List<CoreModel.Decl> decls = core.decls.stream().map(r::resolveDecl).toList();
        return new ResolutionResult<>(CoreCopies.module(core, new ArrayList<>(decls)), r.diags.getDiagnostics());
    }

    // ---- 模块表 ----

    private void buildTables(CoreModel.Module m, ImportedDeclarations imports) {
        m.decls.forEach(this::registerDecl);
        imports.functions().forEach(f -> valueDecls.add(own(f, ResolvedRole.FUNC_REF)));
        imports.types().forEach(t -> valueDecls.add(own(t, ResolvedRole.TYPE)));
        imports.types().stream().filter(t -> !imports.enumMembers().containsKey(t))
            .forEach(t -> ctorDecls.add(own(t, ResolvedRole.TYPE)));
        enums.putAll(imports.enumMembers());
        imports.fields().forEach((s, fs) -> datas.put(s, nullValued(fs)));
        datas.put(VerdictSignatures.TYPE_NAME, nullValued(VerdictSignatures.fields()));
    }

    private static Map<String, CoreModel.Field> nullValued(List<String> names) {
        Map<String, CoreModel.Field> out = new LinkedHashMap<>();
        names.forEach(n -> out.put(n, null));
        return out;
    }

    private void registerDecl(CoreModel.Decl d) {
        switch (d) {
            case CoreModel.Data data -> {
                Map<String, CoreModel.Field> fields = new LinkedHashMap<>();
                data.fields.forEach(f -> fields.put(f.name, f));
                datas.put(data.name, fields);
                valueDecls.add(declEntry(data.name, data.origin, ResolvedRole.TYPE));
                ctorDecls.add(declEntry(data.name, data.origin, ResolvedRole.TYPE));
            }
            case CoreModel.Enum en -> {
                enums.put(en.name, en.variants);
                valueDecls.add(declEntry(en.name, en.origin, ResolvedRole.TYPE));
            }
            case CoreModel.Func f -> {
                funcs.put(f.name, f);
                valueDecls.add(declEntry(f.name, f.origin, ResolvedRole.FUNC_REF));
            }
            case CoreModel.Import ignored -> { }
        }
    }

    private static Entry declEntry(String canonical, CoreModel.Origin origin, ResolvedRole role) {
        String written = origin != null && origin.written != null ? origin.written : canonical;
        return new Entry(CaseFold.nfc(written), canonical, role);
    }

    private static Entry own(String name, ResolvedRole role) {
        return new Entry(name, name, role);
    }

    private static List<Entry> entriesOf(Iterable<String> names, ResolvedRole role) {
        List<Entry> out = new ArrayList<>();
        names.forEach(n -> out.add(own(n, role)));
        return out;
    }

    // ---- 词汇视图 ----

    private List<VocabMap> vocabTypes() {
        return scopes == null ? List.of() : List.of(new VocabMap(scopes.types(), ResolvedRole.TYPE));
    }

    private List<VocabMap> vocabTypesAndFunctions() {
        if (scopes == null) return List.of();
        return List.of(new VocabMap(scopes.functions(), ResolvedRole.FUNC_REF), new VocabMap(scopes.types(), ResolvedRole.TYPE));
    }

    /** 按父名取作用域词汇；parent 为 null 时取全部父名。 */
    private List<VocabMap> scoped(Function<VocabularyScopes, Map<String, Map<String, List<Candidate>>>> pick,
                                  String parent, ResolvedRole role) {
        if (scopes == null) return List.of();
        return pick.apply(scopes).entrySet().stream()
            .filter(e -> parent == null || e.getKey().equals(parent))
            .map(e -> new VocabMap(e.getValue(), role))
            .toList();
    }

    /** 源码声明优先；未命中再查词汇（含规范名直写）。 */
    private Choice declThenVocab(List<Entry> decls, List<VocabMap> maps, String written) {
        Choice fromDecl = ResolutionChoice.chooseDecl(decls, written);
        return fromDecl instanceof Miss ? choice.chooseVocab(maps, written) : fromDecl;
    }

    /** 枚举 e 的成员；e 为 null 时为全部枚举的成员。 */
    private Choice chooseMember(String e, String written) {
        List<Entry> decls = enums.entrySet().stream()
            .filter(x -> e == null || x.getKey().equals(e))
            .flatMap(x -> entriesOf(x.getValue(), ResolvedRole.MEMBER_REF).stream())
            .toList();
        return declThenVocab(decls, scoped(VocabularyScopes::enumMembers, e, ResolvedRole.MEMBER_REF), written);
    }

    /** Data s 的字段；s 为 null 时为全部 Data 的字段（接收者类型未知）。 */
    private Choice chooseField(String s, String written) {
        List<Entry> decls = datas.entrySet().stream()
            .filter(x -> s == null || x.getKey().equals(s))
            .flatMap(x -> entriesOf(x.getValue().keySet(), ResolvedRole.FIELD_REF).stream())
            .toList();
        return declThenVocab(decls, scoped(VocabularyScopes::fields, s, ResolvedRole.FIELD_REF), written);
    }

    private boolean isEnum(String name) {
        return enums.containsKey(name) || (scopes != null && scopes.enumMembers().containsKey(name));
    }

    /** 已知类型名（TypeName）；类型变量、容器与推断类型均视为未知。 */
    private static String typeNameOf(Static t) {
        return t instanceof Known k && k.type() instanceof CoreModel.TypeName tn ? tn.name : null;
    }

    private String enumOf(Static t) {
        String name = typeNameOf(t);
        return name != null && isEnum(name) ? name : null;
    }

    private String dataOf(Static t) {
        String name = typeNameOf(t);
        return name != null && datas.containsKey(name) ? name : null;
    }

    /** 字段的静态类型：声明类型；未声明类型的字段为动态值；导入 Data 的字段未知。 */
    private Static fieldType(String s, String field) {
        CoreModel.Field f = datas.getOrDefault(s, Map.of()).get(field);
        if (f == null) return UNKNOWN;
        return f.typeInferred ? DYNAMIC : known(f.type);
    }

    /** 不透明类型（白名单）：动态值、类型变量、Map 及内置标量等。其后的点分段原样保留、不解析。 */
    private boolean isOpaque(Static t) {
        return switch (t) {
            case Dynamic ignored -> true;
            case Unknown ignored -> false;
            case Known k -> dataOf(t) == null && isOpaqueType(k.type());
        };
    }

    private static boolean isOpaqueType(CoreModel.Type t) {
        if (t instanceof CoreModel.TypeVar || t instanceof CoreModel.MapT) return true;
        return t instanceof CoreModel.TypeName tn && OPAQUE_TYPE_NAMES.contains(tn.name);
    }

    private static boolean isTypeVar(Static t) {
        return t instanceof Known k && k.type() instanceof CoreModel.TypeVar;
    }

    // ---- 作用域 ----

    private record Local(String name, Static type) {}

    private static final class Scope {
        final Scope parent;
        final List<Local> locals = new ArrayList<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        /** 由内向外、同层后声明者在前。 */
        List<Local> visible() {
            List<Local> out = new ArrayList<>();
            for (Scope s = this; s != null; s = s.parent) out.addAll(s.locals.reversed());
            return out;
        }
    }

    /** 遍历上下文：作用域、所在函数 / lambda 的声明返回类型、各层 lambda 的捕获改写表、所在函数的类型参数。 */
    private record Frame(Scope scope, CoreModel.Type ret, List<Map<String, Set<String>>> captures, Set<String> typeParams) {
        Frame child() {
            return new Frame(new Scope(scope), ret, captures, typeParams);
        }

        void declare(String name, Static type) {
            scope.locals.add(new Local(name, type));
        }
    }

    private record LocalChoice(Choice choice, Local local) {}

    /** LOCAL（§6.2 第 1 步）：精确命中最近声明，否则折叠唯一。 */
    private static LocalChoice findLocal(Scope scope, String written) {
        List<Local> locals = scope.visible();
        String w = CaseFold.nfc(written);
        Optional<Local> exact = locals.stream().filter(l -> l.name().equals(w)).findFirst();
        if (exact.isPresent()) return new LocalChoice(new Hit(localEntry(exact.get())), exact.get());
        String key = CaseFold.foldKey(w);
        List<Local> folded = locals.stream().filter(l -> CaseFold.foldKey(l.name()).equals(key)).toList();
        Choice picked = ResolutionChoice.choose(List.of(), folded.stream().map(TypedReferenceResolver::localEntry).toList());
        if (!(picked instanceof Hit h)) return new LocalChoice(picked, null);
        Local local = folded.stream().filter(l -> l.name().equals(h.entry().canonical())).findFirst().orElseThrow();
        return new LocalChoice(picked, local);
    }

    private static Entry localEntry(Local l) {
        return new Entry(l.name(), l.name(), ResolvedRole.LOCAL);
    }

    // ---- 静态类型（§4 类型来源表） ----

    /** 规范名路径的类型：首段局部名的声明类型，逐段取字段声明类型；不产生诊断。 */
    private Static pathType(Scope scope, String name) {
        String[] parts = name.split("\\.", -1);
        Static type = scope.visible().stream().filter(l -> l.name().equals(parts[0])).findFirst()
            .map(Local::type).orElse(UNKNOWN);
        for (int i = 1; i < parts.length; i++) {
            if (isOpaque(type)) return DYNAMIC;
            String s = dataOf(type);
            type = s == null ? UNKNOWN : fieldType(s, parts[i]);
        }
        return type;
    }

    /** 被调源码函数；同名局部名（如 lambda 绑定）遮蔽模块函数。 */
    private CoreModel.Func calleeOf(Scope scope, CoreModel.Expr target) {
        if (!(target instanceof CoreModel.Name n)) return null;
        if (scope.visible().stream().anyMatch(l -> l.name().equals(n.name))) return null;
        return funcs.get(n.name);
    }

    private static CoreModel.Type declaredRet(CoreModel.Func f) {
        return f != null && !f.retTypeInferred ? f.ret : null;
    }

    /** 已解析表达式的 R2 静态类型；表外的一切为未知。 */
    private Static typeOf(Scope scope, CoreModel.Expr e) {
        return switch (e) {
            case CoreModel.Name n -> pathType(scope, n.name);
            case CoreModel.Construct c -> known(typeName(c.typeName));
            case CoreModel.Call c -> {
                CoreModel.Func callee = calleeOf(scope, c.target);
                yield callee == null ? UNKNOWN : declared(declaredRet(callee), typeParamsOf(callee));
            }
            default -> UNKNOWN;
        };
    }

    private static CoreModel.TypeName typeName(String name) {
        CoreModel.TypeName t = new CoreModel.TypeName();
        t.name = name;
        return t;
    }

    // ---- 诊断与改写 ----

    /** 诊断位置只取 file/start/end，不带 written/role。 */
    private static Optional<CoreModel.Origin> spanOf(CoreModel.Origin origin) {
        if (origin == null) return Optional.empty();
        CoreModel.Origin out = new CoreModel.Origin();
        out.file = origin.file;
        out.start = origin.start;
        out.end = origin.end;
        return Optional.of(out);
    }

    private static Map<String, Object> params(String written, ResolvedRole role, List<Entry> cands) {
        return Map.of("written", written, "role", role.name(), "candidates", ResolutionChoice.formatCandidates(cands));
    }

    private void report(ErrorCode code, CoreModel.Origin at, String written, ResolvedRole role, List<Entry> cands) {
        diags.error(code, spanOf(at), params(written, role, cands));
    }

    private void warn(ErrorCode code, CoreModel.Origin at, String written, ResolvedRole role, List<Entry> cands) {
        diags.warning(code, spanOf(at), params(written, role, cands));
    }

    private static List<Entry> choiceEntries(Choice c) {
        return switch (c) {
            case Hit h -> List.of(h.entry());
            case Ambiguous a -> a.candidates();
            case Miss ignored -> List.of();
        };
    }

    private static CoreModel.Name unmarked(CoreModel.Name n) {
        return CoreCopies.name(n, n.name, n.origin);
    }

    /** 写入规范名，并在 origin 上记录原写法与角色（written 与规范名不同时）。 */
    private static CoreModel.Name renamed(CoreModel.Name n, String canonical, String written, ResolvedRole role) {
        return CoreCopies.name(n, canonical, CoreModel.Origin.withResolution(n.origin, written, canonical, role));
    }

    // ---- FIELD_REF（点分路径与 Set 目标） ----

    private record PathResult(String name, Static type) {}

    /** 写法本身即某个已声明字段的规范名（§6.2 第 5 步）：接收者类型未知时直接接受，不告警。 */
    private boolean isCanonicalField(String seg) {
        String w = CaseFold.nfc(seg);
        return datas.values().stream().anyMatch(fs -> fs.containsKey(w));
    }

    /** 一个字段段：类型已知查 fields[S]；未知时规范名直写直接接受，否则按全局唯一候选采用（写法不同时报 W801）。 */
    private PathResult resolveSegment(Static type, String seg, CoreModel.Origin at) {
        String s = dataOf(type);
        if (s == null && isCanonicalField(seg)) return new PathResult(CaseFold.nfc(seg), UNKNOWN);
        Choice picked = chooseField(s, seg);
        if (picked instanceof Hit h) {
            String canonical = h.entry().canonical();
            if (s == null && !canonical.equals(CaseFold.nfc(seg))) {
                warn(ErrorCode.RESOLVED_WITHOUT_TYPE, at, seg, ResolvedRole.FIELD_REF, List.of(h.entry()));
            }
            return new PathResult(canonical, s == null ? UNKNOWN : fieldType(s, canonical));
        }
        if (picked instanceof Ambiguous a) report(ErrorCode.AMBIGUOUS_FIELD, at, seg, ResolvedRole.FIELD_REF, a.candidates());
        else report(ErrorCode.UNRESOLVED_FIELD, at, seg, ResolvedRole.FIELD_REF, List.of());
        return new PathResult(CaseFold.nfc(seg), UNKNOWN);
    }

    /** 首段为局部名时解析整条路径；首段不是局部名（模块 / 类型限定名）返回 null。 */
    private PathResult resolvePath(Scope scope, String written, CoreModel.Origin at) {
        String[] parts = written.split("\\.", -1);
        LocalChoice local = findLocal(scope, parts[0]);
        if (local.choice() instanceof Miss) return null;
        if (local.local() == null) {
            report(ErrorCode.AMBIGUOUS_NAME, at, parts[0], ResolvedRole.LOCAL, choiceEntries(local.choice()));
            return null;
        }
        List<String> out = new ArrayList<>(List.of(local.local().name()));
        Static type = local.local().type();
        for (int i = 1; i < parts.length; i++) {
            // 类型参数没有字段：其上的字段段一定无法解析（类型检查同样拒绝），不让原写法进入 IR
            if (isTypeVar(type)) report(ErrorCode.UNRESOLVED_FIELD, at, parts[i], ResolvedRole.FIELD_REF, List.of());
            if (isOpaque(type)) {
                out.addAll(List.of(parts).subList(i, parts.length));
                return new PathResult(String.join(".", out), DYNAMIC);
            }
            PathResult next = resolveSegment(type, parts[i], at);
            out.add(next.name());
            type = next.type();
        }
        return new PathResult(String.join(".", out), type);
    }

    // ---- 值位置名字 ----

    /** §6.2 第 1、2、4、5 步（无期望类型）。 */
    private Choice chooseValue(Scope scope, String written) {
        Choice local = findLocal(scope, written).choice();
        if (!(local instanceof Miss)) return local;
        return declThenVocab(valueDecls, vocabTypesAndFunctions(), written);
    }

    /**
     * 与期望枚举成员同写法的冲突层候选：模块函数 / 类型、词汇函数 / 类型，以及 R1 命中而 Core 中不可见的声明
     * （如类型别名）。其它枚举的成员不冲突——期望类型已经消歧。仅大小写不同的函数同样冲突（ADR 0051 §14 第 1 条）。
     */
    private List<Entry> otherLayers(String written, String current, CoreModel.ResolutionMark mark) {
        List<Entry> found = Stream.concat(
            choiceEntries(ResolutionChoice.chooseDecl(valueDecls, written)).stream(),
            choiceEntries(choice.chooseVocab(vocabTypesAndFunctions(), written)).stream()).toList();
        boolean viaR1 = mark.r1Role() == ResolvedRole.FUNC_REF || mark.r1Role() == ResolvedRole.TYPE;
        return found.isEmpty() && viaR1 ? List.of(new Entry(written, current, mark.r1Role())) : found;
    }

    /** R1 对同一写法的值位置规则已歧义（并已在同一位置报 E804）：R2 不重复报告。 */
    private boolean reportedByR1(Frame f, CoreModel.ResolutionMark mark) {
        return mark.r1Role() == null && chooseValue(f.scope(), mark.written()) instanceof Ambiguous;
    }

    /** 期望枚举 e 下的裸名（§6.2 跨层裁决表）。成员未命中返回 null，交给无期望类型规则。 */
    private CoreModel.Name resolveUnderEnum(Frame f, CoreModel.Name n, CoreModel.ResolutionMark mark, String e) {
        Choice member = chooseMember(e, mark.written());
        Choice local = findLocal(f.scope(), mark.written()).choice();
        if (!(local instanceof Miss)) {
            // LOCAL 层先于成员：命中即取局部名，成员同时命中时提示遮蔽
            if (local instanceof Hit && member instanceof Hit h) {
                warn(ErrorCode.SHADOWED_VOCABULARY, n.origin, mark.written(), ResolvedRole.LOCAL, List.of(h.entry()));
            }
            return member instanceof Miss ? null : unmarked(n);
        }
        if (member instanceof Miss) return null;
        if (member instanceof Ambiguous a) {
            report(ErrorCode.AMBIGUOUS_NAME, n.origin, mark.written(), ResolvedRole.MEMBER_REF, a.candidates());
            return unmarked(n);
        }
        Entry hit = ((Hit) member).entry();
        List<Entry> others = otherLayers(mark.written(), n.name, mark);
        if (!others.isEmpty()) {
            List<Entry> cands = Stream.concat(Stream.of(hit), others.stream()).toList();
            if (!reportedByR1(f, mark)) report(ErrorCode.AMBIGUOUS_NAME, n.origin, mark.written(), ResolvedRole.MEMBER_REF, cands);
            return unmarked(n);
        }
        // 保持写法形态：裸名得裸成员规范名，使本地化源码与规范名拼写的同一程序产出相同 IR
        return renamed(n, hit.canonical(), mark.written(), ResolvedRole.MEMBER_REF);
    }

    /**
     * 无期望类型：沿用 R1 结果；R1 未命中时按值位置规则（歧义已由 R1 报告）。仍未命中时，写法恰为某一个
     * 源码 / 导入枚举成员的规范名则原样接受（多个枚举 → E804），否则 E802；调用目标全无候选时交由类型检查（R19）。
     */
    private CoreModel.Name resolveValue(Frame f, CoreModel.Name n, CoreModel.ResolutionMark mark, boolean callee) {
        if (mark.r1Role() != null || BUILTIN_VALUES.contains(n.name)) return unmarked(n);
        Choice picked = chooseValue(f.scope(), mark.written());
        if (picked instanceof Hit h) return renamed(n, h.entry().canonical(), mark.written(), h.entry().role());
        // UFCS 拆出的部分可能来自限定调用（`io.get(…)`），首段为命名空间时不报 E802
        if (picked instanceof Ambiguous || mark.ufcs()) return unmarked(n);
        String w = CaseFold.nfc(mark.written());
        List<String> owners = enums.entrySet().stream().filter(x -> x.getValue().contains(w)).map(Map.Entry::getKey).toList();
        if (owners.size() > 1) {
            List<Entry> cands = owners.stream().map(e -> new Entry(mark.written(), e + "." + w, ResolvedRole.MEMBER_REF)).toList();
            report(ErrorCode.AMBIGUOUS_NAME, n.origin, mark.written(), ResolvedRole.MEMBER_REF, cands);
        }
        if (owners.isEmpty() && !callee) report(ErrorCode.UNRESOLVED_NAME, n.origin, mark.written(), ResolvedRole.FUNC_REF, List.of());
        return unmarked(n);
    }

    private CoreModel.Name resolveName(Frame f, CoreModel.Name n, CoreModel.Type expected, boolean callee) {
        CoreModel.ResolutionMark mark = n.mark;
        if (mark == null) return n;
        if (mark.written().contains(".")) {
            PathResult path = resolvePath(f.scope(), mark.written(), n.origin);
            return path == null ? unmarked(n) : renamed(n, path.name(), mark.written(), ResolvedRole.FIELD_REF);
        }
        String e = enumOf(known(expected));
        CoreModel.Name under = e == null ? null : resolveUnderEnum(f, n, mark, e);
        CoreModel.Name out = under != null ? under : resolveValue(f, n, mark, callee);
        recordCapture(f, n.name, out.name);
        return out;
    }

    /** 记录 lambda 体内裸名的改写，供改写捕获列表。 */
    private static void recordCapture(Frame f, String before, String after) {
        for (Map<String, Set<String>> map : f.captures()) {
            map.computeIfAbsent(before, k -> new LinkedHashSet<>()).add(after);
        }
    }

    // ---- 表达式 ----

    private CoreModel.Expr resolveExpr(Frame f, CoreModel.Expr e, CoreModel.Type expected) {
        return switch (e) {
            case CoreModel.Name n -> resolveName(f, n, expected, false);
            case CoreModel.Call c -> resolveCall(f, c);
            case CoreModel.Construct c -> resolveConstruct(f, c);
            case CoreModel.Ok o -> CoreCopies.ok(o, sub(f, o.expr));
            case CoreModel.Err x -> CoreCopies.err(x, sub(f, x.expr));
            case CoreModel.Some s -> CoreCopies.some(s, sub(f, s.expr));
            case CoreModel.Await a -> CoreCopies.await(a, sub(f, a.expr));
            case CoreModel.IfE i -> CoreCopies.ifExpr(i, sub(f, i.cond), sub(f, i.thenE), sub(f, i.elseE));
            case CoreModel.ListE l -> CoreCopies.list(l, l.elements.stream().map(x -> sub(f, x)).toList());
            case CoreModel.Lambda l -> resolveLambda(f, l);
            case CoreModel.Bool ignored -> e;
            case CoreModel.IntE ignored -> e;
            case CoreModel.LongE ignored -> e;
            case CoreModel.DoubleE ignored -> e;
            case CoreModel.DecimalE ignored -> e;
            case CoreModel.StringE ignored -> e;
            case CoreModel.NullE ignored -> e;
            case CoreModel.NoneE ignored -> e;
        };
    }

    private CoreModel.Expr sub(Frame f, CoreModel.Expr e) {
        return resolveExpr(f, e, null);
    }

    /** 点分调用目标（{@code Text.concat(…)}）是限定调用，首段为命名空间（§5 MODULE，不本地化），原样保留。 */
    private static boolean isQualifiedCall(CoreModel.Expr target) {
        return target instanceof CoreModel.Name n && n.name.contains(".");
    }

    /** 调用实参的期望类型取被调源码函数对应参数的声明类型。 */
    private CoreModel.Call resolveCall(Frame f, CoreModel.Call e) {
        CoreModel.Expr target = switch (e.target) {
            case CoreModel.Name n when isQualifiedCall(n) -> unmarked(n);
            case CoreModel.Name n -> resolveName(f, n, null, true);
            default -> sub(f, e.target);
        };
        CoreModel.Func callee = calleeOf(f.scope(), target);
        List<CoreModel.Param> params = callee == null ? List.of() : callee.params;
        List<CoreModel.Expr> args = new ArrayList<>();
        for (int i = 0; i < e.args.size(); i++) {
            CoreModel.Type expected = i < params.size() ? declaredType(params.get(i)) : null;
            args.add(resolveExpr(f, e.args.get(i), expected));
        }
        return CoreCopies.call(e, target, args);
    }

    /** ConstructField 的期望类型取所构造 Data 的字段声明类型（字段名已由 R1 解析）。 */
    private CoreModel.Construct resolveConstruct(Frame f, CoreModel.Construct e) {
        List<CoreModel.FieldInit> fields = e.fields.stream()
            .map(cf -> CoreCopies.fieldInit(cf, resolveExpr(f, cf.expr, asType(fieldType(e.typeName, cf.name)))))
            .toList();
        return CoreCopies.construct(e, new ArrayList<>(fields));
    }

    private CoreModel.Lambda resolveLambda(Frame f, CoreModel.Lambda e) {
        Map<String, Set<String>> renames = new LinkedHashMap<>();
        List<Map<String, Set<String>>> captures = new ArrayList<>(f.captures());
        captures.add(renames);
        Frame inner = new Frame(new Scope(f.scope()), e.ret, captures, f.typeParams());
        // 与 TS 一致：lambda 参数取其类型（未标注时为按名推断的占位），不按 typeInferred 视为未知
        e.params.forEach(p -> inner.declare(p.name, declared(p.type, f.typeParams())));
        CoreModel.Block body = resolveBlock(inner, e.body);
        return CoreCopies.lambda(e, body, e.captures == null ? null : rewriteCaptures(e.captures, renames));
    }

    /** 捕获名随解析结果改写；解析为限定名（如枚举成员 {@code E.M}）的不再是自由变量。 */
    private static List<String> rewriteCaptures(List<String> captures, Map<String, Set<String>> renames) {
        Set<String> out = new LinkedHashSet<>();
        captures.stream()
            .flatMap(c -> renames.getOrDefault(c, Set.of(c)).stream())
            .filter(c -> !c.contains("."))
            .forEach(out::add);
        return new ArrayList<>(out);
    }

    // ---- 语句 ----

    private CoreModel.Block resolveBlock(Frame f, CoreModel.Block b) {
        Frame inner = f.child();
        return CoreCopies.block(b, resolveStmts(inner, b.statements));
    }

    private List<CoreModel.Stmt> resolveStmts(Frame f, List<CoreModel.Stmt> stmts) {
        List<CoreModel.Stmt> out = new ArrayList<>();
        stmts.forEach(s -> out.add(resolveStmt(f, s)));
        return out;
    }

    private CoreModel.Stmt resolveStmt(Frame f, CoreModel.Stmt s) {
        return switch (s) {
            case CoreModel.Let let -> {
                CoreModel.Expr expr = sub(f, let.expr);
                f.declare(let.name, typeOf(f.scope(), expr));
                yield CoreCopies.let(let, expr);
            }
            case CoreModel.Set set -> resolveSet(f, set);
            case CoreModel.Return ret -> CoreCopies.ret(ret, resolveExpr(f, ret.expr, f.ret()));
            case CoreModel.If i -> CoreCopies.ifStmt(i, sub(f, i.cond), resolveBlock(f, i.thenBlock),
                i.elseBlock == null ? null : resolveBlock(f, i.elseBlock));
            case CoreModel.Match m -> resolveMatch(f, m);
            case CoreModel.Scope sc -> CoreCopies.scope(sc, resolveStmts(f.child(), sc.statements));
            case CoreModel.Block b -> resolveBlock(f, b);
            case CoreModel.Start st -> {
                CoreModel.Expr expr = sub(f, st.expr);
                f.declare(st.name, UNKNOWN);
                yield CoreCopies.start(st, expr);
            }
            case CoreModel.Wait ignored -> s;
            case CoreModel.Workflow wf -> CoreCopies.workflow(wf, wf.steps.stream()
                .map(step -> CoreCopies.step(step, resolveBlock(f, step.body),
                    step.compensate == null ? null : resolveBlock(f, step.compensate)))
                .toList());
        };
    }

    /** Set 目标：单段为局部名（不解析）；点分按路径解析，最后一段的声明类型作为右侧期望类型。 */
    private CoreModel.Set resolveSet(Frame f, CoreModel.Set s) {
        CoreModel.ResolutionMark mark = s.mark;
        PathResult path = mark != null && mark.written().contains(".") ? resolvePath(f.scope(), mark.written(), s.origin) : null;
        CoreModel.Expr expr = resolveExpr(f, s.expr, path == null ? null : asType(path.type()));
        if (path == null) return CoreCopies.set(s, s.name, expr, s.origin);
        CoreModel.Origin origin = CoreModel.Origin.withResolution(s.origin, mark.written(), path.name(), ResolvedRole.FIELD_REF);
        return CoreCopies.set(s, path.name(), expr, origin);
    }

    private CoreModel.Match resolveMatch(Frame f, CoreModel.Match s) {
        CoreModel.Expr expr = sub(f, s.expr);
        Static subject = typeOf(f.scope(), expr);
        List<CoreModel.Case> cases = s.cases.stream().map(c -> resolveCase(f.child(), c, subject)).toList();
        return CoreCopies.match(s, expr, new ArrayList<>(cases));
    }

    private CoreModel.Case resolveCase(Frame f, CoreModel.Case c, Static subject) {
        CoreModel.Pattern pattern = resolvePattern(f, c.pattern, subject);
        CoreModel.Stmt body = c.body instanceof CoreModel.Return ret
            ? CoreCopies.ret(ret, resolveExpr(f, ret.expr, f.ret()))
            : resolveStmt(f, c.body);
        return CoreCopies.kase(c, pattern, body);
    }

    // ---- 模式 ----

    private CoreModel.Pattern resolvePattern(Frame f, CoreModel.Pattern p, Static subject) {
        return switch (p) {
            case CoreModel.PatName n -> resolvePatName(f, n, subject);
            case CoreModel.PatCtor c when isBareCtor(c) -> resolveBareCtor(f, c, subject);
            case CoreModel.PatCtor c -> resolvePatCtor(f, c, subject);
            case CoreModel.PatNull ignored -> p;
            case CoreModel.PatInt ignored -> p;
        };
    }

    /** Java 解析器把无括号的裸 TYPE_IDENT（{@code When Hoch,}）产出为无参数的 PatCtor：等同 TS 的 PatName。 */
    private static boolean isBareCtor(CoreModel.PatCtor c) {
        return (c.args == null || c.args.isEmpty()) && (c.names == null || c.names.isEmpty());
    }

    /** 裸名模式的三种结果：绑定名、已报错保持原样、成员（规范名）。 */
    private sealed interface BareOutcome permits Binding, Kept, MemberOf {}

    private record Binding() implements BareOutcome {}

    private record Kept() implements BareOutcome {}

    private record MemberOf(String canonical) implements BareOutcome {}

    private static final BareOutcome BINDING = new Binding();
    private static final BareOutcome KEPT = new Kept();

    /**
     * 模式名：主语为枚举 E 时，命中 enumMembers[E] 即为成员（与其它层同时命中 → E804）；主语类型未知时，
     * 仅当写法精确等于某枚举成员才视为成员（唯一 → 写法不同时 W801，多个 → E806）；其余为绑定名。
     * 模式名是声明而非引用，故不经过 LOCAL 层。
     */
    private CoreModel.Pattern resolvePatName(Frame f, CoreModel.PatName p, Static subject) {
        BareOutcome outcome = p.mark == null ? BINDING : bareOutcome(p.mark, p.name, p.origin, subject);
        return switch (outcome) {
            case Binding ignored -> {
                f.declare(p.name, UNKNOWN);
                yield p;
            }
            case Kept ignored -> CoreCopies.patName(p, p.name, p.origin);
            case MemberOf m -> CoreCopies.patName(p, m.canonical(),
                CoreModel.Origin.withResolution(p.origin, p.mark.written(), m.canonical(), ResolvedRole.MEMBER_REF));
        };
    }

    /** 裸名构造器模式按裁决 R12 与 PatName 同规则；关键词构造器（无标记）保持原样。 */
    private CoreModel.Pattern resolveBareCtor(Frame f, CoreModel.PatCtor p, Static subject) {
        if (p.mark == null) return p;
        return switch (bareOutcome(p.mark, p.typeName, p.origin, subject)) {
            case Binding ignored -> {
                f.declare(p.typeName, UNKNOWN);
                yield CoreCopies.patCtor(p, p.typeName, p.args, p.origin);
            }
            case Kept ignored -> CoreCopies.patCtor(p, p.typeName, p.args, p.origin);
            case MemberOf m -> CoreCopies.patCtor(p, m.canonical(), p.args,
                CoreModel.Origin.withResolution(p.origin, p.mark.written(), m.canonical(), ResolvedRole.MEMBER_REF));
        };
    }

    private BareOutcome bareOutcome(CoreModel.ResolutionMark mark, String name, CoreModel.Origin at, Static subject) {
        String e = enumOf(subject);
        if (e != null) return bareUnderEnum(mark, name, at, e);
        if (typeNameOf(subject) != null) return BINDING;
        String w = CaseFold.nfc(mark.written());
        List<Entry> exact = allMembers().stream().filter(x -> x.written().equals(w) || x.canonical().equals(w)).toList();
        Choice member = ResolutionChoice.choose(exact, List.of());
        if (member instanceof Miss) return BINDING;
        if (member instanceof Ambiguous a) {
            report(ErrorCode.AMBIGUOUS_PATTERN, at, mark.written(), ResolvedRole.MEMBER_REF, a.candidates());
            return KEPT;
        }
        Entry hit = ((Hit) member).entry();
        if (!hit.canonical().equals(w)) {
            warn(ErrorCode.RESOLVED_WITHOUT_TYPE, at, mark.written(), ResolvedRole.MEMBER_REF, List.of(hit));
        }
        return new MemberOf(hit.canonical());
    }

    private BareOutcome bareUnderEnum(CoreModel.ResolutionMark mark, String name, CoreModel.Origin at, String e) {
        Choice member = chooseMember(e, mark.written());
        if (member instanceof Miss) return BINDING;
        List<Entry> conflicts = member instanceof Hit ? otherLayers(mark.written(), name, mark) : List.of();
        if (member instanceof Ambiguous || !conflicts.isEmpty()) {
            List<Entry> cands = Stream.concat(choiceEntries(member).stream(), conflicts.stream()).toList();
            report(ErrorCode.AMBIGUOUS_NAME, at, mark.written(), ResolvedRole.MEMBER_REF, cands);
            return KEPT;
        }
        return new MemberOf(((Hit) member).entry().canonical());
    }

    /** 全部枚举成员候选：源码与导入（规范名）及词汇（原写法）。 */
    private List<Entry> allMembers() {
        Stream<Entry> decls = enums.values().stream().flatMap(ms -> entriesOf(ms, ResolvedRole.MEMBER_REF).stream());
        Stream<Entry> vocab = scoped(VocabularyScopes::enumMembers, null, ResolvedRole.MEMBER_REF).stream()
            .flatMap(m -> m.map().values().stream().flatMap(List::stream)
                .map(c -> new Entry(c.written(), c.canonical(), m.role())));
        return Stream.concat(decls, vocab).toList();
    }

    private CoreModel.PatCtor resolvePatCtor(Frame f, CoreModel.PatCtor p, Static subject) {
        List<CoreModel.Pattern> args = p.args == null ? null
            : new ArrayList<>(p.args.stream().map(a -> resolvePattern(f, a, UNKNOWN)).toList());
        if (p.names != null) p.names.forEach(n -> f.declare(n, UNKNOWN));
        CoreModel.PatCtor base = CoreCopies.patCtor(p, p.typeName, args, p.origin);
        return p.mark == null ? base : ctorResolution(base, p.mark, subject);
    }

    /** 构造器模式按主语类型的三种情形：候选来源与歧义 / 未命中时的诊断码。 */
    private record CtorRule(Choice choice, ResolvedRole role, boolean typed, ErrorCode ambiguous, ErrorCode unresolved) {}

    /** 主语为枚举 → 成员（同 R1）；为其它已知类型 → Data 构造器（同 R1）；未知 → 全部成员与构造器中唯一（W801）。 */
    private CtorRule ctorRule(String written, Static subject) {
        String e = enumOf(subject);
        if (e != null) {
            return new CtorRule(chooseMember(e, written), ResolvedRole.MEMBER_REF, true,
                ErrorCode.AMBIGUOUS_NAME, ErrorCode.UNRESOLVED_PATTERN);
        }
        if (typeNameOf(subject) != null) {
            return new CtorRule(declThenVocab(ctorDecls, vocabTypes(), written), ResolvedRole.TYPE, true,
                ErrorCode.AMBIGUOUS_NAME, ErrorCode.UNRESOLVED_TYPE);
        }
        List<Entry> decls = Stream.concat(
            enums.values().stream().flatMap(ms -> entriesOf(ms, ResolvedRole.MEMBER_REF).stream()),
            ctorDecls.stream()).toList();
        List<VocabMap> maps = Stream.concat(
            scoped(VocabularyScopes::enumMembers, null, ResolvedRole.MEMBER_REF).stream(), vocabTypes().stream()).toList();
        return new CtorRule(declThenVocab(decls, maps, written), ResolvedRole.TYPE, false,
            ErrorCode.AMBIGUOUS_PATTERN, ErrorCode.UNRESOLVED_PATTERN);
    }

    private CoreModel.PatCtor ctorResolution(CoreModel.PatCtor p, CoreModel.ResolutionMark mark, Static subject) {
        CtorRule rule = ctorRule(mark.written(), subject);
        if (rule.choice() instanceof Hit h) {
            String canonical = h.entry().canonical();
            ResolvedRole role = h.entry().role();
            if (!rule.typed() && !canonical.equals(CaseFold.nfc(mark.written()))) {
                warn(ErrorCode.RESOLVED_WITHOUT_TYPE, p.origin, mark.written(), role, List.of(h.entry()));
            }
            return CoreCopies.patCtor(p, canonical, p.args,
                CoreModel.Origin.withResolution(p.origin, mark.written(), canonical, role));
        }
        if (rule.choice() instanceof Ambiguous a) report(rule.ambiguous(), p.origin, mark.written(), rule.role(), a.candidates());
        else report(rule.unresolved(), p.origin, mark.written(), rule.role(), List.of());
        return p;
    }

    // ---- 入口 ----

    private CoreModel.Decl resolveDecl(CoreModel.Decl d) {
        if (!(d instanceof CoreModel.Func fn)) return d;
        Set<String> typeParams = typeParamsOf(fn);
        Frame f = new Frame(new Scope(null), declaredRet(fn), List.of(), typeParams);
        fn.params.forEach(p -> f.declare(p.name, declared(declaredType(p), typeParams)));
        return CoreCopies.func(fn, resolveBlock(f, fn.body));
    }
}
