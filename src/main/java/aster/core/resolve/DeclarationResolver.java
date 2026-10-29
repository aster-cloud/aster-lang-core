package aster.core.resolve;

import aster.core.ast.Block;
import aster.core.ast.Decl;
import aster.core.ast.Expr;
import aster.core.ast.Module;
import aster.core.ast.Pattern;
import aster.core.ast.Resolved;
import aster.core.ast.ResolvedRole;
import aster.core.ast.Span;
import aster.core.ast.Stmt;
import aster.core.ast.Type;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 名称解析阶段 R1（ADR 0051 §4–§6）：在 AST 上解析不依赖类型的位置——
 * 声明名（Data / Enum / 别名 / Func / 字段 / 枚举成员）、全部类型位置、函数引用、
 * 枚举限定引用 {@code E.M}、构造表达式的类型名与字段名、主语类型已知的 PatternCtor。
 *
 * <p>节点的 name 保持原写法，结果写入 {@code resolved}。点分后续段、期望枚举下的裸名、
 * 主语未知的 PatternCtor 以及 PatternName 留给 R2（Core 上的类型驱动解析）。输入 AST 不被修改。
 * 行为与诊断须与 TS {@code resolveDeclarations} 一致。
 */
public final class DeclarationResolver {

    /**
     * 内置类型：与 TS BUILTIN_TYPE_NAMES 一致，另加 Java 解析器对关键词 {@code result of} /
     * {@code option of} / {@code maybe of} 产出的小写 TypeApp 基名（TS 为专门节点，不经名称查找）。
     */
    private static final Set<String> BUILTIN_TYPE_NAMES = Set.of(
        "Text", "Int", "Bool", "Float", "Long", "Double", "Unit", "Decimal", "DateTime",
        "Result", "Option", "List", "Map", "Set", "Workflow", "Verdict",
        "result", "option", "maybe");

    /** 解析器在 Lambda 缺省返回类型等处合成的占位类型名，交由类型推断处理。 */
    private static final String INFERRED_TYPE_PLACEHOLDER = "Unknown";

    private final VocabularyScopes scopes;
    private final ResolutionChoice choice = new ResolutionChoice();
    private final DiagnosticBuilder diags = new DiagnosticBuilder();

    // ---- 模块符号表：本模块与导入模块已解析的声明，供引用位置的 §6.2 第 2 步使用 ----
    private final List<Entry> types = new ArrayList<>();
    private final List<Entry> functions = new ArrayList<>();
    private final Set<String> enums = new HashSet<>();
    private final Map<String, List<Entry>> members = new HashMap<>();
    private final Map<String, List<Entry>> fields = new HashMap<>();

    // ---- 预先解析的声明名，按节点身份索引（重复声明的记录可能值相等） ----
    private final Map<Decl, Resolved> byDecl = new IdentityHashMap<>();
    private final Map<Decl.Data, List<Resolved>> fieldNames = new IdentityHashMap<>();
    private final Map<Decl.Enum, List<Resolved>> variantNames = new IdentityHashMap<>();

    private DeclarationResolver(VocabularyScopes scopes) {
        this.scopes = scopes;
    }

    /**
     * R1：解析声明与类型位置。返回新的 AST（输入不变）与诊断（E800/E801/E802/E803 未解析、
     * E804/E805 歧义、E807 重复声明、W800 遮蔽词汇）。
     */
    public static ResolutionResult<Module> resolve(Module module, ResolutionContext ctx) {
        DeclarationResolver r = new DeclarationResolver(ctx.scopes());
        r.declareTopLevel(module);
        r.registerImports(ctx.imports());
        List<Decl> decls = module.decls().stream().map(r::rewriteDecl).toList();
        Module out = new Module(module.name(), module.profile(), decls, module.span());
        return new ResolutionResult<>(out, r.diags.getDiagnostics());
    }

    // ---- 词法作用域 ----

    /** 源码局部名；typeName 为其声明类型（已解析的规范名），未知时为 null。 */
    private record Local(String name, String typeName) {}

    private record LocalChoice(Choice choice, Local local) {}

    /** 局部名链，按父链向外查找；typeParams 为所在函数保留下来的类型参数名（不查词汇）。 */
    private static final class Scope {
        final Scope parent;
        final List<Local> locals = new ArrayList<>();
        final Set<String> typeParams;

        Scope(Scope parent, Set<String> typeParams) {
            this.parent = parent;
            this.typeParams = typeParams;
        }

        Scope child() {
            return new Scope(this, typeParams);
        }

        void add(String name) {
            locals.add(new Local(CaseFold.nfc(name), null));
        }

