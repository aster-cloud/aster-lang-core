package aster.core.lexer;

import java.io.Serializable;

/**
 * 源码位置信息
 * <p>
 * 记录 token 在源文件中的行号和列号（均为 1-based）。
 *
 * @param line 行号（从 1 开始）
 * @param col  列号（从 1 开始）
 */
public record Position(int line, int col) implements Serializable {}
