package aster.core.ir;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core IR 数据模型单元测试
 * <p>
 * 验证 Core IR 节点的 JSON 序列化/反序列化正确性，确保与 TypeScript 版本的互操作性。
 */
class CoreModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testModuleSerialization() throws Exception {
        // 创建一个简单的模块
        CoreModel.Module module = new CoreModel.Module();
        module.name = "test";
        module.decls = List.of();

        // 序列化
        String json = mapper.writeValueAsString(module);

        // 反序列化
        CoreModel.Module deserialized = mapper.readValue(json, CoreModel.Module.class);

        // 验证
        assertEquals("test", deserialized.name);
        assertNotNull(deserialized.decls);
        assertTrue(deserialized.decls.isEmpty());
    }

    @Test
    void testFuncDecl() throws Exception {
        // 创建函数声明
        CoreModel.Func func = new CoreModel.Func();
        func.name = "add";
        func.typeParams = List.of();
        func.params = List.of();
        func.ret = new CoreModel.TypeName();
        ((CoreModel.TypeName) func.ret).name = "Int";
        func.effects = List.of();
        func.body = new CoreModel.Block();
        func.body.statements = List.of();

        // 序列化
        String json = mapper.writeValueAsString(func);

        // 验证 JSON 包含 "kind": "Func"
        assertTrue(json.contains("\"kind\":\"Func\""));

        // 反序列化
        CoreModel.Decl deserialized = mapper.readValue(json, CoreModel.Decl.class);

        // 验证类型
        assertTrue(deserialized instanceof CoreModel.Func);
        CoreModel.Func deserializedFunc = (CoreModel.Func) deserialized;
        assertEquals("add", deserializedFunc.name);
    }

    @Test
    void testIntExpr() throws Exception {
        // 创建整数表达式
        CoreModel.IntE intExpr = new CoreModel.IntE();
        intExpr.value = 42;

        // 序列化
        String json = mapper.writeValueAsString(intExpr);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Int\""));
        assertTrue(json.contains("\"value\":42"));

        // 反序列化
        CoreModel.Expr deserialized = mapper.readValue(json, CoreModel.Expr.class);

        // 验证类型和值
        assertTrue(deserialized instanceof CoreModel.IntE);
        assertEquals(42, ((CoreModel.IntE) deserialized).value);
    }

    @Test
    void testStringExpr() throws Exception {
        // 创建字符串表达式
        CoreModel.StringE strExpr = new CoreModel.StringE();
        strExpr.value = "hello";

        // 序列化
        String json = mapper.writeValueAsString(strExpr);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"String\""));
        assertTrue(json.contains("\"value\":\"hello\""));

        // 反序列化
        CoreModel.Expr deserialized = mapper.readValue(json, CoreModel.Expr.class);

        // 验证
        assertTrue(deserialized instanceof CoreModel.StringE);
        assertEquals("hello", ((CoreModel.StringE) deserialized).value);
    }

    @Test
    void testCallExpr() throws Exception {
        // 创建函数调用表达式
        CoreModel.Call call = new CoreModel.Call();
        call.target = new CoreModel.Name();
        ((CoreModel.Name) call.target).name = "print";

        CoreModel.StringE arg = new CoreModel.StringE();
        arg.value = "test";
        call.args = List.of(arg);

        // 序列化
        String json = mapper.writeValueAsString(call);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Call\""));
        assertTrue(json.contains("\"kind\":\"Name\""));

        // 反序列化
        CoreModel.Expr deserialized = mapper.readValue(json, CoreModel.Expr.class);

        // 验证
        assertTrue(deserialized instanceof CoreModel.Call);
        CoreModel.Call deserializedCall = (CoreModel.Call) deserialized;
        assertTrue(deserializedCall.target instanceof CoreModel.Name);
        assertEquals("print", ((CoreModel.Name) deserializedCall.target).name);
        assertEquals(1, deserializedCall.args.size());
    }

    @Test
    void testLambdaExpr() throws Exception {
        // 创建 Lambda 表达式
        CoreModel.Lambda lambda = new CoreModel.Lambda();
        lambda.params = List.of();
        lambda.ret = new CoreModel.TypeName();
        ((CoreModel.TypeName) lambda.ret).name = "Int";
        lambda.body = new CoreModel.Block();
        lambda.body.statements = List.of();
        lambda.captures = List.of();

        // 序列化
        String json = mapper.writeValueAsString(lambda);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Lambda\""));

        // 反序列化
        CoreModel.Expr deserialized = mapper.readValue(json, CoreModel.Expr.class);

        // 验证
        assertTrue(deserialized instanceof CoreModel.Lambda);
        CoreModel.Lambda deserializedLambda = (CoreModel.Lambda) deserialized;
        assertNotNull(deserializedLambda.params);
        assertNotNull(deserializedLambda.captures);
    }

    @Test
    void testIfStmt() throws Exception {
        // 创建 if 语句
        CoreModel.If ifStmt = new CoreModel.If();
        ifStmt.cond = new CoreModel.Bool();
        ((CoreModel.Bool) ifStmt.cond).value = true;
        ifStmt.thenBlock = new CoreModel.Block();
        ifStmt.thenBlock.statements = List.of();
        ifStmt.elseBlock = new CoreModel.Block();
        ifStmt.elseBlock.statements = List.of();

        // 序列化
        String json = mapper.writeValueAsString(ifStmt);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"If\""));
        assertTrue(json.contains("\"kind\":\"Bool\""));

        // 反序列化
        CoreModel.Stmt deserialized = mapper.readValue(json, CoreModel.Stmt.class);

        // 验证
        assertTrue(deserialized instanceof CoreModel.If);
        CoreModel.If deserializedIf = (CoreModel.If) deserialized;
        assertTrue(deserializedIf.cond instanceof CoreModel.Bool);
    }

    @Test
    void testPatCtor() throws Exception {
        // 创建构造器模式
        CoreModel.PatCtor patCtor = new CoreModel.PatCtor();
        patCtor.typeName = "Some";
        patCtor.names = List.of("x");
        patCtor.args = List.of();

        // 序列化
        String json = mapper.writeValueAsString(patCtor);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"PatCtor\""));
        assertTrue(json.contains("\"typeName\":\"Some\""));

        // 反序列化
        CoreModel.Pattern deserialized = mapper.readValue(json, CoreModel.Pattern.class);

        // 验证
        assertTrue(deserialized instanceof CoreModel.PatCtor);
        CoreModel.PatCtor deserializedPat = (CoreModel.PatCtor) deserialized;
        assertEquals("Some", deserializedPat.typeName);
        assertEquals(1, deserializedPat.names.size());
        assertEquals("x", deserializedPat.names.get(0));
    }

    @Test
    void testResultType() throws Exception {
        // 创建 Result 类型
        CoreModel.Result result = new CoreModel.Result();
        result.ok = new CoreModel.TypeName();
        ((CoreModel.TypeName) result.ok).name = "Int";
        result.err = new CoreModel.TypeName();
        ((CoreModel.TypeName) result.err).name = "String";

        // 序列化
        String json = mapper.writeValueAsString(result);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Result\""));

        // 反序列化
        CoreModel.Type deserialized = mapper.readValue(json, CoreModel.Type.class);

        // 验证
        assertTrue(deserialized instanceof CoreModel.Result);
        CoreModel.Result deserializedResult = (CoreModel.Result) deserialized;
        assertTrue(deserializedResult.ok instanceof CoreModel.TypeName);
        assertTrue(deserializedResult.err instanceof CoreModel.TypeName);
    }

    @Test
    void testAnnotation() throws Exception {
        // 创建注解
        CoreModel.Annotation annotation = new CoreModel.Annotation();
        annotation.name = "sensitive";
        annotation.params = java.util.Map.of("level", "high");

        // 序列化
        String json = mapper.writeValueAsString(annotation);

        // 验证 JSON 格式
        assertTrue(json.contains("\"name\":\"sensitive\""));
        assertTrue(json.contains("\"level\":\"high\""));

        // 反序列化
        CoreModel.Annotation deserialized = mapper.readValue(json, CoreModel.Annotation.class);

        // 验证
        assertEquals("sensitive", deserialized.name);
        assertEquals("high", deserialized.params.get("level"));
    }

    @Test
    void testTypeVarAndTypeApp() throws Exception {
        // 创建类型变量
        CoreModel.TypeVar typeVar = new CoreModel.TypeVar();
        typeVar.name = "T";

        // 序列化类型变量
        String jsonVar = mapper.writeValueAsString(typeVar);
        assertTrue(jsonVar.contains("\"kind\":\"TypeVar\""));

        // 反序列化类型变量
        CoreModel.Type deserializedVar = mapper.readValue(jsonVar, CoreModel.Type.class);
        assertTrue(deserializedVar instanceof CoreModel.TypeVar);
        assertEquals("T", ((CoreModel.TypeVar) deserializedVar).name);

        // 创建类型应用（如 List<T>）
        CoreModel.TypeApp typeApp = new CoreModel.TypeApp();
        typeApp.base = "List";
        typeApp.args = List.of(typeVar);

        // 序列化类型应用
        String jsonApp = mapper.writeValueAsString(typeApp);
        assertTrue(jsonApp.contains("\"kind\":\"TypeApp\""));

        // 反序列化类型应用
        CoreModel.Type deserializedApp = mapper.readValue(jsonApp, CoreModel.Type.class);
        assertTrue(deserializedApp instanceof CoreModel.TypeApp);
        CoreModel.TypeApp app = (CoreModel.TypeApp) deserializedApp;
        assertEquals("List", app.base);
        assertEquals(1, app.args.size());
    }

    @Test
    void testStartAndWaitStmt() throws Exception {
        // 创建 Start 语句
        CoreModel.Start start = new CoreModel.Start();
        start.name = "task1";
        start.expr = new CoreModel.Name();
        ((CoreModel.Name) start.expr).name = "asyncFunc";

        // 序列化
        String jsonStart = mapper.writeValueAsString(start);
        assertTrue(jsonStart.contains("\"kind\":\"Start\""));

        // 反序列化
        CoreModel.Stmt deserializedStart = mapper.readValue(jsonStart, CoreModel.Stmt.class);
        assertTrue(deserializedStart instanceof CoreModel.Start);
        assertEquals("task1", ((CoreModel.Start) deserializedStart).name);

        // 创建 Wait 语句
        CoreModel.Wait wait = new CoreModel.Wait();
        wait.names = List.of("task1", "task2");

        // 序列化
        String jsonWait = mapper.writeValueAsString(wait);
        assertTrue(jsonWait.contains("\"kind\":\"Wait\""));

        // 反序列化
        CoreModel.Stmt deserializedWait = mapper.readValue(jsonWait, CoreModel.Stmt.class);
        assertTrue(deserializedWait instanceof CoreModel.Wait);
        assertEquals(2, ((CoreModel.Wait) deserializedWait).names.size());
    }
}
