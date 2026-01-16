package aster.core.typecheck.model;

import aster.core.ir.CoreModel.Origin;
import aster.core.ir.CoreModel.Type;

import java.util.Optional;

/**
 * 符号信息
 * <p>
 * 记录符号表中的符号信息，包括名称、类型、种类、可变性等。
 * 支持符号遮蔽检测和闭包捕获分析。
 *
 * @param name          符号名称
 * @param type          符号类型
 * @param kind          符号种类（变量、参数、函数、数据类型）
 * @param mutable       是否可变
 * @param span          源码位置信息（可选）
 * @param captured      是否被闭包捕获
 * @param shadowedFrom  被遮蔽的符号（可选）
 * @param declaredEffect 函数声明的效果（仅对 FUNCTION 类型有效，可选）
 */
public record SymbolInfo(
  String name,
  Type type,
  SymbolKind kind,
  boolean mutable,
  Optional<Origin> span,
  boolean captured,
  Optional<SymbolInfo> shadowedFrom,
  Optional<String> declaredEffect
) {

  /**
   * 符号种类
   */
  public enum SymbolKind {
    /** 变量（let 声明） */
    VARIABLE,
    /** 参数（函数参数） */
    PARAMETER,
    /** 函数（fun 声明） */
    FUNCTION,
    /** 数据类型（data/enum 声明） */
    DATA_TYPE
  }

  /**
   * 创建紧凑构造器，确保非空约束
   */
  public SymbolInfo {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("name cannot be null or empty");
    }
    if (type == null) {
      throw new IllegalArgumentException("type cannot be null");
    }
    if (kind == null) {
      throw new IllegalArgumentException("kind cannot be null");
    }
    if (span == null) {
      span = Optional.empty();
    }
    if (shadowedFrom == null) {
      shadowedFrom = Optional.empty();
    }
    if (declaredEffect == null) {
      declaredEffect = Optional.empty();
    }
  }

  /**
   * 检查是否遮蔽了其他符号
   *
   * @return 如果遮蔽了其他符号返回 true
   */
  public boolean isShadowing() {
    return shadowedFrom.isPresent();
  }
}
