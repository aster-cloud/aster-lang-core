package aster.core.typecheck;

import aster.core.ir.CoreModel;
import aster.core.ir.CoreModel.Origin;
import aster.core.ir.CoreModel.Type;
import aster.core.typecheck.model.SymbolInfo;
import aster.core.typecheck.model.SymbolInfo.SymbolKind;

import java.io.Serial;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * 符号表（Symbol Table）
 * <p>
 * 基于栈的作用域管理系统，支持符号定义、查找、遮蔽检测和闭包捕获分析。
 * 完全复制 TypeScript 版本的符号表设计，确保行为一致。
 * <p>
 * 核心功能：
 * - 作用域管理：支持嵌套作用域（MODULE、FUNCTION、BLOCK、LAMBDA）
 * - 符号定义：支持变量、函数、数据类型、类型别名
 * - 符号查找：支持当前作用域查找和递归查找
 * - 遮蔽检测：自动检测符号遮蔽并记录被遮蔽的符号
 * - 闭包捕获：支持标记和查询被闭包捕获的符号
 * - 类型别名：支持类型别名定义、解析和展开（含递归检测）
 */
public final class SymbolTable {

  // ========== 作用域类型 ==========

  /**
   * 作用域类型
   */
  public enum ScopeType {
    /** 模块级作用域（顶层） */
    MODULE,
    /** 函数作用域 */
    FUNCTION,
    /** 代码块作用域 */
    BLOCK,
    /** Lambda 作用域 */
    LAMBDA
  }

  // ========== 异常定义 ==========

  /**
   * 重复符号定义异常
   */
  public static final class DuplicateSymbolError extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;
    private final transient SymbolInfo symbol;

    public DuplicateSymbolError(SymbolInfo symbol) {
      super("Duplicate symbol '" + symbol.name() + "' declared in the same scope");
      this.symbol = symbol;
    }

