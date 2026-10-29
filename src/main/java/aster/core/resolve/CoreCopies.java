package aster.core.resolve;

import aster.core.ir.CoreModel;

import java.util.List;

/**
 * R2 使用的 Core 节点浅拷贝：只替换给定字段，其余字段与输入共享；不复制 ResolutionMark，
 * 因此 R2 的输出不携带标记、输入 Core 不被修改。
 */
final class CoreCopies {

    private CoreCopies() {}

    static CoreModel.Name name(CoreModel.Name n, String name, CoreModel.Origin origin) {
        CoreModel.Name out = new CoreModel.Name();
        out.name = name;
        out.origin = origin;
        return out;
    }

    static CoreModel.Set set(CoreModel.Set s, String name, CoreModel.Expr expr, CoreModel.Origin origin) {
        CoreModel.Set out = new CoreModel.Set();
        out.name = name;
        out.expr = expr;
        out.origin = origin;
        return out;
    }

    static CoreModel.PatName patName(CoreModel.PatName p, String name, CoreModel.Origin origin) {
        CoreModel.PatName out = new CoreModel.PatName();
        out.name = name;
        out.origin = origin;
        return out;
    }

    static CoreModel.PatCtor patCtor(CoreModel.PatCtor p, String typeName, List<CoreModel.Pattern> args,
                                     CoreModel.Origin origin) {
        CoreModel.PatCtor out = new CoreModel.PatCtor();
        out.typeName = typeName;
        out.names = p.names;
        out.args = args;
        out.origin = origin;
        return out;
    }

    static CoreModel.Let let(CoreModel.Let s, CoreModel.Expr expr) {
        CoreModel.Let out = new CoreModel.Let();
        out.name = s.name;
        out.expr = expr;
        out.origin = s.origin;
        return out;
    }

    static CoreModel.Return ret(CoreModel.Return s, CoreModel.Expr expr) {
        CoreModel.Return out = new CoreModel.Return();
        out.expr = expr;
        out.origin = s.origin;
        return out;
    }

    static CoreModel.If ifStmt(CoreModel.If s, CoreModel.Expr cond, CoreModel.Block thenBlock, CoreModel.Block elseBlock) {
        CoreModel.If out = new CoreModel.If();
        out.cond = cond;
        out.thenBlock = thenBlock;
        out.elseBlock = elseBlock;
        out.origin = s.origin;
        return out;
    }

    static CoreModel.Match match(CoreModel.Match s, CoreModel.Expr expr, List<CoreModel.Case> cases) {
        CoreModel.Match out = new CoreModel.Match();
        out.expr = expr;
        out.cases = cases;
        out.origin = s.origin;
        return out;
    }

    static CoreModel.Case kase(CoreModel.Case c, CoreModel.Pattern pattern, CoreModel.Stmt body) {
        CoreModel.Case out = new CoreModel.Case();
        out.pattern = pattern;
        out.body = body;
        out.origin = c.origin;
        return out;
    }

    static CoreModel.Scope scope(CoreModel.Scope s, List<CoreModel.Stmt> statements) {
        CoreModel.Scope out = new CoreModel.Scope();
        out.statements = statements;
        out.origin = s.origin;
        return out;
    }

    static CoreModel.Block block(CoreModel.Block b, List<CoreModel.Stmt> statements) {
        CoreModel.Block out = new CoreModel.Block();
        out.statements = statements;
        out.origin = b.origin;
        return out;
    }

    static CoreModel.Start start(CoreModel.Start s, CoreModel.Expr expr) {
        CoreModel.Start out = new CoreModel.Start();
        out.name = s.name;
        out.expr = expr;
        out.origin = s.origin;
        return out;
    }

    static CoreModel.Workflow workflow(CoreModel.Workflow s, List<CoreModel.Step> steps) {
        CoreModel.Workflow out = new CoreModel.Workflow();
        out.steps = steps;
        out.effectCaps = s.effectCaps;
        out.retry = s.retry;
        out.timeout = s.timeout;
        out.origin = s.origin;
        return out;
    }