        /** 由内向外、同层后声明者在前。 */
        List<Local> visible() {
            List<Local> out = new ArrayList<>();
            for (Scope s = this; s != null; s = s.parent) {
                out.addAll(s.locals.reversed());
            }
            return out;
        }
    }

    private static Scope rootScope(List<String> typeParams) {
        return new Scope(null, Set.copyOf(typeParams));
    }

    private static LocalChoice findLocal(Scope scope, String written) {
        List<Local> locals = scope.visible();
        String w = CaseFold.nfc(written);
        Optional<Local> exact = locals.stream().filter(l -> l.name().equals(w)).findFirst();
        if (exact.isPresent()) return new LocalChoice(new Hit(localEntry(exact.get().name())), exact.get());
        String key = CaseFold.foldKey(w);
        List<Local> folded = locals.stream().filter(l -> CaseFold.foldKey(l.name()).equals(key)).toList();
        Choice picked = ResolutionChoice.choose(List.of(), folded.stream().map(l -> localEntry(l.name())).toList());
        return new LocalChoice(picked, picked instanceof Hit ? folded.get(0) : null);
    }

    private static Entry localEntry(String name) {
        return new Entry(name, name, ResolvedRole.LOCAL);
    }

    // ---- 诊断 ----

    private void report(ErrorCode code, String written, ResolvedRole role, String candidates, Span span) {
        Map<String, Object> params = Map.of("written", written, "role", role.name(), "candidates", candidates);
        if (code == ErrorCode.SHADOWED_VOCABULARY) {
            diags.warning(code, origin(span), params);
            return;
        }
        diags.error(code, origin(span), params);
    }

    private void reportAmbiguous(String written, ResolvedRole role, List<Entry> cands, Span span) {
        report(ErrorCode.AMBIGUOUS_NAME, written, role, ResolutionChoice.formatCandidates(cands), span);
    }

    private void reportUnresolved(ErrorCode code, String written, ResolvedRole role, Span span) {
        report(code, written, role, "", span);
    }

    /** 源码名胜出、而词汇对同一写法精确给出不同规范名时 → W800。 */
    private void warnShadow(String written, Entry winner, List<VocabMap> maps, Span span) {
        String w = CaseFold.nfc(written);
        boolean shadowed = maps.stream().anyMatch(m -> VocabularyScopes.lookup(m.map(), w).exact().stream()
            .anyMatch(c -> !c.canonical().equals(winner.canonical())));
        if (shadowed) report(ErrorCode.SHADOWED_VOCABULARY, written, winner.role(), winner.canonical(), span);
    }

    private static Optional<CoreModel.Origin> origin(Span span) {
        if (span == null) return Optional.empty();
        CoreModel.Origin o = new CoreModel.Origin();
        o.start = position(span.start());
        o.end = position(span.end());
        return Optional.of(o);
    }

    private static CoreModel.Position position(Span.Position p) {
        if (p == null) return null;
        CoreModel.Position out = new CoreModel.Position();
        out.line = p.line();
        out.col = p.col();
        return out;
    }

    // ---- 词汇视图 ----

    private List<VocabMap> vocabTypes() {
        return scopes == null ? List.of() : List.of(new VocabMap(scopes.types(), ResolvedRole.TYPE));
    }

    private List<VocabMap> vocabFunctions() {
        return scopes == null ? List.of() : List.of(new VocabMap(scopes.functions(), ResolvedRole.FUNC_REF));
    }

    private List<VocabMap> scopedVocab(Function<VocabularyScopes, Map<String, Map<String, List<Candidate>>>> pick,
                                       String parent, ResolvedRole role) {
        Map<String, List<Candidate>> map = scopes == null ? null : pick.apply(scopes).get(parent);
        return map == null ? List.of() : List.of(new VocabMap(map, role));
    }

    // ---- 声明（§6.1） ----

    /** 声明写法 → 规范名：精确命中词汇 → 折叠唯一 → 原写法；折叠多候选报 E804。 */
    private Resolved declare(String written, ResolvedRole role, List<VocabMap> maps, Span span) {
        Choice picked = choice.chooseVocab(maps, written);
        if (picked instanceof Hit h) return new Resolved(h.entry().canonical(), role, written);
        if (picked instanceof Ambiguous a) reportAmbiguous(written, role, a.candidates(), span);
        return new Resolved(CaseFold.nfc(written), role, written);
    }

    private record DeclaredSite(Resolved resolved, Span span) {}

