package aster.core.typecheck.pii;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * PII 元数据，记录敏感级别与类别集合，用于在检查过程中携带污点信息。
 */
public final class PiiMeta {

  public enum Level {
    L1(1),
    L2(2),
    L3(3);

    private final int order;

    Level(int order) {
      this.order = order;
    }

    public int getOrder() {
      return order;
    }
  }

  private final Level level;
  private final Set<String> categories;

  public PiiMeta(Level level, Set<String> categories) {
    this.level = Objects.requireNonNull(level, "level");
    this.categories = Collections.unmodifiableSet(new HashSet<>(categories));
  }

  public Level getLevel() {
    return level;
  }

  public Set<String> getCategories() {
    return categories;
  }

  /**
   * 合并两个 PII 元数据，等级取较高者，类别集合取并集。
   */
  public static PiiMeta merge(PiiMeta left, PiiMeta right) {
    if (left == null) return right;
    if (right == null) return left;

    var maxLevel = left.level.getOrder() >= right.level.getOrder() ? left.level : right.level;
    var mergedCategories = new HashSet<String>();
    mergedCategories.addAll(left.categories);
    mergedCategories.addAll(right.categories);
    return new PiiMeta(maxLevel, mergedCategories);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof PiiMeta that)) return false;
    return level == that.level && categories.equals(that.categories);
  }

  @Override
  public int hashCode() {
    return Objects.hash(level, categories);
  }
}
