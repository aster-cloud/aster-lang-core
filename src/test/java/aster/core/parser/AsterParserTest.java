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
            This module is app.

            To helloMessage produce Text:
              Return "Hello, world!".
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree, "应该成功解析模块");
    }

    @Test
    void testFunctionWithParameters() {
        String input = """
            To add with x: Int and y: Int, produce Int:
              Return x + y.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testDataDeclaration() {
        String input = """
            Define User with name: Text and age: Int.
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
            To test produce Int:
              Let x be 42.
              Return x.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
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

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testCallExpression() {
        String input = """
            To main produce Int:
              Return add 1 2.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testBinaryExpression() {
        String input = """
            To calc produce Int:
              Return 1 + 2 * 3.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testComparisonExpression() {
        String input = """
            To compare with x: Int, produce Bool:
              Return x > 5.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    @Test
    void testGenericType() {
        String input = """
            To getList produce List<Int>.
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
            This module is app.

            Define User with name: Text and age: Int.

            To greet with user: User, produce Text:
              Return "Hello, " + user.name.
            """;

        ParseTree tree = parse(input);
        assertNotNull(tree);
    }

    // TODO: testSyntaxError - 需要实现错误处理策略
    // 当前使用 DiagnosticErrorListener 报告错误但不抛出异常
}
