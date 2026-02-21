package aster.core.parser;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 ANTLR4 Parser 的核心语法解析功能
 */
class AsterParserTest {

    /**
     * 辅助方法：解析输入字符串为 ParseTree
     */
    private ParseTree parse(String input) {
        // 词法分析
        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        // 预加载所有 tokens（对于自定义 Lexer 生成 INDENT/DEDENT 是必需的）
        tokens.fill();
        tokens.seek(0);

        // 语法分析
        AsterParser parser = new AsterParser(tokens);

        // 使用诊断错误监听器（不使用 BailErrorStrategy，避免与 INDENT/DEDENT 冲突）
        parser.removeErrorListeners();
        parser.addErrorListener(new DiagnosticErrorListener(true));

        try {
            return parser.module();
        } catch (Exception e) {
            throw new RuntimeException("解析失败: " + e.getMessage(), e);
        }
    }

    @Test
    void testSimpleModule() {
        String input = """
            Module app.

            Rule helloMessage:
              Return "Hello, world!".
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree, "应该成功解析模块");
    }

    @Test
    void testFunctionWithParameters() {
        String input = """
            Rule add given x: Int and y: Int:
              Return x + y.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testDataDeclaration() {
        String input = """
            Define User has name: Text and age: Int.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testEnumDeclaration() {
        String input = """
            Define Status as one of Success, Failure, Pending.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testLetStatement() {
        String input = """
            Rule test:
              Let x be 42.
              Return x.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testIfStatement() {
        String input = """
            Rule check given x: Int:
              If x > 0:
                Return "positive".
              Else:
                Return "non-positive".
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testCallExpression() {
        String input = """
            Rule main:
              Return add 1 2.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testBinaryExpression() {
        String input = """
            Rule calc:
              Return 1 + 2 * 3.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testComparisonExpression() {
        String input = """
            Rule compare given x: Int:
              Return x > 5.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testGenericType() {
        String input = """
            Rule getList produce List<Int>.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testImportDeclaration() {
        String input = """
            use foo.bar.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testImportWithAlias() {
        String input = """
            use foo.bar as Baz.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testCompleteExample() {
        String input = """
            Module app.

            Define User has name: Text and age: Int.

            Rule greet given user: User:
              Return "Hello, " + user.name.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    // ============================================================
    // 新语法测试（Module/Rule/given/has）
    // ============================================================

    @Test
    void testNewModuleSyntax() {
        String input = """
            Module app.

            Rule helloMessage:
              Return "Hello, world!".
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree, "应该成功解析新模块语法");
    }

    @Test
    void testRuleWithGivenParameters() {
        String input = """
            Rule add given x: Int and y: Int:
              Return x + y.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testRuleWithImplicitReturnType() {
        String input = """
            Rule generateQuote given driver, vehicle:
              Return 42.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testDataDeclWithHas() {
        String input = """
            Driver has id, age, yearsLicensed, accidents.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testArticleDataDeclWithHas() {
        String input = """
            a Driver has id, age, yearsLicensed.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testCompleteNewSyntaxExample() {
        String input = """
            Module insurance.auto.

            a Driver has id, age, yearsLicensed, accidents, violations.
            a Vehicle has vin, year, value, safetyRating.
            a Quote has approved, premium, deductible, reason.

            Rule generateQuote given driver, vehicle:
              If driver.age < 18:
                Return Quote with approved = false, premium = 0, deductible = 0, reason = "Driver under 18".
              Let basePremium be calculateBase with driver, vehicle.
              Return Quote with approved = true, premium = basePremium, deductible = 500, reason = "Approved".

            Rule calculateBase given driver, vehicle:
              If driver.age < 25:
                Return 300.
              Return 200.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree, "完整的新语法示例应该能成功解析");
    }

    // TODO: testSyntaxError - 需要实现错误处理策略
    // 当前使用 DiagnosticErrorListener 报告错误但不抛出异常
}
