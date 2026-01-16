package aster.core.parser;

import aster.core.ast.*;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AstBuilder 单元测试
 * <p>
 * 测试 Parse Tree 到 AST 的转换是否正确。
 */
class AstBuilderTest {

    /**
     * 辅助方法：解析输入并构建 AST
     */
    private aster.core.ast.Module parseAndBuild(String input) {
        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        tokens.fill();
        tokens.seek(0);

        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();

        AsterParser.ModuleContext moduleCtx = parser.module();

        if (moduleCtx == null) {
            fail("Parser returned null ModuleContext");
        }

        AstBuilder builder = new AstBuilder();
        aster.core.ast.Module module = builder.visitModule(moduleCtx);

        if (module == null) {
            fail("AstBuilder returned null Module");
        }

        return module;
    }

    @Test
    void testSimpleModule() {
        String input = """
            This module is app.

            To helloMessage produce Text:
              Return "Hello, world!".
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        assertNotNull(module);
        assertEquals("app", module.name());
        assertEquals(1, module.decls().size());

        Decl.Func func = (Decl.Func) module.decls().get(0);
        assertEquals("helloMessage", func.name());
        assertEquals(0, func.params().size());
        assertEquals("Text", ((Type.TypeName) func.retType()).name());
        assertNotNull(func.body());

        Block body = func.body();
        assertEquals(1, body.statements().size());

        Stmt.Return returnStmt = (Stmt.Return) body.statements().get(0);
        Expr.String stringExpr = (Expr.String) returnStmt.expr();
        assertEquals("Hello, world!", stringExpr.value());
    }

    @Test
    void testFunctionWithParameters() {
        String input = """
            To add with x: Int and y: Int, produce Int:
              Return x + y.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        assertEquals(1, module.decls().size());
        Decl.Func func = (Decl.Func) module.decls().get(0);
        assertEquals("add", func.name());
        assertEquals(2, func.params().size());

        Decl.Parameter param1 = func.params().get(0);
        assertEquals("x", param1.name());
        assertEquals("Int", ((Type.TypeName) param1.type()).name());

        Decl.Parameter param2 = func.params().get(1);
        assertEquals("y", param2.name());
        assertEquals("Int", ((Type.TypeName) param2.type()).name());

        assertEquals("Int", ((Type.TypeName) func.retType()).name());
    }

    @Test
    void testDataDeclaration() {
        String input = """
            Define User with name: Text and age: Int.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        assertEquals(1, module.decls().size());
        Decl.Data data = (Decl.Data) module.decls().get(0);
        assertEquals("User", data.name());
        assertEquals(2, data.fields().size());

        Decl.Field field1 = data.fields().get(0);
        assertEquals("name", field1.name());
        assertEquals("Text", ((Type.TypeName) field1.type()).name());

        Decl.Field field2 = data.fields().get(1);
        assertEquals("age", field2.name());
        assertEquals("Int", ((Type.TypeName) field2.type()).name());
    }

    @Test
    void testTypeAlias() {
        String input = """
            type Email as Text.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        assertEquals(1, module.decls().size());
        assertTrue(module.decls().get(0) instanceof Decl.TypeAlias);

        Decl.TypeAlias typeAlias = (Decl.TypeAlias) module.decls().get(0);
        assertEquals("Email", typeAlias.name());
        assertTrue(typeAlias.annotations().isEmpty());

        Type.TypeName target = (Type.TypeName) typeAlias.type();
        assertEquals("Text", target.name());
    }

    @Test
    void testTypeAliasWithAnnotation() {
        String input = """
            @pii type Email as Text.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        assertEquals(1, module.decls().size());
        Decl.TypeAlias typeAlias = (Decl.TypeAlias) module.decls().get(0);
        assertEquals("Email", typeAlias.name());
        assertEquals(1, typeAlias.annotations().size());
        assertEquals("pii", typeAlias.annotations().get(0));
    }

    @Test
    void testFieldWithAnnotation() {
        String input = """
            Define User with @pii email: Text and age: Int.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        Decl.Data data = (Decl.Data) module.decls().get(0);
        Decl.Field emailField = data.fields().get(0);
        assertNotNull(emailField.annotations());
        assertEquals(1, emailField.annotations().size());

        Annotation annotation = emailField.annotations().get(0);
        assertEquals("pii", annotation.name());
        assertTrue(annotation.params().isEmpty());
    }

