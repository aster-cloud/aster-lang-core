package aster.core.parser;

import aster.core.ast.*;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试自然语言风格的 CNL 语法解析
 */
class NaturalLanguageCnlTest {

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
    void testNaturalLanguageOperators() {
        String source = """
            Module test.

            Rule testLessThan given x:
              If x less than 10
                Return true.
              Return false.
            """;

        aster.core.ast.Module module = parseAndBuild(source);
        assertNotNull(module);
        assertEquals("test", module.name());
        assertEquals(1, module.decls().size());

        Decl.Func func = (Decl.Func) module.decls().get(0);
        assertEquals("testLessThan", func.name());

        // 验证参数使用类型推断
        assertEquals(1, func.params().size());
        assertEquals("x", func.params().get(0).name());

        // 验证返回类型被推断为 Text (基于函数名前缀 "test")
        // 注意: testLessThan 以 "test" 开头，但不在生成器前缀中，所以返回 Text
        // 但是布尔前缀检查会匹配 "check"、"validate" 等，不会匹配 "test"
        // 所以返回类型是 Text
        assertTrue(func.retType() instanceof Type.TypeName);
    }

    @Test
    void testGreaterThanOperator() {
        String source = """
            Module test.

            Rule checkValue given count:
              If count greater than 5
                Return true.
              Return false.
            """;

        aster.core.ast.Module module = parseAndBuild(source);
        assertNotNull(module);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        assertEquals("checkValue", func.name());

        // checkValue 以 "check" 开头，所以返回类型推断为 Bool
        Type retType = func.retType();
        assertTrue(retType instanceof Type.TypeName);
        assertEquals("Bool", ((Type.TypeName) retType).name());
    }

    @Test
    void testArithmeticOperators() {
        String source = """
            Module test.

            Rule calculateTotal given price, quantity:
              Let subtotal be price times quantity.
              Let tax be subtotal divided by 10.
              Let total be subtotal plus tax.
              Return total.
            """;

        aster.core.ast.Module module = parseAndBuild(source);
        assertNotNull(module);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        assertEquals("calculateTotal", func.name());

        // calculateTotal 以 "calculate" 开头，所以返回类型推断为 Int
        Type retType = func.retType();
        assertTrue(retType instanceof Type.TypeName);
        assertEquals("Int", ((Type.TypeName) retType).name());
    }

    @Test
    void testWithCallSyntax() {
        String source = """
            Module test.

            Rule computeSum given a, b:
              Return a plus b.

            Rule main:
              Let result be computeSum with 10, 20.
              Return result.
            """;

        aster.core.ast.Module module = parseAndBuild(source);
        assertNotNull(module);
        assertEquals(2, module.decls().size());

        // 验证 main 函数中的 WITH 调用语法
        Decl.Func mainFunc = (Decl.Func) module.decls().get(1);
        assertEquals("main", mainFunc.name());
        assertNotNull(mainFunc.body());
        assertEquals(2, mainFunc.body().statements().size());
    }

    @Test
    void testImplicitParameterTypes() {
        String source = """
            Module test.

            Define User has userId, age, isActive, createdAt, totalAmount.
            """;

        aster.core.ast.Module module = parseAndBuild(source);
        assertNotNull(module);

        Decl.Data data = (Decl.Data) module.decls().get(0);
        assertEquals("User", data.name());
        assertEquals(5, data.fields().size());

        // userId -> Text (Id 后缀)
        assertEquals("Text", ((Type.TypeName) data.fields().get(0).type()).name());
        // age -> Int (Age 后缀)
        assertEquals("Int", ((Type.TypeName) data.fields().get(1).type()).name());
        // isActive -> Bool (is 前缀)
        assertEquals("Bool", ((Type.TypeName) data.fields().get(2).type()).name());
        // createdAt -> DateTime (At 后缀)
        assertEquals("DateTime", ((Type.TypeName) data.fields().get(3).type()).name());
        // totalAmount -> Float (Amount 后缀)
        assertEquals("Float", ((Type.TypeName) data.fields().get(4).type()).name());
    }

    @Test
    void testGenerateReturnTypeInference() {
        String source = """
            Module test.

            Define Quote has premium.

            Rule generateQuote given amount:
              Return Quote with premium set to amount.
            """;

        aster.core.ast.Module module = parseAndBuild(source);
        assertNotNull(module);

        Decl.Func func = (Decl.Func) module.decls().get(1);
        assertEquals("generateQuote", func.name());

        // generateQuote 以 "generate" 开头，所以返回类型推断为 Quote
        Type retType = func.retType();
        assertTrue(retType instanceof Type.TypeName);
        assertEquals("Quote", ((Type.TypeName) retType).name());
    }

    @Test
    void testFullAutoInsuranceExample() {
        String source = """
            Module insurance.auto.

            Define Driver has id, age, yearsLicensed, accidents, violations.

            Define Vehicle has vin, year, value, safetyRating.

            Define Quote has approved, premium, deductible, reason.

            Rule generateQuote given driver, vehicle:
              If driver.age less than 18
                Return Quote with approved set to false, premium set to 0, deductible set to 0, reason set to "Driver under 18".
              If driver.accidents greater than 3
                Return Quote with approved set to false, premium set to 0, deductible set to 0, reason set to "Too many accidents".
              Let basePremium be calculateBase with driver, vehicle.
              Let riskFactor be calculateRisk with driver.
              Let finalPremium be basePremium times riskFactor divided by 100.
              Return Quote with approved set to true, premium set to finalPremium, deductible set to 500, reason set to "Approved".

            Rule calculateBase given driver, vehicle:
              If driver.age less than 25
                Return 300.
              If driver.age less than 65
                Return 200.
              Return 250.

            Rule calculateRisk given driver:
              Let base be 100.
              If driver.accidents greater than 0
                Let base be base plus driver.accidents times 20.
              If driver.violations greater than 0
                Let base be base plus driver.violations times 10.
              Return base.
            """;

        aster.core.ast.Module module = parseAndBuild(source);
        assertNotNull(module);
        assertEquals("insurance.auto", module.name());

        // 3 个 Define + 3 个 Rule = 6 个声明
        assertEquals(6, module.decls().size());

        // 验证数据类型定义
        Decl.Data driver = (Decl.Data) module.decls().get(0);
        assertEquals("Driver", driver.name());
        assertEquals(5, driver.fields().size());

        Decl.Data vehicle = (Decl.Data) module.decls().get(1);
        assertEquals("Vehicle", vehicle.name());
        assertEquals(4, vehicle.fields().size());

        Decl.Data quote = (Decl.Data) module.decls().get(2);
        assertEquals("Quote", quote.name());
        assertEquals(4, quote.fields().size());

        // 验证函数
        Decl.Func generateQuote = (Decl.Func) module.decls().get(3);
        assertEquals("generateQuote", generateQuote.name());
        assertEquals(2, generateQuote.params().size());

        Decl.Func calculateBase = (Decl.Func) module.decls().get(4);
        assertEquals("calculateBase", calculateBase.name());

        Decl.Func calculateRisk = (Decl.Func) module.decls().get(5);
        assertEquals("calculateRisk", calculateRisk.name());
    }
}
