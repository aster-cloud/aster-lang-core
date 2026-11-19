package aster.core.embed;

import aster.core.ast.Block;
import aster.core.ast.Decl;
import aster.core.ast.Expr;
import aster.core.ast.Module;
import aster.core.ast.Pattern;
import aster.core.ast.Span;
import aster.core.ast.Stmt;
import aster.core.ast.Type;
import aster.core.identifier.CaseFold;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.typecheck.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * IR 名字守卫（ADR 0060 §5.3 第 2 条，诊断 E808 {@code ALIAS_IDENTIFIER_COLLISION}）。
 *
 * <p>汉字单词别名没有空格，可能与源码里的名字同形。结构词在名字位置是软关键字（{@code structKeywordName}），
 * 若不拦截，参数 {@code 低头} 在 {@code RETURN → 低头} 下会被一致改名为 {@code Return} 并照常编译——
 * 字段名变了、输出记录的键也变了，这是 ADR 0060 资产 A2 要防的静默改义。
 *
 * <p>规则：只在 aliasSet 含汉字单词别名时启用。令 K 为每个带汉字单词别名的 kind 在 en-US 词法表中的全部拼写
 * （规范拼写 + 官方别名）加上这些汉字别名本身，均取折叠键；解析后（名称解析之前）AST 里任一名字——
 * 点号分隔的名字逐段检查——折叠后属于 K 即失败。包含别名本身是因为两引擎在名字位置的处理不必相同
 * （core 在文本层把别名译成英文关键词，ts 的上下文关键词模型可能保留原写法）。多词别名不需要本守卫：含空格，不可能是名字。
 *
 * <p>检查的名字：模块名、导入路径与导入别名、Data / 枚举 / 类型别名 / 规则的声明名、字段、枚举成员、类型参数、
 * 参数（含 lambda）、Let / Set / Start / Wait / 工作流步骤名、名字引用与成员访问、调用目标、构造类型与构造字段、
 * 模式里的构造名、绑定名与模式名、类型引用。与 aster-lang-ts {@code alias-identifier-guard.ts} 逐项对应。
 */
public final class AliasIdentifierGuard {

    private AliasIdentifierGuard() {}

    /** 守卫命中：携带 E808 诊断（按源码遍历顺序，同一名字同一位置只报一次）。 */
    public static final class AliasIdentifierCollision extends InProcessCnlParser.CnlParseException {
        private static final long serialVersionUID = 1L;

        AliasIdentifierCollision(String message, List<CnlErrorListener.Diagnostic> diagnostics) {
            super(message, diagnostics);
        }
    }

    /** K 中一项：命中时诊断里报告的 kind 与别名。 */
    private record Hit(SemanticTokenKind kind, String alias) {}

    /**
     * 检查 AST；aliasSet 不含汉字单词别名时直接返回。
     *
     * @throws AliasIdentifierCollision 任一名字折叠后属于 K
     */
    public static void check(Module ast, Map<SemanticTokenKind, List<String>> aliasSet, String locale) {
        Map<String, Hit> forbidden = forbiddenKeys(aliasSet, locale);
        if (forbidden.isEmpty() || ast == null) {
            return;
        }
        Collector collector = new Collector(forbidden);
        collector.module(ast);
        if (!collector.diagnostics.isEmpty()) {
            String summary = "别名名字守卫失败 — " + String.join("; ",
                collector.diagnostics.stream().map(CnlErrorListener.Diagnostic::message).toList());
            throw new AliasIdentifierCollision(summary, collector.diagnostics);
        }
    }