    @Test
    void testParamWithAnnotation() {
        String input = """
            To send with @pii email: Text, produce Text:
              Return email.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        Decl.Parameter param = func.params().get(0);
        assertNotNull(param.annotations());
        assertEquals(1, param.annotations().size());

        Annotation annotation = param.annotations().get(0);
        assertEquals("pii", annotation.name());
        assertTrue(annotation.params().isEmpty());
    }

    @Test
    void testEnumDeclaration() {
        String input = """
            Define Status as one of Success, Failure, Pending.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        assertEquals(1, module.decls().size());
        Decl.Enum enumDecl = (Decl.Enum) module.decls().get(0);
        assertEquals("Status", enumDecl.name());
        assertEquals(3, enumDecl.variants().size());
        assertEquals("Success", enumDecl.variants().get(0));
        assertEquals("Failure", enumDecl.variants().get(1));
        assertEquals("Pending", enumDecl.variants().get(2));
    }

    @Test
    void testLetStatement() {
        String input = """
            To test produce Int:
              Let x be 42.
              Return x.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        Block body = func.body();
        assertEquals(2, body.statements().size());

        Stmt.Let letStmt = (Stmt.Let) body.statements().get(0);
        assertEquals("x", letStmt.name());
        Expr.Int intExpr = (Expr.Int) letStmt.expr();
        assertEquals(42, intExpr.value());
    }

    @Test
    void testIfStatement() {
        String input = """
            To check with x: Int, produce Text:
              If x > 0:
                Return "positive".
              Else:
                Return "non-positive".
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        Block body = func.body();
        assertEquals(1, body.statements().size());

        Stmt.If ifStmt = (Stmt.If) body.statements().get(0);
        assertNotNull(ifStmt.cond());
        assertNotNull(ifStmt.thenBlock());
        assertNotNull(ifStmt.elseBlock());

        // Then block
        assertEquals(1, ifStmt.thenBlock().statements().size());
        Stmt.Return thenReturn = (Stmt.Return) ifStmt.thenBlock().statements().get(0);
        assertEquals("positive", ((Expr.String) thenReturn.expr()).value());

        // Else block
        assertEquals(1, ifStmt.elseBlock().statements().size());
        Stmt.Return elseReturn = (Stmt.Return) ifStmt.elseBlock().statements().get(0);
        assertEquals("non-positive", ((Expr.String) elseReturn.expr()).value());
    }

    @Test
    void testStringLiteralEscapesInAst() {
        String input = """
            To sample produce Text:
              Return "line1\\nline2\\t\\u0041".
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);
        Expr.String stringExpr = (Expr.String) returnStmt.expr();
        assertEquals("line1\nline2\tA", stringExpr.value());
    }

    @Test
    void testBinaryExpression() {
        String input = """
            To calc produce Int:
              Return 1 + 2 * 3.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        // 表达式应该是一个 Call 节点 (+ 操作)
        Expr.Call addCall = (Expr.Call) returnStmt.expr();
        assertEquals("+", ((Expr.Name) addCall.target()).name());
        assertEquals(2, addCall.args().size());
    }

    @Test
    void parsePrefixLessThan() {
        String input = """
            To compare with x: Int and y: Int, produce Bool:
              Return <(x, y).
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        assertTrue(returnStmt.expr() instanceof Expr.Call);
        Expr.Call call = (Expr.Call) returnStmt.expr();
        assertEquals("<", ((Expr.Name) call.target()).name());
        assertEquals(2, call.args().size());
        assertEquals("x", ((Expr.Name) call.args().get(0)).name());
        assertEquals("y", ((Expr.Name) call.args().get(1)).name());
    }

    @Test
    void parsePrefixPlus() {
        String input = """
            To sum with a: Int and b: Int, produce Int:
              Return +(a, b).
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        assertTrue(returnStmt.expr() instanceof Expr.Call);
        Expr.Call call = (Expr.Call) returnStmt.expr();
        assertEquals("+", ((Expr.Name) call.target()).name());
        assertEquals(2, call.args().size());
        assertEquals("a", ((Expr.Name) call.args().get(0)).name());
        assertEquals("b", ((Expr.Name) call.args().get(1)).name());
    }

