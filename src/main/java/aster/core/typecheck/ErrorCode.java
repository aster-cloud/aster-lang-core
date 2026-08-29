// 本文件由 scripts/generate_error_codes.ts 自动生成，请勿手动修改。
// 源数据: shared/error_codes.json

package aster.core.typecheck;


/**
 * 错误码与消息模板的枚举定义，由共享 JSON 自动生成，确保 Java 与 TypeScript 行为一致。
 */
public enum ErrorCode {
  TYPE_MISMATCH("E001", Category.TYPE, Severity.ERROR, "Type mismatch: expected {expected}, got {actual}", "Check that the type annotation matches the inferred expression type."),
  TYPE_MISMATCH_ASSIGN("E002", Category.TYPE, Severity.ERROR, "Type mismatch assigning to '{name}': {expected} vs {actual}", "Ensure the variable's previous binding type matches the current assignment."),
  RETURN_TYPE_MISMATCH("E003", Category.TYPE, Severity.ERROR, "Return type mismatch: expected {expected}, got {actual}", "Check that the return statement matches the declared return type."),
  TYPE_VAR_UNDECLARED("E004", Category.TYPE, Severity.ERROR, "Type variable '{name}' is used in '{func}' but not declared in its type parameters.", "Declare used type variables in the function signature's 'of' clause."),
  TYPE_PARAM_UNUSED("E005", Category.TYPE, Severity.WARNING, "Type parameter '{name}' on '{func}' is declared but not used.", "Remove unused type parameters to avoid confusion."),
  TYPEVAR_LIKE_UNDECLARED("E006", Category.TYPE, Severity.ERROR, "Type variable-like '{name}' is used in '{func}' but not declared; declare it with 'of {name}'.", "For names that look like type variables, declare them in the 'of' clause."),
  TYPEVAR_INCONSISTENT("E007", Category.TYPE, Severity.ERROR, "Type variable '{name}' inferred inconsistently: {previous} vs {actual}", "Ensure all usage sites of a type variable produce the same concrete type."),
  IF_BRANCH_MISMATCH("E008", Category.TYPE, Severity.ERROR, "If branch type mismatch: then {thenType} vs else {elseType}", "Ensure both branches of an if expression return the same type."),
  MATCH_BRANCH_MISMATCH("E009", Category.TYPE, Severity.ERROR, "Match case return types differ: {expected} vs {actual}", "Check that all match branches return the same type."),
  INTEGER_PATTERN_TYPE("E010", Category.TYPE, Severity.ERROR, "Integer pattern used on non-Int scrutinee ({scrutineeType})", "Only use integer patterns on Int-typed match expressions."),
  UNKNOWN_FIELD("E011", Category.TYPE, Severity.ERROR, "Unknown field '{field}' for {type}", "Check that the field name is correct for the data type."),
  FIELD_TYPE_MISMATCH("E012", Category.TYPE, Severity.ERROR, "Field '{field}' expects {expected}, got {actual}", "Verify the field initializer expression matches the declared type."),
  MISSING_REQUIRED_FIELD("E013", Category.TYPE, Severity.ERROR, "Construction of {type} missing required field '{field}'", "Provide all required fields declared in the data type."),
  NOT_CALL_ARITY("E014", Category.TYPE, Severity.ERROR, "not(...) expects 1 argument", "Adjust the not() call to have exactly 1 argument."),
  AWAIT_TYPE("E015", Category.TYPE, Severity.WARNING, "await expects Maybe<T> or Result<T,E>, got {type}", "Only use await on Maybe or Result types."),
  DUPLICATE_ENUM_CASE("E016", Category.TYPE, Severity.WARNING, "Duplicate enum case '{case}' in match on {type}.", "Remove duplicate enum branches to keep the match concise."),
  NON_EXHAUSTIVE_MAYBE("E017", Category.TYPE, Severity.WARNING, "Non-exhaustive match on Maybe type; missing {missing} case.", "Add both null and non-null branches for Maybe matches."),
  NON_EXHAUSTIVE_ENUM("E018", Category.TYPE, Severity.WARNING, "Non-exhaustive match on {type}; missing: {missing}", "Add all uncovered enum branches, or add a wildcard."),
  AMBIGUOUS_INTEROP_NUMERIC("E019", Category.TYPE, Severity.WARNING, "Ambiguous interop call '{target}': mixing numeric kinds (Int={hasInt}, Long={hasLong}, Double={hasDouble}). Overload resolution may widen/box implicitly.", "Unify numeric argument types in interop calls to avoid implicit boxing and widening."),
  LIST_ELEMENT_TYPE_MISMATCH("E020", Category.TYPE, Severity.ERROR, "List literal element type mismatch: expected {expected}, got {actual}", "Ensure all elements in a list literal have the same type."),
  OPTIONAL_EXPECTED("E021", Category.TYPE, Severity.ERROR, "Optional value required here: expected Maybe or Option, but got {actual}", "Pass a Maybe/Option type or explicitly wrap the value."),
  WORKFLOW_COMPENSATE_TYPE("E022", Category.TYPE, Severity.ERROR, "Compensate block for step '{step}' must return Result<Unit, {expectedErr}>, got {actual}", "Ensure the compensate block returns Result<Unit, E> where E is the step error type."),
  WORKFLOW_COMPENSATE_MISSING("E023", Category.EFFECT, Severity.WARNING, "Step '{step}' performs side effects but does not define a compensate block.", "Provide a compensate block for steps with IO side effects to enable rollback."),
  WORKFLOW_RETRY_INVALID("E024", Category.TYPE, Severity.ERROR, "Workflow retry max attempts must be greater than zero (actual: {maxAttempts}).", "Set retry.maxAttempts to a positive integer."),
  WORKFLOW_TIMEOUT_INVALID("E025", Category.TYPE, Severity.ERROR, "Workflow timeout must be greater than zero milliseconds (actual: {milliseconds}).", "Set the timeout to a positive value to ensure compensate logic can be triggered."),
  WORKFLOW_MISSING_IO_EFFECT("E026", Category.EFFECT, Severity.ERROR, "Workflow '{func}' must declare @io effect before using a 'workflow' block.", "Add @io effect declaration to the function header."),
  WORKFLOW_UNDECLARED_CAPABILITY("E027", Category.CAPABILITY, Severity.ERROR, "Workflow '{func}' step '{step}' uses capability {capability} that is not declared on the function header.", "Declare {capability} in the function header or adjust the step code."),
  COMPENSATE_NEW_CAPABILITY("E028", Category.CAPABILITY, Severity.ERROR, "Compensate block for step '{step}' in function '{func}' introduces new capability {capability} that does not appear in the main step body.", "Compensate blocks can only reuse capabilities from the main step body."),
  WORKFLOW_UNKNOWN_STEP_DEPENDENCY("E029", Category.SCOPE, Severity.ERROR, "Workflow step '{step}' depends on undefined step '{dependency}'.", "Only reference declared step names in the current workflow."),
  WORKFLOW_CIRCULAR_DEPENDENCY("E030", Category.TYPE, Severity.ERROR, "Workflow contains circular step dependency: {cycle}", "Remove or restructure circular dependencies to enable topological execution."),
  // ADR 0025：Decimal↔Double 混算编译期拦截（与 TS E031 对齐）。Double 是二进制浮点，
  // 与精确 Decimal 混算会注入舍入误差，破坏可证明性；Int/Long→Decimal 精确提升放行。
  DECIMAL_DOUBLE_MIXING("E031", Category.TYPE, Severity.ERROR, "Cannot combine Decimal and Double in '{operator}'. Double is binary floating-point and would inject rounding error into an exact Decimal. Use Decimal literals (e.g. 1.08m) on both sides, or Int/Long (exact promotion).", "Make both operands Decimal (suffix m), or use Int/Long which promote exactly to Decimal."),
  CALL_ARITY_MISMATCH("E032", Category.TYPE, Severity.ERROR, "Function '{func}' expects {expected} argument(s), got {actual}.", "Adjust the call to pass exactly the declared number of arguments."),
  // P0-R3 (codex review High #3): 与 TS 端 ERROR_METADATA category 对齐——
  // 5 个核心 PII codes 从 Category.TYPE 改为 Category.PII。之前的 'TYPE'
  // 分类是历史包袱，让任何按 category 派生 PII 集合的代码（如跨语言
  // conformance）必然不一致。
  PII_ASSIGN_DOWNGRADE("E070", Category.PII, Severity.ERROR, "Cannot assign PII data to lower-level target: {source} -> {target}", "Use a sanitization function or declare a matching @pii level on the target."),
  PII_SINK_UNSANITIZED("E072", Category.PII, Severity.ERROR, "PII level {level} data output to {sinkKind} without sanitization", "Call redact() or tokenize() before output to reduce sensitivity."),
  PII_ARG_VIOLATION("E073", Category.PII, Severity.ERROR, "PII argument type mismatch: expected {expected}, got {actual}", "Check the function signature to ensure PII levels and categories match."),
  DUPLICATE_IMPORT_ALIAS("E100", Category.SCOPE, Severity.WARNING, "Duplicate import alias '{alias}'.", "Use unique aliases for different imports to avoid shadowing."),
  UNDEFINED_VARIABLE("E101", Category.SCOPE, Severity.ERROR, "Undefined variable: {name}", "Declare and initialize the variable before use."),
  MULTIPLE_ENTRY_RULES("E102", Category.SCOPE, Severity.ERROR, "Multiple @entry rules in module: {rules}", "Keep at most one Rule annotated with @entry in a module."),
  IMPORT_SYMBOL_CONFLICT("E103", Category.SCOPE, Severity.WARNING, "Import symbol conflict: {symbol}", "Adjust the import alias or the local top-level declaration name to avoid the import symbol conflict."),
  // 与 TS 端 ErrorCode.DUPLICATE_SYMBOL 对齐（原 TS 端占 E102，反向重建 error_codes.json
  // 时按用户裁决迁至 E104，让 E102 归 MULTIPLE_ENTRY_RULES）。
  DUPLICATE_SYMBOL("E104", Category.SCOPE, Severity.ERROR, "Symbol '{name}' is already defined in this scope.", "Choose a different name or check for unintended duplicate declarations."),
  EFF_MISSING_IO("E200", Category.EFFECT, Severity.ERROR, "Function '{func}' may perform I/O but is missing @io effect.", "Declare @io effect for functions that perform I/O."),
  EFF_MISSING_CPU("E201", Category.EFFECT, Severity.ERROR, "Function '{func}' may perform CPU-bound work but is missing @cpu (or @io) effect.", "Declare @cpu or @io effect for CPU-intensive functions."),
  EFF_SUPERFLUOUS_IO_CPU_ONLY("E202", Category.EFFECT, Severity.INFO, "Function '{func}' declares @io but only CPU-like work found; @io subsumes @cpu and may be unnecessary.", "If the function only does CPU work, consider removing the redundant @io declaration."),
  EFF_SUPERFLUOUS_IO("E203", Category.EFFECT, Severity.WARNING, "Function '{func}' declares @io but no obvious I/O found.", "Confirm @io is needed; remove if no I/O behavior exists."),
  EFF_SUPERFLUOUS_CPU("E204", Category.EFFECT, Severity.WARNING, "Function '{func}' declares @cpu but no obvious CPU-bound work found.", "Remove the redundant @cpu declaration or add corresponding CPU work."),
  EFF_INFER_MISSING_IO("E205", Category.EFFECT, Severity.ERROR, "Function '{func}' missing @io effect declaration, inference requires IO.", "Add @io effect based on inference results."),
  EFF_INFER_MISSING_CPU("E206", Category.EFFECT, Severity.ERROR, "Function '{func}' missing @cpu effect declaration, inference requires CPU (or @io).", "Add @cpu or @io effect based on inference results."),
  EFF_INFER_REDUNDANT_IO("E207", Category.EFFECT, Severity.WARNING, "Function '{func}' declares @io but no IO side effects inferred.", "Confirm whether to keep the @io declaration."),
  EFF_INFER_REDUNDANT_CPU("E208", Category.EFFECT, Severity.WARNING, "Function '{func}' declares @cpu but no CPU side effects inferred.", "Remove the @cpu declaration if no CPU side effects exist."),
  EFF_INFER_REDUNDANT_CPU_WITH_IO("E209", Category.EFFECT, Severity.WARNING, "Function '{func}' declares both @cpu and @io; @cpu is redundant since @io is required.", "Keep @io only; remove the redundant @cpu."),
  // 与 TS 端 ErrorCode.EFFECT_VAR_UNDECLARED / EFFECT_VAR_UNRESOLVED 对齐（TS 端已有，
  // 补齐 Java 侧码表使双引擎 error_codes.json 契约一致；Java 引擎实现效果变量检查时可 emit）。
  EFFECT_VAR_UNDECLARED("E210", Category.TYPE, Severity.ERROR, "Effect variable {var} undeclared", "Add the effect type parameter to the function signature's effect parameter list."),
  EFFECT_VAR_UNRESOLVED("E211", Category.TYPE, Severity.ERROR, "Effect variable {vars} could not be resolved to concrete effects", "Provide explicit effects (pure/cpu/io/workflow) or remove unused effect variables."),
  // E212（issue aster-lang-ts#90）：调用了效果未知的 builtin。既非本地函数、无导入效果
  // 签名、不匹配任何已知前缀、也不属于已知纯 stdlib 命名空间——此前这类调用被**静默推断
  // 为 pure**，一个真做网络请求但名为 Webhook.post 的 builtin 因此不会触发缺 @io 诊断。
  // 前缀匹配本质不可能完备，故不改判为"不纯"（会大量误报），而是把未知显式暴露成 warning。
  // 目前只有 TS 侧发出该码；此处登记以保持 shared/error_codes.json 单源码表两端一致
  // （generate_error_codes.ts 会校验 drift）。
  EFF_INFER_UNKNOWN_BUILTIN("E212", Category.EFFECT, Severity.WARNING, "Effect of builtin '{builtin}' called by '{func}' is unknown; it is NOT assumed pure", "Declare it via ASTER_EFFECT_CONFIG prefixes, supply an imported effect signature, or use a known stdlib namespace."),
  CAPABILITY_NOT_ALLOWED("E300", Category.CAPABILITY, Severity.ERROR, "Function '{func}' requires {cap} capability but manifest for module '{module}' denies it.", "Update the capability manifest or modify the function to comply."),
  EFF_CAP_MISSING("E301", Category.CAPABILITY, Severity.ERROR, "Function '{func}' uses {cap} capability but header declares [{declared}].", "Declare the actually used capabilities in the function header."),
  EFF_CAP_SUPERFLUOUS("E302", Category.CAPABILITY, Severity.INFO, "Function '{func}' declares {cap} capability but it is not used.", "Remove unused capability declarations for clarity."),
  CAPABILITY_INFER_MISSING_IO("E303", Category.CAPABILITY, Severity.ERROR, "Function '{func}' uses IO capabilities [{capabilities}] but is missing @io effect (e.g., {calls}).", "Declare @io effect in the function header, or remove related calls to stay pure."),
  CAPABILITY_INFER_MISSING_CPU("E304", Category.CAPABILITY, Severity.ERROR, "Function '{func}' performs CPU capability calls (e.g., {calls}) but declares neither @cpu nor @io effect.", "Add @cpu or @io effect to cover CPU capabilities."),
  PII_HTTP_UNENCRYPTED("E400", Category.PII, Severity.ERROR, "PII data transmitted over HTTP without encryption", "Use an encrypted channel (HTTPS) or sanitize before transmitting PII data."),
  PII_ANNOTATION_MISSING("E401", Category.PII, Severity.ERROR, "PII annotation missing for value flowing into '{sink}'", "Add @pii annotation to sensitive data for tracking."),
  PII_SENSITIVITY_MISMATCH("E402", Category.PII, Severity.WARNING, "PII sensitivity mismatch: required {required}, got {actual}", "Adjust the data sensitivity level or update the process requirements."),
  // P0-R2: 与 TS 端 aster-lang-ts ErrorCode.PII_MISSING_CONSENT_CHECK 镜像
  PII_MISSING_CONSENT_CHECK("E403", Category.PII, Severity.WARNING, "Function '{func}' processes PII data without consent check (GDPR Art. 6)", "Call checkConsent() or add @consent_required annotation before processing PII data."),
  // P0-R2 (codex review High #7): PII analyzer 内部失败的专用 code，与 TS
  // 端 aster-lang-ts ErrorCode.PII_ANALYZER_FAILED 镜像。
  // P0-R3 (codex review Medium #5): 文案与 TS 端对齐——业务用户友好语气，
  // 明确指引"this policy should not be deployed"。
  PII_ANALYZER_FAILED("E404", Category.PII, Severity.ERROR, "PII safety analysis failed for this module — the editor cannot verify whether sensitive data is correctly handled. This policy should not be deployed until the analysis succeeds. Internal reason: {reason}", "Try saving and reloading the file. If the error persists, contact your administrator or report this issue with the source code attached."),
  ASYNC_START_NOT_WAITED("E500", Category.ASYNC, Severity.ERROR, "Started async task '{task}' not waited", "Call wait on started async tasks to ensure completion."),
  ASYNC_WAIT_NOT_STARTED("E501", Category.ASYNC, Severity.ERROR, "Waiting for async task '{task}' that was never started", "Ensure the task name in wait matches a started task."),
  ASYNC_DUPLICATE_START("E502", Category.ASYNC, Severity.ERROR, "Async task '{task}' started multiple times ({count} occurrences)", "Avoid starting the same task multiple times; reuse or rename."),
  ASYNC_DUPLICATE_WAIT("E503", Category.ASYNC, Severity.WARNING, "Async task '{task}' waited multiple times ({count} occurrences)", "Ensure each task is waited on only once, or use a separate synchronization mechanism."),
  ASYNC_WAIT_BEFORE_START("E504", Category.ASYNC, Severity.ERROR, "Wait for async task '{task}' occurs before any matching start", "Execute start before wait, and ensure both are on compatible control paths."),
  // P0-R3 (codex review High #3): 与 TS 端 ERROR_METADATA 对齐
  PII_IMPLICIT_UPLEVEL("W071", Category.PII, Severity.WARNING, "Implicit PII level escalation detected: {source} -> {target}", "Add explicit type annotations for level changes to aid auditing."),
  PII_SINK_UNKNOWN("W074", Category.PII, Severity.WARNING, "PII data may flow to {sinkKind} without annotation", "Add @pii annotation to track sensitive data flow."),
  WORKFLOW_RETRY_INCONSISTENT("W105", Category.TYPE, Severity.WARNING, "Workflow retry configuration may be unreasonable: {reason}", "Check the combination of total wait time, maxAttempts, and backoff strategy."),
  WORKFLOW_TIMEOUT_UNREASONABLE("W106", Category.TYPE, Severity.WARNING, "Workflow timeout configuration may be unreasonable: {reason}", "Check whether the timeout value is too large or too small."),
  ;

