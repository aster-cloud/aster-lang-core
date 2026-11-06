package aster.core.parser;

import aster.core.typecheck.ErrorCode;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

import java.util.Map;

/**
 * 带错误码的可恢复解析错误（ADR 0051 §16 R56）。
 *
 * <p>继承 {@link IllegalStateException}：既有调用方（aster-api 等）按 IllegalStateException 捕获、
 * 按 {@link #getMessage()} 展示的行为不变，消息原文也不变；新增的码、参数与位置供
 * {@link ParseDiagnostics} 产出结构化诊断。位置未知时为 0，由外层 {@link #at(Token)} 补齐。
 */
public final class ParseDiagnosticException extends IllegalStateException {

    private final ErrorCode code;
    private final Map<String, String> params;
    private final int line;
    private final int column;

    public ParseDiagnosticException(ErrorCode code, Map<String, String> params, String message) {
        this(code, params, message, 0, 0, null);
    }

    public ParseDiagnosticException(ErrorCode code, Map<String, String> params, String message, Token at) {
        this(code, params, message, lineOf(at), columnOf(at), null);
    }

    public ParseDiagnosticException(ErrorCode code, Map<String, String> params, String message,
                                    ParserRuleContext at) {
        this(code, params, message, at == null ? null : at.getStart());
    }

    public ParseDiagnosticException(ErrorCode code, Map<String, String> params, String message,
                                    int line, int column) {
        this(code, params, message, line, column, null);
    }

    public ParseDiagnosticException(ErrorCode code, Map<String, String> params, String message,
                                    Token at, Throwable cause) {
        this(code, params, message, lineOf(at), columnOf(at), cause);
    }

    private ParseDiagnosticException(ErrorCode code, Map<String, String> params, String message,
                                     int line, int column, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.params = params == null ? Map.of() : Map.copyOf(params);
        this.line = line;
        this.column = column;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, String> params() {
        return params;
    }

    /** 1-based 行号；0 表示未知。 */
    public int line() {
        return line;
    }

    /** 1-based 列号；0 表示未知。 */
    public int column() {
        return column;
    }

    /** 位置未知时补上 {@code at} 的位置（已知位置保持不变）。 */
    public ParseDiagnosticException at(Token at) {
        if (line > 0 || at == null) {
            return this;
        }
        ParseDiagnosticException out = new ParseDiagnosticException(
            code, params, getMessage(), lineOf(at), columnOf(at), getCause());
        out.setStackTrace(getStackTrace());
        return out;
    }

    public ParseDiagnostic toDiagnostic() {
        return new ParseDiagnostic(code, line, column, params, getMessage());
    }

    private static int lineOf(Token t) {
        return t == null ? 0 : t.getLine();
    }

    private static int columnOf(Token t) {
        return t == null ? 0 : t.getCharPositionInLine() + 1;
    }
}
