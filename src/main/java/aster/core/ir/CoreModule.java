package aster.core.ir;

/**
 * Core IR 模块标记接口
 * <p>
 * 本模块提供 Aster Lang 编译器的 Core IR（中间表示）数据结构，
 * 由 TypeScript 编译管线生成后传递给 Java 后端（字节码生成、验证等）。
 * <p>
 * 设计目标：
 * - 跨语言互操作：与 TypeScript 通过 JSON 序列化通信
 * - 不可变数据：使用 Java 25 Records 确保线程安全
 * - 类型安全：强类型约束避免运行时错误
 * - 可测试性：支持黄金测试与属性测试
 *
 * @since 0.3.0
 */
public interface CoreModule {
    // 占位接口，后续任务将迁移 Core IR POJO 到此模块
}
