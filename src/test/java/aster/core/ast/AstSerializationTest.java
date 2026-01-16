package aster.core.ast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CNL AST 序列化测试
 * <p>
 * 验证 AST 节点的 JSON 序列化/反序列化功能，确保与 TypeScript 版本的互操作性。
 */
class AstSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ============================================================
    // 辅助方法
    // ============================================================

    private Span createSpan(int startLine, int startCol, int endLine, int endCol) {
        return new Span(
            new Span.Position(startLine, startCol),
            new Span.Position(endLine, endCol)
        );
    }

    // ============================================================
    // Module 测试
    // ============================================================

    @Test
    void testModuleSerialization() throws Exception {
        // 创建一个简单的模块
        Module module = new Module("test", List.of(), createSpan(1, 1, 1, 10));

        // 序列化
        String json = mapper.writeValueAsString(module);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Module\""));
        assertTrue(json.contains("\"name\":\"test\""));

        // 反序列化
        Module deserialized = mapper.readValue(json, Module.class);

        // 验证
        assertEquals("test", deserialized.name());
        assertNotNull(deserialized.decls());
        assertTrue(deserialized.decls().isEmpty());
    }

    // ============================================================
    // Declaration 测试
    // ============================================================

    @Test
    void testImportDecl() throws Exception {
        // 创建 Import 声明
        Decl.Import importDecl = new Decl.Import("std.io", null, createSpan(1, 1, 1, 15));

        // 序列化
        String json = mapper.writeValueAsString(importDecl);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Import\""));
        assertTrue(json.contains("\"path\":\"std.io\""));

        // 反序列化
        Decl deserialized = mapper.readValue(json, Decl.class);

        // 验证
        assertTrue(deserialized instanceof Decl.Import);
        assertEquals("std.io", ((Decl.Import) deserialized).path());
    }

    @Test
    void testDataDecl() throws Exception {
        // 创建 Data 声明
        Type intType = new Type.TypeName("Int", List.of(), createSpan(2, 10, 2, 13));
        Decl.Field field = new Decl.Field("age", intType, null);
        Decl.Data dataDecl = new Decl.Data("Person", List.of(field), createSpan(1, 1, 3, 2));

        // 序列化
        String json = mapper.writeValueAsString(dataDecl);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Data\""));
        assertTrue(json.contains("\"name\":\"Person\""));

        // 反序列化
        Decl deserialized = mapper.readValue(json, Decl.class);

        // 验证
        assertTrue(deserialized instanceof Decl.Data);
        Decl.Data data = (Decl.Data) deserialized;
        assertEquals("Person", data.name());
        assertEquals(1, data.fields().size());
        assertEquals("age", data.fields().get(0).name());
    }

    @Test
    void testEnumDecl() throws Exception {
        // 创建 Enum 声明
        Decl.Enum enumDecl = new Decl.Enum("Status", List.of("Active", "Inactive"), createSpan(1, 1, 1, 30));

        // 序列化
        String json = mapper.writeValueAsString(enumDecl);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Enum\""));
        assertTrue(json.contains("\"name\":\"Status\""));
        assertTrue(json.contains("\"Active\""));

        // 反序列化
        Decl deserialized = mapper.readValue(json, Decl.class);

        // 验证
        assertTrue(deserialized instanceof Decl.Enum);
        Decl.Enum enumData = (Decl.Enum) deserialized;
        assertEquals("Status", enumData.name());
        assertEquals(2, enumData.variants().size());
    }

    @Test
    void testFuncDecl() throws Exception {
        // 创建函数声明
        Type intType = new Type.TypeName("Int", List.of(), createSpan(1, 20, 1, 23));
        Block emptyBody = new Block(List.of(), createSpan(1, 25, 1, 27));
        Span nameSpan = createSpan(1, 1, 1, 4);
        Decl.Func funcDecl = new Decl.Func(
            "add",
            nameSpan,
            List.of(),
            List.of(),
            intType,
            List.of(),
            emptyBody,
            List.of(),
            List.of(),
            false,
            createSpan(1, 1, 1, 27)
        );

        // 序列化
        String json = mapper.writeValueAsString(funcDecl);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Func\""));
        assertTrue(json.contains("\"name\":\"add\""));

        // 反序列化
        Decl deserialized = mapper.readValue(json, Decl.class);

        // 验证
        assertTrue(deserialized instanceof Decl.Func);
        assertEquals("add", ((Decl.Func) deserialized).name());
    }

    // ============================================================
    // Statement 测试
    // ============================================================

    @Test
    void testLetStmt() throws Exception {
        // 创建 Let 语句
        Expr.Int intExpr = new Expr.Int(42, createSpan(1, 10, 1, 12));
        Stmt.Let letStmt = new Stmt.Let("x", intExpr, createSpan(1, 1, 1, 12));

        // 序列化
        String json = mapper.writeValueAsString(letStmt);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Let\""));
        assertTrue(json.contains("\"name\":\"x\""));
        assertTrue(json.contains("\"value\":42"));

        // 反序列化
        Stmt deserialized = mapper.readValue(json, Stmt.class);

        // 验证
        assertTrue(deserialized instanceof Stmt.Let);
        assertEquals("x", ((Stmt.Let) deserialized).name());
    }

    @Test
    void testIfStmt() throws Exception {
        // 创建 If 语句
        Expr.Bool cond = new Expr.Bool(true, createSpan(1, 4, 1, 8));
        Block thenBlock = new Block(List.of(), createSpan(1, 10, 1, 12));
        Stmt.If ifStmt = new Stmt.If(cond, thenBlock, null, createSpan(1, 1, 1, 12));

        // 序列化
        String json = mapper.writeValueAsString(ifStmt);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"If\""));
        assertTrue(json.contains("\"kind\":\"Bool\""));

        // 反序列化
        Stmt deserialized = mapper.readValue(json, Stmt.class);

        // 验证
        assertTrue(deserialized instanceof Stmt.If);
        assertTrue(((Stmt.If) deserialized).cond() instanceof Expr.Bool);
    }

    // ============================================================
    // Expression 测试
    // ============================================================

    @Test
    void testNameExpr() throws Exception {
        // 创建 Name 表达式
        Expr.Name name = new Expr.Name("x", createSpan(1, 1, 1, 2));

        // 序列化
        String json = mapper.writeValueAsString(name);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Name\""));
        assertTrue(json.contains("\"name\":\"x\""));

        // 反序列化
        Expr deserialized = mapper.readValue(json, Expr.class);

        // 验证
        assertTrue(deserialized instanceof Expr.Name);
        assertEquals("x", ((Expr.Name) deserialized).name());
    }

    @Test
    void testLiteralExprs() throws Exception {
        // Bool
        Expr.Bool boolExpr = new Expr.Bool(true, createSpan(1, 1, 1, 5));
        String boolJson = mapper.writeValueAsString(boolExpr);
        assertTrue(boolJson.contains("\"kind\":\"Bool\""));
        assertTrue(boolJson.contains("\"value\":true"));

        // Int
        Expr.Int intExpr = new Expr.Int(42, createSpan(1, 1, 1, 3));
        String intJson = mapper.writeValueAsString(intExpr);
        assertTrue(intJson.contains("\"kind\":\"Int\""));
        assertTrue(intJson.contains("\"value\":42"));

        // String
        Expr.String strExpr = new Expr.String("hello", createSpan(1, 1, 1, 8));
        String strJson = mapper.writeValueAsString(strExpr);
        assertTrue(strJson.contains("\"kind\":\"String\""));
        assertTrue(strJson.contains("\"value\":\"hello\""));
    }

    @Test
    void testCallExpr() throws Exception {
        // 创建函数调用表达式
        Expr.Name target = new Expr.Name("print", createSpan(1, 1, 1, 6));
        Expr.String arg = new Expr.String("test", createSpan(1, 7, 1, 13));
        Expr.Call call = new Expr.Call(target, List.of(arg), createSpan(1, 1, 1, 14));

        // 序列化
        String json = mapper.writeValueAsString(call);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Call\""));
        assertTrue(json.contains("\"kind\":\"Name\""));

        // 反序列化
        Expr deserialized = mapper.readValue(json, Expr.class);

        // 验证
        assertTrue(deserialized instanceof Expr.Call);
        Expr.Call deserializedCall = (Expr.Call) deserialized;
        assertTrue(deserializedCall.target() instanceof Expr.Name);
        assertEquals(1, deserializedCall.args().size());
    }

    @Test
    void testConstructExpr() throws Exception {
        // 创建构造表达式
        Expr.Int value = new Expr.Int(25, createSpan(2, 10, 2, 12));
        Expr.Construct.ConstructField field = new Expr.Construct.ConstructField("age", value);
        Expr.Construct construct = new Expr.Construct("Person", List.of(field), createSpan(1, 1, 3, 2));

        // 序列化
        String json = mapper.writeValueAsString(construct);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Construct\""));
        assertTrue(json.contains("\"typeName\":\"Person\""));

        // 反序列化
        Expr deserialized = mapper.readValue(json, Expr.class);

        // 验证
        assertTrue(deserialized instanceof Expr.Construct);
        Expr.Construct deserializedConstruct = (Expr.Construct) deserialized;
        assertEquals("Person", deserializedConstruct.typeName());
        assertEquals(1, deserializedConstruct.fields().size());
    }

    @Test
    void testLambdaExpr() throws Exception {
        // 创建 Lambda 表达式
        Type intType = new Type.TypeName("Int", List.of(), createSpan(1, 15, 1, 18));
        Block body = new Block(List.of(), createSpan(1, 20, 1, 22));
        Expr.Lambda lambda = new Expr.Lambda(List.of(), intType, body, createSpan(1, 1, 1, 22));

        // 序列化
        String json = mapper.writeValueAsString(lambda);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Lambda\""));

        // 反序列化
        Expr deserialized = mapper.readValue(json, Expr.class);

        // 验证
        assertTrue(deserialized instanceof Expr.Lambda);
        assertNotNull(((Expr.Lambda) deserialized).params());
    }

    // ============================================================
    // Pattern 测试
    // ============================================================

    @Test
    void testPatternCtor() throws Exception {
        // 创建构造器模式
        Pattern.PatternCtor patCtor = new Pattern.PatternCtor(
            "Some",
            List.of("x"),
            null,
            createSpan(1, 1, 1, 8)
        );

        // 序列化
        String json = mapper.writeValueAsString(patCtor);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"PatternCtor\""));
        assertTrue(json.contains("\"typeName\":\"Some\""));

        // 反序列化
        Pattern deserialized = mapper.readValue(json, Pattern.class);

        // 验证
        assertTrue(deserialized instanceof Pattern.PatternCtor);
        Pattern.PatternCtor deserializedPat = (Pattern.PatternCtor) deserialized;
        assertEquals("Some", deserializedPat.typeName());
        assertEquals(1, deserializedPat.names().size());
        assertEquals("x", deserializedPat.names().get(0));
    }

    // ============================================================
    // Type 测试
    // ============================================================

    @Test
    void testTypeName() throws Exception {
        // 创建类型名称
        Type.TypeName typeName = new Type.TypeName("Int", List.of(), createSpan(1, 1, 1, 4));

        // 序列化
        String json = mapper.writeValueAsString(typeName);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"TypeName\""));
        assertTrue(json.contains("\"name\":\"Int\""));

        // 反序列化
        Type deserialized = mapper.readValue(json, Type.class);

        // 验证
        assertTrue(deserialized instanceof Type.TypeName);
        assertEquals("Int", ((Type.TypeName) deserialized).name());
    }

    @Test
    void testResultType() throws Exception {
        // 创建 Result 类型
        Type.TypeName okType = new Type.TypeName("Int", List.of(), createSpan(1, 8, 1, 11));
        Type.TypeName errType = new Type.TypeName("String", List.of(), createSpan(1, 13, 1, 19));
        Type.Result result = new Type.Result(okType, errType, List.of(), createSpan(1, 1, 1, 20));

        // 序列化
        String json = mapper.writeValueAsString(result);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"Result\""));

        // 反序列化
        Type deserialized = mapper.readValue(json, Type.class);

        // 验证
        assertTrue(deserialized instanceof Type.Result);
        assertTrue(((Type.Result) deserialized).ok() instanceof Type.TypeName);
        assertTrue(((Type.Result) deserialized).err() instanceof Type.TypeName);
    }

    @Test
    void testTypeApp() throws Exception {
        // 创建类型应用（如 List<Int>）
        Type.TypeName intType = new Type.TypeName("Int", List.of(), createSpan(1, 6, 1, 9));
        Type.TypeApp typeApp = new Type.TypeApp("List", List.of(), List.of(intType), createSpan(1, 1, 1, 10));

        // 序列化
        String json = mapper.writeValueAsString(typeApp);

        // 验证 JSON 格式
        assertTrue(json.contains("\"kind\":\"TypeApp\""));
        assertTrue(json.contains("\"base\":\"List\""));

        // 反序列化
        Type deserialized = mapper.readValue(json, Type.class);

        // 验证
        assertTrue(deserialized instanceof Type.TypeApp);
        Type.TypeApp app = (Type.TypeApp) deserialized;
        assertEquals("List", app.base());
        assertEquals(1, app.args().size());
    }

    @Test
    void testAnnotation() throws Exception {
        // 创建注解
        Annotation annotation = new Annotation("sensitive", Map.of("level", "high"));

        // 序列化
        String json = mapper.writeValueAsString(annotation);

        // 验证 JSON 格式
        assertTrue(json.contains("\"name\":\"sensitive\""));
        assertTrue(json.contains("\"level\""));

        // 反序列化
        Annotation deserialized = mapper.readValue(json, Annotation.class);

        // 验证
        assertEquals("sensitive", deserialized.name());
        assertEquals("high", deserialized.params().get("level"));
    }
}
