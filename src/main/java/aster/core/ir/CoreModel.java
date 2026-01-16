package aster.core.ir;

import com.fasterxml.jackson.annotation.*;
import java.util.*;

/**
 * Core IR（中间表示）数据模型
 * <p>
 * 本类定义了 Aster Lang 编译器的 Core IR 数据结构，用于在编译管线各阶段之间传递程序表示。
 * Core IR 是 CNL AST 降级后的简化表示，移除了语法糖并统一了语义结构。
 * <p>
 * 设计原则：
 * - 不可变数据：所有类使用 final 字段确保线程安全
 * - JSON 序列化：通过 Jackson 注解支持与 TypeScript 版本互操作
 * - 类型安全：使用 sealed 接口限制子类型
 * - 位置追踪：所有节点携带 Origin 信息用于错误报告
 *
 * @since 0.3.0
 */
public final class CoreModel {

  // ========== 公共数据类型 ==========

  /**
   * 源码位置信息（行号和列号）
   */
  public static final class Position {
    public int line;  // 行号（从 1 开始）
    public int col;   // 列号（从 1 开始）
  }

  /**
   * 源码范围信息（文件名 + 起止位置）
   */
  public static final class Origin {
    public String file;        // 源文件路径
    public Position start;     // 起始位置
    public Position end;       // 结束位置
  }

  /**
   * 模块定义（编译单元）
   */
  public static final class Module {
    public String kind = "Module";  // 类型标识（与 TypeScript Core IR 保持一致）
    public String name;             // 模块名称
    public List<Decl> decls;        // 顶层声明列表
    public Origin origin;           // 源码位置
  }

  // ========== 声明 (Decl) ==========

