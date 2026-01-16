package aster.core.typecheck.benchmark;

import aster.core.ir.CoreModel;
import aster.core.typecheck.BuiltinTypes;
import aster.core.typecheck.TypeChecker;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * TypeChecker 性能基准测试
 *
 * 测试场景：
 * - 小型模块：简单类型检查（< 10 个声明）
 * - 中型模块：复杂类型推断（10-50 个声明）
 * - 大型模块：大规模类型检查（> 50 个声明）
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TypeCheckerBenchmark {

    private CoreModel.Module smallModule;
    private CoreModel.Module mediumModule;
    private CoreModel.Module largeModule;

    @Setup
    public void setup() {
        // 小型模块：5 个简单函数
        smallModule = createSmallModule();

        // 中型模块：20 个函数，包含泛型和递归
        mediumModule = createMediumModule();

        // 大型模块：100 个函数，复杂类型推断
        largeModule = createLargeModule();
    }

    @Benchmark
    public void typecheckSmallModule() {
        TypeChecker checker = new TypeChecker();
        checker.typecheckModule(smallModule);
    }

    @Benchmark
    public void typecheckMediumModule() {
        TypeChecker checker = new TypeChecker();
        checker.typecheckModule(mediumModule);
    }

    @Benchmark
    public void typecheckLargeModule() {
        TypeChecker checker = new TypeChecker();
        checker.typecheckModule(largeModule);
    }

    // ==================== 测试数据生成 ====================

    /**
     * 小型模块：5 个简单函数
     *
     * func add(a: Int, b: Int) -> Int
     * func multiply(x: Int, y: Int) -> Int
     * func greet(name: String) -> String
     * func isPositive(n: Int) -> Bool
     * func main() -> Int
     */
    private CoreModel.Module createSmallModule() {
        CoreModel.Module module = new CoreModel.Module();
        module.name = "small";

        List<CoreModel.Decl> decls = new ArrayList<>();

        // func add(a: Int, b: Int) -> Int
        CoreModel.Func add = new CoreModel.Func();
        add.name = "add";
        add.params = List.of(
            createParam("a", BuiltinTypes.INT),
            createParam("b", BuiltinTypes.INT)
        );
        add.ret = createTypeName(BuiltinTypes.INT);
        add.body = createBlock(createReturn(createName("a")));
        add.effects = List.of();
        decls.add(add);

        // func multiply(x: Int, y: Int) -> Int
        CoreModel.Func multiply = new CoreModel.Func();
        multiply.name = "multiply";
        multiply.params = List.of(
            createParam("x", BuiltinTypes.INT),
            createParam("y", BuiltinTypes.INT)
        );
        multiply.ret = createTypeName(BuiltinTypes.INT);
        multiply.body = createBlock(createReturn(createName("x")));
        multiply.effects = List.of();
        decls.add(multiply);

        // func greet(name: String) -> String
        CoreModel.Func greet = new CoreModel.Func();
        greet.name = "greet";
        greet.params = List.of(createParam("name", BuiltinTypes.STRING));
        greet.ret = createTypeName(BuiltinTypes.STRING);
        greet.body = createBlock(createReturn(createName("name")));
        greet.effects = List.of();
        decls.add(greet);

        // func isPositive(n: Int) -> Bool
        CoreModel.Func isPositive = new CoreModel.Func();
        isPositive.name = "isPositive";
        isPositive.params = List.of(createParam("n", BuiltinTypes.INT));
        isPositive.ret = createTypeName(BuiltinTypes.BOOL);
        isPositive.body = createBlock(createReturn(createBool(true)));
        isPositive.effects = List.of();
        decls.add(isPositive);

        // func main() -> Int
        CoreModel.Func main = new CoreModel.Func();
        main.name = "main";
        main.params = List.of();
        main.ret = createTypeName(BuiltinTypes.INT);
        main.body = createBlock(createReturn(createInt(0)));
        main.effects = List.of();
        decls.add(main);

        module.decls = decls;
        return module;
    }

    /**
     * 中型模块：20 个函数，包含递归和条件分支
     */
    private CoreModel.Module createMediumModule() {
        CoreModel.Module module = new CoreModel.Module();
        module.name = "medium";

        List<CoreModel.Decl> decls = new ArrayList<>();

        // 添加 20 个函数，交替使用不同的复杂度
        for (int i = 0; i < 20; i++) {
            CoreModel.Func func = new CoreModel.Func();
            func.name = "func" + i;
            func.params = List.of(createParam("x", BuiltinTypes.INT));
            func.ret = createTypeName(BuiltinTypes.INT);
            func.effects = List.of();

            if (i % 3 == 0) {
                // 简单返回
                func.body = createBlock(createReturn(createName("x")));
            } else if (i % 3 == 1) {
                // 条件分支
                CoreModel.If ifExpr = new CoreModel.If();
                ifExpr.cond = createBool(true);
                ifExpr.then_ = createInt(1);
                ifExpr.else_ = createInt(0);
                func.body = createBlock(createReturn(ifExpr));
            } else {
                // 递归调用（调用前一个函数）
                int targetFunc = i > 0 ? i - 1 : 0;
                CoreModel.Call call = new CoreModel.Call();
                call.target = createName("func" + targetFunc);
                call.args = List.of(createName("x"));
                func.body = createBlock(createReturn(call));
            }

            decls.add(func);
        }

        module.decls = decls;
        return module;
    }

    /**
     * 大型模块：100 个函数，模拟大规模代码库
     */
    private CoreModel.Module createLargeModule() {
        CoreModel.Module module = new CoreModel.Module();
        module.name = "large";

        List<CoreModel.Decl> decls = new ArrayList<>();

        // 添加数据类型
        CoreModel.Data userData = new CoreModel.Data();
        userData.name = "User";
        CoreModel.Field nameField = new CoreModel.Field();
        nameField.name = "name";
        nameField.type = createTypeName(BuiltinTypes.STRING);
        CoreModel.Field ageField = new CoreModel.Field();
        ageField.name = "age";
        ageField.type = createTypeName(BuiltinTypes.INT);
        userData.fields = List.of(nameField, ageField);
        decls.add(userData);

        // 添加枚举类型
        CoreModel.Enum statusEnum = new CoreModel.Enum();
        statusEnum.name = "Status";
        CoreModel.EnumCase activeCase = new CoreModel.EnumCase();
        activeCase.name = "Active";
        CoreModel.EnumCase inactiveCase = new CoreModel.EnumCase();
        inactiveCase.name = "Inactive";
        statusEnum.cases = List.of(activeCase, inactiveCase);
        decls.add(statusEnum);

        // 添加 100 个函数
        for (int i = 0; i < 100; i++) {
            CoreModel.Func func = new CoreModel.Func();
            func.name = "func" + i;
            func.effects = List.of();

            // 交替使用不同的参数和返回类型
            if (i % 4 == 0) {
                func.params = List.of(createParam("x", BuiltinTypes.INT));
                func.ret = createTypeName(BuiltinTypes.INT);
                func.body = createBlock(createReturn(createInt(i)));
            } else if (i % 4 == 1) {
                func.params = List.of(createParam("s", BuiltinTypes.STRING));
                func.ret = createTypeName(BuiltinTypes.STRING);
                func.body = createBlock(createReturn(createName("s")));
            } else if (i % 4 == 2) {
                func.params = List.of(createParam("b", BuiltinTypes.BOOL));
                func.ret = createTypeName(BuiltinTypes.BOOL);
                func.body = createBlock(createReturn(createBool(true)));
            } else {
                // 使用自定义类型
                func.params = List.of(createParam("user", "User"));
                func.ret = createTypeName("User");
                func.body = createBlock(createReturn(createName("user")));
            }

            decls.add(func);
        }

        module.decls = decls;
        return module;
    }

    // ==================== 辅助方法 ====================

    private CoreModel.Param createParam(String name, String typeName) {
        CoreModel.Param param = new CoreModel.Param();
        param.name = name;
        param.type = createTypeName(typeName);
        return param;
    }

    private CoreModel.TypeName createTypeName(String name) {
        CoreModel.TypeName type = new CoreModel.TypeName();
        type.name = name;
        return type;
    }

    private CoreModel.Name createName(String name) {
        CoreModel.Name n = new CoreModel.Name();
        n.name = name;
        return n;
    }

    private CoreModel.IntE createInt(int value) {
        CoreModel.IntE i = new CoreModel.IntE();
        i.value = value;
        return i;
    }

    private CoreModel.Bool createBool(boolean value) {
        CoreModel.Bool b = new CoreModel.Bool();
        b.value = value;
        return b;
    }

    private CoreModel.Block createBlock(CoreModel.Stmt... stmts) {
        CoreModel.Block block = new CoreModel.Block();
        block.statements = List.of(stmts);
        return block;
    }

    private CoreModel.Return createReturn(CoreModel.Expr expr) {
        CoreModel.Return ret = new CoreModel.Return();
        ret.expr = expr;
        return ret;
    }
}
