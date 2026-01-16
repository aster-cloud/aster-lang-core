package aster.core.typecheck.visitor;

import aster.core.ir.CoreModel;

/**
 * Core IR 访问者接口
 * <p>
 * 定义遍历 Core IR 所有节点类型的访问方法。支持泛型上下文和返回值类型，
 * 可用于实现类型检查、代码生成、静态分析等多种编译器阶段。
 * <p>
 * 设计原则：
 * - 泛型设计：Ctx 为上下文类型，R 为返回值类型
 * - 完整覆盖：包含所有 Core IR 节点类型
 * - 默认方法：提供默认分发逻辑，子类可按需覆写
 *
 * @param <Ctx> 访问上下文类型（如 VisitorContext）
 * @param <R>   返回值类型（可为 Void）
 */
public interface CoreVisitor<Ctx, R> {

  // ========== 顶层节点 ==========

  /**
   * 访问模块
   */
  R visitModule(CoreModel.Module m, Ctx ctx);

  /**
   * 访问声明（分发到具体声明类型）
   */
  R visitDeclaration(CoreModel.Decl d, Ctx ctx);

  // ========== 语句级节点 ==========

  /**
   * 访问代码块
   */
  R visitBlock(CoreModel.Block b, Ctx ctx);

  /**
   * 访问语句（分发到具体语句类型）
   */
  R visitStatement(CoreModel.Stmt s, Ctx ctx);

  // ========== 表达式级节点 ==========

  /**
   * 访问表达式（分发到具体表达式类型）
   */
  R visitExpression(CoreModel.Expr e, Ctx ctx);

  /**
   * 访问模式（可选，用于 match 语句）
   */
  default R visitPattern(CoreModel.Pattern p, Ctx ctx) {
    return null; // 默认不处理，子类按需覆写
  }

  /**
   * 访问类型（可选，用于类型注解）
   */
  default R visitType(CoreModel.Type t, Ctx ctx) {
    return null; // 默认不处理，子类按需覆写
  }

  // ========== 声明类型的默认分发方法 ==========

  default R visitImport(CoreModel.Import i, Ctx ctx) {
    return visitDeclaration(i, ctx);
  }

  default R visitData(CoreModel.Data d, Ctx ctx) {
    return visitDeclaration(d, ctx);
  }

  default R visitEnum(CoreModel.Enum e, Ctx ctx) {
    return visitDeclaration(e, ctx);
  }

  default R visitFunc(CoreModel.Func f, Ctx ctx) {
    return visitDeclaration(f, ctx);
  }

  // ========== 语句类型的默认分发方法 ==========

  default R visitLet(CoreModel.Let l, Ctx ctx) {
    return visitStatement(l, ctx);
  }

  default R visitSet(CoreModel.Set s, Ctx ctx) {
    return visitStatement(s, ctx);
  }

  default R visitReturn(CoreModel.Return r, Ctx ctx) {
    return visitStatement(r, ctx);
  }

  default R visitIf(CoreModel.If i, Ctx ctx) {
    return visitStatement(i, ctx);
  }

  default R visitMatch(CoreModel.Match m, Ctx ctx) {
    return visitStatement(m, ctx);
  }

  default R visitScope(CoreModel.Scope s, Ctx ctx) {
    return visitStatement(s, ctx);
  }

  default R visitStart(CoreModel.Start s, Ctx ctx) {
    return visitStatement(s, ctx);
  }

  default R visitWait(CoreModel.Wait w, Ctx ctx) {
    return visitStatement(w, ctx);
  }

  default R visitWorkflow(CoreModel.Workflow w, Ctx ctx) {
    return visitStatement(w, ctx);
  }

  // ========== 表达式类型的默认分发方法 ==========

  default R visitName(CoreModel.Name n, Ctx ctx) {
    return visitExpression(n, ctx);
  }

  default R visitBool(CoreModel.Bool b, Ctx ctx) {
    return visitExpression(b, ctx);
  }

  default R visitInt(CoreModel.IntE i, Ctx ctx) {
    return visitExpression(i, ctx);
  }

  default R visitLong(CoreModel.LongE l, Ctx ctx) {
    return visitExpression(l, ctx);
  }

  default R visitDouble(CoreModel.DoubleE d, Ctx ctx) {
    return visitExpression(d, ctx);
  }

  default R visitString(CoreModel.StringE s, Ctx ctx) {
    return visitExpression(s, ctx);
  }

  default R visitNull(CoreModel.NullE n, Ctx ctx) {
    return visitExpression(n, ctx);
  }

  default R visitOk(CoreModel.Ok o, Ctx ctx) {
    return visitExpression(o, ctx);
  }

  default R visitErr(CoreModel.Err e, Ctx ctx) {
    return visitExpression(e, ctx);
  }

  default R visitSome(CoreModel.Some s, Ctx ctx) {
    return visitExpression(s, ctx);
  }

  default R visitNone(CoreModel.NoneE n, Ctx ctx) {
    return visitExpression(n, ctx);
  }

  default R visitConstruct(CoreModel.Construct c, Ctx ctx) {
    return visitExpression(c, ctx);
  }

  default R visitCall(CoreModel.Call c, Ctx ctx) {
    return visitExpression(c, ctx);
  }

  default R visitLambda(CoreModel.Lambda l, Ctx ctx) {
    return visitExpression(l, ctx);
  }

  default R visitAwait(CoreModel.Await a, Ctx ctx) {
    return visitExpression(a, ctx);
  }

  // ========== 模式类型的默认分发方法 ==========

  default R visitPatNull(CoreModel.PatNull p, Ctx ctx) {
    return visitPattern(p, ctx);
  }

  default R visitPatCtor(CoreModel.PatCtor p, Ctx ctx) {
    return visitPattern(p, ctx);
  }

  default R visitPatName(CoreModel.PatName p, Ctx ctx) {
    return visitPattern(p, ctx);
  }

  default R visitPatInt(CoreModel.PatInt p, Ctx ctx) {
    return visitPattern(p, ctx);
  }
}