    /**
     * 折叠键 → 命中信息。kind 按枚举名、别名按字典序处理，使「同一折叠键对应多个 kind」时报告的 kind 确定
     * （校验器已禁止跨 kind 重复别名，这只在英文拼写相同的两个 kind 上可能发生）。
     */
    static Map<String, Hit> forbiddenKeys(Map<SemanticTokenKind, List<String>> aliasSet, String locale) {
        Map<String, Hit> out = new LinkedHashMap<>();
        if (aliasSet == null || aliasSet.isEmpty()) {
            return out;
        }
        TreeMap<String, SemanticTokenKind> kinds = new TreeMap<>();
        aliasSet.keySet().forEach(k -> kinds.put(k.name(), k));
        Lexicon english = englishLexicon();
        for (SemanticTokenKind kind : kinds.values()) {
            TreeSet<String> han = new TreeSet<>();
            List<String> aliases = aliasSet.get(kind);
            if (aliases != null) {
                for (String alias : aliases) {
                    if (alias != null && UserAliasValidator.isHanSingleToken(normalize(alias), locale)) {
                        han.add(normalize(alias));
                    }
                }
            }
            if (han.isEmpty()) {
                continue;
            }
            String reported = han.first();
            for (String alias : han) {
                out.putIfAbsent(CaseFold.foldKey(alias), new Hit(kind, alias));
            }
            for (String spelling : englishSpellings(english, kind)) {
                out.putIfAbsent(CaseFold.foldKey(spelling), new Hit(kind, reported));
            }
        }
        return out;
    }

    private static List<String> englishSpellings(Lexicon english, SemanticTokenKind kind) {
        List<String> out = new ArrayList<>();
        String canonical = english.getKeywords().get(kind);
        if (canonical != null && !canonical.isBlank()) {
            out.add(canonical);
        }
        Map<SemanticTokenKind, List<String>> aliases = english.getAliases();
        if (aliases != null && aliases.get(kind) != null) {
            aliases.get(kind).stream().filter(a -> a != null && !a.isBlank()).forEach(out::add);
        }
        return out;
    }

    private static Lexicon englishLexicon() {
        LexiconRegistry registry = LexiconRegistry.getInstance();
        return registry.has("en-us") ? registry.getOrThrow("en-us") : registry.getDefault();
    }

