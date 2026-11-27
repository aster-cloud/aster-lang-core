package aster.core.parser;

import org.antlr.v4.runtime.*;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.typecheck.ErrorCode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** 上一个返回的有意义 token（同 {@link #prevMeaningfulLower}），供运算符短语判定前一个记号能否结束操作数（K-c）。 */
    private Token prevMeaningful = null;

    /** {@link #prevMeaningful} 是否紧跟在点之后（成员名，K-c）：`s.tIMES Times 2`、`s.type Plus 1` 的成员名是操作数。 */
    private boolean prevIsMember = false;

    /** 上一个有意义 token 之后是否出现过换行 / 缩进记号（K-c）：语句末尾的句点之后换行，下一行的名字不是成员名。 */
    private boolean lineBreakSincePrev = false;

    /** {@link #prevMeaningful} 是否是一行的第一个有意义记号（aster-lang-core#328：语句开头的 Set）。 */
    private boolean prevStartsLine = false;

    /** 刚见过 Match：其后的第一个 INDENT 开启 Match 分支块（K-c，D3 判定模式位置用）。 */
    private boolean matchHeaderPending = false;

    /** 各层 Match 分支块的缩进（空格数），栈顶是最内层（K-c）。 */
    private final Deque<Integer> matchCaseIndents = new ArrayDeque<>();

    /** 是否处在 Match 分支的模式里（`When` 之后、括号外的第一个逗号之前，K-c）：模式里的 `Not(a)` 是构造器名，不读作 not。 */
    private boolean inPattern = false;

    /** 模式里未闭合的左括号数（K-c）。 */
    private int patternParens = 0;

    /** 下一个软比较词（under / over）的起点：`Is under` 的 is 已按短语改写时，其后的 under 同样改成小写文本（K-c）。 */
    private int pendingSoftWordStart = -1;

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
    //  - 不含 `has`/`with`:全局进列表会把其后非标识符位置的 OF 家族构造器误当标识符。
    //    改靠【后继 token 判定】消歧:
    //      has 结果 .        → 后继是 `.` → 字段名(还原)
    //      has 结果 as Text. → 后继是 `as` → 字段名(还原)
    //      with 结果 set to  → 后继是 `set` → 构造字段名(还原)
    //      with 成功值 x     → 后继是 `x`(表达式起点) → 构造器(展开);该写法缺 `set to`,
    //                          由解析器按构造字段报错(无 `f with x` 调用形式,与 TS 一致)
    private static final Set<String> IDENTIFIER_INTRODUCERS = Set.of(
        "define", "rule", "given", "let"
    );

    public AsterCustomLexer(CharStream input) {
        super(input);
        indentStack.push(0); // 初始缩进为 0
        skipLeadingBom();
    }

    /**
     * 消费首字符的 UTF-8 BOM（U+FEFF）。
     *
     * <p>Windows 记事本等编辑器默认给 UTF-8 文件加 BOM。不剥离的话首 token 会以
     * U+FEFF 开头：{@code Module} 变成 {@code ﻿Module}，要么报「未知标识符」，
     * 要么被 {@code Character.isUnicodeIdentifierPart} 放行而**静默生成错误的模块名**
     * ——后者更坏，因为它不报错。
     *
     * <p>★这里是**跨引擎对等**修复：TS 侧 {@code src/frontend/lexer.ts} 一直在剥
     * （`input.charCodeAt(0) === 0xfeff` 时 `i++; col++`），Java 侧从未剥过。
     * 同一份源文件在两个引擎上解析结果不同，属于双引擎分叉。
     * 早先的死 Lexer 里有这段逻辑但零引用，删除它只是把「看起来有」变成「确实没有」。
     *
     * <p>放在构造器而不是某个上层入口：{@code CharStream} 由仓外调用方构造，
     * 本类是所有解析路径的**唯一收口点**，在这里剥才不会漏掉某条入口。
     *
     * <p>★列号**不偏移**：BOM 是编码标记、不是源码内容，不该占一列。
     * 实测（本类构造后）：
     * <pre>
     *   不带 BOM: 首 token col=0
     *   带 BOM:   首 token col=0
     * </pre>
     * 这正是同 PR 的 {@code BomStrippingTest.BOM不得让首token的列号偏移()} 所断言的。
     *
     * <p>★TS 侧原本是**会偏移**的（{@code lexer.ts} 里 `i++; col++`，实测首 token
     * col 由 1 变 2）。本修复消除了 Java 侧的偏移，因而**与当时的 TS 产生了分叉**，
     * 故同时提交了 TS 侧的配套修复（去掉 `col++`）把两边都收敛到「不占列」。
     * 列号偏移会带歪诊断位置、span 与 IDE 高亮——而 ProofIR 的 span 是签字锚定
     * 的一部分，不是可以随手偏一列的东西。
     */
    private void skipLeadingBom() {
        if (getInputStream() != null && getInputStream().LA(1) == 0xFEFF) {
            getInputStream().consume();
        }
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
            inPattern = false;
            lineBreakSincePrev = true;
            return t;
        }

        t = splitSetTarget(t);
        t = mergeOperatorPhrase(t);
        retypePrefixKeyword(t);
        retypeConstraintKeyword(t);
        recordMeaningful(t);
        return t;
    }

    /**
     * 可以接在名字之后的关键词文本（K，任意大小写）：名字后面是这些词时，名字照旧是名字。即计划 RK3 的全部 K 关键词，
     * 加上软比较词与 if / then / else。
     */
    static final Set<String> WORDS_AFTER_NAME = Set.of(
        "given", "with", "and", "or", "produce", "has", "as", "one", "of", "use", "version", "be", "set", "to",
        "for", "function", "map", "async", "times", "plus", "minus", "modulo", "workflow", "step", "retry", "timeout",
        "depends", "on", "compensate", "max", "attempts", "backoff", "second", "seconds", "required", "between",
        "matching", "performs", "module", "is", "less", "greater", "more", "at", "equal", "equals", "divided",
        "integer", "under", "over", "then", "else", "otherwise", "if", "it");

    /**
     * 记号能起一个操作数、而又不能接在名字之后（K-b，前缀关键词 not / async 的判定）：名字、字面量、not、function、Map、apply；
     * 名字里排除 {@link #WORDS_AFTER_NAME}。括号、方括号（`It performs E [Http]` 的效应名之后）、运算符、点、逗号、换行等都不算：
     * `Not(x)`、`Not + (x)`、`Not.x` 里的 Not 照旧是名字（读作 not 是 D3，属于 K-c）。
     */
    static boolean startsOperandOnly(Token t) {
        if (t == null) {
            return false;
        }
        int type = t.getType();
        if (type == AsterParser.IDENT || type == AsterParser.TYPE_IDENT) {
            return EscapedToken.isEscaped(t) || t.getText() == null
                || !WORDS_AFTER_NAME.contains(t.getText().toLowerCase(java.util.Locale.ROOT));
        }
        return type == AsterParser.INT_LITERAL || type == AsterParser.FLOAT_LITERAL
            || type == AsterParser.DECIMAL_LITERAL || type == AsterParser.LONG_LITERAL
            || type == AsterParser.STRING_LITERAL || type == AsterParser.BOOL_LITERAL
            || type == AsterParser.NULL_LITERAL || type == AsterParser.NOT || type == AsterParser.FUNCTION || type == AsterParser.MAP
            || type == AsterParser.APPLY;
    }

    /**
     * 运算符短语的首词（K-c，小写）：比较词前的连接词 is、not equal to 的 not、单词算术运算符与软比较词。less than、at least、
     * divided by 等不带 is 的多词运算符由 Canonicalizer 按词法表大小写不敏感地归一成小写，到这里已是运算符记号。
     */
    private static final Set<String> PHRASE_FIRST_WORDS = Set.of("is", "not", "times", "plus", "minus", "modulo", "under", "over");

    /**
     * K-c 新增读法的运算符短语（大小写不敏感，词间只能是水平空白，与 AsterLexer.g4 多词运算符一致），按 aster-lang-ts
     * parseComparison / parseAdditive / parseMultiplicative 接受的写法列出。分组序号见 {@link #PHRASE_TYPES}。
     * {@code less than or equal to} / {@code greater than or equal to} 不在其中：aster-lang-ts 连小写也不收。
     */
    private static final Pattern OPERATOR_PHRASE = Pattern.compile(
        "(?i)(?:((?:is[ \\t]+)?not[ \\t]+equal[ \\t]+to)|(is[ \\t]+equal[ \\t]+to)|(is[ \\t]+less[ \\t]+than)"
            + "|(is[ \\t]+(?:greater|more)[ \\t]+than)|(is[ \\t]+at[ \\t]+least)|(is[ \\t]+at[ \\t]+most)"
            + "|(times)|(plus)|(minus)|(modulo)|(is[ \\t]+)?(under|over))(?![\\p{L}\\p{M}\\p{N}_])");

    /** {@link #OPERATOR_PHRASE} 各分组对应的运算符记号；最后两组（软比较词）另行处理。 */
    private static final int[] PHRASE_TYPES = {
        AsterParser.NOT_EQUAL_TO_WORD, AsterParser.EQUALS_TO_WORD, AsterParser.LESS_THAN_WORD,
        AsterParser.GREATER_THAN_WORD, AsterParser.GREATER_THAN_OR_EQUAL_WORD, AsterParser.LESS_THAN_OR_EQUAL_WORD,
        AsterParser.TIMES_WORD, AsterParser.PLUS_WORD, AsterParser.MINUS_WORD, AsterParser.MODULO_WORD,
    };

    /**
     * 运算符短语的其余大小写拼写（K-c，ADR 0056 修订 A4 §1.1）：{@code x Times 2}、{@code x Is Less Than y}、
     * {@code x NOT EQUAL TO y}、{@code x Under 5} 改写成与小写拼写相同的运算符记号（软比较词 under / over / is 改成小写文本的
     * IDENT，由 softComparator 照旧按文本识别）。解析器看到的与小写拼写逐记号相同，文法不变。
     *
     * <p>只在名字读法走不通时改写：前一个有意义的记号能结束一个操作数（{@link #endsOperand}，或是成员名；可隔着续行换行，
     * 语句以句点结束，句点之后的记号不算），短语之后的记号能起一个操作数
     * （{@link #startsOperand}）。名字后面直接跟名字在文法里不成立，所以 {@code Let Is be x.} 之后的 {@code Return Is less than 5.}、
     * {@code Set Plus to 1.} 里的 Is / Plus 照旧是名字。短语必须恰好落在记号边界上（{@code Is less than or equal to} 的
     * {@code less than or equal to} 是一个记号，不拆）。名字转义永远是名字（ADR 0064 N3），由本地化关键词还原的标识符不参与。
     */
    private Token mergeOperatorPhrase(Token t) {
        if (pendingSoftWordStart >= 0 && t.getStartIndex() == pendingSoftWordStart) {
            pendingSoftWordStart = -1;
            return lowercaseIdent(t);
        }
        int type = t.getType();
        if ((type != AsterParser.IDENT && type != AsterParser.TYPE_IDENT && type != AsterParser.NOT)
                || EscapedToken.isEscaped(t) || t.getText() == null
                || !PHRASE_FIRST_WORDS.contains(t.getText().toLowerCase(Locale.ROOT))
                || !(prevIsMember || endsOperand(prevMeaningful))) {
            return t;
        }
        String line = restOfLine(t.getStartIndex());
        Matcher m = OPERATOR_PHRASE.matcher(line);
        if (!m.lookingAt() || m.group().equals(m.group().toLowerCase(Locale.ROOT))) {
            return t;
        }
        int end = m.end();
        if (!phraseOnTokenBoundary(line, end)) {
            return t;
        }
        if (m.group(12) != null) {
            // 软比较词：is 与 under / over 各自保留为一个记号，文本改成小写
            if (m.group(11) != null) pendingSoftWordStart = t.getStartIndex() + m.start(12);
            return lowercaseIdent(t);
        }
        int group = 1;
        while (m.group(group) == null) group++;
        CommonToken merged = new CommonToken(t);
        merged.setType(PHRASE_TYPES[group - 1]);
        merged.setStopIndex(t.getStartIndex() + end - 1);
        merged.setText(m.group());
        // 消费短语里其余的词（同一行、无隐藏记号，见 phraseOnTokenBoundary）
        int stop = t.getStopIndex();
        while (stop < merged.getStopIndex()) {
            Token n = bufferedNext != null ? bufferedNext : super.nextToken();
            bufferedNext = null;
            stop = n.getStopIndex();
        }
        return merged;
    }

    /**
     * Set 的目标名 {@code equals}（aster-lang-core#328）：生成的词法器把 {@code set equals to 5.} 里的 {@code equals to}
     * 切成运算符记号 EQUALS_TO_WORD，但多词运算符只能接在操作数之后，语句开头的 Set 之后是目标名。这里把它拆回名字
     * {@code equals} 与 Set 的 {@code to}（TO_WORD），与 aster-lang-ts 相同。只看语句开头（行首）的 Set（任意大小写；
     * 其余大小写拼写由 kwSet 按 `名字 to` 读作 set）；大写开头的 {@code Equals to} 不会到这里：Canonicalizer 不再把 Set 的
     * 目标名改写成小写。
     */
    private Token splitSetTarget(Token t) {
        if (t.getType() != AsterParser.EQUALS_TO_WORD || !prevStartsLine || !"set".equals(prevMeaningfulLower)) {
            return t;
        }
        String text = t.getText();
        if (text == null || !text.startsWith("equals") || continuesWord(t.getStopIndex() + 1)) {
            return t;
        }
        int toOffset = text.lastIndexOf("to");
        CommonToken name = new CommonToken(t);
        name.setType(AsterParser.IDENT);
        name.setText("equals");
        name.setStopIndex(t.getStartIndex() + "equals".length() - 1);
        CommonToken to = new CommonToken(t);
        to.setType(AsterParser.TO_WORD);
        to.setText("to");
        to.setStartIndex(t.getStartIndex() + toOffset);
        to.setStopIndex(t.getStartIndex() + toOffset + 1);
        to.setCharPositionInLine(t.getCharPositionInLine() + toOffset);
        pending.addFirst(to);
        return name;
    }

    /** 原文 {@code index} 处是词内字符（{@code set equals tomorrow} 的 to 只是 tomorrow 的前缀，不拆）。 */
    private boolean continuesWord(int index) {
        CharStream in = getInputStream();
        if (index >= in.size()) {
            return false;
        }
        int c = in.getText(org.antlr.v4.runtime.misc.Interval.of(index, index)).codePointAt(0);
        return Character.isLetterOrDigit(c) || c == '_' || Character.getType(c) == Character.NON_SPACING_MARK
            || Character.getType(c) == Character.COMBINING_SPACING_MARK;
    }

    /** 同一记号改成小写文本的 IDENT（软比较词，K-c）。 */
    private static Token lowercaseIdent(Token t) {
        CommonToken c = new CommonToken(t);
        c.setType(AsterParser.IDENT);
        c.setText(t.getText().toLowerCase(Locale.ROOT));
        return c;
    }

    /**
     * 短语判定看的原文窗口（K-c）：最长的短语 {@code is not equal to} 加上其后的一个记号足够落在窗口里；窗口按行截断。
     * 窗口有界，每个候选记号的开销与行长无关（长行里大量 Plus / Is 时不退化成平方）。
     * 短语之后的字符串操作数只看起始引号，越出窗口的长字符串照样识别（phraseOnTokenBoundary）。
     */
    private static final int PHRASE_WINDOW = 256;

    /** 从 {@code start} 起、至多 {@link #PHRASE_WINDOW} 个字符、到行尾为止的原文。 */
    private String restOfLine(int start) {
        CharStream in = getInputStream();
        int last = Math.min(in.size() - 1, start + PHRASE_WINDOW - 1);
        if (last < start) {
            return "";
        }
        String text = in.getText(org.antlr.v4.runtime.misc.Interval.of(start, last));
        int nl = indexOfLineBreak(text);
        return nl < 0 ? text : text.substring(0, nl);
    }

    private static int indexOfLineBreak(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') return i;
        }
        return -1;
    }

    /**
     * 用生成的词法器（不经本类的改写）重新切分这一行：短语的结尾须是某个记号的结尾、中间没有隐藏记号（注释），且其后的记号
     * 能起一个操作数。
     */
    private static boolean phraseOnTokenBoundary(String line, int end) {
        if (line.indexOf('\uE000') >= 0) {
            return false;
        }
        AsterLexer sub = new AsterLexer(CharStreams.fromString(line));
        sub.removeErrorListeners();
        // 只切到短语之后的第一个记号为止
        for (Token tok = sub.nextToken(); tok.getType() != Token.EOF; tok = sub.nextToken()) {
            if (tok.getStopIndex() < end - 1) {
                if (tok.getChannel() != Token.DEFAULT_CHANNEL) return false;
                continue;
            }
            if (tok.getStopIndex() != end - 1) return false;
            // 字符串字面量可能越出窗口：只看起始的引号（字符串总能起一个操作数）
            int i = end;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            if (i < line.length() && line.charAt(i) == '"') return true;
            Token next = sub.nextToken();
            return next.getType() != Token.EOF && startsOperand(next);
        }
        return false;
    }

    /**
     * 记号能结束一个操作数（K-c 运算符短语之前）：名字（文本不是 {@link #WORDS_AFTER_NAME} 里的关键词，也不是 not）、字面量、
     * 右括号、右方括号；成员名（点之后的名字）另见 {@link #mergeOperatorPhrase}。保守取舍：以关键词同形词为名的变量
     * （{@code max Times 2}）不改写，照旧报错。
     */
    static boolean endsOperand(Token p) {
        if (p == null) {
            return false;
        }
        int type = p.getType();
        if (type == AsterParser.IDENT || type == AsterParser.TYPE_IDENT) {
            if (EscapedToken.isEscaped(p)) return true;
            String lower = p.getText() == null ? "" : p.getText().toLowerCase(Locale.ROOT);
            return !WORDS_AFTER_NAME.contains(lower) && !lower.equals("not");
        }
        return type == AsterParser.INT_LITERAL || type == AsterParser.FLOAT_LITERAL
            || type == AsterParser.DECIMAL_LITERAL || type == AsterParser.LONG_LITERAL
            || type == AsterParser.STRING_LITERAL || type == AsterParser.BOOL_LITERAL
            || type == AsterParser.NULL_LITERAL || type == AsterParser.RPAREN || type == AsterParser.RBRACKET;
    }

    /** 记号（生成的词法器切出的）能起一个操作数（K-c 运算符短语之后）。 */
    private static boolean startsOperand(Token n) {
        int type = n.getType();
        return type == AsterLexer.ESCAPED_NAME || type == AsterParser.MINUS || type == AsterParser.DECIMAL_LITERAL
            || type == AsterParser.APPLY || startsExpression(n);
    }

    /**
     * 前缀关键词 not / async 的其余大小写拼写（Not、NOT、nOT、Async…，K-b）在词法上是名字记号；名字读法走不通、关键词读法
     * 走得通时（下一个记号能起操作数、又不能接在名字之后，{@link #startsOperandOnly}）改成 NOT / ASYNC 记号，文本与位置不变。
     * async 另外只在 as 之后（`Start t as Async f().`）。not 后接左括号另见 {@link #isNotCall}（K-c，D3）。
     *
     * <p>为什么在词法器里判定：not 能接在任何操作数之前，若用关键词辅助规则，其名字分支在预测深处一律当作成立，任何
     * 「名字 操作数」（`Return x y.`）都会被预测读成 `not y`，出错源码的诊断随之漂移。这里只看下一个记号，与解析器上下文无关，
     * 解析器看到的仍是 K 之前的 NOT / ASYNC 记号。名字转义永远是名字（ADR 0064 N3）。
     */
    private void retypePrefixKeyword(Token t) {
        int type = t.getType();
        if ((type != AsterParser.TYPE_IDENT && type != AsterParser.IDENT) || EscapedToken.isEscaped(t)
                || t.getText() == null || !(t instanceof WritableToken w)) {
            return;
        }
        String lower = t.getText().toLowerCase(java.util.Locale.ROOT);
        boolean not = "not".equals(lower);
        boolean async = "async".equals(lower) && "as".equals(prevMeaningfulLower);
        if (!not && !async) {
            return;
        }
        Token next = peekNextRealToken();
        if (startsOperandOnly(next) || (not && isNotCall(next))) {
            w.setType(not ? AsterParser.NOT : AsterParser.ASYNC);
        }
    }

    /**
     * 是否处在字段 / 形参列表里（K-d，约束词判定用）：见过 given / with / has（任意大小写）之后为真，到括号外的冒号或语句末尾的
     * 句点（其后不紧跟名字字符，区别于限定名里的点）为止。多行字段列表跨越换行，故换行不结束。语句里（`Return x == "a" Required.`）
     * 的约束词同形词因此不改记号，出错时的诊断照旧显示原文。
     */
    private boolean inDeclarationList = false;

    /** 字段 / 形参列表里未闭合的左括号数：注解参数里的冒号（`@source(form: "loan")`）不结束列表。 */
    private int declarationListParens = 0;

    private void trackDeclarationList(Token t, int type) {
        if (type == AsterParser.LPAREN) {
            declarationListParens++;
        } else if (type == AsterParser.RPAREN && declarationListParens > 0) {
            declarationListParens--;
        }
        if ((type == AsterParser.COLON && declarationListParens == 0) || (type == AsterParser.DOT && !followedByNameChar(t))) {
            inDeclarationList = false;
            declarationListParens = 0;
        } else if (type == AsterParser.GIVEN || type == AsterParser.WITH || type == AsterParser.HAS
                || ((type == AsterParser.IDENT || type == AsterParser.TYPE_IDENT) && !EscapedToken.isEscaped(t)
                    && t.getText() != null && DECLARATION_LIST_WORDS.contains(t.getText().toLowerCase(java.util.Locale.ROOT)))) {
            inDeclarationList = true;
        }
    }

    /** 字段 / 形参列表的引导词（任意大小写；小写拼写是 GIVEN / WITH / HAS 记号）。 */
    private static final Set<String> DECLARATION_LIST_WORDS = Set.of("given", "with", "has");

    /** 记号之后的下一个字符是否是名字字符（限定名 `io.Http` 里的点之后紧跟名字）。 */
    private boolean followedByNameChar(Token t) {
        int next = t.getStopIndex() + 1;
        CharStream in = getInputStream();
        if (next >= in.size()) {
            return false;
        }
        int c = in.getText(org.antlr.v4.runtime.misc.Interval.of(next, next)).codePointAt(0);
        return Character.isLetterOrDigit(c) || c == '_' || c == '`';
    }

    /** 约束关键词（K-d，小写）→ 记号类型。小写拼写本来就是这些记号。 */
    private static final java.util.Map<String, Integer> CONSTRAINT_KEYWORDS = java.util.Map.of(
        "required", AsterParser.REQUIRED, "between", AsterParser.BETWEEN, "matching", AsterParser.MATCHING);

    /**
     * 约束关键词 required / between / matching 的其余大小写拼写（Required、BETWEEN、mATCHING…，K-d）在词法上是名字记号；
     * 处在约束位置时改成 REQUIRED / BETWEEN / MATCHING 记号，文本与位置不变：前一个记号能结束字段 / 形参的名字或类型，或是上一个
     * 约束的结尾（同一行，{@link #endsConstraintSubject}），且下一个记号是该约束之后才能出现的记号（{@link #followsConstraint}）。
     * 这两个条件同时成立的「名字 名字 …」在文法里不能走通（同 K-c 的运算符短语），名字读法因此不受影响；万一出现在名字位置，
     * REQUIRED / BETWEEN / MATCHING 也在 structKeywordName 里。
     *
     * <p>为什么不用关键词辅助规则：辅助规则的名字分支在外层决策的预测深处一律当作成立，类型之后的任何名字都能读成约束，
     * `Type With AS Int.` 因而也能读成省略 Define 的数据类型（with = With，字段 AS，约束 Int），分支序号更小而被误选；
     * 出错源码的诊断也随之漂移。名字转义永远是名字（ADR 0064 N3）。
     */
    private void retypeConstraintKeyword(Token t) {
        int type = t.getType();
        if ((type != AsterParser.TYPE_IDENT && type != AsterParser.IDENT) || EscapedToken.isEscaped(t)
                || t.getText() == null || !(t instanceof WritableToken w) || !inDeclarationList || lineBreakSincePrev
                || !endsConstraintSubject(prevMeaningful)) {
            return;
        }
        String lower = t.getText().toLowerCase(java.util.Locale.ROOT);
        Integer keyword = CONSTRAINT_KEYWORDS.get(lower);
        if (keyword != null && followsConstraint(lower, peekNextRealToken())) {
            w.setType(keyword);
        }
    }

    /** 记号能结束约束之前的部分：字段 / 形参名或类型的末尾（名字、右括号、问号），或上一个约束的末尾（约束词、数值、字符串）。 */
    private static boolean endsConstraintSubject(Token p) {
        if (p == null) {
            return false;
        }
        int type = p.getType();
        return endsOperand(p) || type == AsterParser.QUESTION || type == AsterParser.REQUIRED;
    }

    /** 下一个记号只能出现在该约束之后：required 之后是分隔符、句末、冒号或下一个约束；between 之后是数值；matching 之后是字符串或 pattern。 */
    private static boolean followsConstraint(String keyword, Token n) {
        if (n == null) {
            return false;
        }
        int type = n.getType();
        return switch (keyword) {
            case "between" -> type == AsterParser.INT_LITERAL || type == AsterParser.FLOAT_LITERAL || type == AsterParser.MINUS;
            case "matching" -> type == AsterParser.STRING_LITERAL || type == AsterParser.IDENT;
            default -> type == AsterParser.COMMA || type == AsterParser.DOT || type == AsterParser.COLON
                || type == AsterParser.RPAREN || type == Token.EOF || type == NEWLINE
                || type == AsterParser.REQUIRED || type == AsterParser.BETWEEN || type == AsterParser.MATCHING
                || type == AsterParser.AND || type == AsterParser.PRODUCE
                || type == AsterParser.GREATER_THAN_OR_EQUAL_WORD || type == AsterParser.LESS_THAN_OR_EQUAL_WORD
                || ((type == AsterParser.IDENT || type == AsterParser.TYPE_IDENT) && !EscapedToken.isEscaped(n)
                    && n.getText() != null && FOLLOWS_REQUIRED_WORDS.contains(n.getText().toLowerCase(java.util.Locale.ROOT)));
        };
    }

    /** required 之后可以出现的关键词文本（任意大小写）：下一个约束、and、produce。 */
    private static final Set<String> FOLLOWS_REQUIRED_WORDS = Set.of("required", "between", "matching", "and", "produce");

    /**
     * {@code Not(x)} / {@code NOT (x > 1)}（K-c，产品裁决 D3）：not 后接左括号读作 not，与 aster-lang-ts 一致，此前 core 读作
     * 调用名为 Not 的函数。名字读法照旧的位置：成员调用 {@code x.Not(y)}（前一个记号是点）、注解 {@code @Not(…)}、Match 分支的
     * 构造器模式 {@code When Not(a),}（{@link #inPattern}）。要调用名为 Not 的函数请写 {@code `Not`(x)}（名字转义）。
     */
    private boolean isNotCall(Token next) {
        if (next == null || next.getType() != AsterParser.LPAREN || inPattern) {
            return false;
        }
        int prev = prevMeaningful == null ? Token.INVALID_TYPE : prevMeaningful.getType();
        return prev != AsterParser.DOT && prev != AsterParser.AT;
    }

    /**
     * 词法期括号嵌套深度上限——**先于 ANTLR 解析**生效（issue #140）。
     *
     * <p>★为什么必须在这一层：{@code AstBuilder.MAX_EXPR_DEPTH} 的守卫位于
     * **AstBuilder 访问期**（{@code visitExpr}），而 ANTLR 生成解析器自身的
     * 解析期递归发生得更早。嵌套足够深时 {@code parser.module()} 阶段就先抛
     * {@code StackOverflowError}——那是 {@code Error} 级，**不是**可恢复的解析错误，
     * 不会被转成 Diagnostic。
     *
     * <p>实测（修复前，默认栈）：
     * <pre>
     *   depth=500   srcBytes=1043  → IllegalStateException（AstBuilder 守卫生效）
     *   depth=1000  srcBytes=2043  → IllegalStateException（生效）
     *   depth=2000  srcBytes=4043  → StackOverflowError（守卫被绕过）
     * </pre>
     * **只需约 4KB 源码**即可让守卫失效并抛出 Error。
     *
     * <p>而 {@code AstBuilder} 注释里声称的兜底「64KB 源长度上限
     * （SourcePolicyRequest @Size / CnlSourceLimits）」这两个类型**在本仓不存在**——
     * 全仓 grep 只命中注释本身。那个上限由外部 aster-api 服务层实施，
     * 直接使用本库的调用方（含本仓自带的 TypeCheckCli）不受任何限制。
     *
     * <p>本守卫放在 {@code nextToken} 的公共路径上：token 流是**所有**解析入口的必经之处
     * （本仓无统一 parser facade，各调用方自行组装 lexer+parser），
     * 放这里才能覆盖全部路径，而不是只覆盖某一个入口。
     *
     * <p>上限与 {@code MAX_EXPR_DEPTH} 同值：括号深度是表达式深度的下界，越过它的输入
     * 本就会被 AstBuilder 拒绝——本守卫只是让拒绝**发生得更早**（在栈溢出之前）。
     *
     * <p>★阈值必须低于**真实栈容量**（aster-api#472）：此前取 300，但每层括号在
     * {@code AstBuilder} 里是 expr → orExpr → … → postfixExpr → parenExpr → expr 十几帧递归，
     * 299 层括号在 1MB 栈（Linux x64 JVM 默认 -Xss，即 CI hosted runner 与生产 Quarkus worker
     * 线程）上就已经 {@code StackOverflowError}，守卫形同虚设。实测全链路首次溢出深度
     * （GraalVM 25，JIT / -Xint 解释器冷启动）：
     * <pre>
     *   线程栈   括号        方括号      not 链        内联 if
     *   512K     98 / 84     69 / 68     1176 / 850    1077 / 418
     *   1024K    313 / 196   157 / 157   2500 / 1980   2500 / 973
     *   2048K    625 / 419   515 / 335   5857 / 4240   5000 / 2084
     * </pre>
     * 最窄的是 1MB 栈上的方括号（157），上限取其一半以下的 64（512K 栈仍有余量），
     * 并与 aster-lang-ts {@code MAX_RECURSION_DEPTH} 同值；双引擎共享夹具
     * {@code parse_diagnostics_parity.json} 锁定「63 层括号放行、64 层 E054」。
     * 守卫是否先于溢出触发由 {@code NestingGuardStackSafetyTest} 在显式 1MB / 512K 栈的线程上回归。
     */
    public static final int MAX_NESTING_DEPTH = 64;

    private int nestingDepth = 0;

    /**
     * 连续 {@code not} 的计数（issue #157）。
     *
     * <p>括号深度守卫看不见 {@code not not not …}——这条链**全程零括号**。
     * 而 {@code AstBuilder} 的 {@code MAX_EXPR_DEPTH} 只在 {@code visitExpr}
     * 入口递增，属**访问期**；{@code notExpr : NOT notExpr} 的右递归在 ANTLR
     * **解析期**就已经递归下去了，还没轮到访问期。两道防线中间恰好漏了这一类。
     *
     * <p>★实测复现（默认栈的 Gradle test worker）：
     * <pre>
     *   n=12000 → OK
     *   n=15000 → StackOverflowError
     * </pre>
     * issue 估计约 1 万触发，实测阈值在 12000–15000 之间。阈值随栈大小浮动，
     * 生产环境栈更小则更容易触发，所以不能拿「实测要 1.2 万」当作不修的理由。
     *
     * <p>为什么与括号共用 {@code MAX_NESTING_DEPTH}：N 个连续 {@code not}
     * 强制 N 层解析期递归，与 N 层括号同量级；且越过上限的 not 链必然对应
     * 同样层数的表达式嵌套，本就会被 AstBuilder 拒绝——本守卫只是让拒绝
     * **发生得更早**（在栈溢出之前）。
     *
     * <p>★这是**单侧守卫**，不是双引擎分叉的新增项。实测：TS 引擎的 parser
     * 是迭代式的，50 万个 {@code not} 用时 521ms 正常返回，本就不会栈溢出；
     * 而既有的括号守卫同样是 Java-only（TS 侧 400 层括号照常解析）。
     * 也就是说「Java 有解析期深度上限、TS 没有」是**既定先例**，
     * 本守卫与之一致，而非引入新的不对称。
     *
     * <p>代价是：深度介于上限与 TS 原生栈容量之间的输入，在两个引擎上
     * 一个被拒一个通过。考虑到这类输入没有任何真实用途（64 层 not 不是
     * 人写得出来的），且放任 Java 侧栈溢出的后果更坏（{@code StackOverflowError}
     * 是 Error 级，穿透一切 {@code catch (Exception)}），取「宁可拒绝」。
     */
    private int consecutiveNotDepth = 0;

    /**
     * 词法期类型嵌套深度上限（aster-lang-core#250）。
     *
     * <p>类型嵌套（{@code list of list of …}、{@code Map K to Map …}、{@code result of A and result of …}）
     * 此前没有任何守卫：{@code type} 规则在 ANTLR 解析期逐层递归，嵌套够深时 {@code parser.module()} 先抛
     * {@code StackOverflowError}，与 issue #140 的括号同一类问题。本守卫与括号 / not 链守卫同处
     * {@link #nextToken()} 的公共路径，覆盖全部解析入口。
     *
     * <p>计数单位与 aster-lang-ts {@code MAX_TYPE_RECURSION_DEPTH} 相同：每进入一次类型解析计 1（含叶子类型名），
     * 详见 {@link TypeNestingTracker}。两端同值，双引擎共享夹具 {@code parse_diagnostics_parity.json} 锁定
     * list 链、Map 键 / 值交替、result 嵌套三种形态的「上限放行、上限 + 1 E054」。
     *
     * <p>阈值：实测（GraalVM 25，守卫之前）1MB 栈 {@code -Xint} 下最窄的是 {@code result of Int and result of …}
     * （约 206 层即溢出，ANTLR 预测的 closure 递归比 {@code type} 规则本身更深），{@code list of} 链 526 层；
     * 512K 栈 {@code -Xint} 下分别为 91 / 228。上限取 1MB {@code -Xint} 最窄值的一半以下、512K 仍有余量的 64，
     * 与表达式守卫同值。完整实测表见 {@code TypeNestingGuardStackSafetyTest}。
     */
    public static final int MAX_TYPE_NESTING_DEPTH = 64;

    private final TypeNestingTracker typeNesting = new TypeNestingTracker(MAX_TYPE_NESTING_DEPTH);

    private void trackNestingDepth(int type, String text) {
        if (typeNesting.onToken(type, text)) {
            throw new ParseDiagnosticException(ErrorCode.PARSE_NESTING_TOO_DEEP,
                Map.of("limit", String.valueOf(MAX_TYPE_NESTING_DEPTH)),
                "类型嵌套过深（> " + MAX_TYPE_NESTING_DEPTH + "），拒绝以防栈溢出", getLine(), getCharPositionInLine() + 1);
        }
        if (type == AsterParser.LPAREN || type == AsterParser.LBRACKET) {
            if (++nestingDepth > MAX_NESTING_DEPTH) {
                // 与 AstBuilder 深度守卫同一机制：抛可恢复的解析错误，而非放任 Error 穿透。
                throw new ParseDiagnosticException(ErrorCode.PARSE_NESTING_TOO_DEEP,
                    Map.of("limit", String.valueOf(MAX_NESTING_DEPTH)),
                    "括号嵌套过深（> " + MAX_NESTING_DEPTH + "），拒绝以防栈溢出", getLine(), getCharPositionInLine() + 1);
            }
        } else if (type == AsterParser.RPAREN || type == AsterParser.RBRACKET) {
            if (nestingDepth > 0) {
                nestingDepth--;
            }
        }

        // ★连续 NOT 计数：只有**紧挨着**的 not 才累加，任何其他有意义 token 都清零。
        //   `not not x` 累加，`not x and not y` 不累加——后者是正常写法，
        //   把它算进去会误伤合法代码。
        if (type == AsterParser.NOT) {
            if (++consecutiveNotDepth > MAX_NESTING_DEPTH) {
                throw new ParseDiagnosticException(ErrorCode.PARSE_NESTING_TOO_DEEP,
                    Map.of("limit", String.valueOf(MAX_NESTING_DEPTH)),
                    "not 链过长（> " + MAX_NESTING_DEPTH + "），拒绝以防栈溢出", getLine(), getCharPositionInLine() + 1);
            }
        } else if (type != AsterParser.INDENT
                   && type != AsterParser.DEDENT && type != Token.EOF) {
            // 缩进不打断 not 链（`not\n        not x` 仍是一条链），其余一律清零。
            //
            // ★这里**不列 NEWLINE**：实测它根本到不了本方法——`nextToken()`
            //   对 NEWLINE 提前 return，从不调用 recordMeaningful。
            //   列上去会是一条**永远为真、无法被证伪**的条款：单删它没有任何
            //   用例变红（已实测），读代码的人却会以为它在起作用。
            //   跨行 not 链之所以仍被计入，靠的是 INDENT 豁免——
            //   单删 INDENT 豁免，CrossLineNotTest 立刻变红（已实测）。
            consecutiveNotDepth = 0;
        }
    }

    /**
     * 词法期守卫按记号种类判断类型位置的起止（as / produce 之后是类型，X of 开启泛型参数）。K（ADR 0056 修订 A4 §1.1）
     * 让这些词的其余大小写拼写（As、PRODUCE、Of…）在解析器的关键词位置也算关键词，而它们在词法上仍是 TYPE_IDENT / IDENT；
     * 这里按文本把它们折回关键词种类，守卫才会与解析器同样识别类型位置。名字转义永远是名字（ADR 0064 N3），不折。
     * 折错（同形词作名字）只会让守卫多一次复位或多数一个 X of 帧，不会对合法源码报错：深度上限仍由解析器层守卫兜底。
     */
    private static int caseFoldedKeywordType(Token t) {
        int type = t.getType();
        if ((type != AsterParser.TYPE_IDENT && type != AsterParser.IDENT) || EscapedToken.isEscaped(t) || t.getText() == null) {
            return type;
        }
        return switch (t.getText().toLowerCase(java.util.Locale.ROOT)) {
            case "as" -> AsterParser.AS;
            case "produce" -> AsterParser.PRODUCE;
            case "of" -> AsterParser.OF;
            default -> type;
        };
    }

    /**
     * 嵌套守卫另外按文本认 K-b 的 map / to / and（类型里的 Map 帧与分隔符），与 {@link #caseFoldedKeywordType} 同理：
     * `MAP Text TO …` 与小写拼写同码同位置报 E054。只用于守卫计数，不影响标识符声明列表的判定。
     * （not 链不需要：读作 not 的 Not / NOT 已由 {@link #retypePrefixKeyword} 改成 NOT 记号。）
     */
    private static int nestingGuardType(Token t, int folded) {
        if (folded != AsterParser.TYPE_IDENT && folded != AsterParser.IDENT || EscapedToken.isEscaped(t) || t.getText() == null) {
            return folded;
        }
        return switch (t.getText().toLowerCase(java.util.Locale.ROOT)) {
            case "map" -> AsterParser.MAP;
            case "to" -> AsterParser.TO_WORD;
            case "and" -> AsterParser.AND;
            default -> folded;
        };
    }

    /**
     * 记录上一个有意义 token 的小写文本（用于标记位置判定）。
     * 跳过 NEWLINE/INDENT/DEDENT/EOF/隐藏通道——它们不影响"前驱是否结构关键词"判定。
     */
    private void recordMeaningful(Token t) {
        int type = caseFoldedKeywordType(t);
        trackNestingDepth(nestingGuardType(t, type), t.getText());
        if (type == NEWLINE || type == AsterParser.INDENT || type == AsterParser.DEDENT
                || type == Token.EOF || t.getChannel() == Token.HIDDEN_CHANNEL) {
            if (type == NEWLINE || type == AsterParser.INDENT || type == AsterParser.DEDENT) lineBreakSincePrev = true;
            trackMatchBlocks(type);
            return;
        }
        trackPattern(type);
        trackDeclarationList(t, type);
        prevIsMember = prevMeaningful != null && prevMeaningful.getType() == AsterParser.DOT && !lineBreakSincePrev;
        prevStartsLine = lineBreakSincePrev || prevMeaningful == null;
        prevMeaningful = t;
        lineBreakSincePrev = false;
        String txt = t.getText();
        // 转义名永远是名字（ADR 0064 N3）：不让 `define` / `given` 这样的转义名开启标识符声明列表
        prevMeaningfulLower = txt == null || EscapedToken.isEscaped(t) ? null : txt.toLowerCase(java.util.Locale.ROOT);

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
     * Match 分支块的进出（K-c）：Match 之后的第一个 INDENT 记下分支块的缩进；DEDENT 退到更浅的缩进时出块。
     * 只服务于 {@link #inPattern}：词法器不知道解析上下文，`When Not(a),` 在 Match 分支里是构造器模式，在规则体里
     * （判定糖 `When 条件, 结论.`）是表达式，按所在的块区分。
     */
    private void trackMatchBlocks(int type) {
        if (type == AsterParser.INDENT && matchHeaderPending) {
            matchHeaderPending = false;
            matchCaseIndents.push(indentStack.peek());
        } else if (type == AsterParser.DEDENT) {
            while (!matchCaseIndents.isEmpty() && matchCaseIndents.peek() > indentStack.peek()) {
                matchCaseIndents.pop();
            }
        }
    }

    /** 模式位置的进出（K-c）：Match 分支块里的 When 开启模式，括号外的逗号结束（换行另在 nextToken 里结束）。 */
    private void trackPattern(int type) {
        if (type == AsterParser.MATCH) {
            matchHeaderPending = true;
        } else if (type == AsterParser.DOT) {
            matchHeaderPending = false;
        }
        if (type == AsterParser.WHEN && !matchCaseIndents.isEmpty() && matchCaseIndents.peek().equals(indentStack.peek())) {
            inPattern = true;
            patternParens = 0;
        } else if (inPattern && type == AsterParser.LPAREN) {
            patternParens++;
        } else if (inPattern && type == AsterParser.RPAREN) {
            patternParens--;
        } else if (inPattern && type == AsterParser.COMMA && patternParens <= 0) {
            inPattern = false;
        }
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
            return synthetic(kind, source, t);
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
            || type == AsterParser.ELSE || type == AsterParser.THEN
            // MAX/ATTEMPTS 同列入 structKeywordName（retry 语法 token，标识符位置当软关键字）
            || type == AsterParser.MAX || type == AsterParser.ATTEMPTS;
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
            pending.add(synthetic(type, st.getText(), at));
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
     * 名字转义（ADR 0064 §3）：合法转义改写成带 {@code escaped} 标志的 IDENT / TYPE_IDENT；
     * 其余以反引号开头的写法报 E902（N1a）。在 {@code emit} 这一层处理，所以 {@code nextToken} 与
     * OF 家族判定的预读（{@link #peekNextRealToken}）拿到的都是改写后的 token。
     */
    @Override
    public Token emit() {
        if (_type == ESCAPED_NAME) {
            String raw = getText();
            String name = raw.substring(1, raw.length() - 1);
            EscapedToken t = new EscapedToken(_tokenFactorySourcePair, escapedNameType(name), _channel,
                _tokenStartCharIndex, getCharIndex() - 1);
            t.setText(name);
            t.setLine(_tokenStartLine);
            t.setCharPositionInLine(_tokenStartCharPositionInLine);
            emit(t);
            return t;
        }
        if (_type == ESCAPE_BAD) {
            throw invalidEscape(getText(), _tokenStartLine, _tokenStartCharPositionInLine + 1);
        }
        return super.emit();
    }

    /** 转义名的 token 类：与不转义时同一规则（AsterLexer.g4 TYPE_IDENT / IDENT 的首字符）。 */
    static int escapedNameType(String name) {
        char c = name.charAt(0);
        boolean upper = (c >= 'A' && c <= 'Z') || (c >= '\u00C0' && c <= '\u00D6') || (c >= '\u00D8' && c <= '\u00DE')
            || (c >= '\u0100' && c <= '\u017F') || isCjk(c);
        return upper ? AsterParser.TYPE_IDENT : AsterParser.IDENT;
    }

    /**
     * E902 的分类（ADR 0064 N1a），按优先级：同一行没有闭合的反引号 → unterminated；两个反引号之间
     * 什么都没有 → empty；否则 invalid_char，{@code char} 是第一个不能作标识符首字符（位置 0）或续字符的字符。
     * 位置总是开头的反引号。
     *
     * @param text ESCAPE_BAD 匹配到的原文（含反引号）
     */
    static ParseDiagnosticException invalidEscape(String text, int line, int column) {
        if (text.length() < 2 || text.charAt(text.length() - 1) != '`') {
            return escapeError("unterminated", null, "名字转义缺少闭合的反引号", line, column);
        }
        String content = text.substring(1, text.length() - 1);
        if (content.isEmpty()) {
            return escapeError("empty", null, "名字转义为空", line, column);
        }
        int i = 0;
        while (i < content.length()) {
            int cp = content.codePointAt(i);
            boolean ok = i == 0 ? isEscapeStart(cp) : isEscapeContinue(cp);
            if (!ok) {
                String ch = new String(Character.toChars(cp));
                return escapeError("invalid_char", ch, "名字转义含非法字符 " + ch, line, column);
            }
            i += Character.charCount(cp);
        }
        // ESCAPE_BAD 只在 ESCAPED_NAME 匹配不上时出现，内容逐字合法却走到这里说明两边字符集不一致
        throw new IllegalStateException("名字转义字符集与词法规则不一致: " + text);
    }

    private static ParseDiagnosticException escapeError(String reason, String ch, String message, int line, int column) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("reason", reason);
        if (ch != null) {
            params.put("char", "'" + ch + "'");
        }
        return new ParseDiagnosticException(ErrorCode.PARSE_INVALID_ESCAPE, params, message, line, column);
    }

    /** 标识符首字符（AsterLexer.g4 EscIdentStart）。 */
    static boolean isEscapeStart(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')
            || (cp >= 0x00C0 && cp <= 0x00D6) || (cp >= 0x00D8 && cp <= 0x00DE) || (cp >= 0x0100 && cp <= 0x017F)
            || (cp >= 0x00DF && cp <= 0x00F6) || (cp >= 0x00F8 && cp <= 0x00FF)
            || isCjk(cp) || isDevanagari(cp);
    }

    /** 标识符续字符（AsterLexer.g4 EscIdentContinue，不含私有区标记）。 */
    static boolean isEscapeContinue(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9') || cp == '_'
            || (cp >= 0x00C0 && cp <= 0x017F) || isCjk(cp) || isDevanagari(cp);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF);
    }

    private static boolean isDevanagari(int cp) {
        return (cp >= 0x0900 && cp <= 0x0963) || (cp >= 0x0966 && cp <= 0x097F);
    }

    /**
     * 创建 INDENT token
     */
    private Token createIndent() {
        return synthetic(AsterParser.INDENT, "<INDENT>");
    }

    /**
     * 创建错误 token（替代 RuntimeException，维持解析器继续运行）
     */
    private Token createErrorToken(String message) {
        return synthetic(Token.INVALID_TYPE, "<ERROR: " + message + ">");
    }

    /**
     * 创建 DEDENT token
     */
    private Token createDedent() {
        return synthetic(AsterParser.DEDENT, "<DEDENT>");
    }

    /** 在当前输入位置合成一个零宽 token（缩进标记、错误标记）。 */
    private CommonToken synthetic(int type, String text) {
        int index = _input.index();
        return synthetic(type, text, getLine(), getCharPositionInLine(), index, index - 1);
    }

    /** 合成一个覆盖 {@code at} 原文范围的 token（关键词还原成的标识符、展开出的英文关键词）。 */
    private CommonToken synthetic(int type, String text, Token at) {
        return synthetic(type, text, at.getLine(), at.getCharPositionInLine(), at.getStartIndex(), at.getStopIndex());
    }

    /**
     * 合成 token 必须带本词法器的来源（token source + char stream）：ANTLR 的单 token 补全恢复
     * （{@code DefaultErrorStrategy.getMissingSymbol}）用当前 token 的 {@code getTokenSource().getInputStream()}
     * 构造缺失 token。没有来源时，缺句末标点且紧接文件末尾的源码会在恢复中抛 NullPointerException，
     * 而不是报告语法错误。
     */
    private CommonToken synthetic(int type, String text, int line, int column, int start, int stop) {
        CommonToken t = new CommonToken(_tokenFactorySourcePair, type, Token.DEFAULT_CHANNEL, start, stop);
        t.setText(text);
        t.setLine(line);
        t.setCharPositionInLine(column);
        return t;
    }
}
