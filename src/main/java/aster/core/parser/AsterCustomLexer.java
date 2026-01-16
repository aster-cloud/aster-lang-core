package aster.core.parser;

import org.antlr.v4.runtime.*;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 自定义 Lexer 类，扩展 ANTLR4 生成的 AsterLexer，添加缩进敏感语法支持。
 * <p>
 * **核心功能**：
 * <ul>
 *   <li>在换行后检测缩进变化，动态生成 INDENT/DEDENT token</li>
 *   <li>维护缩进栈（indentStack），跟踪嵌套层级</li>
 *   <li>验证缩进合法性（必须是偶数空格，dedent 必须对齐）</li>
 * </ul>
 * <p>
 * **实现原理**：
 * <ul>
 *   <li>覆盖 {@link #nextToken()} 方法，在返回 NEWLINE token 后检查下一行缩进</li>
 *   <li>使用 pending queue 存储待返回的 INDENT/DEDENT token</li>
 *   <li>在文件结束时自动生成所有剩余的 DEDENT token</li>
 * </ul>
 * <p>
 * **参考实现**：Python ANTLR4 grammar 的缩进处理方式
 *
 * @see AsterLexer
 */
public class AsterCustomLexer extends AsterLexer {

    /**
     * 缩进栈，记录每层缩进的空格数
     * <p>
     * 初始值为 [0]，表示文件开头没有缩进。
     */
    private final Deque<Integer> indentStack = new ArrayDeque<>();

    /**
     * 待返回的 token 队列
     * <p>
     * 当遇到缩进变化时，可能需要生成多个 INDENT/DEDENT token，
     * 先存入 pending 队列，逐个返回。
     */
    private final Deque<Token> pending = new ArrayDeque<>();

    /**
     * EOF token（缓存，用于在文件结束时生成剩余的 DEDENT）
     */
    private Token eofToken = null;

    /**
     * 是否需要检查缩进（在 NEWLINE 后设为 true）
     */
    private boolean checkIndent = false;

    public AsterCustomLexer(CharStream input) {
        super(input);
        indentStack.push(0); // 初始缩进为 0
    }

    @Override
    public Token nextToken() {
        // 如果 pending 队列不为空，优先返回队列中的 token
        if (!pending.isEmpty()) {
            return pending.poll();
        }

        // 如果需要检查缩进，先检查再返回下一个 token
        if (checkIndent) {
            checkIndent = false;
            handleIndentation();
            if (!pending.isEmpty()) {
                return pending.poll();
            }
        }

        // 获取下一个 token（从基类 AsterLexer）
        Token t = super.nextToken();

        // 如果遇到 EOF，生成所有剩余的 DEDENT token
        if (t.getType() == Token.EOF) {
            if (eofToken == null) {
                eofToken = t;
                // 生成所有剩余的 DEDENT token
                while (indentStack.size() > 1) {
                    indentStack.pop();
                    pending.add(createDedent());
                }
                // 最后返回 EOF
                pending.add(eofToken);
            }
            // 从 pending 队列中返回
            if (!pending.isEmpty()) {
                return pending.poll();
            }
            return eofToken;
        }

        // 跳过 WS 和 COMMENT（它们已经被 skip 或放入 HIDDEN channel）
        if (t.getChannel() == Token.HIDDEN_CHANNEL) {
            return t;
        }

        // 如果遇到 NEWLINE，标记需要检查缩进
        if (t.getType() == NEWLINE) {
            checkIndent = true;
            return t;
        }

        return t;
    }

    /**
     * 处理缩进变化，生成 INDENT/DEDENT token
     * <p>
     * 在遇到 NEWLINE 后调用，检查下一行的缩进空格数。
     */
    private void handleIndentation() {
        CharStream input = getInputStream();
        int offset = 0;
        int spaces = 0;

        while (true) {
            int la = input.LA(offset + 1);
            if (la == ' ') {
                offset++;
                spaces++;
                continue;
            }
            if (la == '\t') {
                throw new RuntimeException("Tabs are not allowed for indentation at line " + getLine());
            }
            break;
        }

        int nextChar = input.LA(offset + 1);
        if (nextChar == IntStream.EOF) {
            return;
        }
        if (nextChar == '\n' || nextChar == '\r' || nextChar == '#') {
            checkIndent = true;
            return;
        }

        int currentIndent = indentStack.peek();

        if (spaces == currentIndent) {
            // 缩进未变化，不需要生成 token
            return;
        } else if (spaces > currentIndent) {
            // 缩进增加，生成 INDENT token
            if ((spaces - currentIndent) % 2 != 0) {
                throw new RuntimeException("Invalid indentation: must be incremented by even number of spaces at line " + getLine());
            }
            indentStack.push(spaces);
            pending.add(createIndent());
        } else {
            // 缩进减少，生成 DEDENT token
            while (!indentStack.isEmpty() && spaces < indentStack.peek()) {
                indentStack.pop();
                pending.add(createDedent());
            }

            // 验证 dedent 对齐
            if (indentStack.isEmpty() || indentStack.peek() != spaces) {
                throw new RuntimeException("Inconsistent dedent: does not match any previous indentation level at line " + getLine());
            }
        }
    }

    /**
     * 创建 INDENT token
     */
    private Token createIndent() {
        CommonToken t = new CommonToken(AsterParser.INDENT, "<INDENT>");
        t.setLine(getLine());
        t.setCharPositionInLine(getCharPositionInLine());
        return t;
    }

    /**
     * 创建 DEDENT token
     */
    private Token createDedent() {
        CommonToken t = new CommonToken(AsterParser.DEDENT, "<DEDENT>");
        t.setLine(getLine());
        t.setCharPositionInLine(getCharPositionInLine());
        return t;
    }
}
