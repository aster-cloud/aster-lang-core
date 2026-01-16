package aster.core.lowering;

import aster.core.ast.*;
import aster.core.ir.CoreModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CoreLowering 单元测试
 * <p>
 * 验证 AST 到 Core IR 的降级逻辑正确性
 */
class CoreLoweringTest {
    private final CoreLowering lowering = new CoreLowering();

    // 辅助方法：创建简单类型
    private Type.TypeName typeInt() {
        return new Type.TypeName("Int", null, null);
    }

    private Type.TypeName typeString() {
        return new Type.TypeName("String", null, null);
    }

    private Type.TypeName type(String name) {
        return new Type.TypeName(name, null, null);
    }

    /**
     * 测试简单模块降级
     */
    @Test
    void testLowerSimpleModule() {
        // 创建包含一个函数的模块
        var func = new Decl.Func(
            "main",
            null,  // nameSpan
            List.of(),  // typeParams
            List.of(),  // params
            typeInt(),
            null,  // retAnnotations
            new Block(List.of(), null),
            List.of(),  // effects
            List.of(),  // effectCaps
            false,  // effectCapsExplicit
            null  // span
        );

        var module = new aster.core.ast.Module("test", List.of(func), null);

        // 降级
        var lowered = lowering.lowerModule(module);

        // 验证
        assertNotNull(lowered);
        assertEquals("test", lowered.name);
        assertEquals(1, lowered.decls.size());
        assertTrue(lowered.decls.get(0) instanceof CoreModel.Func);
    }

    /**
     * 测试函数降级（带参数）
     */
    @Test
    void testLowerFunctionWithParameters() {
        var func = new Decl.Func(
            "add",
            null,
            List.of(),
            List.of(
                new Decl.Parameter("a", typeInt(), null, null),
                new Decl.Parameter("b", typeInt(), null, null)
            ),
            typeInt(),
            null,
            new Block(List.of(), null),
            List.of(),
            List.of(),
            false,
            null
        );

        var module = new aster.core.ast.Module(null, List.of(func), null);
        var lowered = lowering.lowerModule(module);
        var funcLowered = (CoreModel.Func) lowered.decls.get(0);

        assertNotNull(funcLowered);
        assertEquals("add", funcLowered.name);
        assertEquals(2, funcLowered.params.size());
        assertEquals("a", funcLowered.params.get(0).name);
        assertEquals("b", funcLowered.params.get(1).name);
        assertTrue(funcLowered.ret instanceof CoreModel.TypeName);
        assertEquals("Int", ((CoreModel.TypeName) funcLowered.ret).name);
    }

    /**
     * 测试数据类型降级
     */
    @Test
    void testLowerDataType() {
        var data = new Decl.Data(
            "User",
            List.of(
                new Decl.Field("name", typeString(), null),
                new Decl.Field("age", typeInt(), null)
            ),
            null
        );

        var module = new aster.core.ast.Module(null, List.of(data), null);
        var lowered = lowering.lowerModule(module);
        var dataLowered = (CoreModel.Data) lowered.decls.get(0);

        assertNotNull(dataLowered);
        assertEquals("User", dataLowered.name);
        assertEquals(2, dataLowered.fields.size());
        assertEquals("name", dataLowered.fields.get(0).name);
        assertEquals("age", dataLowered.fields.get(1).name);
    }

    /**
     * 测试枚举降级
     */
    @Test
    void testLowerEnum() {
        var enumDecl = new Decl.Enum(
            "Color",
            List.of("Red", "Green", "Blue"),
            null
        );

        var module = new aster.core.ast.Module(null, List.of(enumDecl), null);
        var lowered = lowering.lowerModule(module);
        var enumLowered = (CoreModel.Enum) lowered.decls.get(0);

        assertNotNull(enumLowered);
        assertEquals("Color", enumLowered.name);
        assertEquals(3, enumLowered.variants.size());
        assertTrue(enumLowered.variants.contains("Red"));
        assertTrue(enumLowered.variants.contains("Green"));
        assertTrue(enumLowered.variants.contains("Blue"));
    }

