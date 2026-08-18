package aster.core.typecheck.model;

import aster.core.ir.CoreModel;
import aster.core.ir.CoreModel.Type;
import aster.core.typecheck.SymbolTable;
import aster.core.typecheck.DiagnosticBuilder;

import java.util.Map;

/**
 * 访问者上下文
 * <p>
 * 在遍历 Core IR 时携带类型检查所需的上下文信息，包括符号表、诊断收集器、
 * 类型别名、预期返回类型和当前效果。
 * <p>
 * 设计原则：
 * - 可变上下文：支持在遍历过程中更新状态
 * - 作用域管理：通过 SymbolTable 管理嵌套作用域
 * - 错误收集：通过 DiagnosticBuilder 收集诊断信息
 */
public final class VisitorContext {

  /**
   * 效果级别（用于效果系统）
   */
  public enum Effect {
    /** 纯函数：无副作用 */
    PURE,
    /** CPU 密集型：计算密集但无 I/O */
    CPU,
    /** I/O 操作：包含外部交互 */
    IO
  }

  // ========== 字段 ==========

  /**
   * 符号表
   */
  private final SymbolTable symbolTable;

  /**
   * 诊断构建器
   */
  private final DiagnosticBuilder diagnostics;

  /**
   * 类型别名映射（类型名 -> 类型定义）
   */
  private final Map<String, Type> typeAliases;

  /**
   * data 声明表（类型名 → 声明），用于构造器字段校验。
   *
   * <p>★2026-08-17 审计：此前 checkConstruct 是空壳（注释自述「简化实现」），
   * FIELD_TYPE_MISMATCH / UNKNOWN_FIELD / MISSING_REQUIRED_FIELD 三个错误码在
   * Java 侧 emit 站点数为 **0**，而 TS 侧全部实现——同一段 CNL：TS 报 3 个诊断，
   * Java 静默接受。合规引擎对未覆盖的决策分支不告警，且错误码表两侧 byte-identical
   * 制造了「表对齐 = 行为对齐」的假象。
   *
   * <p>要校验字段就必须拿到 data 的字段列表，而 SymbolTable 只登记了类型名
   * （defineDataType 丢弃了 fields），故在此单独携带。
   */
  private final Map<String, CoreModel.Data> dataDecls;

  /**
   * 预期返回类型（用于检查 return 语句）
   */
  private Type expectedReturnType;

  /**
   * 当前效果（用于效果检查）
   */
  private Effect currentEffect;

  // ========== 构造器 ==========

  /**
   * 创建访问者上下文
   *
   * @param symbolTable        符号表
   * @param diagnostics        诊断构建器
   * @param typeAliases        类型别名映射
   * @param expectedReturnType 预期返回类型
   * @param currentEffect      当前效果
   */
  /** 向后兼容重载：不携带 data 声明表（构造器字段校验将退化为不校验）。 */
  public VisitorContext(
    SymbolTable symbolTable,
    DiagnosticBuilder diagnostics,
    Map<String, Type> typeAliases,
    Type expectedReturnType,
    Effect currentEffect
  ) {
    this(symbolTable, diagnostics, typeAliases, expectedReturnType, currentEffect, Map.of());
  }

  public VisitorContext(
    SymbolTable symbolTable,
    DiagnosticBuilder diagnostics,
    Map<String, Type> typeAliases,
    Type expectedReturnType,
    Effect currentEffect,
    Map<String, CoreModel.Data> dataDecls
  ) {
    if (symbolTable == null) {
      throw new IllegalArgumentException("symbolTable cannot be null");
    }
    if (diagnostics == null) {
      throw new IllegalArgumentException("diagnostics cannot be null");
    }
    if (typeAliases == null) {
      throw new IllegalArgumentException("typeAliases cannot be null");
    }
    this.symbolTable = symbolTable;
    this.diagnostics = diagnostics;
    this.typeAliases = Map.copyOf(typeAliases);
    this.expectedReturnType = expectedReturnType;
    this.currentEffect = currentEffect != null ? currentEffect : Effect.PURE;
    this.dataDecls = dataDecls != null ? Map.copyOf(dataDecls) : Map.of();
  }

  // ========== Getters ==========

  public SymbolTable getSymbolTable() {
    return symbolTable;
  }

  public DiagnosticBuilder getDiagnostics() {
    return diagnostics;
  }

  /** data 声明表（类型名 → 声明）。空表表示调用方未提供，构造器字段校验将跳过。 */
  public Map<String, CoreModel.Data> getDataDecls() {
    return dataDecls;
  }

  public Map<String, Type> getTypeAliases() {
    return typeAliases;
  }

  public Type getExpectedReturnType() {
    return expectedReturnType;
  }

  public Effect getCurrentEffect() {
    return currentEffect;
  }

  // ========== Setters ==========

  public void setExpectedReturnType(Type expectedReturnType) {
    this.expectedReturnType = expectedReturnType;
  }

  public void setCurrentEffect(Effect currentEffect) {
    this.currentEffect = currentEffect != null ? currentEffect : Effect.PURE;
  }
}
