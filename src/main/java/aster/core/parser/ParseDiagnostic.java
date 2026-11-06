package aster.core.parser;

import aster.core.typecheck.ErrorCode;

import java.util.Map;

/**
 * 结构化解析诊断（ADR 0051 §16 R56）：共享码表 E050–E069 中的码 + 1-based 行列 + 命名参数。
 *
 * <p>参数取值约定与 aster-lang-ts 一致：关键词写 {@code kw.<规范关键词>}，抽象 token / 语法构造写
 * {@code token.<名称>}，字面片段带单引号，数值写数字串。{@code message} 只是英文渲染结果，
 * 消费方应按 {@code code} + {@code params} 取译文。
 *
 * @param code    错误码（category=syntax）
 * @param line    1-based 行号
 * @param column  1-based 列号
 * @param params  命名参数（不可变）
 * @param message 英文消息
 */
public record ParseDiagnostic(ErrorCode code, int line, int column, Map<String, String> params, String message) {

    public ParseDiagnostic {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
