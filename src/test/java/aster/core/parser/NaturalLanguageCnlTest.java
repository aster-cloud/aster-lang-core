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
            This module is test.

            To testLessThan with x, produce:
              If x less than 10:
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
            This module is test.

            To checkValue with count, produce:
              If count greater than 5:
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
            This module is test.

            To calculateTotal with price, quantity, produce:
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
            This module is test.

            To computeSum with a, b, produce:
              Return a plus b.

            To main produce:
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
            This module is test.

            Define User with userId, age, isActive, createdAt, totalAmount.
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
            This module is test.

            Define Quote with premium.

            To generateQuote with amount, produce:
              Return Quote with premium = amount.
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
            This module is insurance.auto.

            Define Driver with id, age, yearsLicensed, accidents, violations.

            Define Vehicle with vin, year, value, safetyRating.

            Define Quote with approved, premium, deductible, reason.

            To generateQuote with driver, vehicle, produce:
              If driver.age less than 18:
                Return Quote with approved = false, premium = 0, deductible = 0, reason = "Driver under 18".
              If driver.accidents greater than 3:
                Return Quote with approved = false, premium = 0, deductible = 0, reason = "Too many accidents".
              Let basePremium be calculateBase with driver, vehicle.
              Let riskFactor be calculateRisk with driver.
              Let finalPremium be basePremium times riskFactor divided by 100.
              Return Quote with approved = true, premium = finalPremium, deductible = 500, reason = "Approved".

            To calculateBase with driver, vehicle, produce:
              If driver.age less than 25:
                Return 300.
              If driver.age less than 65:
                Return 200.
              Return 250.

            To calculateRisk with driver, produce:
              Let base be 100.
              If driver.accidents greater than 0:
                Let base be base plus driver.accidents times 20.
              If driver.violations greater than 0:
                Let base be base plus driver.violations times 10.
              Return base.
            """;

        aster.core.ast.Module module = parseAndBuild(source);
        assertNotNull(module);
        assertEquals("insurance.auto", module.name());

        // 3 个 Define + 3 个 To = 6 个声明
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