  private final String code;
  private final Category category;
  private final Severity severity;
  private final String messageTemplate;
  private final String help;

  ErrorCode(String code, Category category, Severity severity, String messageTemplate, String help) {
    this.code = code;
    this.category = category;
    this.severity = severity;
    this.messageTemplate = messageTemplate;
    this.help = help;
  }

  public String code() {
    return code;
  }

  public Category category() {
    return category;
  }

  public Severity severity() {
    return severity;
  }

  public String messageTemplate() {
    return messageTemplate;
  }

  public String help() {
    return help;
  }

  /** 命名占位符 `{name}` —— 与 shared/error_codes.json 逐字一致。 */
  private static final java.util.regex.Pattern PLACEHOLDER =
      java.util.regex.Pattern.compile("\\{(\\w+)}");

  /**
   * 用**命名参数**渲染消息模板。
   *
   * <p>★这是全仓唯一的消息渲染实现（aster-lang-core#137）。此前存在两条互相矛盾的
   * 路径：DiagnosticBuilder 走命名参数 `{name}`，而本类的 format(Object...) 走
   * String.format 的 `%s`；生成器又把 json 的 `{name}` 一律改写成 `%s`，
   * 于是走 DiagnosticBuilder 的 23 个码全部渲染出**字面的 `%s`**
   * （实测 E101「Undefined variable: %s」连是哪个变量都不告诉用户）。
   *
   * <p>现在模板与 json 逐字一致，两个消费者共用本方法。
   * 缺失的 key 保留原占位符（便于发现漏传，而不是渲染成 null）。
   */
  public String render(java.util.Map<String, Object> params) {
    java.util.Map<String, Object> p = params == null ? java.util.Map.of() : params;
    var matcher = PLACEHOLDER.matcher(messageTemplate);
    var out = new StringBuilder();
    while (matcher.find()) {
      Object value = p.get(matcher.group(1));
      String replacement;
      if (value == null) {
        replacement = "{" + matcher.group(1) + "}";
      } else if (value instanceof java.util.List<?> list) {
        replacement = String.join(", ", list.stream().map(String::valueOf).toList());
      } else if (value instanceof Object[] array) {
        replacement = String.join(", ", java.util.Arrays.stream(array).map(String::valueOf).toList());
      } else {
        replacement = String.valueOf(value);
      }
      matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  public enum Category {
    TYPE,
    SCOPE,
    EFFECT,
    CAPABILITY,
    PII,
    ASYNC,
    OTHER
  }

  public enum Severity {
    ERROR,
    WARNING,
    INFO
  }
}