    /** 同角色同作用域两个声明解析到同一规范名 → E807（报在后一处）。 */
    private void checkDuplicates(List<DeclaredSite> sites) {
        Map<String, Resolved> first = new HashMap<>();
        for (DeclaredSite site : sites) {
            Resolved resolved = site.resolved();
            Resolved prev = first.putIfAbsent(resolved.canonical(), resolved);
            if (prev == null) continue;
            String candidates = prev.written() + ", " + resolved.written() + " → " + resolved.canonical();
            report(ErrorCode.DUPLICATE_DECLARATION, resolved.written(), resolved.role(), candidates, site.span());
        }
    }

    /** 预先解析全部声明名并建立模块符号表；别名与 Data/Enum 同组查重。 */
    private void declareTopLevel(Module module) {
        List<DeclaredSite> typeSites = new ArrayList<>();
        List<DeclaredSite> funcSites = new ArrayList<>();
        for (Decl d : module.decls()) {
            Resolved resolved = declareName(d);
            if (resolved == null) continue;
            byDecl.put(d, resolved);
            (d instanceof Decl.Func ? funcSites : typeSites).add(new DeclaredSite(resolved, d.span()));
        }
        checkDuplicates(typeSites);
        checkDuplicates(funcSites);
        module.decls().stream().filter(byDecl::containsKey).forEach(d -> registerDecl(d, byDecl.get(d)));
    }

    private Resolved declareName(Decl d) {
        return switch (d) {
            case Decl.Import ignored -> null;
            case Decl.Func f -> declare(f.name(), ResolvedRole.FUNC_DECL, vocabFunctions(), f.span());
            case Decl.Data data -> declare(data.name(), ResolvedRole.TYPE_DECL, vocabTypes(), data.span());
            case Decl.Enum en -> declare(en.name(), ResolvedRole.TYPE_DECL, vocabTypes(), en.span());
            case Decl.TypeAlias alias -> declare(alias.name(), ResolvedRole.TYPE_DECL, vocabTypes(), alias.span());
        };
    }

    private void registerDecl(Decl d, Resolved resolved) {
        Entry entry = refEntry(resolved, ResolvedRole.TYPE);
        switch (d) {
            case Decl.Import ignored -> { }
            case Decl.Func ignored -> functions.add(refEntry(resolved, ResolvedRole.FUNC_REF));
            case Decl.TypeAlias ignored -> types.add(entry);
            case Decl.Data data -> {
                types.add(entry);
                declareFields(data, resolved.canonical());
            }
            case Decl.Enum en -> {
                types.add(entry);
                enums.add(resolved.canonical());
                declareVariants(en, resolved.canonical());
            }
        }
    }