    /** 与 {@link UserAliasValidator} 同一归一（trim + 折叠空白 + 小写）。 */
    private static String normalize(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** AST 遍历：按源码顺序收集命中。 */
    private static final class Collector {
        private final Map<String, Hit> forbidden;
        private final List<CnlErrorListener.Diagnostic> diagnostics = new ArrayList<>();
        private final java.util.Set<String> reported = new java.util.HashSet<>();

        Collector(Map<String, Hit> forbidden) {
            this.forbidden = forbidden;
        }

        /** 点号分隔的名字逐段检查（模块名、导入路径、成员访问、限定调用目标）。 */
        void name(String written, Span span) {
            if (written == null || written.isEmpty()) {
                return;
            }
            for (String segment : written.split("\\.", -1)) {
                Hit hit = forbidden.get(CaseFold.foldKey(segment));
                if (hit != null) {
                    report(segment, hit, span);
                }
            }
        }

        private void report(String segment, Hit hit, Span span) {
            int line = span == null || span.start() == null ? 1 : span.start().line();
            int col = span == null || span.start() == null ? 1 : span.start().col();
            if (!reported.add(line + ":" + col + ":" + segment)) {
                return;
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", segment);
            params.put("alias", hit.alias());
            params.put("kind", hit.kind().name());
            ErrorCode code = ErrorCode.ALIAS_IDENTIFIER_COLLISION;
            Map<String, String> stringParams = new LinkedHashMap<>();
            params.forEach((k, v) -> stringParams.put(k, String.valueOf(v)));
            diagnostics.add(new CnlErrorListener.Diagnostic(line, col,
                code.code() + " " + code.render(params), code.code(), stringParams));
        }

        void module(Module m) {
            name(m.name(), m.span());
            each(m.decls(), this::decl);
        }

        void decl(Decl d) {
            switch (d) {
                case Decl.Import i -> {
                    name(i.path(), i.span());
                    name(i.alias(), i.span());
                }
                case Decl.Data data -> {
                    name(data.name(), data.span());
                    each(data.fields(), f -> {
                        name(f.name(), f.span() != null ? f.span() : data.span());
                        type(f.type());
                    });
                }
                case Decl.Enum e -> {
                    name(e.name(), e.span());
                    List<String> variants = e.variants() == null ? List.of() : e.variants();
                    for (int i = 0; i < variants.size(); i++) {
                        Span s = e.variantSpans() != null && i < e.variantSpans().size() ? e.variantSpans().get(i) : e.span();
                        name(variants.get(i), s);
                    }
                }
                case Decl.TypeAlias t -> {
                    name(t.name(), t.span());
                    type(t.type());
                }
                case Decl.Func f -> {
                    name(f.name(), f.nameSpan() != null ? f.nameSpan() : f.span());
                    each(f.typeParams(), p -> name(p, f.span()));
                    each(f.params(), p -> parameter(p, f.span()));
                    type(f.retType());
                    block(f.body());
                }
            }
        }

        void parameter(Decl.Parameter p, Span fallback) {
            name(p.name(), p.span() != null ? p.span() : fallback);
            type(p.type());
        }

        void block(Block b) {
            if (b != null) {
                each(b.statements(), this::stmt);
            }
        }

        void stmt(Stmt s) {
            switch (s) {
                case Stmt.Let l -> {
                    name(l.name(), l.span());
                    expr(l.expr());
                }
                case Stmt.Set st -> {
                    name(st.name(), st.span());
                    expr(st.expr());
                }
                case Stmt.Return r -> expr(r.expr());
                case Stmt.If i -> {
                    expr(i.cond());
                    block(i.thenBlock());
                    block(i.elseBlock());
                }
                case Stmt.Match m -> {
                    expr(m.expr());
                    each(m.cases(), c -> {
                        pattern(c.pattern());
                        switch (c.body()) {
                            case Stmt.Return r -> expr(r.expr());
                            case Block b -> block(b);
                        }
                    });
                }
                case Stmt.Start st -> {
                    name(st.name(), st.span());
                    expr(st.expr());
                }
                case Stmt.Wait w -> each(w.names(), n -> name(n, w.span()));
                case Stmt.Workflow w -> each(w.steps(), step -> {
                    name(step.name(), step.span());
                    each(step.dependencies(), dep -> name(dep, step.span()));
                    block(step.body());
                    block(step.compensate());
                });
                case Block b -> block(b);
            }
        }

        void expr(Expr e) {
            switch (e) {
                case null -> { }
                case Expr.Name n -> name(n.name(), n.span());
                case Expr.Call c -> {
                    expr(c.target());
                    each(c.args(), this::expr);
                }
                case Expr.ListLiteral l -> each(l.items(), this::expr);
                case Expr.Construct c -> {
                    name(c.typeName(), c.span());
                    each(c.fields(), f -> {
                        name(f.name(), f.span() != null ? f.span() : c.span());
                        expr(f.expr());
                    });
                }
                case Expr.Ok o -> expr(o.expr());
                case Expr.Err o -> expr(o.expr());
                case Expr.Some o -> expr(o.expr());
                case Expr.Await o -> expr(o.expr());
                case Expr.Lambda l -> {
                    each(l.params(), p -> parameter(p, l.span()));
                    type(l.retType());
                    block(l.body());
                }
                case Expr.IfExpr i -> {
                    expr(i.cond());
                    expr(i.thenExpr());
                    expr(i.elseExpr());
                }
                case Expr.Bool _, Expr.Int _, Expr.Long _, Expr.Double _, Expr.Decimal _, Expr.String _,
                     Expr.Null _, Expr.None _ -> { }
            }
        }

        void pattern(Pattern p) {
            switch (p) {
                case null -> { }
                case Pattern.PatternCtor c -> {
                    name(c.typeName(), c.span());
                    each(c.names(), n -> name(n, c.span()));
                    each(c.args(), this::pattern);
                }
                case Pattern.PatternName n -> name(n.name(), n.span());
                case Pattern.PatternNull _, Pattern.PatternInt _ -> { }
            }
        }

        void type(Type t) {
            switch (t) {
                case null -> { }
                case Type.TypeName n -> name(n.name(), n.span());
                case Type.TypeVar v -> name(v.name(), v.span());
                case Type.TypeApp a -> {
                    name(a.base(), a.span());
                    each(a.args(), this::type);
                }
                case Type.Result r -> {
                    type(r.ok());
                    type(r.err());
                }
                case Type.Maybe m -> type(m.type());
                case Type.Option o -> type(o.type());
                case Type.List l -> type(l.type());
                case Type.Map m -> {
                    type(m.key());
                    type(m.val());
                }
                case Type.FuncType f -> {
                    each(f.params(), this::type);
                    type(f.ret());
                }
            }
        }

        private static <T> void each(List<T> items, java.util.function.Consumer<T> action) {
            if (items != null) {
                items.forEach(action);
            }
        }
    }
}
