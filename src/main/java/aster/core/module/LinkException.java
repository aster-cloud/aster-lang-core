package aster.core.module;

import java.io.Serial;

/**
 * Module graph 合并阶段的结构性错误。
 */
public final class LinkException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 1L;

  public LinkException(String message) {
    super(message);
  }
}
