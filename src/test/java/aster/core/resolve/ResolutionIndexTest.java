package aster.core.resolve;

import aster.core.ast.ResolvedRole;
import aster.core.resolve.ResolutionChoice.DeclTable;
import aster.core.resolve.ResolutionChoice.Entry;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 名称解析的索引（aster-lang-core#312）与此前逐项比对的结果相同：{@link DeclTable} 对照 {@link ResolutionChoice#chooseDecl(List, String)}，
 * {@link LocalScope} 对照「由内向外、同层后声明者在前」的可见列表。名字取自一个小字母表（含大小写与 NFC 变体），
 * 让精确、折叠与歧义三种结果都经常出现。
 */
class ResolutionIndexTest {

    private static final List<String> WORDS = List.of("a", "A", "ab", "Ab", "AB", "é", "é", "É", "x");

    @Provide
    Arbitrary<List<Entry>> entries() {
        Arbitrary<String> word = Arbitraries.of(WORDS);
        Arbitrary<ResolvedRole> role = Arbitraries.of(ResolvedRole.FUNC_REF, ResolvedRole.TYPE);
        return Combinators.combine(word, word, role).as(Entry::new).list().ofMaxSize(12);
    }

    @Provide
    Arbitrary<String> word() {
        return Arbitraries.of(WORDS);
    }

    @Property(tries = 500)
    void declTableMatchesLinearScan(@ForAll("entries") List<Entry> first, @ForAll("entries") List<Entry> second,
                                    @ForAll("word") String written) {
        DeclTable a = new DeclTable();
        DeclTable b = new DeclTable();
        first.forEach(a::add);
        second.forEach(b::add);
        List<Entry> concatenated = new ArrayList<>(first);
        concatenated.addAll(second);
        assertThat(ResolutionChoice.chooseDecl(written, a, b)).isEqualTo(ResolutionChoice.chooseDecl(concatenated, written));
    }

    /** 一层：本层声明的名字（按声明顺序）。 */
    @Provide
    Arbitrary<List<List<String>>> layers() {
        return Arbitraries.of(WORDS).list().ofMaxSize(6).list().ofMinSize(1).ofMaxSize(4);
    }

    @Property(tries = 500)
    void localScopeMatchesVisibleList(@ForAll("layers") List<List<String>> layers, @ForAll("word") String written) {
        LocalScope<String> scope = null;
        List<String> visible = new ArrayList<>();
        for (int depth = 0; depth < layers.size(); depth++) {
            List<String> layer = layers.get(depth);
            scope = new LocalScope<>(scope);
            for (int i = 0; i < layer.size(); i++) scope.add(layer.get(i), layer.get(i) + "@" + depth + "#" + i);
            List<String> inner = new ArrayList<>();
            for (int i = layer.size() - 1; i >= 0; i--) inner.add(layer.get(i) + "@" + depth + "#" + i);
            visible.addAll(0, inner);
        }
        String key = aster.core.identifier.CaseFold.foldKey(written);
        List<String> wantFolded = visible.stream().filter(v -> aster.core.identifier.CaseFold.foldKey(nameOf(v)).equals(key)).toList();
        String wantExact = visible.stream().filter(v -> nameOf(v).equals(written)).findFirst().orElse(null);
        assertThat(scope.folded(key)).isEqualTo(wantFolded);
        assertThat(scope.nearest(written)).isEqualTo(wantExact);
    }

    @Test
    void sameLayerLaterDeclarationWins() {
        LocalScope<String> outer = new LocalScope<>(null);
        outer.add("x", "outer");
        LocalScope<String> inner = outer.child();
        inner.add("x", "first");
        inner.add("x", "second");
        inner.add("X", "upper");
        assertThat(inner.nearest("x")).isEqualTo("second");
        assertThat(inner.folded("x")).containsExactly("upper", "second", "first", "outer");
        assertThat(outer.nearest("X")).isNull();
    }

    private static String nameOf(String v) {
        return v.substring(0, v.indexOf('@'));
    }
}