    public SymbolInfo getSymbol() {
      return symbol;
    }
  }

  // ========== 内部类：作用域 ==========

  /**
   * 作用域（内部实现）
   * <p>
   * 采用树形结构，每个作用域持有父作用域引用和子作用域列表。
   */
  private static final class Scope {
    private final Map<String, SymbolInfo> symbols = new HashMap<>();
    private final List<Scope> children = new ArrayList<>();
    private final Scope parent;
    private final ScopeType type;

    Scope(Scope parent, ScopeType type) {
      this.parent = parent;
      this.type = type;
    }

    /**
     * 在当前作用域定义符号
     *
     * @throws DuplicateSymbolError 如果符号已存在
     */
    void define(SymbolInfo symbol) {
      if (symbols.containsKey(symbol.name())) {
        throw new DuplicateSymbolError(symbol);
      }
      symbols.put(symbol.name(), symbol);
    }

    /**
     * 递归查找符号（当前作用域 + 父作用域）
     */
    Optional<SymbolInfo> lookup(String name) {
      var local = symbols.get(name);
      if (local != null) {
        return Optional.of(local);
      }
      return parent != null ? parent.lookup(name) : Optional.empty();
    }

    /**
     * 仅在当前作用域查找符号
     */
    Optional<SymbolInfo> lookupLocal(String name) {
      return Optional.ofNullable(symbols.get(name));
    }

    /**
     * 查找被当前符号遮蔽的符号（在父作用域中查找）
     */
    Optional<SymbolInfo> findShadowed(String name) {
      return parent != null ? parent.lookup(name) : Optional.empty();
    }

    /**
     * 进入子作用域
     */
    Scope enterScope(ScopeType type) {
      var child = new Scope(this, type);
      children.add(child);
      return child;
    }

    /**
     * 退出当前作用域（返回父作用域）
     */
    Scope exitScope() {
      return parent;
    }

    /**
     * 标记符号为被闭包捕获
     * <p>
     * 【修复】递归查找符号定义的作用域并在那里更新，而非在当前作用域更新。
     */
    void markCaptured(String name) {
      // 先在本地查找
      var local = symbols.get(name);
      if (local != null) {
        // 找到了，在当前作用域更新
        var captured = new SymbolInfo(
          local.name(),
          local.type(),
          local.kind(),
          local.mutable(),
          local.span(),
          true, // captured = true
          local.shadowedFrom(),
          local.declaredEffect()
        );
        symbols.put(name, captured);
      } else if (parent != null) {
        // 未找到，递归到父作用域
        parent.markCaptured(name);
      }
      // 如果到根作用域还未找到，则忽略（符号不存在）
    }

    /**
     * 获取当前作用域中被捕获的符号
     */
    List<SymbolInfo> getCapturedSymbols() {
      return symbols.values().stream()
        .filter(SymbolInfo::captured)
        .toList();
    }

    Scope getParent() {
      return parent;
    }

    ScopeType getType() {
      return type;
    }
  }

  // ========== 类型别名条目 ==========

  /**
   * 类型别名条目（内部使用）
   *
   * @param type 别名展开后的类型
   * @param span 源码位置
   * @param typeParams 类型参数列表（泛型别名使用，如 Box<T> 的参数列表为 ["T"]）
   */
  private record TypeAliasEntry(Type type, Optional<Origin> span, List<String> typeParams) {
  }

  // ========== 符号定义选项 ==========

  /**
   * 符号定义选项
   *
   * @param mutable        是否可变
   * @param span           源码位置
   * @param captured       是否被闭包捕获
   * @param onShadow       遮蔽回调（当检测到符号遮蔽时调用）
   * @param declaredEffect 声明的效果（仅函数符号有效）
   */
  public record DefineOptions(
    boolean mutable,
    Optional<Origin> span,
    boolean captured,
    Optional<BiConsumer<SymbolInfo, SymbolInfo>> onShadow,
    Optional<String> declaredEffect
  ) {

    /**
     * 默认选项（不可变、无位置、未捕获、无回调、无效果）
     */
    public static DefineOptions defaults() {
      return new DefineOptions(false, Optional.empty(), false, Optional.empty(), Optional.empty());
    }

    /**
     * 可变选项
     */
    public static DefineOptions mutable(Origin span) {
      return new DefineOptions(true, Optional.ofNullable(span), false, Optional.empty(), Optional.empty());
    }

    /**
     * 不可变选项
     */
    public static DefineOptions immutable(Origin span) {
      return new DefineOptions(false, Optional.ofNullable(span), false, Optional.empty(), Optional.empty());
    }
  }

  // ========== 字段 ==========

  private final Scope root;
  private Scope current;
  private final Map<String, TypeAliasEntry> typeAliases = new HashMap<>();
  private final Map<String, Type> aliasCache = new HashMap<>();

  // ========== 构造器 ==========

  /**
   * 创建符号表（自动初始化模块级作用域）
   */
  public SymbolTable() {
    this.root = new Scope(null, ScopeType.MODULE);
    this.current = root;
  }

  // ========== 作用域管理 ==========

  /**
   * 进入新作用域
   */
  public void enterScope(ScopeType type) {
    current = current.enterScope(type);
  }

  /**
   * 退出当前作用域
   *
   * @throws IllegalStateException 如果尝试退出根作用域
   */
  public void exitScope() {
    var parent = current.exitScope();
    if (parent == null) {
      throw new IllegalStateException("Cannot exit root scope");
    }
    current = parent;
  }

  /**
   * 获取当前作用域（用于调试或高级用途）
   */
  public ScopeType getCurrentScopeType() {
    return current.getType();
  }

  // ========== 符号管理 ==========

  /**
   * 在当前作用域定义符号
   *
   * @param name    符号名称
   * @param type    符号类型
   * @param kind    符号种类
   * @param options 定义选项
   * @throws DuplicateSymbolError 如果符号已存在于当前作用域
   */
  public void define(String name, Type type, SymbolKind kind, DefineOptions options) {
    // 查找被遮蔽的符号
    var shadowed = current.findShadowed(name);

    // 创建符号信息
    var symbol = new SymbolInfo(
      name,
      type,
      kind,
      options.mutable(),
      options.span(),
      options.captured(),
      shadowed,
      options.declaredEffect() // 传递声明的效果
    );

    // 定义符号（可能抛出 DuplicateSymbolError）
    current.define(symbol);

    // 触发遮蔽回调
    if (shadowed.isPresent() && options.onShadow().isPresent()) {
      options.onShadow().get().accept(symbol, shadowed.get());
    }
  }

  /**
   * 查找符号（递归查找所有作用域）
   */
  public Optional<SymbolInfo> lookup(String name) {
    return current.lookup(name);
  }

  /**
   * 仅在当前作用域查找符号
   */
  public Optional<SymbolInfo> lookupInCurrentScope(String name) {
    return current.lookupLocal(name);
  }

  /**
   * 标记符号为被闭包捕获
   */
  public void markCaptured(String name) {
    current.markCaptured(name);
  }

  /**
   * 获取当前作用域中被捕获的符号
   */
  public List<SymbolInfo> getCapturedSymbols() {
    return current.getCapturedSymbols();
  }

  // ========== 类型别名管理 ==========

  /**
   * 定义类型别名
   *
   * @param name 别名名称
   * @param type 别名类型
   * @param span 源码位置（可选）
   * @throws IllegalArgumentException 如果别名已存在
   */
  public void defineTypeAlias(String name, Type type, Optional<Origin> span) {
    defineTypeAlias(name, type, span, List.of());
  }

  /**
   * 定义泛型类型别名
   *
   * @param name 别名名称
   * @param type 别名类型
   * @param span 源码位置（可选）
   * @param typeParams 类型参数列表（如 ["T", "E"] 表示 Box<T, E>）
   * @throws IllegalArgumentException 如果别名已存在
   */
  public void defineTypeAlias(String name, Type type, Optional<Origin> span, List<String> typeParams) {
    if (typeAliases.containsKey(name)) {
      throw new IllegalArgumentException("Duplicate type alias '" + name + "'");
    }
    typeAliases.put(name, new TypeAliasEntry(type, span, typeParams));
    aliasCache.remove(name); // 清除缓存
  }

  /**
   * 解析类型别名（带缓存和递归检测）
   *
   * @param name 别名名称
   * @return 展开后的类型，如果别名不存在或存在递归则返回 empty
   */
  public Optional<Type> resolveTypeAlias(String name) {
    // 检查缓存
    if (aliasCache.containsKey(name)) {
      return Optional.of(aliasCache.get(name));
    }

    // 递归解析
    var resolved = resolveAliasRecursive(name, new HashSet<>());
    resolved.ifPresent(type -> aliasCache.put(name, type));
    return resolved;
  }

  /**
   * 获取所有类型别名（不展开）
   */
  public Map<String, Type> getTypeAliases() {
    var result = new HashMap<String, Type>();
    typeAliases.forEach((name, entry) -> result.put(name, entry.type()));
    return result;
  }

  /**
   * 获取类型别名的类型参数列表
   *
   * @param name 别名名称
   * @return 类型参数列表，如果别名不存在则返回 empty
   */
  public Optional<List<String>> getTypeAliasParams(String name) {
    var entry = typeAliases.get(name);
    return entry != null ? Optional.of(entry.typeParams()) : Optional.empty();
  }

  // ========== 私有辅助方法 ==========

  /**
   * 递归解析类型别名（检测递归）
   */
  private Optional<Type> resolveAliasRecursive(String name, Set<String> stack) {
    var entry = typeAliases.get(name);
    if (entry == null) {
      return Optional.empty();
    }

    // 检测递归
    if (stack.contains(name)) {
      return Optional.empty();
    }

    stack.add(name);
    var expanded = expandAliasType(entry.type(), stack);
    stack.remove(name);
    return Optional.of(expanded);
  }

  /**
   * 展开类型中的所有别名引用
   */
  private Type expandAliasType(Type type, Set<String> stack) {
    return switch (type) {
      case CoreModel.TypeName tn -> expandTypeName(tn, stack);
      case CoreModel.Maybe m -> expandMaybeType(m, stack);
      case CoreModel.Option o -> expandOptionType(o, stack);
      case CoreModel.Result r -> expandResultType(r, stack);
      case CoreModel.ListT l -> expandListType(l, stack);
      case CoreModel.MapT m -> expandMapType(m, stack);
      case CoreModel.TypeApp ta -> expandTypeApp(ta, stack);
      case CoreModel.FuncType ft -> expandFuncType(ft, stack);
      case CoreModel.PiiType pii -> expandPiiType(pii, stack);
      case CoreModel.TypeVar tv -> tv; // 类型变量不展开
    };
  }

  /**
   * 展开 TypeName 中的别名引用
   */
  private Type expandTypeName(CoreModel.TypeName tn, Set<String> stack) {
    var aliasName = tn.name;
    if (typeAliases.containsKey(aliasName)) {
      var resolved = resolveAliasRecursive(aliasName, stack);
      return resolved.orElse(tn);
    }
    return tn;
  }

  /**
   * 展开 Maybe 类型中的别名引用
   */
  private Type expandMaybeType(CoreModel.Maybe m, Set<String> stack) {
    var expanded = new CoreModel.Maybe();
    expanded.type = expandAliasType(m.type, stack);
    expanded.origin = m.origin;
    return expanded;
  }

  /**
   * 展开 Option 类型中的别名引用
   */
  private Type expandOptionType(CoreModel.Option o, Set<String> stack) {
    var expanded = new CoreModel.Option();
    expanded.type = expandAliasType(o.type, stack);
    expanded.origin = o.origin;
    return expanded;
  }

  /**
   * 展开 Result 类型中的别名引用
   */
  private Type expandResultType(CoreModel.Result r, Set<String> stack) {
    var expanded = new CoreModel.Result();
    expanded.ok = expandAliasType(r.ok, stack);
    expanded.err = expandAliasType(r.err, stack);
    expanded.origin = r.origin;
    return expanded;
  }

  /**
   * 展开 List 类型中的别名引用
   */
  private Type expandListType(CoreModel.ListT l, Set<String> stack) {
    var expanded = new CoreModel.ListT();
    expanded.type = expandAliasType(l.type, stack);
    expanded.origin = l.origin;
    return expanded;
  }

  /**
   * 展开 Map 类型中的别名引用
   */
  private Type expandMapType(CoreModel.MapT m, Set<String> stack) {
    var expanded = new CoreModel.MapT();
    expanded.key = expandAliasType(m.key, stack);
    expanded.val = expandAliasType(m.val, stack);
    expanded.origin = m.origin;
    return expanded;
  }

  /**
   * 展开 TypeApp 中的别名引用
   */
  private Type expandTypeApp(CoreModel.TypeApp ta, Set<String> stack) {
    var expanded = new CoreModel.TypeApp();
    expanded.base = ta.base;
    expanded.args = ta.args.stream()
      .map(arg -> expandAliasType(arg, stack))
      .toList();
    expanded.origin = ta.origin;
    return expanded;
  }

  /**
   * 展开 FuncType 中的别名引用
   */
  private Type expandFuncType(CoreModel.FuncType ft, Set<String> stack) {
    var expanded = new CoreModel.FuncType();
    expanded.params = ft.params.stream()
      .map(param -> expandAliasType(param, stack))
      .toList();
    expanded.ret = expandAliasType(ft.ret, stack);
    expanded.origin = ft.origin;
    return expanded;
  }

  private Type expandPiiType(CoreModel.PiiType pii, Set<String> stack) {
    var expanded = new CoreModel.PiiType();
    expanded.baseType = expandAliasType(pii.baseType, stack);
    expanded.sensitivity = pii.sensitivity;
    expanded.category = pii.category;
    expanded.origin = pii.origin;
    return expanded;
  }
}