    @Test
    void parsePrefixGreaterEqual() {
        String input = """
            To ge with m: Int and n: Int, produce Bool:
              Return >=(m, n).
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        assertTrue(returnStmt.expr() instanceof Expr.Call);
        Expr.Call call = (Expr.Call) returnStmt.expr();
        assertEquals(">=", ((Expr.Name) call.target()).name());
        assertEquals(2, call.args().size());
        assertEquals("m", ((Expr.Name) call.args().get(0)).name());
        assertEquals("n", ((Expr.Name) call.args().get(1)).name());
    }

    @Test
    void parsePrefixInvalidArity() {
        String input = """
            To invalid with x: Int, produce Int:
              Return +(x).
            """;

        assertThrows(IllegalStateException.class, () -> parseAndBuild(input));
    }

    @Test
    void testCallExpression() {
        String input = """
            To main produce Int:
              Return add(1, 2).
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        Expr.Call call = (Expr.Call) returnStmt.expr();
        assertEquals("add", ((Expr.Name) call.target()).name());
        assertEquals(2, call.args().size());
        assertEquals(1, ((Expr.Int) call.args().get(0)).value());
        assertEquals(2, ((Expr.Int) call.args().get(1)).value());
    }

    @Test
    void testQualifiedCallWithParentheses() {
        String input = """
            To fetch produce Text:
              Return Http.get("https://api.example.com").
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        assertTrue(returnStmt.expr() instanceof Expr.Call);
        Expr.Call call = (Expr.Call) returnStmt.expr();
        assertEquals("Http.get", ((Expr.Name) call.target()).name());
        assertEquals(1, call.args().size());
        assertEquals("https://api.example.com", ((Expr.String) call.args().get(0)).value());
    }

    @Test
    void testResultConstructors() {
        String input = """
            To wrap produce Result:
              Return Ok(42).
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        assertTrue(returnStmt.expr() instanceof Expr.Ok);
        Expr.Ok ok = (Expr.Ok) returnStmt.expr();
        assertEquals(42, ((Expr.Int) ok.expr()).value());
    }

    @Test
    void testErrConstructor() {
        String input = """
            To fail produce Result:
              Return Err("oops").
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        assertTrue(returnStmt.expr() instanceof Expr.Err);
        Expr.Err err = (Expr.Err) returnStmt.expr();
        assertEquals("oops", ((Expr.String) err.expr()).value());
    }

    @Test
    void testOptionConstructors() {
        String input = """
            To optionize produce Option<Int>:
              Return Some(7).
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        assertTrue(returnStmt.expr() instanceof Expr.Some);
        Expr.Some some = (Expr.Some) returnStmt.expr();
        assertEquals(7, ((Expr.Int) some.expr()).value());
    }

    @Test
    void testNoneConstructor() {
        String input = """
            To getNone produce Option<Int>:
              Return None().
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        assertTrue(returnStmt.expr() instanceof Expr.None);
    }

    @Test
    void testMethodCallTransformsReceiver() {
        String input = """
            To compute produce Int:
              Return value.sum(1, 2).
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        assertTrue(returnStmt.expr() instanceof Expr.Call);
        Expr.Call call = (Expr.Call) returnStmt.expr();
        assertEquals("sum", ((Expr.Name) call.target()).name());
        assertEquals(3, call.args().size());
        assertEquals("value", ((Expr.Name) call.args().get(0)).name());
        assertEquals(1, ((Expr.Int) call.args().get(1)).value());
        assertEquals(2, ((Expr.Int) call.args().get(2)).value());
    }

