package aster.core.lexicon;

import java.util.List;

/**
 * 复合关键词模式 - 定义上下文敏感的关键词组合。
 * <p>
 * 用于文档化语法组（如中文 "若...为" 模式匹配），为工具（LSP、格式化器）
 * 提供元数据，并在错误上下文使用关键词时提供更清晰的诊断信息。
 *
 * @param name                模式名称（用于调试、文档和错误消息）
 * @param opener              开启上下文的关键词（如 MATCH = "若"）
 * @param contextualKeywords  只在该上下文内有效的关键词（如 WHEN = "为"）
 * @param closer              上下文结束方式（默认为 DEDENT）
 */
public record CompoundPattern(
    String name,
    SemanticTokenKind opener,
    List<SemanticTokenKind> contextualKeywords,
    CloserType closer
) {
    /**
     * 上下文结束方式
     */
    public enum CloserType {
        /** 缩进减少时结束 */
        DEDENT,
        /** 换行时结束 */
        NEWLINE
    }

    /**
     * 创建使用默认结束方式（DEDENT）的复合模式
     */
    public CompoundPattern(String name, SemanticTokenKind opener, List<SemanticTokenKind> contextualKeywords) {
        this(name, opener, contextualKeywords, CloserType.DEDENT);
    }

    /**
     * 创建 match-when 模式（若...为）
     * <p>
     * 用于中文模式匹配语法：
     * <pre>
     * 若 变量：
     *   为 值1：返回 结果1。
     *   为 值2：返回 结果2。
     * </pre>
     */
    public static CompoundPattern matchWhen() {
        return new CompoundPattern(
            "match-when",
            SemanticTokenKind.MATCH,
            List.of(SemanticTokenKind.WHEN),
            CloserType.DEDENT
        );
    }

    /**
     * 创建 let-be 模式（令...为）
     * <p>
     * 用于中文变量声明语法：
     * <pre>
     * 令 变量名 为 值。
     * </pre>
     */
    public static CompoundPattern letBe() {
        return new CompoundPattern(
            "let-be",
            SemanticTokenKind.LET,
            List.of(SemanticTokenKind.BE),
            CloserType.NEWLINE
        );
    }
}
