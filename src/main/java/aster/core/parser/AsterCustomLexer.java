package aster.core.parser;

import org.antlr.v4.runtime.*;

import aster.core.canonicalizer.Canonicalizer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

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

    /**
     * 上一个返回的【有意义】token 的小写文本（跳过 INDENT/DEDENT/NEWLINE），
     * 用于"关键词当标识符"标记的位置判定（前驱是否为结构关键词）。
     */
    private String prevMeaningfulLower = null;

    /**
     * 是否正处于【标识符声明列表】内（见过结构关键词,经逗号/and 延续,直到 `.`/`:`/换行结束）。
     * 用于字段/参数列表里逗号后的标记 token（`年龄，结果`）仍判为标识符位置。
     */
    private boolean inIdentifierList = false;

    /**
     * 引出【标识符位置】的英文结构关键词（翻译后形式），前驱是它们则当前标记 token 是标识符。
     * 含逗号/and（字段/参数列表延续，由 prevMeaningful 间接覆盖）。
     */
    // 引出【标识符声明位置】的结构关键词(其紧后是用户命名:类型/字段/参数/变量名)。
    // 只保留【后继判定不足以消歧】的真声明头。注意:
    //  - 不含 `as`/`produce`:引出【类型】,OF 家族在该位置是类型构造关键词,不能当标识符。
    //  - 不含 `set`/`and`:construct 字段名在 set 之前;`and` 只是列表延续。
    //  - 不含 `has`/`with`:它们也是函数调用后缀(`f with 成功值 x`),全局进列表会把调用参数里
    //    的 OF 家族构造器误当标识符。改靠【后继 token 判定】消歧:
    //      has 结果 .        → 后继是 `.` → 字段名(还原)
    //      has 结果 as Text. → 后继是 `as` → 字段名(还原)
    //      with 结果 set to  → 后继是 `set` → 构造字段名(还原)
    //      f with 成功值 x   → 后继是 `x`(表达式起点) → 构造器(展开)
    private static final Set<String> IDENTIFIER_INTRODUCERS = Set.of(
        "define", "rule", "given", "let"
    );

    public AsterCustomLexer(CharStream input) {
        super(input);
        indentStack.push(0); // 初始缩进为 0
    }

    @Override
    public Token nextToken() {
        // 如果 pending 队列不为空，优先返回队列中的 token
        if (!pending.isEmpty()) {
            Token p = pending.poll();
            recordMeaningful(p);  // 展开 token 也须更新前驱状态,否则后续标记位置判定错位
            return p;
        }

        // 如果需要检查缩进，先检查再返回下一个 token
        if (checkIndent) {
            checkIndent = false;
            handleIndentation();
            if (!pending.isEmpty()) {
                Token p = pending.poll();
                recordMeaningful(p);
                return p;
            }
        }

        // 获取下一个 token：优先消费 peek 缓存的（避免 OF 家族构造器判定时预读丢 token），
        // 先返回预读保留的隐藏 token（注释 trivia），再返回真实 token，否则从基类取。
        Token t;
        if (!bufferedHidden.isEmpty()) {
            return bufferedHidden.poll();  // 隐藏 token 直接返回,不参与标记/缩进/前驱逻辑
        } else if (bufferedNext != null) {
            t = bufferedNext;
            bufferedNext = null;
        } else {
            t = super.nextToken();
        }

        // "关键词当标识符"标记 token：按位置还原成标识符或展开成英文关键词。
        if (t.getType() != Token.EOF && Canonicalizer.isWrappedKeywordIdent(t.getText())) {
            Token resolved = resolveWrappedKeywordIdent(t);
            // resolveWrappedKeywordIdent 可能把展开 token 入 pending，这里返回首个
            if (resolved != null) {
                recordMeaningful(resolved);
                return resolved;
            }
            if (!pending.isEmpty()) {
                Token first = pending.poll();
                recordMeaningful(first);
                return first;
            }
        }

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

        recordMeaningful(t);
        return t;
    }

    /**
     * 记录上一个有意义 token 的小写文本（用于标记位置判定）。
     * 跳过 NEWLINE/INDENT/DEDENT/EOF/隐藏通道——它们不影响"前驱是否结构关键词"判定。
     */
    private void recordMeaningful(Token t) {
        int type = t.getType();
        if (type == NEWLINE || type == AsterParser.INDENT || type == AsterParser.DEDENT
                || type == Token.EOF || t.getChannel() == Token.HIDDEN_CHANNEL) {
            return;
        }
        String txt = t.getText();
        prevMeaningfulLower = txt == null ? null : txt.toLowerCase(java.util.Locale.ROOT);

        // 维护"标识符声明列表"状态：结构关键词进入；`.`/`:`/`(`/`as`/`produce` 等结束；
        // 其余（字段名/逗号/and）延续。`as`/`produce` 之后是【类型】(OF 家族在该位置是
        // 类型构造关键词,非标识符),故退出列表。
        if (prevMeaningfulLower != null && IDENTIFIER_INTRODUCERS.contains(prevMeaningfulLower)) {
            inIdentifierList = true;
        } else if (type == AsterParser.DOT || type == AsterParser.COLON
                || type == AsterParser.LPAREN || type == AsterParser.RPAREN
                || type == AsterParser.AS || type == AsterParser.BE || type == AsterParser.TO_WORD
                || (prevMeaningfulLower != null && prevMeaningfulLower.equals("produce"))) {
            // 语句/类型/调用/值边界 → 列表结束。
            // BE(`Let x be <expr>`)/TO_WORD(`F set to <expr>`)之后是表达式值,
            // 其中 OF 家族是构造器关键词,不能当标识符 → 退出列表。
            inIdentifierList = false;
        }
        // 其余 token（字段名 IDENT、逗号、and）保持 inIdentifierList 不变，使
        // `包含 年龄，结果` 里逗号后的 结果 仍判为标识符位置。
    }

    /**
     * 解析"关键词当标识符"标记 token：按位置决定还原成标识符还是展开成英文关键词。
     * <ul>
     *   <li>标识符位置（前驱是结构关键词，或处于标识符声明列表内）→ 单 IDENT，文本=源词。</li>
     *   <li>关键词位置 → 展开成英文关键词的各 token，逐个入 pending。</li>
     * </ul>
     * 返回直接返回的 token（标识符情形），或 null（已把展开 token 入 pending）。
     */
    private Token resolveWrappedKeywordIdent(Token t) {
        String text = t.getText();
        String source = Canonicalizer.unwrapSource(text);
        String english = Canonicalizer.unwrapEnglish(text);
        if (source == null || english == null) {
            // 防御：格式异常时按英文展开（保持可解析）
            english = source != null ? source : text;
            source = null;
        }

        boolean afterIntroducer =
            prevMeaningfulLower != null && IDENTIFIER_INTRODUCERS.contains(prevMeaningfulLower);
        boolean inListContinuation = inIdentifierList;

        // 判定标识符 vs 关键词(OF 家族 `IDENT OF expr` 构造器):
        // - 前驱是结构关键词,或处于标识符声明列表内 → 声明位置,当标识符。
        // - 否则看【后继 token】:OF 家族关键词形需要 expr 操作数(`成功值 x`),若后继能起一个
        //   表达式 → 当关键词(展开);若后继是句末符/逗号/右括号/换行/EOF → 当标识符(变量引用等)。
        // 不用全局"已声明名"集合(会污染后续同形关键词,破坏 parity)。
        boolean asIdentifier;
        if (source != null && (afterIntroducer || inListContinuation)) {
            asIdentifier = true;
        } else if (source != null) {
            Token nextReal = peekNextRealToken();
            asIdentifier = !startsExpression(nextReal);
        } else {
            asIdentifier = false;
        }

        if (asIdentifier) {
            // 单 IDENT/TYPE_IDENT，文本=源词（与 TS 引擎标识符名一致）
            int kind = isUppercaseStart(source) ? AsterParser.TYPE_IDENT : AsterParser.IDENT;
            CommonToken id = new CommonToken(kind, source);
            id.setLine(t.getLine());
            id.setCharPositionInLine(t.getCharPositionInLine());
            return id;
        }

        // 关键词位置：把英文展开形重新词法化成 token 入 pending。
        expandEnglishToPending(english, t);
        return null;
    }

    /** 预读的下一个真实 token（被 peekNextRealToken 缓存,nextToken 顶部优先消费）。 */
    private Token bufferedNext = null;
    /** 预读时遇到的 HIDDEN 通道 token（注释），保留以便按序返回,不丢 trivia。 */
    private final Deque<Token> bufferedHidden = new ArrayDeque<>();

    /**
     * 预读下一个【真实】token（跳过但保留 HIDDEN 通道）并缓存,供 OF 家族构造器判定使用。
     * 预读到的真实/隐藏 token 均缓存,由 nextToken 在 super.nextToken 之前按序优先返回,
     * 既不丢真实语法 token,也不丢注释 trivia。
     */
    private Token peekNextRealToken() {
        if (bufferedNext == null) {
            Token n = super.nextToken();
            while (n.getChannel() == Token.HIDDEN_CHANNEL && n.getType() != Token.EOF) {
                bufferedHidden.add(n);  // 保留隐藏 token,稍后按序返回
                n = super.nextToken();
            }
            bufferedNext = n;
        }
        return bufferedNext;
    }

    /**
     * 判断 token 能否起一个表达式(OF 家族构造器的操作数)。
     * 覆盖 primaryExpr/unaryExpr/ifExpr/lambdaExpr 的起点:标识符、字面量(含 null/bool)、
     * 括号/列表、以及在表达式位置当软关键词的结构词(if/not/function/Map/let/match/... )。
     */
    private static boolean startsExpression(Token t) {
        if (t == null) {
            return false;
        }
        int type = t.getType();
        return type == AsterParser.IDENT || type == AsterParser.TYPE_IDENT
            || type == AsterParser.INT_LITERAL || type == AsterParser.FLOAT_LITERAL
            || type == AsterParser.LONG_LITERAL || type == AsterParser.STRING_LITERAL
            || type == AsterParser.BOOL_LITERAL || type == AsterParser.NULL_LITERAL
            || type == AsterParser.LPAREN || type == AsterParser.LBRACKET
            || type == AsterParser.FUNCTION || type == AsterParser.MAP
            || type == AsterParser.IF || type == AsterParser.NOT
            // 结构软关键词在表达式位置可当变量名/方法名(structKeywordName)
            || type == AsterParser.LET || type == AsterParser.MATCH
            || type == AsterParser.RETURN || type == AsterParser.RULE
            || type == AsterParser.DEFINE || type == AsterParser.WHEN
            || type == AsterParser.START || type == AsterParser.WAIT
            || type == AsterParser.ELSE || type == AsterParser.THEN;
    }

    /** 把英文展开形（如 "result of"）重新词法化成 token，全部入 pending。 */
    private void expandEnglishToPending(String english, Token at) {
        AsterCustomLexer sub = new AsterCustomLexer(CharStreams.fromString(english));
        Token st;
        while ((st = sub.nextToken()).getType() != Token.EOF) {
            int type = st.getType();
            if (type == NEWLINE || type == AsterParser.INDENT || type == AsterParser.DEDENT) {
                continue;
            }
            CommonToken ct = new CommonToken(type, st.getText());
            ct.setLine(at.getLine());
            ct.setCharPositionInLine(at.getCharPositionInLine());
            pending.add(ct);
        }
    }

    /** 判断源词首字符是否大写（决定 IDENT vs TYPE_IDENT；CJK 等无大小写归 TYPE_IDENT，与 ANTLR 规则一致）。 */
    private static boolean isUppercaseStart(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        // ASCII 小写或下划线 → IDENT；其余（大写、CJK 等）→ TYPE_IDENT（同 AsterLexer.g4）
        return !((c >= 'a' && c <= 'z') || c == '_');
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
                // 将 tab 诊断为错误 token，而非崩溃解析器
                Token errToken = createErrorToken("Tab indentation is not allowed (use spaces) at line " + getLine());
                pending.add(errToken);
                return;
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
                // 报告诊断错误 token，而非崩溃解析器
                pending.add(createErrorToken("Invalid indentation at line " + getLine() + ": must be even spaces"));
                indentStack.push(spaces); // 继续解析，将此缩进压栈以维持状态
                pending.add(createIndent());
                return;
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
                // 报告诊断错误 token，而非崩溃解析器
                pending.add(createErrorToken("Inconsistent dedent at line " + getLine() + ": does not match any previous indentation level"));
                // 将当前空格数压栈，维持解析器继续运行
                indentStack.push(spaces);
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
     * 创建错误 token（替代 RuntimeException，维持解析器继续运行）
     */
    private Token createErrorToken(String message) {
        CommonToken t = new CommonToken(Token.INVALID_TYPE, "<ERROR: " + message + ">");
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
