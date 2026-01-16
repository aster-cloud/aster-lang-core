package aster.core.typecheck.capability;

import aster.core.typecheck.CapabilityKind;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Manifest 配置，描述全局允许与拒绝的能力集合。
 */
public final class ManifestConfig {

  private final Set<CapabilityKind> allowedCaps;
  private final Set<CapabilityKind> deniedCaps;

  public ManifestConfig(Set<CapabilityKind> allowedCaps, Set<CapabilityKind> deniedCaps) {
    this.allowedCaps = wrap(allowedCaps);
    this.deniedCaps = wrap(deniedCaps);
  }

  /**
   * 判断能力是否被允许（命中允许列表且未出现在拒绝列表）。
   */
  public boolean isAllowed(CapabilityKind cap) {
    if (cap == null) {
      return false;
    }
    if (deniedCaps.contains(cap)) {
      return false;
    }
    if (allowedCaps.isEmpty()) {
      return false;
    }
    return allowedCaps.contains(cap);
  }

  public Set<CapabilityKind> allowedCaps() {
    return allowedCaps;
  }

  public Set<CapabilityKind> deniedCaps() {
    return deniedCaps;
  }

  public static ManifestConfig empty() {
    return new ManifestConfig(Set.of(), Set.of());
  }

  private Set<CapabilityKind> wrap(Set<CapabilityKind> caps) {
    if (caps == null || caps.isEmpty()) {
      return Collections.unmodifiableSet(EnumSet.noneOf(CapabilityKind.class));
    }
    var copied = EnumSet.noneOf(CapabilityKind.class);
    for (var cap : caps) {
      copied.add(Objects.requireNonNull(cap, "capability entry must not be null"));
    }
    return Collections.unmodifiableSet(copied);
  }
}
