package aster.core.embed;

import aster.core.ast.Block;
import aster.core.ast.Decl;
import aster.core.ast.Expr;
import aster.core.ast.Module;
import aster.core.ast.Pattern;
import aster.core.ast.Span;
import aster.core.ast.Stmt;
import aster.core.ir.CoreModel;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.typecheck.DiagnosticBuilder;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 关键词被改名为名字的警告（ADR 0056 修订 A6 K3，诊断 W802 {@code KEYWORD_RENAMED_AS_NAME}）。
 *
 * <p>非英文语言包下，规范化把关键词的本地化拼写译成英文规范拼写，名字位置的同形词随之被改名：zh 字段 {@code 版本}
 * 的运行时键变成 {@code version}，de {@code erlauben}、hi {@code अनुमति} 变成 {@code allow}。作者与审阅者读到的是原词，
 * 却没有任何诊断。产品裁决（2026-11-22）：解析行为不变（两引擎都按关键词的英文拼写取名），改为两引擎同码同文发出警告；
 * 是否升级为错误（Q4）待门禁扫描后决定。
 *
 * <p>判据只依赖 AST 里的名字文本与源码行，两引擎结果相同（core 的规范化没有源映射，不能逐 token 知道哪个词是翻译来的）：
 * 语言包不是 en-US 时，令 W 为每个 kind（TYPE_ALIAS 除外——它不做全局翻译，名字位置的 {@code 类型} 照常是名字）的 en-US
 * 规范拼写，条件是该拼写是单个词，且本语言包的规范拼写或官方别名中至少有一个与它不同（否则不会发生改名）。名字等于
 * W 中某个拼写、而它所在的源码行里没有这个英文拼写的整词出现时报 W802——这时名字只能来自关键词翻译。作者在非英文源码里
 * 直接写英文拼写（zh 源码里的 {@code allow}）不报：IR 名字就是所写的词。同一行既写英文拼写又写本地化关键词作名字时会漏报，
 * 只影响警告。
 *
 * <p>检查的位置：声明出的名字（数据类型、字段、枚举与成员、类型别名、规则、类型参数、形参含 lambda、Let / Set / Start /
 * Wait / 工作流步骤、构造字段、构造模式的绑定名）与点号名字的非首段（成员访问、限定调用）。不检查类型引用与名字引用的首段：
 * 内置类型（{@code 整数} → {@code Int}）与运算形式的调用（{@code 不是(x)} → {@code not(x)}）在这些位置本来就是关键词。
 * 与 aster-lang-ts {@code keyword-name-guard.ts} 逐项对应。
 *
 * <p>名字转义（ADR 0064）不需要额外判定：{@code `版本`} 不经翻译，名字就是 {@code 版本}，不等于 W 中任何拼写；
 * {@code `version`} 的英文拼写整词出现在本行（反引号不是标识符字符），同样不命中。help 建议的正是转义写法。
 */
public final class KeywordNameGuard {

    private KeywordNameGuard() {}

    /** W 中一项：命中时诊断里报告的 kind 与本语言包的规范拼写。 */
    record Hit(SemanticTokenKind kind, String keyword) {}

    /**
     * 本语言包的 W：英文规范拼写 → 命中信息。kind 按枚举名排序处理，两个 kind 英文拼写相同时取先者，与 ts 同序。
     */
    static Map<String, Hit> collisionWords(Lexicon lexicon) {
        Map<String, Hit> out = new LinkedHashMap<>();
        if (lexicon == null || "en-US".equalsIgnoreCase(lexicon.getId())) {
            return out;
        }
        Lexicon english = englishLexicon();
        TreeMap<String, SemanticTokenKind> kinds = new TreeMap<>();
        english.getKeywords().keySet().forEach(k -> kinds.put(k.name(), k));
        for (SemanticTokenKind kind : kinds.values()) {
            if (kind == SemanticTokenKind.TYPE_ALIAS) {
                continue;
            }
            String en = english.getKeywords().get(kind);
            if (en == null || en.isBlank() || en.chars().anyMatch(Character::isWhitespace)) {
                continue;
            }
            List<String> spellings = new ArrayList<>();
            String local = lexicon.getKeywords().get(kind);
            if (local != null && !local.isBlank()) {
                spellings.add(local);
            }
            List<String> aliases = lexicon.getAliases() == null ? null : lexicon.getAliases().get(kind);
            if (aliases != null) {
                aliases.stream().filter(a -> a != null && !a.isBlank()).forEach(spellings::add);
            }
            if (spellings.stream().anyMatch(s -> !s.equals(en))) {
                out.putIfAbsent(en, new Hit(kind, spellings.get(0)));
            }
        }
        return out;
    }

    /**
     * 检查 AST，返回 W802 警告（按行、名字排序；同一名字同一位置只报一次）。语言包为 en-US 或缺失时返回空表。
     */
    public static List<Diagnostic> check(Module ast, Lexicon lexicon, String source) {
        Map<String, Hit> words = collisionWords(lexicon);
        if (words.isEmpty() || ast == null) {
            return List.of();
        }
        Collector collector = new Collector(words, source == null ? List.of() : List.of(source.split("\n", -1)));
        collector.module(ast);
        List<Diagnostic> out = new ArrayList<>(collector.diags.getDiagnostics());
        out.sort(Comparator.comparingInt(KeywordNameGuard::lineOf)
            .thenComparing(d -> String.valueOf(d.data().get("name"))));
        return List.copyOf(out);
    }

    private static int lineOf(Diagnostic d) {
        return d.span().map(o -> o.start == null ? 0 : o.start.line).orElse(0);
    }

    private static Lexicon englishLexicon() {
        LexiconRegistry registry = LexiconRegistry.getInstance();
        return registry.has("en-us") ? registry.getOrThrow("en-us") : registry.getDefault();
    }

