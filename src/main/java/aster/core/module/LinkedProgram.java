package aster.core.module;

import aster.core.ir.CoreModel;
import java.util.Map;

/**
 * Linker 输出：已合并的单 Module 与 mangled 名称追踪表。
 *
 * @param merged     可直接交给 Truffle loader 的 Core module
 * @param traceNames mangled 顶层名 -> 原始 module.symbol
 */
public record LinkedProgram(CoreModel.Module merged, Map<String, String> traceNames) {
}