    @Test
    void testChainedMethodCall() {
        String input = """
            To load produce Text:
              Return Http.get("https://example.com").then(handle).
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        assertTrue(returnStmt.expr() instanceof Expr.Call);
        Expr.Call outer = (Expr.Call) returnStmt.expr();
        assertEquals("then", ((Expr.Name) outer.target()).name());
        assertEquals(2, outer.args().size());
        assertTrue(outer.args().get(0) instanceof Expr.Call);
        Expr.Call inner = (Expr.Call) outer.args().get(0);
        assertEquals("Http.get", ((Expr.Name) inner.target()).name());
        assertEquals(1, inner.args().size());
        assertEquals("https://example.com", ((Expr.String) inner.args().get(0)).value());
        assertEquals("handle", ((Expr.Name) outer.args().get(1)).name());
    }

    @Test
    void testGenericType() {
        String input = """
            To getList produce List<Int>.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        Type.TypeApp typeApp = (Type.TypeApp) func.retType();
        assertEquals("List", typeApp.base());
        assertEquals(1, typeApp.args().size());
        assertEquals("Int", ((Type.TypeName) typeApp.args().get(0)).name());
    }

    @Test
    void parseImportWithoutAlias() {
        String input = """
            Use data.List.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        assertEquals(1, module.decls().size());
        Decl.Import importDecl = (Decl.Import) module.decls().get(0);
        assertEquals("data.List", importDecl.path());
        assertNull(importDecl.alias());
    }

    @Test
    void parseImportWithAlias() {
        String input = """
            Use io.Http as HttpClient.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        assertEquals(1, module.decls().size());
        Decl.Import importDecl = (Decl.Import) module.decls().get(0);
        assertEquals("io.Http", importDecl.path());
        assertEquals("HttpClient", importDecl.alias());
    }

    @Test
    void parseImportWithNestedPath() {
        String input = """
            Use a.b.c.D.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        assertEquals(1, module.decls().size());
        Decl.Import importDecl = (Decl.Import) module.decls().get(0);
        assertEquals("a.b.c.D", importDecl.path());
        assertNull(importDecl.alias());
    }

    @Test
    void parseEnumWithArticleAndOr() {
        String input = """
            Define a RuleType as one of Access or Validation or Transformation or Notification.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        assertEquals(1, module.decls().size());
        Decl.Enum enumDecl = (Decl.Enum) module.decls().get(0);
        assertEquals("RuleType", enumDecl.name());
        assertEquals(List.of("Access", "Validation", "Transformation", "Notification"), enumDecl.variants());
    }

    @Test
    void parseStartAndWaitStatements() {
        String input = """
            To run produce Text:
              Start task as async load().
              Wait for task.
              Return "done".
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        Block body = func.body();
        assertEquals(3, body.statements().size());

        Stmt.Start start = (Stmt.Start) body.statements().get(0);
        assertEquals("task", start.name());
        Expr.Call asyncCall = (Expr.Call) start.expr();
        assertEquals("async", ((Expr.Name) asyncCall.target()).name());

        Stmt.Wait wait = (Stmt.Wait) body.statements().get(1);
        assertEquals(List.of("task"), wait.names());
    }

    @Test
    void parseListLiteralAndNotExpression() {
        String input = """
            To example produce Bool:
              Define values as [1, 2].
              Return not Bool.equals(values, values).
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        Block body = func.body();
        assertEquals(2, body.statements().size());

        Stmt.Let define = (Stmt.Let) body.statements().get(0);
        Expr.ListLiteral literal = (Expr.ListLiteral) define.expr();
        assertEquals(2, literal.items().size());

        Stmt.Return ret = (Stmt.Return) body.statements().get(1);
        Expr.Call notCall = (Expr.Call) ret.expr();
        assertEquals("not", ((Expr.Name) notCall.target()).name());
    }

    @Test
    void testSpanInformation() {
        String input = """
            To test produce Int:
              Return 42.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        assertNotNull(module.span());
        assertEquals(1, module.span().start().line());

        Decl.Func func = (Decl.Func) module.decls().get(0);
        assertNotNull(func.span());
        assertEquals(1, func.span().start().line());
    }

    @Test
    void testLiteralTypes() {
        String input = """
            To literals produce Int:
              Let b be true.
              Let i be 42.
              Let l be 100L.
              Let f be 3.14.
              Let s be "hello".
              Return 0.
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        Block body = func.body();
        assertEquals(6, body.statements().size());

        // Bool
        Stmt.Let boolLet = (Stmt.Let) body.statements().get(0);
        assertTrue(((Expr.Bool) boolLet.expr()).value());

        // Int
        Stmt.Let intLet = (Stmt.Let) body.statements().get(1);
        assertEquals(42, ((Expr.Int) intLet.expr()).value());

        // Long
        Stmt.Let longLet = (Stmt.Let) body.statements().get(2);
        assertEquals(100L, ((Expr.Long) longLet.expr()).value());

        // Double
        Stmt.Let floatLet = (Stmt.Let) body.statements().get(3);
        assertEquals(3.14, ((Expr.Double) floatLet.expr()).value(), 0.001);

        // String
        Stmt.Let stringLet = (Stmt.Let) body.statements().get(4);
        assertEquals("hello", ((Expr.String) stringLet.expr()).value());
    }

