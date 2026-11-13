package aster.core.ir;

import java.util.List;

/**
 * Core IR 读入失败（ADR 0055 §5）：版本错误、JSON 语法错误、schema 拒收、严格绑定失败或不变式不成立。
 *
 * <p>{@link #code()} 供 HTTP 读方映射错误码：{@code unsupported_schema_version} 或 {@code invalid_request}。
 */
public final class CoreIrFormatException extends Exception {

    private final String code;
    private final List<CoreIrProblem> problems;

    public CoreIrFormatException(String code, List<CoreIrProblem> problems, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.problems = List.copyOf(problems);
    }

    /** {@link CoreIrJson#UNSUPPORTED_SCHEMA_VERSION} 或 {@link CoreIrJson#INVALID_REQUEST}。 */
    public String code() {
        return code;
    }

    /** 具体问题（JSON 语法错误或绑定失败时为空）。 */
    public List<CoreIrProblem> problems() {
        return problems;
    }
}