    /**
     * 测试 Import 降级
     */
    @Test
    void testLowerImport() {
        var importDecl = new Decl.Import(
            "std.io",
            "IO",
            null
        );

        var module = new aster.core.ast.Module(null, List.of(importDecl), null);
        var lowered = lowering.lowerModule(module);
        var importLowered = (CoreModel.Import) lowered.decls.get(0);

        assertNotNull(importLowered);
        assertEquals("std.io", importLowered.path);
        assertEquals("IO", importLowered.alias);
    }

    /**
     * 测试带效果的函数降级
     */
    @Test
    void testLowerFunctionWithEffects() {
        var func = new Decl.Func(
            "readFile",
            null,
            List.of(),
            List.of(new Decl.Parameter("path", typeString(), null, null)),
            typeString(),
            null,
            new Block(List.of(), null),
            List.of("io"),  // effects
            List.of(),
            false,
            null
        );

        var module = new aster.core.ast.Module(null, List.of(func), null);
        var lowered = lowering.lowerModule(module);
        var funcLowered = (CoreModel.Func) lowered.decls.get(0);

        assertNotNull(funcLowered);
        assertEquals("readFile", funcLowered.name);
        assertEquals(1, funcLowered.effects.size());
        assertEquals("io", funcLowered.effects.get(0));
    }

    /**
     * 测试空模块降级
     */
    @Test
    void testLowerEmptyModule() {
        var module = new aster.core.ast.Module(null, List.of(), null);

        var lowered = lowering.lowerModule(module);

        assertNotNull(lowered);
        assertNull(lowered.name);
        assertEquals(0, lowered.decls.size());
    }

    /**
     * 测试类型参数化函数降级
     */
    @Test
    void testLowerGenericFunction() {
        var func = new Decl.Func(
            "identity",
            null,
            List.of("T"),  // typeParams
            List.of(new Decl.Parameter("x", type("T"), null, null)),
            type("T"),
            null,
            new Block(List.of(), null),
            List.of(),
            List.of(),
            false,
            null
        );

        var module = new aster.core.ast.Module(null, List.of(func), null);
        var lowered = lowering.lowerModule(module);
        var funcLowered = (CoreModel.Func) lowered.decls.get(0);

        assertNotNull(funcLowered);
        assertEquals("identity", funcLowered.name);
        assertEquals(1, funcLowered.typeParams.size());
        assertEquals("T", funcLowered.typeParams.get(0));
    }

    /**
     * 测试复杂数据类型（带注解字段）降级
     */
    @Test
    void testLowerDataTypeWithAnnotations() {
        var data = new Decl.Data(
            "Person",
            List.of(
                new Decl.Field(
                    "id",
                    typeInt(),
                    List.of(new Annotation("primary", Map.of()))
                )
            ),
            null
        );

        var module = new aster.core.ast.Module(null, List.of(data), null);
        var lowered = lowering.lowerModule(module);
        var dataLowered = (CoreModel.Data) lowered.decls.get(0);

        assertNotNull(dataLowered);
        assertEquals("Person", dataLowered.name);
        assertEquals(1, dataLowered.fields.size());
        assertEquals(1, dataLowered.fields.get(0).annotations.size());
    }

    /**
     * 测试多声明模块降级
     */
    @Test
    void testLowerModuleWithMultipleDeclarations() {
        var func = new Decl.Func("main", null, List.of(), List.of(), typeInt(), null,
            new Block(List.of(), null), List.of(), List.of(), false, null);
        var data = new Decl.Data("User", List.of(new Decl.Field("name", typeString(), null)), null);
        var enumDecl = new Decl.Enum("Status", List.of("Active", "Inactive"), null);

        var module = new aster.core.ast.Module("app", List.of(func, data, enumDecl), null);
        var lowered = lowering.lowerModule(module);

        assertNotNull(lowered);
        assertEquals("app", lowered.name);
        assertEquals(3, lowered.decls.size());
        assertTrue(lowered.decls.get(0) instanceof CoreModel.Func);
        assertTrue(lowered.decls.get(1) instanceof CoreModel.Data);
        assertTrue(lowered.decls.get(2) instanceof CoreModel.Enum);
    }
}