    /**
     * Phase 3-5: 测试 Maybe 类型（T?）
     */
    @Test
    void testMaybeType() {
        String input = """
            To fromMaybe with x: Text? and d: Text, produce Text:
              Return d.
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);

        // 检查第一个参数是 Maybe 类型
        Decl.Parameter param1 = func.params().get(0);
        assertEquals("x", param1.name());
        assertTrue(param1.type() instanceof Type.Maybe);
        Type.Maybe maybeType = (Type.Maybe) param1.type();
        assertTrue(maybeType.type() instanceof Type.TypeName);
        assertEquals("Text", ((Type.TypeName) maybeType.type()).name());

        // 检查第二个参数是普通类型
        Decl.Parameter param2 = func.params().get(1);
        assertEquals("d", param2.name());
        assertTrue(param2.type() instanceof Type.TypeName);
        assertEquals("Text", ((Type.TypeName) param2.type()).name());
    }

    /**
     * Phase 3-5: 测试函数类型（FuncType）
     */
    @Test
    void testFuncType() {
        String input = """
            To apply with f: (Int, Int) -> Int and x: Int, produce Int:
              Return x.
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);

        // 检查第一个参数是函数类型
        Decl.Parameter param1 = func.params().get(0);
        assertEquals("f", param1.name());
        assertTrue(param1.type() instanceof Type.FuncType);
        Type.FuncType funcType = (Type.FuncType) param1.type();

        // 检查函数类型参数
        assertEquals(2, funcType.params().size());
        assertEquals("Int", ((Type.TypeName) funcType.params().get(0)).name());
        assertEquals("Int", ((Type.TypeName) funcType.params().get(1)).name());

