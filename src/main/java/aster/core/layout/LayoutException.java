package aster.core.layout;

/**
 * 显示排版规则失败（ADR 0059 修订 4 §4）：{@link #rule()} 为 {@code "L1"} … {@code "L9"}，与共享向量的 {@code expect}
 * 对照；message 只用于诊断（写 stderr），不跨实现比较。
 */
public final class LayoutException extends RuntimeException {

    private final String rule;

    public LayoutException(String rule, String message) {
        super(rule + ": " + message);
        this.rule = rule;
    }

    /** 失败的规则号。 */
    public String rule() {
        return rule;
    }
}