    /** AST 遍历：与 ts {@code keyword-name-guard.ts} 的 Collector 逐项对应。 */
    private static final class Collector {
        private final Map<String, Hit> words;
        private final List<String> lines;
        private final DiagnosticBuilder diags = new DiagnosticBuilder();
        private final Set<String> reported = new HashSet<>();

        Collector(Map<String, Hit> words, List<String> lines) {
            this.words = words;
            this.lines = lines;
        }

        /** 声明出的名字：整体比较。 */
        void declared(String name, Span span) {
            if (name != null) {
                hit(name, span);
            }
        }

        /** 名字引用：只看点号名字的非首段（成员访问、限定调用）。 */
        void referenced(String name, Span span) {
            if (name == null) {
                return;
            }
            String[] segments = name.split("\\.", -1);
            for (int i = 1; i < segments.length; i++) {
                hit(segments[i], span);
            }
        }

        private void hit(String segment, Span span) {
            Hit hit = words.get(segment);
            if (hit == null) {
                return;
            }
            int line = span == null || span.start() == null ? 1 : span.start().line();
            int col = span == null || span.start() == null ? 1 : span.start().col();
            if (writtenInEnglish(line, segment) || !reported.add(line + ":" + col + ":" + segment)) {
                return;
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", segment);
            params.put("keyword", hit.keyword());
            params.put("kind", hit.kind().name());
            diags.warning(ErrorCode.KEYWORD_RENAMED_AS_NAME, origin(span), params);
        }

        /** 源码第 {@code line} 行是否有 {@code word} 的整词出现（两侧都不是 ASCII 字母、数字或下划线；W 的拼写都是 ASCII）。 */
        private boolean writtenInEnglish(int line, String word) {
            if (line < 1 || line > lines.size()) {
                return false;
            }
            String text = lines.get(line - 1);
            for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + 1)) {
                if (!isAsciiWordChar(text, i - 1) && !isAsciiWordChar(text, i + word.length())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isAsciiWordChar(String text, int index) {
            if (index < 0 || index >= text.length()) {
                return false;
            }
            char c = text.charAt(index);
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
        }

        void module(Module m) {
            each(m.decls(), this::decl);
        }

        void decl(Decl d) {
            switch (d) {
                case Decl.Import _ -> { }
                case Decl.Data data -> {
                    declared(data.name(), data.span());
                    each(data.fields(), f -> declared(f.name(), f.span() != null ? f.span() : data.span()));
                }
                case Decl.Enum e -> {
                    declared(e.name(), e.span());
                    List<String> variants = e.variants() == null ? List.of() : e.variants();
                    for (int i = 0; i < variants.size(); i++) {
                        Span s = e.variantSpans() != null && i < e.variantSpans().size() ? e.variantSpans().get(i) : e.span();
                        declared(variants.get(i), s);
                    }
                }
                case Decl.TypeAlias t -> declared(t.name(), t.span());
                case Decl.Func f -> {
                    declared(f.name(), f.nameSpan() != null ? f.nameSpan() : f.span());
                    each(f.typeParams(), p -> declared(p, f.span()));
                    each(f.params(), p -> declared(p.name(), p.span() != null ? p.span() : f.span()));
                    block(f.body());
                }
            }
        }

        void block(Block b) {
            if (b != null) {
                each(b.statements(), this::stmt);
            }
        }

        void stmt(Stmt s) {
            switch (s) {
                case Stmt.Let l -> {
                    declared(l.name(), l.span());
                    expr(l.expr());
                }
                case Stmt.Set st -> {
                    declared(st.name(), st.span());
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
                    declared(st.name(), st.span());
                    expr(st.expr());
                }
                case Stmt.Wait w -> each(w.names(), n -> declared(n, w.span()));
                case Stmt.Workflow w -> each(w.steps(), step -> {
                    declared(step.name(), step.span());
                    block(step.body());
                    block(step.compensate());
                });
                case Block b -> block(b);
            }
        }

        void expr(Expr e) {
            switch (e) {
                case null -> { }
                case Expr.Name n -> referenced(n.name(), n.span());
                case Expr.Call c -> {
                    expr(c.target());
                    each(c.args(), this::expr);
                }
                case Expr.ListLiteral l -> each(l.items(), this::expr);
                case Expr.Construct c -> each(c.fields(), f -> {
                    declared(f.name(), f.span() != null ? f.span() : c.span());
                    expr(f.expr());
                });
                case Expr.Ok o -> expr(o.expr());
                case Expr.Err o -> expr(o.expr());
                case Expr.Some o -> expr(o.expr());
                case Expr.Await o -> expr(o.expr());
                case Expr.Lambda l -> {
                    each(l.params(), p -> declared(p.name(), p.span() != null ? p.span() : l.span()));
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
                    each(c.names(), n -> declared(n, c.span()));
                    each(c.args(), this::pattern);
                }
                // 模式名是枚举成员或 none 等关键词形式的引用，不是声明，不检查
                case Pattern.PatternName _, Pattern.PatternNull _, Pattern.PatternInt _ -> { }
            }
        }

        private static Optional<CoreModel.Origin> origin(Span span) {
            if (span == null || span.start() == null) {
                return Optional.empty();
            }
            CoreModel.Origin o = new CoreModel.Origin();
            o.start = position(span.start());
            o.end = position(span.end() != null ? span.end() : span.start());
            return Optional.of(o);
        }

        private static CoreModel.Position position(Span.Position p) {
            CoreModel.Position out = new CoreModel.Position();
            out.line = p.line();
            out.col = p.col();
            return out;
        }

        private static <T> void each(List<T> items, Consumer<T> action) {
            if (items != null) {
                items.forEach(action);
            }
        }
    }
}
