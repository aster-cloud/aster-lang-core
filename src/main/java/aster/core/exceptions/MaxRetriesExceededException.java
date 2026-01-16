package aster.core.exceptions;

import java.io.Serial;

/**
 * 工作流重试次数超限异常
 *
 * 当重试次数达到 RetryPolicy.maxAttempts 后抛出
 */
public class MaxRetriesExceededException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int maxAttempts;
    private final String failureReason;

    public MaxRetriesExceededException(int maxAttempts, String failureReason, Throwable cause) {
        super(String.format("Max retries (%d) exceeded: %s", maxAttempts, failureReason), cause);
        this.maxAttempts = maxAttempts;
        this.failureReason = failureReason;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