    static CoreModel.Step step(CoreModel.Step s, CoreModel.Block body, CoreModel.Block compensate) {
        CoreModel.Step out = new CoreModel.Step();
        out.kind = s.kind;
        out.name = s.name;
        out.body = body;
        out.compensate = compensate;
        out.dependencies = s.dependencies;
        out.effectCaps = s.effectCaps;
        out.origin = s.origin;
        return out;
    }

    static CoreModel.Call call(CoreModel.Call e, CoreModel.Expr target, List<CoreModel.Expr> args) {
        CoreModel.Call out = new CoreModel.Call();
        out.target = target;
        out.args = args;
        out.origin = e.origin;
        return out;
    }

    static CoreModel.Construct construct(CoreModel.Construct e, List<CoreModel.FieldInit> fields) {
        CoreModel.Construct out = new CoreModel.Construct();
        out.typeName = e.typeName;
        out.fields = fields;
        out.origin = e.origin;
        return out;
    }

    static CoreModel.FieldInit fieldInit(CoreModel.FieldInit f, CoreModel.Expr expr) {
        CoreModel.FieldInit out = new CoreModel.FieldInit();
        out.name = f.name;
        out.expr = expr;
        return out;
    }

    static CoreModel.Ok ok(CoreModel.Ok e, CoreModel.Expr expr) {
        CoreModel.Ok out = new CoreModel.Ok();
        out.expr = expr;
        out.origin = e.origin;
        return out;
    }

    static CoreModel.Err err(CoreModel.Err e, CoreModel.Expr expr) {
        CoreModel.Err out = new CoreModel.Err();
        out.expr = expr;
        out.origin = e.origin;
        return out;
    }

    static CoreModel.Some some(CoreModel.Some e, CoreModel.Expr expr) {
        CoreModel.Some out = new CoreModel.Some();
        out.expr = expr;
        out.origin = e.origin;
        return out;
    }

    static CoreModel.Await await(CoreModel.Await e, CoreModel.Expr expr) {
        CoreModel.Await out = new CoreModel.Await();
        out.expr = expr;
        out.origin = e.origin;
        return out;
    }

    static CoreModel.IfE ifExpr(CoreModel.IfE e, CoreModel.Expr cond, CoreModel.Expr thenE, CoreModel.Expr elseE) {
        CoreModel.IfE out = new CoreModel.IfE();
        out.cond = cond;
        out.thenE = thenE;
        out.elseE = elseE;
        out.origin = e.origin;
        return out;
    }

    static CoreModel.ListE list(CoreModel.ListE e, List<CoreModel.Expr> elements) {
        CoreModel.ListE out = new CoreModel.ListE();
        out.elements = elements;
        out.origin = e.origin;
        return out;
    }

    static CoreModel.Lambda lambda(CoreModel.Lambda e, CoreModel.Block body, List<String> captures) {
        CoreModel.Lambda out = new CoreModel.Lambda();
        out.params = e.params;
        out.ret = e.ret;
        out.body = body;
        out.captures = captures;
        out.origin = e.origin;
        return out;
    }

    static CoreModel.Func func(CoreModel.Func d, CoreModel.Block body) {
        CoreModel.Func out = new CoreModel.Func();
        out.name = d.name;
        out.typeParams = d.typeParams;
        out.params = d.params;
        out.ret = d.ret;
        out.retTypeInferred = d.retTypeInferred;
        out.annotations = d.annotations;
        out.retAnnotations = d.retAnnotations;
        out.effects = d.effects;
        out.effectCaps = d.effectCaps;
        out.effectCapsExplicit = d.effectCapsExplicit;
        out.piiLevel = d.piiLevel;
        out.piiCategories = d.piiCategories;
        out.body = body;
        out.origin = d.origin;
        return out;
    }

    static CoreModel.Module module(CoreModel.Module m, List<CoreModel.Decl> decls) {
        CoreModel.Module out = new CoreModel.Module();
        out.kind = m.kind;
        out.name = m.name;
        out.profile = m.profile;
        out.decls = decls;
        out.origin = m.origin;
        return out;
    }
}