    private void declareFields(Decl.Data data, String parent) {
        List<VocabMap> maps = scopedVocab(VocabularyScopes::fields, parent, ResolvedRole.FIELD_REF);
        List<Span> spans = data.fields().stream().map(f -> f.span() != null ? f.span() : data.span()).toList();
        List<Resolved> names = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            names.add(declare(data.fields().get(i).name(), ResolvedRole.FIELD_DECL, maps, spans.get(i)));
        }
        checkDuplicates(sites(names, spans));
        fieldNames.put(data, names);
        fields.put(parent, names.stream().map(n -> refEntry(n, ResolvedRole.FIELD_REF)).toList());
    }

    private void declareVariants(Decl.Enum en, String parent) {
        List<VocabMap> maps = scopedVocab(VocabularyScopes::enumMembers, parent, ResolvedRole.MEMBER_REF);
        List<Span> spans = IntStream.range(0, en.variants().size()).mapToObj(en::variantSpan).toList();
        List<Resolved> names = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            names.add(declare(en.variants().get(i), ResolvedRole.MEMBER_DECL, maps, spans.get(i)));
        }
        checkDuplicates(sites(names, spans));
        variantNames.put(en, names);
        members.put(parent, names.stream().map(n -> refEntry(n, ResolvedRole.MEMBER_REF)).toList());
    }

    /** 条目声明与其位置一一配对（诊断落在条目上，aster-lang-test#174 子项 2）。 */
    private static List<DeclaredSite> sites(List<Resolved> names, List<Span> spans) {
        return IntStream.range(0, names.size()).mapToObj(i -> new DeclaredSite(names.get(i), spans.get(i))).toList();
    }

    private static Entry refEntry(Resolved resolved, ResolvedRole role) {
        return new Entry(CaseFold.nfc(resolved.written()), resolved.canonical(), role);
    }

    private static Entry own(String name, ResolvedRole role) {
        return new Entry(name, name, role);
    }

    private void registerImports(ImportedDeclarations imports) {
        imports.types().forEach(t -> types.add(own(t, ResolvedRole.TYPE)));
        imports.functions().forEach(f -> functions.add(own(f, ResolvedRole.FUNC_REF)));
        imports.enumMembers().forEach((e, ms) -> {
            enums.add(e);
            members.put(e, ms.stream().map(m -> own(m, ResolvedRole.MEMBER_REF)).toList());
        });
        imports.fields().forEach((s, fs) -> fields.put(s, fs.stream().map(f -> own(f, ResolvedRole.FIELD_REF)).toList()));
    }

    // ---- 引用（§6.2 第 1、2、4、5 步） ----

    /** found=false 表示 §6.2 第 1–5 步全部未命中（由调用方按位置报 E80x）。 */
    private record RefResult(String canonical, boolean found) {}

    /** ambiguous 为折叠歧义时使用的诊断码（字段为 E805，其余为 E804）。 */
    private record RefSite(String written, ResolvedRole role, Span span, ErrorCode ambiguous) {}

    /** 源码声明 → 词汇 → 规范名直写；歧义在此报告并视为已处理，未命中交给调用方。 */
    private RefResult resolveRef(RefSite ref, List<Entry> decls, List<VocabMap> maps) {
        Choice fromDecl = ResolutionChoice.chooseDecl(decls, ref.written());
        if (fromDecl instanceof Hit h) {
            Entry winner = new Entry(h.entry().written(), h.entry().canonical(), ref.role());
            warnShadow(ref.written(), winner, maps, ref.span());
            return new RefResult(h.entry().canonical(), true);
        }
        Choice picked = fromDecl instanceof Miss ? choice.chooseVocab(maps, ref.written()) : fromDecl;
        return switch (picked) {
            case Hit h -> new RefResult(h.entry().canonical(), true);
            case Miss ignored -> new RefResult(CaseFold.nfc(ref.written()), false);
            case Ambiguous a -> {
                String cands = ResolutionChoice.formatCandidates(a.candidates());
                report(ref.ambiguous(), ref.written(), ref.role(), cands, ref.span());
                yield new RefResult(CaseFold.nfc(ref.written()), true);
            }
        };
    }

    private record TypeResolution(Resolved resolved, boolean unresolved) {}

    /** TYPE 位置：源码声明（含导入）→ 词汇 → 内置类型；全部未命中报 E800，规范名取原写法。 */
    private Resolved resolveTypeName(String written, Span span) {
        return resolveTypeNameChecked(written, span).resolved();
    }

    /** 同 resolveTypeName，另返回是否已报 E800（供调用方抑制级联诊断）。 */
    private TypeResolution resolveTypeNameChecked(String written, Span span) {
        RefSite site = new RefSite(written, ResolvedRole.TYPE, span, ErrorCode.AMBIGUOUS_NAME);
        RefResult ref = resolveRef(site, types, vocabTypes());
        boolean unresolved = !ref.found() && !isBuiltinType(ref.canonical());
        if (unresolved) reportUnresolved(ErrorCode.UNRESOLVED_TYPE, written, ResolvedRole.TYPE, span);
        return new TypeResolution(new Resolved(ref.canonical(), ResolvedRole.TYPE, written), unresolved);
    }

    private static boolean isBuiltinType(String name) {
        return BUILTIN_TYPE_NAMES.contains(name) || INFERRED_TYPE_PLACEHOLDER.equals(name);
    }

    /** 不产生诊断地探测一个名字在 TYPE 位置对源码声明或词汇的命中情况。 */
    private Choice probeTypeChoice(String written) {
        Choice fromDecl = ResolutionChoice.chooseDecl(types, written);
        return fromDecl instanceof Miss ? choice.chooseVocab(vocabTypes(), written) : fromDecl;
    }

    private String probeType(String written) {
        return probeTypeChoice(written) instanceof Hit h ? h.entry().canonical() : null;
    }

    /** 值位置单段名：LOCAL → 模块函数/类型 → 词汇函数/类型 → 规范名直写；全部失败留给 R2。 */
    private Resolved resolveValueName(Scope scope, String written, Span span) {
        List<VocabMap> maps = Stream.concat(vocabFunctions().stream(), vocabTypes().stream()).toList();
        LocalChoice local = findLocal(scope, written);
        boolean notLocal = local.choice() instanceof Miss;
        Choice decl = notLocal
            ? ResolutionChoice.chooseDecl(Stream.concat(functions.stream(), types.stream()).toList(), written)
            : local.choice();
        Choice picked = decl instanceof Miss ? choice.chooseVocab(maps, written) : decl;
        ResolvedRole role = notLocal ? ResolvedRole.FUNC_REF : ResolvedRole.LOCAL;
        if (picked instanceof Ambiguous a) reportAmbiguous(written, role, a.candidates(), span);
        if (!(picked instanceof Hit h)) return null;
        if (decl instanceof Hit) warnShadow(written, h.entry(), maps, span);
        return new Resolved(h.entry().canonical(), h.entry().role(), written);
    }

    private boolean isEnum(String name) {
        return enums.contains(name) || (scopes != null && scopes.enumMembers().containsKey(name));
    }

    /** 首段是否为局部名或函数（源码 / 词汇）：是则不是 {@code E.M} 形式，整体留给 R2。 */
    private boolean headIsValue(Scope scope, String head) {
        if (!(findLocal(scope, head).choice() instanceof Miss)) return true;
        if (!(ResolutionChoice.chooseDecl(functions, head) instanceof Miss)) return true;
        return !(choice.chooseVocab(vocabFunctions(), head) instanceof Miss);
    }

    /** {@code E.M}：首段非局部名、非函数且（静默探测）为枚举类型时，才正式解析并按 enumMembers[E] 解析成员。 */
    private Resolved resolveQualified(Scope scope, String written, Span span) {
        String[] parts = written.split("\\.", -1);
        if (parts.length != 2 || headIsValue(scope, parts[0])) return null;
        String probed = probeType(parts[0]);
        if (!isEnum(probed != null ? probed : CaseFold.nfc(parts[0]))) return null;
        String enumName = resolveTypeName(parts[0], span).canonical();
        RefResult member = resolveMember(enumName, parts[1], span);
        if (!member.found()) reportUnresolved(ErrorCode.UNRESOLVED_NAME, parts[1], ResolvedRole.MEMBER_REF, span);
        return new Resolved(enumName + "." + member.canonical(), ResolvedRole.MEMBER_REF, written);
    }

    private RefResult resolveMember(String enumName, String written, Span span) {
        List<VocabMap> maps = scopedVocab(VocabularyScopes::enumMembers, enumName, ResolvedRole.MEMBER_REF);
        RefSite site = new RefSite(written, ResolvedRole.MEMBER_REF, span, ErrorCode.AMBIGUOUS_NAME);
        return resolveRef(site, members.getOrDefault(enumName, List.of()), maps);
    }

    private Expr.Name resolveName(Scope scope, Expr.Name n) {
        Resolved resolved = n.name().contains(".")
            ? resolveQualified(scope, n.name(), n.span())
            : resolveValueName(scope, n.name(), n.span());
        return resolved == null ? n : new Expr.Name(n.name(), n.span(), resolved);
    }

    // ---- 类型 ----

    private Type resolveType(Scope scope, Type t) {
        return switch (t) {
            // 经 R13 筛选后仍保留的类型参数（隐式或显式）不查词汇
            case Type.TypeName tn -> scope.typeParams.contains(tn.name()) ? tn
                : new Type.TypeName(tn.name(), tn.annotations(), tn.span(), resolveTypeName(tn.name(), tn.span()));
            case Type.TypeApp ta -> new Type.TypeApp(ta.base(), ta.annotations(), resolveTypes(scope, ta.args()),
                ta.span(), resolveTypeName(ta.base(), ta.span()));
            case Type.Maybe m -> new Type.Maybe(resolveType(scope, m.type()), m.annotations(), m.span());
            case Type.Option o -> new Type.Option(resolveType(scope, o.type()), o.annotations(), o.span());
            case Type.List l -> new Type.List(resolveType(scope, l.type()), l.annotations(), l.span());
            case Type.Result r -> new Type.Result(resolveType(scope, r.ok()), resolveType(scope, r.err()),
                r.annotations(), r.span());
            case Type.Map m -> new Type.Map(resolveType(scope, m.key()), resolveType(scope, m.val()),
                m.annotations(), m.span());
            case Type.FuncType f -> new Type.FuncType(resolveTypes(scope, f.params()), resolveType(scope, f.ret()),
                f.annotations(), f.span());
            case Type.TypeVar v -> v;
        };
    }

    private List<Type> resolveTypes(Scope scope, List<Type> ts) {
        return ts.stream().map(x -> resolveType(scope, x)).toList();
    }

    /** 参数：名为 LOCAL（不查词汇），类型递归解析；已知类型名记入作用域供模式匹配使用。 */
    private Decl.Parameter resolveParam(Scope scope, Decl.Parameter p) {
        Type type = resolveType(scope, p.type());
        String typeName = type instanceof Type.TypeName tn && tn.resolved() != null ? tn.resolved().canonical() : null;
        scope.locals.add(new Local(CaseFold.nfc(p.name()), typeName));
        Resolved resolved = new Resolved(p.name(), ResolvedRole.LOCAL, p.name());
        return new Decl.Parameter(p.name(), type, p.annotations(), p.span(), resolved, p.typeInferred());
    }

    // ---- 表达式 ----

    private Expr resolveExpr(Scope scope, Expr e) {
        return switch (e) {
            case Expr.Name n -> resolveName(scope, n);
            case Expr.Call c -> new Expr.Call(resolveExpr(scope, c.target()), resolveExprs(scope, c.args()), c.span(), c.ufcs());
            case Expr.Construct c -> resolveConstruct(scope, c);
            case Expr.Ok o -> new Expr.Ok(resolveExpr(scope, o.expr()), o.span());
            case Expr.Err x -> new Expr.Err(resolveExpr(scope, x.expr()), x.span());
            case Expr.Some s -> new Expr.Some(resolveExpr(scope, s.expr()), s.span());
            case Expr.Await a -> new Expr.Await(resolveExpr(scope, a.expr()), a.span());
            case Expr.IfExpr i -> new Expr.IfExpr(resolveExpr(scope, i.cond()), resolveExpr(scope, i.thenExpr()),
                resolveExpr(scope, i.elseExpr()), i.span());
            case Expr.ListLiteral l -> new Expr.ListLiteral(resolveExprs(scope, l.items()), l.span());
            case Expr.Lambda l -> resolveLambda(scope, l);
            case Expr.Bool ignored -> e;
            case Expr.Int ignored -> e;
            case Expr.Long ignored -> e;
            case Expr.Double ignored -> e;
            case Expr.Decimal ignored -> e;
            case Expr.String ignored -> e;
            case Expr.Null ignored -> e;
            case Expr.None ignored -> e;
        };
    }

    private List<Expr> resolveExprs(Scope scope, List<Expr> es) {
        return es.stream().map(x -> resolveExpr(scope, x)).toList();
    }

    /** {@code X with f = …}：X 按 TYPE 解析，字段按已解析的 X 查 fields[X]；X 已报 E800 时不再逐个报 E801。 */
    private Expr.Construct resolveConstruct(Scope scope, Expr.Construct e) {
        TypeResolution type = resolveTypeNameChecked(e.typeName(), leadingSpan(e.span(), e.typeName()));
        String parent = type.resolved().canonical();
        List<Entry> decls = fields.getOrDefault(parent, List.of());
        List<VocabMap> maps = scopedVocab(VocabularyScopes::fields, parent, ResolvedRole.FIELD_REF);
        List<Expr.Construct.ConstructField> out = e.fields().stream()
            .map(f -> resolveConstructField(scope, f, type.unresolved() ? null : decls, maps, f.span() != null ? f.span() : e.span()))
            .toList();
        return new Expr.Construct(e.typeName(), out, e.span(), type.resolved());
    }

    /** decls 为 null 表示构造类型未解析：只解析字段值表达式。span 为该字段条目位置（缺失时为整个构造表达式）。 */
    private Expr.Construct.ConstructField resolveConstructField(Scope scope, Expr.Construct.ConstructField f,
                                                                List<Entry> decls, List<VocabMap> maps, Span span) {
        Expr expr = resolveExpr(scope, f.expr());
        if (decls == null) return new Expr.Construct.ConstructField(f.name(), expr, null, f.span());
        RefSite site = new RefSite(f.name(), ResolvedRole.FIELD_REF, span, ErrorCode.AMBIGUOUS_FIELD);
        RefResult ref = resolveRef(site, decls, maps);
        if (!ref.found()) reportUnresolved(ErrorCode.UNRESOLVED_FIELD, f.name(), ResolvedRole.FIELD_REF, span);
        return new Expr.Construct.ConstructField(f.name(), expr,
            new Resolved(ref.canonical(), ResolvedRole.FIELD_REF, f.name()), f.span());
    }

    /** 构造表达式不单独记录类型名 span：取表达式起点加类型名长度（类型名为单个 token）。 */
    private static Span leadingSpan(Span span, String text) {
        if (span == null || span.start() == null) return span;
        Span.Position start = span.start();
        return new Span(start, new Span.Position(start.line(), start.col() + text.length()));
    }

    private Expr.Lambda resolveLambda(Scope scope, Expr.Lambda e) {
        Scope inner = scope.child();
        List<Decl.Parameter> params = e.params().stream().map(p -> resolveParam(inner, p)).toList();
        Type retType = e.retType() == null ? null : resolveType(inner, e.retType());
        return new Expr.Lambda(params, retType, resolveBlock(inner, e.body()), e.span());
    }

    // ---- 语句 ----

    private Block resolveBlock(Scope scope, Block b) {
        Scope inner = scope.child();
        return new Block(b.statements().stream().map(s -> resolveStmt(inner, s)).toList(), b.span());
    }

    private Block resolveOptionalBlock(Scope scope, Block b) {
        return b == null ? null : resolveBlock(scope, b);
    }

    private Stmt resolveStmt(Scope scope, Stmt s) {
        return switch (s) {
            case Stmt.Let let -> resolveLet(scope, let);
            // Set 目标属于 FIELD_REF / LOCAL 的类型驱动解析（R2）
            case Stmt.Set set -> new Stmt.Set(set.name(), resolveExpr(scope, set.expr()), set.span());
            case Stmt.Return ret -> resolveReturn(scope, ret);
            case Stmt.If i -> new Stmt.If(resolveExpr(scope, i.cond()), resolveBlock(scope, i.thenBlock()),
                resolveOptionalBlock(scope, i.elseBlock()), i.span());
            case Stmt.Match m -> resolveMatch(scope, m);
            case Stmt.Start st -> {
                scope.add(st.name());
                yield new Stmt.Start(st.name(), resolveExpr(scope, st.expr()), st.span());
            }
            case Stmt.Wait w -> w;
            case Stmt.Workflow wf -> resolveWorkflow(scope, wf);
            case Block b -> resolveBlock(scope, b);
        };
    }

    private Stmt.Return resolveReturn(Scope scope, Stmt.Return ret) {
        return new Stmt.Return(resolveExpr(scope, ret.expr()), ret.span());
    }

    private Stmt.Let resolveLet(Scope scope, Stmt.Let s) {
        Expr expr = resolveExpr(scope, s.expr());
        scope.add(s.name());
        return new Stmt.Let(s.name(), expr, s.span(), new Resolved(s.name(), ResolvedRole.LOCAL, s.name()));
    }

    /** 工作流与步骤名为源码局部声明、retry / timeout 为语法配置：均不查词汇，只解析步骤体。 */
    private Stmt.Workflow resolveWorkflow(Scope scope, Stmt.Workflow wf) {
        List<Stmt.WorkflowStep> steps = wf.steps().stream()
            .map(step -> new Stmt.WorkflowStep(step.name(), resolveBlock(scope, step.body()),
                resolveOptionalBlock(scope, step.compensate()), step.dependencies(), step.span()))
            .toList();
        return new Stmt.Workflow(steps, wf.retry(), wf.timeout(), wf.span());
    }

    /** Match 主语为已知类型的局部名（参数）时，PatternCtor 可在 R1 解析。 */
    private static String subjectType(Scope scope, Expr e) {
        if (!(e instanceof Expr.Name n) || n.name().contains(".")) return null;
        LocalChoice local = findLocal(scope, n.name());
        return local.choice() instanceof Hit && local.local() != null ? local.local().typeName() : null;
    }

    private Stmt.Match resolveMatch(Scope scope, Stmt.Match s) {
        Expr expr = resolveExpr(scope, s.expr());
        String subject = subjectType(scope, s.expr());
        List<Stmt.Case> cases = s.cases().stream().map(c -> resolveCase(scope.child(), c, subject)).toList();
        return new Stmt.Match(expr, cases, s.span());
    }

    private Stmt.Case resolveCase(Scope scope, Stmt.Case c, String subject) {
        Pattern pattern = resolvePattern(scope, c.pattern(), subject);
        Stmt.Case.CaseBody body = switch (c.body()) {
            case Block b -> resolveBlock(scope, b);
            case Stmt.Return ret -> resolveReturn(scope, ret);
        };
        return new Stmt.Case(pattern, body, c.span());
    }

    // ---- 模式 ----

    private Pattern resolvePattern(Scope scope, Pattern p, String subject) {
        return switch (p) {
            // 绑定名不查词汇；期望枚举下的裸名是否为成员由 R2 判定，故不写 resolved
            case Pattern.PatternName n -> {
                scope.add(n.name());
                yield n;
            }
            case Pattern.PatternCtor c -> resolvePatternCtor(scope, c, subject);
            case Pattern.PatternNull ignored -> p;
            case Pattern.PatternInt ignored -> p;
        };
    }

    /**
     * Java 解析器把无括号的裸 TYPE_IDENT（{@code When Hoch,}）也产出为无参数的 PatternCtor，TS 则为
     * PatternName：与 TS 一致按绑定名 / 期望枚举下的裸名处理，留给 R2（裁决 R12），不写 resolved。
     */
    private Pattern resolvePatternCtor(Scope scope, Pattern.PatternCtor p, String subject) {
        if (p.args() == null || p.args().isEmpty()) {
            scope.add(p.typeName());
            return p;
        }
        if (p.names() != null) p.names().forEach(scope::add);
        List<Pattern> args = p.args().stream().map(a -> resolvePattern(scope, a, null)).toList();
        return new Pattern.PatternCtor(p.typeName(), p.names(), args, p.span(), ctorResolution(p, subject));
    }

    private Resolved ctorResolution(Pattern.PatternCtor p, String subject) {
        if (subject == null || ResolutionChoice.KEYWORD_CTORS.contains(p.typeName())) return null;
        if (!isEnum(subject)) return resolveTypeName(p.typeName(), p.span());
        RefResult member = resolveMember(subject, p.typeName(), p.span());
        if (!member.found()) reportUnresolved(ErrorCode.UNRESOLVED_PATTERN, p.typeName(), ResolvedRole.MEMBER_REF, p.span());
        return new Resolved(member.canonical(), ResolvedRole.MEMBER_REF, p.typeName());
    }

    // ---- 声明节点改写 ----

    private Decl rewriteDecl(Decl d) {
        Resolved resolved = byDecl.get(d);
        return switch (d) {
            case Decl.Import ignored -> d;
            case Decl.Data data -> rewriteData(data, resolved);
            case Decl.Enum en -> new Decl.Enum(en.name(), en.variants(), en.span(), resolved, variantNames.get(en),
                en.variantSpans());
            // 别名右侧与 TS 一致不解析（TS AST 不保留右侧），只写入别名名的 TYPE_DECL
            case Decl.TypeAlias alias -> new Decl.TypeAlias(alias.annotations(), alias.name(), alias.type(),
                alias.span(), resolved);
            case Decl.Func f -> rewriteFunc(f, resolved);
        };
    }

    private Decl.Data rewriteData(Decl.Data d, Resolved resolved) {
        Scope scope = rootScope(List.of());
        List<Resolved> names = fieldNames.get(d);
        List<Decl.Field> out = new ArrayList<>();
        for (int i = 0; i < d.fields().size(); i++) {
            Decl.Field f = d.fields().get(i);
            out.add(new Decl.Field(f.name(), resolveType(scope, f.type()), f.annotations(), f.typeInferred(), names.get(i),
                f.span()));
        }
        return new Decl.Data(d.name(), out, d.span(), resolved);
    }

    private Decl.Func rewriteFunc(Decl.Func d, Resolved resolved) {
        List<String> typeParams = keptTypeParams(d);
        Scope scope = rootScope(typeParams);
        List<Decl.Parameter> params = d.params().stream().map(p -> resolveParam(scope, p)).toList();
        Type retType = d.retTypeInferred() ? inferredRetType(d.retType()) : resolveType(scope, d.retType());
        Block body = resolveOptionalBlock(scope, d.body());
        return new Decl.Func(d.name(), d.nameSpan(), typeParams, params, retType, d.annotations(), d.retAnnotations(),
            body, d.effects(), d.effectCaps(), d.effectCapsExplicit(), d.span(), d.retTypeInferred(),
            d.typeParamsInferred(), resolved);
    }

    /**
     * 省略返回类型时 retType 是解析器按函数名合成的占位（如 getKunde → Kunde）：静默探测，命中声明 / 导入 /
     * 词汇时写入规范名（与 R13 移除同名隐式类型参数配套，避免本地化写法流入 lowering）；未命中保持原样，不报 E800。
     */
    private Type inferredRetType(Type retType) {
        if (!(retType instanceof Type.TypeName tn) || !(probeTypeChoice(tn.name()) instanceof Hit h)) return retType;
        Resolved stamped = new Resolved(h.entry().canonical(), ResolvedRole.TYPE, tn.name());
        return new Type.TypeName(tn.name(), tn.annotations(), tn.span(), stamped);
    }

    /**
     * 裁决 R13：解析器把签名中未声明的大写类型名推断为隐式类型参数（typeParamsInferred）；
     * 显式 {@code of T} 一律保留。隐式参数若能作为 TYPE 命中源码声明 / 导入 / 词汇，说明它其实是类型名，
     * 从 typeParams 中移除；全部未命中的保留为类型参数。
     */
    private List<String> keptTypeParams(Decl.Func d) {
        if (!d.typeParamsInferred()) return d.typeParams();
        return d.typeParams().stream().filter(name -> !isImplicitTypeName(name, d.span())).toList();
    }

    /** 隐式参数命中类型 → true（应移除）；折叠歧义时报 E804 并保留为类型参数，使编译失败而非静默当作泛型。 */
    private boolean isImplicitTypeName(String name, Span span) {
        Choice picked = probeTypeChoice(name);
        if (picked instanceof Ambiguous a) reportAmbiguous(name, ResolvedRole.TYPE, a.candidates(), span);
        return picked instanceof Hit;
    }
}