        // 检查函数类型返回值
        assertEquals("Int", ((Type.TypeName) funcType.ret()).name());
    }

    /**
     * Phase 3-5: 测试 Match 语句
     */
    @Test
    void testMatchStatement() {
        String input = """
            To check with x: Text?, produce Text:
              Match x:
                When null, Return "empty".
                When v, Return v.
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Match matchStmt = (Stmt.Match) func.body().statements().get(0);

        // 检查 Match 表达式
        assertTrue(matchStmt.expr() instanceof Expr.Name);
        assertEquals("x", ((Expr.Name) matchStmt.expr()).name());

        // 检查 Match cases
        assertEquals(2, matchStmt.cases().size());

        // Case 1: When null
        Stmt.Case case1 = matchStmt.cases().get(0);
        assertTrue(case1.pattern() instanceof Pattern.PatternNull);
        assertTrue(case1.body() instanceof Stmt.Return);
        Stmt.Return return1 = (Stmt.Return) case1.body();
        assertEquals("empty", ((Expr.String) return1.expr()).value());

        // Case 2: When v
        Stmt.Case case2 = matchStmt.cases().get(1);
        assertTrue(case2.pattern() instanceof Pattern.PatternName);
        assertEquals("v", ((Pattern.PatternName) case2.pattern()).name());
        assertTrue(case2.body() instanceof Stmt.Return);
        Stmt.Return return2 = (Stmt.Return) case2.body();
        assertEquals("v", ((Expr.Name) return2.expr()).name());
    }

    /**
     * Phase 3-5: 测试 Lambda 表达式
     */
    @Test
    void testLambdaExpression() {
        String input = """
            To fromMaybe with x: Text? and d: Text, produce Text:
              Let f be function with x: Text?, produce Text:
                Match x:
                  When null, Return d.
                  When v, Return v.
              .
              Return "ok".
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Let letStmt = (Stmt.Let) func.body().statements().get(0);

        // 检查 Lambda 表达式
        assertTrue(letStmt.expr() instanceof Expr.Lambda);
        Expr.Lambda lambda = (Expr.Lambda) letStmt.expr();

        // 检查 Lambda 参数
        assertEquals(1, lambda.params().size());
        assertEquals("x", lambda.params().get(0).name());
        assertTrue(lambda.params().get(0).type() instanceof Type.Maybe);

        // 检查 Lambda 返回类型
        assertTrue(lambda.retType() instanceof Type.TypeName);
        assertEquals("Text", ((Type.TypeName) lambda.retType()).name());

        // 检查 Lambda 函数体
        Block lambdaBody = lambda.body();
        assertEquals(1, lambdaBody.statements().size());
        assertTrue(lambdaBody.statements().get(0) instanceof Stmt.Match);
    }

    /**
     * Phase 3-5: 测试能力标注（Capability Annotation）
     */
    @Test
    void testCapabilityAnnotation() {
        String input = """
            To ping, produce Text. It performs io [Http, Sql, Time]:
              Return "ok".
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);

        // 检查函数名和返回类型
        assertEquals("ping", func.name());
        assertEquals("Text", ((Type.TypeName) func.retType()).name());

        // 检查能力标注
        assertNotNull(func.effects());
        assertEquals(1, func.effects().size());
        assertEquals("io", func.effects().get(0));

        assertNotNull(func.effectCaps());
        assertEquals(3, func.effectCaps().size());
        assertEquals("Http", func.effectCaps().get(0));
        assertEquals("Sql", func.effectCaps().get(1));
        assertEquals("Time", func.effectCaps().get(2));

        assertTrue(func.effectCapsExplicit());
    }

    /**
     * Phase 3-5: 测试不带能力标注的函数
     */
    @Test
    void testFunctionWithoutCapability() {
        String input = """
            To add with x: Int and y: Int, produce Int:
              Return x.
            """;

        aster.core.ast.Module module = parseAndBuild(input);
        Decl.Func func = (Decl.Func) module.decls().get(0);

        // 检查能力标注为空集合且显式标记为 false
        assertNotNull(func.effects());
        assertTrue(func.effects().isEmpty());
        assertNotNull(func.effectCaps());
        assertTrue(func.effectCaps().isEmpty());
        assertFalse(func.effectCapsExplicit());
    }

    /**
     * Phase 3-5: 综合测试 - Lambda + Match + Maybe
     */
    @Test
    void testComprehensiveFeatures() {
        String input = """
            This module is demo.lambdamatchmaybe.

            To fromMaybe with x: Text? and d: Text, produce Text:
              Let f be function with x: Text?, produce Text:
                Match x:
                  When null, Return d.
                  When v, Return v.
              .
              Return f(x).
            """;

        aster.core.ast.Module module = parseAndBuild(input);

        // 检查模块
        assertEquals("demo.lambdamatchmaybe", module.name());
        assertEquals(1, module.decls().size());

        // 检查函数
        Decl.Func func = (Decl.Func) module.decls().get(0);
        assertEquals("fromMaybe", func.name());
        assertEquals(2, func.params().size());

        // 检查 Maybe 类型参数
        assertTrue(func.params().get(0).type() instanceof Type.Maybe);

        // 检查函数体包含 Let 和 Return
        assertEquals(2, func.body().statements().size());
        assertTrue(func.body().statements().get(0) instanceof Stmt.Let);
        assertTrue(func.body().statements().get(1) instanceof Stmt.Return);

        // 检查 Lambda
        Stmt.Let letStmt = (Stmt.Let) func.body().statements().get(0);
        assertTrue(letStmt.expr() instanceof Expr.Lambda);

        // 检查 Return 调用 Lambda
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(1);
        assertTrue(returnStmt.expr() instanceof Expr.Call);
        Expr.Call call = (Expr.Call) returnStmt.expr();
        assertEquals("f", ((Expr.Name) call.target()).name());
        assertEquals(1, call.args().size());
        assertEquals("x", ((Expr.Name) call.args().get(0)).name());
    }
}
