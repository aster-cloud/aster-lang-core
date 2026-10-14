package aster.core.cedar;

import aster.core.typecheck.model.Diagnostic;

import java.util.List;

/**
 * 单条规则的 Cedar 导出结果。整体导出：{@code exportable=false} 时 {@code policies} / {@code schemaJson} 为 null、
 * {@code emitted} 与 {@code policyIds} 为空，只有诊断可用。
 */
public record CedarExport(boolean exportable, String ruleId, String functionName, String policies,
                          List<String> policyIds, List<EmittedPolicy> emitted, String schemaJson, String profile,
                          List<String> controls, RuleFacts facts, List<Diagnostic> diagnostics) {}