  /**
   * 顶层声明的基类型
   * <p>
   * 支持的声明类型：Import（导入）、Data（数据类型）、Enum（枚举）、Func（函数）
   */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Import.class, name = "Import"),
    @JsonSubTypes.Type(value = Data.class, name = "Data"),
    @JsonSubTypes.Type(value = Enum.class, name = "Enum"),
    @JsonSubTypes.Type(value = Func.class, name = "Func")
  })
  public sealed interface Decl permits Import, Data, Enum, Func {}

  /**
   * 导入声明（从其他模块导入定义）
   */
  @JsonTypeName("Import")
  public static final class Import implements Decl {
    public String path;     // 被导入模块路径
    public String alias;    // 别名（可选，为 null 表示使用原名）
    public Origin origin;
  }

  /**
   * 数据类型声明（product type，类似 struct）
   */
  @JsonTypeName("Data")
  public static final class Data implements Decl {
    public String name;               // 类型名称
    public List<Field> fields;        // 字段列表
    public Origin origin;
  }

  /**
   * 枚举类型声明（sum type，代数数据类型）
   */
  @JsonTypeName("Enum")
  public static final class Enum implements Decl {
    public String name;                // 枚举名称
    public List<String> variants;      // 变体名称列表
    public Origin origin;
  }

  /**
   * 函数声明
   */
  @JsonTypeName("Func")
  public static final class Func implements Decl {
    public String name;                    // 函数名
    public List<String> typeParams;        // 类型参数列表（泛型，如 <T, U>）
    public List<Param> params;             // 形参列表
    public Type ret;                       // 返回类型
    public List<String> effects;           // 效果声明列表（IO、CPU、Secrets 等）
    public List<String> effectCaps = Collections.emptyList(); // 能力列表（Http、Sql 等）
    public boolean effectCapsExplicit;     // 是否显式声明能力
    public String piiLevel = "";           // 聚合后的 PII 等级（L1/L2/L3）
    public List<String> piiCategories = Collections.emptyList(); // 聚合后的 PII 类别
    public Block body;                     // 函数体
    public Origin origin;
  }

  /**
   * 字段定义（用于 Data 类型）
   */
  public static final class Field {
    public String name;                          // 字段名
    public Type type;                            // 字段类型
    public List<Annotation> annotations;         // 注解列表（默认空）

    public Field() {
      this.annotations = Collections.emptyList();
    }
  }

  /**
   * 参数定义（用于函数和 Lambda）
   */
  public static final class Param {
    public String name;                          // 参数名
    public Type type;                            // 参数类型
    public List<Annotation> annotations;         // 注解列表（默认空）

    public Param() {
      this.annotations = Collections.emptyList();
    }
  }

  /**
   * 注解定义（元数据标注，如 @sensitive、@pii）
   */
  public static final class Annotation {
    public String name;                              // 注解名称
    public Map<String, Object> params;               // 注解参数（键值对，默认空）

    public Annotation() {
      this.params = Collections.emptyMap();
    }
  }

  // ========== 类型 (Type) ==========

  /**
   * 类型表达式的基类型
   * <p>
   * 支持的类型：TypeName（具名类型）、TypeVar（类型变量）、TypeApp（类型应用）、
   * Result（结果类型）、Maybe（可选类型）、Option（选项类型）、
   * List（列表类型）、Map（映射类型）、FuncType（函数类型）
   */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = TypeName.class, name = "TypeName"),
    @JsonSubTypes.Type(value = TypeVar.class, name = "TypeVar"),
    @JsonSubTypes.Type(value = TypeApp.class, name = "TypeApp"),
    @JsonSubTypes.Type(value = Result.class, name = "Result"),
    @JsonSubTypes.Type(value = Maybe.class, name = "Maybe"),
    @JsonSubTypes.Type(value = Option.class, name = "Option"),
    @JsonSubTypes.Type(value = ListT.class, name = "List"),
    @JsonSubTypes.Type(value = MapT.class, name = "Map"),
    @JsonSubTypes.Type(value = FuncType.class, name = "FuncType"),
    @JsonSubTypes.Type(value = PiiType.class, name = "PiiType")
  })
  public sealed interface Type permits TypeName, TypeVar, TypeApp, Result, Maybe, Option, ListT, MapT, FuncType, PiiType {}

  @JsonTypeName("TypeName")
  public static final class TypeName implements Type {
    public String name;      // 类型名称（如 Int、String、User）
    public Origin origin;
  }

  @JsonTypeName("TypeVar")
  public static final class TypeVar implements Type {
    public String name;      // 类型变量名（如 T、U）
    public Origin origin;
  }

  @JsonTypeName("TypeApp")
  public static final class TypeApp implements Type {
    public String base;           // 基础类型名（如 List、Map）
    public List<Type> args;       // 类型参数列表（如 List<Int> 中的 Int）
    public Origin origin;
  }

  @JsonTypeName("Result")
  public static final class Result implements Type {
    public Type ok;       // 成功分支类型
    public Type err;      // 错误分支类型
    public Origin origin;
  }

  @JsonTypeName("Maybe")
  public static final class Maybe implements Type {
    public Type type;     // 包装的类型（可空）
    public Origin origin;
  }

  @JsonTypeName("Option")
  public static final class Option implements Type {
    public Type type;     // 包装的类型（可选）
    public Origin origin;
  }

  @JsonTypeName("List")
  public static final class ListT implements Type {  // ListT 避免与 java.util.List 冲突
    public Type type;     // 元素类型
    public Origin origin;
  }

  @JsonTypeName("Map")
  public static final class MapT implements Type {   // MapT 避免与 java.util.Map 冲突
    public Type key;      // 键类型
    public Type val;      // 值类型
    public Origin origin;
  }

  @JsonTypeName("FuncType")
  public static final class FuncType implements Type {
    public List<Type> params;    // 参数类型列表
    public Type ret;             // 返回类型
    public Origin origin;
  }

  @JsonTypeName("PiiType")
  public static final class PiiType implements Type {
    public Type baseType;            // 基础类型（如 Text）
    public String sensitivity;      // 敏感级别（L1/L2/L3）
    public String category;         // 数据类别（email/phone 等）
    public Origin origin;
  }

  // ========== 语句 (Stmt) ==========

  /**
   * 语句的基类型
   * <p>
   * 支持的语句：Let（变量绑定）、Set（赋值）、Return（返回）、
   * If（条件分支）、Match（模式匹配）、Scope（作用域）、Block（代码块）、
   * Start（启动并发任务）、Wait（等待并发任务）、Workflow（工作流）
   */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Let.class, name = "Let"),
    @JsonSubTypes.Type(value = Set.class, name = "Set"),
    @JsonSubTypes.Type(value = Return.class, name = "Return"),
    @JsonSubTypes.Type(value = If.class, name = "If"),
    @JsonSubTypes.Type(value = Match.class, name = "Match"),
    @JsonSubTypes.Type(value = Scope.class, name = "Scope"),
    @JsonSubTypes.Type(value = Block.class, name = "Block"),
    @JsonSubTypes.Type(value = Start.class, name = "Start"),
    @JsonSubTypes.Type(value = Wait.class, name = "Wait"),
    @JsonSubTypes.Type(value = Workflow.class, name = "workflow")
  })
  public sealed interface Stmt permits Let, Set, Return, If, Match, Scope, Block, Start, Wait, Workflow {}

  @JsonTypeName("Let")
  public static final class Let implements Stmt {
    public String name;     // 变量名
    public Expr expr;       // 初始值表达式
    public Origin origin;
  }

  @JsonTypeName("Set")
  public static final class Set implements Stmt {
    public String name;     // 变量名
    public Expr expr;       // 新值表达式
    public Origin origin;
  }

  @JsonTypeName("Return")
  public static final class Return implements Stmt {
    public Expr expr;       // 返回值表达式
    public Origin origin;
  }

  @JsonTypeName("If")
  public static final class If implements Stmt {
    public Expr cond;           // 条件表达式
    public Block thenBlock;     // then 分支
    public Block elseBlock;     // else 分支
    public Origin origin;
  }

  @JsonTypeName("Match")
  public static final class Match implements Stmt {
    public Expr expr;           // 被匹配的表达式
    public List<Case> cases;    // 匹配分支列表
    public Origin origin;
  }

  @JsonTypeName("Scope")
  public static final class Scope implements Stmt {
    public List<Stmt> statements;    // 语句列表（引入新作用域）
    public Origin origin;
  }

  @JsonTypeName("Block")
  public static final class Block implements Stmt {
    public List<Stmt> statements;    // 语句列表
    public Origin origin;
  }

  @JsonTypeName("Start")
  public static final class Start implements Stmt {
    public String name;     // 任务名（用于 wait）
    public Expr expr;       // 异步任务表达式
    public Origin origin;
  }

  @JsonTypeName("Wait")
  public static final class Wait implements Stmt {
    public List<String> names;    // 等待的任务名列表
    public Origin origin;
  }

  @JsonTypeName("workflow")
  public static final class Workflow implements Stmt {
    public List<Step> steps = Collections.emptyList();     // 步骤列表
    public List<String> effectCaps = Collections.emptyList(); // 工作流声明的能力
    public RetryPolicy retry;                              // 重试策略
    public Timeout timeout;                                // 超时配置
    public Origin origin;
  }

  public static final class Step {
    public String kind = "step";                   // 与 TS IR 对齐
    public String name;                            // 步骤名称
    public Block body;                             // 主体代码块
    public Block compensate;                       // 补偿代码块（可选）
    public List<String> dependencies = Collections.emptyList(); // 依赖步骤名称
    public List<String> effectCaps = Collections.emptyList();   // 步骤能力
    public Origin origin;
  }

  public static final class RetryPolicy {
    public int maxAttempts;                // 最大重试次数
    public String backoff;                 // 回退策略（exponential/linear）
  }

  public static final class Timeout {
    public long milliseconds;              // 超时时间（毫秒）
  }

  /**
   * 模式匹配分支
   */
  @JsonTypeName("Case")
  public static final class Case {
    public String kind = "Case";  // 类型标识（与 TypeScript Core IR 保持一致）
    public Pattern pattern;       // 匹配模式
    public Stmt body;             // 分支体
    public Origin origin;
  }

  // ========== 模式 (Pattern) ==========

  /**
   * 模式的基类型（用于 match 语句）
   */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = PatNull.class, name = "PatNull"),
    @JsonSubTypes.Type(value = PatCtor.class, name = "PatCtor"),
    @JsonSubTypes.Type(value = PatName.class, name = "PatName"),
    @JsonSubTypes.Type(value = PatInt.class, name = "PatInt")
  })
  public sealed interface Pattern permits PatNull, PatCtor, PatName, PatInt {}

  @JsonTypeName("PatNull")
  public static final class PatNull implements Pattern {
    public Origin origin;
  }

  @JsonTypeName("PatCtor")
  public static final class PatCtor implements Pattern {
    public String typeName;         // 构造器名称
    public List<String> names;      // 位置绑定变量名列表（遗留字段，保持向后兼容）
    public List<Pattern> args;      // 嵌套模式列表（新字段，用于递归模式匹配）
    public Origin origin;
  }

  @JsonTypeName("PatName")
  public static final class PatName implements Pattern {
    public String name;     // 变量绑定名
    public Origin origin;
  }

  @JsonTypeName("PatInt")
  public static final class PatInt implements Pattern {
    public int value;       // 整数字面量
    public Origin origin;
  }

  // ========== 表达式 (Expr) ==========

  /**
   * 表达式的基类型
   */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Name.class, name = "Name"),
    @JsonSubTypes.Type(value = Bool.class, name = "Bool"),
    @JsonSubTypes.Type(value = IntE.class, name = "Int"),
    @JsonSubTypes.Type(value = LongE.class, name = "Long"),
    @JsonSubTypes.Type(value = DoubleE.class, name = "Double"),
    @JsonSubTypes.Type(value = StringE.class, name = "String"),
    @JsonSubTypes.Type(value = NullE.class, name = "Null"),
    @JsonSubTypes.Type(value = Ok.class, name = "Ok"),
    @JsonSubTypes.Type(value = Err.class, name = "Err"),
    @JsonSubTypes.Type(value = Some.class, name = "Some"),
    @JsonSubTypes.Type(value = NoneE.class, name = "None"),
    @JsonSubTypes.Type(value = Construct.class, name = "Construct"),
    @JsonSubTypes.Type(value = Call.class, name = "Call"),
    @JsonSubTypes.Type(value = Lambda.class, name = "Lambda"),
    @JsonSubTypes.Type(value = Await.class, name = "Await")
  })
  public sealed interface Expr permits Name, Bool, IntE, LongE, DoubleE, StringE, NullE, Ok, Err, Some, NoneE, Construct, Call, Lambda, Await {}

  @JsonTypeName("Name")
  public static final class Name implements Expr {
    public String name;     // 变量名或函数名
    public Origin origin;
  }

  @JsonTypeName("Bool")
  public static final class Bool implements Expr {
    public boolean value;   // 布尔值
    public Origin origin;
  }

  @JsonTypeName("Int")
  public static final class IntE implements Expr {
    public int value;       // 32 位整数
    public Origin origin;
  }

  @JsonTypeName("Long")
  public static final class LongE implements Expr {
    public long value;      // 64 位整数
    public Origin origin;
  }

  @JsonTypeName("Double")
  public static final class DoubleE implements Expr {
    public double value;    // 浮点数
    public Origin origin;
  }

  @JsonTypeName("String")
  public static final class StringE implements Expr {
    public String value;    // 字符串字面量
    public Origin origin;
  }

  @JsonTypeName("Null")
  public static final class NullE implements Expr {
    public Origin origin;
  }

  @JsonTypeName("Ok")
  public static final class Ok implements Expr {
    public Expr expr;       // Result 的成功分支值
    public Origin origin;
  }

  @JsonTypeName("Err")
  public static final class Err implements Expr {
    public Expr expr;       // Result 的错误分支值
    public Origin origin;
  }

  @JsonTypeName("Some")
  public static final class Some implements Expr {
    public Expr expr;       // Option 的有值分支
    public Origin origin;
  }

  @JsonTypeName("None")
  public static final class NoneE implements Expr {
    public Origin origin;
  }

  @JsonTypeName("Construct")
  public static final class Construct implements Expr {
    public String typeName;         // 构造的类型名
    public List<FieldInit> fields;  // 字段初始化列表
    public Origin origin;
  }

  /**
   * 字段初始化表达式（用于 Construct）
   */
  public static final class FieldInit {
    public String name;     // 字段名
    public Expr expr;       // 初始值表达式
  }

  @JsonTypeName("Call")
  public static final class Call implements Expr {
    public Expr target;         // 被调用的表达式（通常是函数名）
    public List<Expr> args;     // 参数列表
    public Origin origin;
  }

  @JsonTypeName("Lambda")
  public static final class Lambda implements Expr {
    public List<Param> params;      // 参数列表
    @JsonAlias("retType")           // 兼容 TypeScript 使用的 retType 字段名
    public Type ret;                // 返回类型
    public Block body;              // 函数体
    public List<String> captures;   // 捕获的外部变量列表
    public Origin origin;
  }

  @JsonTypeName("Await")
  public static final class Await implements Expr {
    public Expr expr;       // 异步表达式（等待其完成）
    public Origin origin;
  }
}
