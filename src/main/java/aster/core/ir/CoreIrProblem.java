package aster.core.ir;

/**
 * Core IR 校验发现的一个问题（ADR 0055 §3 / §5）。
 *
 * <p>code 取值：{@code unsupported_schema_version}（{@code /schemaVersion} 缺失、非整数或不为 2）、
 * {@code schema}（JSON Schema 拒收）以及 locales {@code core-ir/README.md} 列出的不变式 code
 * （{@code dup-annotation-arg}、{@code undeclared-effect-var}、{@code duplicate-effect}、
 * {@code effect-param-shadows-builtin}、{@code input-refs-state}）。
 *
 * @param path JSON Pointer（根为空串）
 * @param code 问题类别
 */
public record CoreIrProblem(String path, String code) {}
