package aster.core.resolve;

import aster.core.identifier.CaseFold;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 名称解析 R1 / R2 共用的局部名作用域链：每层按名字与折叠键索引局部名。
 *
 * <p>可见顺序为「由内向外、同层后声明者在前」（ADR 0051 §6.2 第 1 步）。查找只沿父链逐层查表，代价与嵌套深度成正比、
 * 与局部名个数无关；此前每次查找都拷贝并逐个比对全部可见局部名，名称解析随局部名个数二次增长（aster-lang-core#312）。
 *
 * @param <L> 局部名记录（R1 记类型名，R2 记静态类型）
 */
final class LocalScope<L> {

    private final LocalScope<L> parent;
    /** 名字 → 本层最后声明的同名局部名。 */
    private final Map<String, L> byName = new HashMap<>();
    /** 折叠键 → 本层同折叠键的局部名，按声明顺序。 */
    private final Map<String, List<L>> byFold = new HashMap<>();

    LocalScope(LocalScope<L> parent) {
        this.parent = parent;
    }

    LocalScope<L> child() {
        return new LocalScope<>(this);
    }

    /** 在本层声明；name 须已是调用方用于精确比对的形式（R1 为 NFC）。 */
    void add(String name, L local) {
        byName.put(name, local);
        byFold.computeIfAbsent(CaseFold.foldKey(name), k -> new ArrayList<>()).add(local);
    }

    /** 与 name 精确相同的可见局部名中最先可见的一个；没有时为 null。 */
    L nearest(String name) {
        for (LocalScope<L> s = this; s != null; s = s.parent) {
            L local = s.byName.get(name);
            if (local != null) return local;
        }
        return null;
    }

    /** 折叠键为 key 的全部可见局部名，按可见顺序。 */
    List<L> folded(String key) {
        List<L> out = new ArrayList<>();
        for (LocalScope<L> s = this; s != null; s = s.parent) {
            List<L> layer = s.byFold.get(key);
            if (layer != null) out.addAll(layer.reversed());
        }
        return out;
    }
}
