package aster.core.lexer;

import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.PunctuationConfig;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.util.StringEscapes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CNL 词法分析器
 * <p>
 * 将规范化的 CNL 源代码转换为 Token 流。这是 Aster 编译管道的第二步。
 * <p>
 * <b>功能</b>：
 * <ul>
 *   <li>识别关键字、标识符、字面量、运算符和标点符号</li>
 *   <li>处理缩进敏感的语法（INDENT/DEDENT token）</li>
 *   <li>跟踪每个 token 的位置信息（行号和列号）</li>
 *   <li>支持注释的 trivia 通道分类（inline 或 standalone）</li>
 *   <li>支持多语言 CNL 词法（通过 Lexicon 配置）</li>
 * </ul>
 * <p>
 * <b>缩进规则</b>：
 * <ul>
 *   <li>Aster 使用 2 空格缩进</li>
 *   <li>缩进必须是偶数个空格</li>
 *   <li>缩进变化会生成 INDENT 或 DEDENT token</li>
 * </ul>
 *
 * @see <a href="https://github.com/anthropics/aster-lang">Aster Lang 文档</a>
 */
public final class Lexer {

    private final String input;
    private int index;
    private int line;
    private int col;
    private final List<Token> tokens;
    private final Deque<Integer> indentStack;
    private final Lexicon lexicon;
    private final Map<String, SemanticTokenKind> keywordIndex;
    private final PunctuationConfig punctuation;

    /**
     * 使用默认词法表（en-US）创建词法分析器
     *
     * @param input 规范化后的 CNL 源代码
     */
    public Lexer(String input) {
        this(input, LexiconRegistry.getInstance().getDefault());
    }

    /**
     * 使用指定词法表创建词法分析器
     *
     * @param input   规范化后的 CNL 源代码
     * @param lexicon 词法表（定义关键词和标点符号）
     */
    public Lexer(String input, Lexicon lexicon) {
        this.input = input;
        this.index = 0;
        this.line = 1;
        this.col = 1;
        this.tokens = new ArrayList<>();
        this.indentStack = new ArrayDeque<>();
        this.indentStack.push(0);
        this.lexicon = lexicon;
        this.keywordIndex = lexicon.buildKeywordIndex();
        this.punctuation = lexicon.getPunctuation();
    }

    /**
     * 对规范化的 CNL 源代码进行词法分析，生成 Token 流（使用默认英文词法表）
     *
     * @param source 规范化后的 CNL 源代码（应先通过 Canonicalizer 处理）
     * @return Token 列表
     * @throws LexerException 当遇到非法字符或缩进错误时抛出
     */
    public static List<Token> lex(String source) {
        return lex(source, LexiconRegistry.getInstance().getDefault());
    }

    /**
     * 对规范化的 CNL 源代码进行词法分析，生成 Token 流
     *
     * @param source  规范化后的 CNL 源代码（应先通过 Canonicalizer 处理）
     * @param lexicon 词法表（定义关键词和标点符号）
     * @return Token 列表
     * @throws LexerException 当遇到非法字符或缩进错误时抛出
     */
    public static List<Token> lex(String source, Lexicon lexicon) {
        Lexer lexer = new Lexer(source, lexicon);
        return lexer.tokenize();
    }

    /**
     * 获取当前使用的词法表
     *
     * @return 词法表实例
     */
    public Lexicon getLexicon() {
        return lexicon;
    }

    private List<Token> tokenize() {
        // Skip UTF-8 BOM if present
        if (input.length() > 0 && input.charAt(0) == 0xFEFF) {
            index++;
            col++;
        }

        while (!isAtEnd()) {
            char ch = peek();

            // Line comments: '//' or '#'
            if (ch == '#') {
                emitCommentToken(1);
                continue;
            }
            if (ch == '/' && peekNext() == '/') {
                emitCommentToken(2);
                continue;
            }
            // Division operator (must come after '//' comment check)
            if (ch == '/') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.SLASH, "/", start, null);
                continue;
            }

            // Newline + indentation
            if (ch == '\n' || ch == '\r') {
                handleNewline();
                continue;
            }

            // Whitespace
            if (ch == ' ' || ch == '\t') {
                next();
                continue;
            }

            // Punctuation - 需要先保存位置再调用 next()
            // 句号（英文或中文）
            if (ch == '.' || ch == '。') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.DOT, String.valueOf(ch), start, null);
                continue;
            }
            // 冒号（英文或中文）
            if (ch == ':' || ch == '：') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.COLON, String.valueOf(ch), start, null);
                continue;
            }
            // 逗号（英文或中文顿号）
            if (ch == ',' || ch == '，' || ch == '、') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.COMMA, String.valueOf(ch), start, null);
                continue;
            }
            if (ch == '(') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.LPAREN, "(", start, null);
                continue;
            }
            if (ch == ')') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.RPAREN, ")", start, null);
                continue;
            }
            // 方括号或中文标记【】
            if (ch == '[' || ch == '【') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.LBRACKET, String.valueOf(ch), start, null);
                continue;
            }
            if (ch == ']' || ch == '】') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.RBRACKET, String.valueOf(ch), start, null);
                continue;
            }
            if (ch == '!') {
                Position start = new Position(line, col);
                next();
                if (peek() == '=') {
                    next();
                    push(TokenKind.NEQ, "!=", start, null);
                } else {
                    throw LexerException.unexpectedCharacter(ch, new Position(line, col));
                }
                continue;
            }
            if (ch == '=') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.EQUALS, "=", start, null);
                continue;
            }
            if (ch == '+') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.PLUS, "+", start, null);
                continue;
            }
            if (ch == '*') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.STAR, "*", start, null);
                continue;
            }
            if (ch == '?') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.QUESTION, "?", start, null);
                continue;
            }
            if (ch == '@') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.AT, "@", start, null);
                continue;
            }
            if (ch == '-') {
                Position start = new Position(line, col);
                next();
                push(TokenKind.MINUS, "-", start, null);
                continue;
            }
            if (ch == '<') {
                Position start = new Position(line, col);
                next();
                if (peek() == '=') {
                    next();
                    push(TokenKind.LTE, "<=", start, null);
                } else {
                    push(TokenKind.LT, "<", start, null);
                }
                continue;
            }
            if (ch == '>') {
                Position start = new Position(line, col);
                next();
                if (peek() == '=') {
                    next();
                    push(TokenKind.GTE, ">=", start, null);
                } else {
                    push(TokenKind.GT, ">", start, null);
                }
                continue;
            }

            // String literal（支持英文引号和中文直角引号）
            if (ch == '"' || ch == '「') {
                scanString(ch);
                continue;
            }

            // Identifiers / keywords
            if (isLetter(ch)) {
                scanIdentifierOrKeyword();
                continue;
            }

            // Numbers
            if (isDigit(ch)) {
                scanNumber();
                continue;
            }

            throw LexerException.unexpectedCharacter(ch, new Position(line, col));
        }

        // Close indentation stack
        while (indentStack.size() > 1) {
            indentStack.pop();
            push(TokenKind.DEDENT, null);
        }

        push(TokenKind.EOF, null);
        return tokens;
    }

    // ============================================================
    // 字符操作方法
    // ============================================================

    private boolean isAtEnd() {
        return index >= input.length();
    }

    private char peek() {
        return isAtEnd() ? '\0' : input.charAt(index);
    }

    private char peekNext() {
        return index + 1 >= input.length() ? '\0' : input.charAt(index + 1);
    }

    private char next() {
        if (isAtEnd()) return '\0';
        char ch = input.charAt(index++);
        if (ch == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        return ch;
    }

    private boolean isLetter(char ch) {
        // 支持 ASCII 字母
        if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) {
            return true;
        }
        // 支持 Unicode 字母（中文、日文等）
        return Character.isLetter(ch);
    }

    /**
     * 判断字符是否为标识符的有效字符（字母、数字、下划线或 Unicode 字母）
     */
    private boolean isIdentifierChar(char ch) {
        return isLetter(ch) || isDigit(ch) || ch == '_';
    }

    private boolean isDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }

    private boolean isLineBreak(char ch) {
        return ch == '\n' || ch == '\r';
    }

    // ============================================================
    // Token 生成方法
    // ============================================================

    private void push(TokenKind kind, Object value) {
        push(kind, value, new Position(line, col), null);
    }

    private void push(TokenKind kind, Object value, Position start, String channel) {
        Token token = new Token(kind, value, start, new Position(line, col), channel);
        tokens.add(token);
    }

    // ============================================================
    // 缩进处理
    // ============================================================

    private void handleNewline() {
        // 保存位置在消费换行符之前
        Position start = new Position(line, col);

        char ch = peek();
        if (ch == '\r') {
            next();
            if (peek() == '\n') {
                next();
            }
        } else {
            next();
        }

        push(TokenKind.NEWLINE, null, start, null);

        // Measure indentation
        int spaces = 0;
        int k = index;
        while (k < input.length() && input.charAt(k) == ' ') {
            spaces++;
            k++;
        }

        // Skip blank lines
        if (k >= input.length() || input.charAt(k) == '\n') {
            index = k;
            return;
        }

        // Skip comment lines
        if (k < input.length() &&
            (input.charAt(k) == '#' ||
             (input.charAt(k) == '/' && k + 1 < input.length() && input.charAt(k + 1) == '/'))) {
            index = k;
            col += spaces;
            return;
        }

        emitIndentDedent(spaces);
        index = k;
        col += spaces;
    }

    private void emitIndentDedent(int spaces) {
        int last = indentStack.peek();

        if (spaces == last) {
            return;
        }

        if (spaces % 2 != 0) {
            throw LexerException.invalidIndentation(new Position(line, col));
        }

        if (spaces > last) {
            indentStack.push(spaces);
            push(TokenKind.INDENT, null);
        } else {
            while (!indentStack.isEmpty() && spaces < indentStack.peek()) {
                indentStack.pop();
                push(TokenKind.DEDENT, null);
            }
            if (indentStack.isEmpty() || indentStack.peek() != spaces) {
                throw LexerException.inconsistentDedent(new Position(line, col));
            }
        }
    }

    // ============================================================
    // 注释处理
    // ============================================================

    private void emitCommentToken(int prefixLength) {
        Position start = new Position(line, col);
        StringBuilder raw = new StringBuilder();

        for (int j = 0; j < prefixLength; j++) {
            raw.append(next());
        }

        while (!isAtEnd() && !isLineBreak(peek())) {
            raw.append(next());
        }

        String body = raw.substring(prefixLength).replaceFirst("^\\s*", "");
        Token prev = findPrevSignificantToken();
        String trivia = (prev != null && prev.end().line() == start.line()) ? "inline" : "standalone";

        CommentValue commentValue = new CommentValue(raw.toString(), body, trivia);
        push(TokenKind.COMMENT, commentValue, start, "trivia");
    }

    private Token findPrevSignificantToken() {
        for (int idx = tokens.size() - 1; idx >= 0; idx--) {
            Token token = tokens.get(idx);
            if ("trivia".equals(token.channel())) {
                continue;
            }
            if (token.kind() == TokenKind.NEWLINE ||
                token.kind() == TokenKind.INDENT ||
                token.kind() == TokenKind.DEDENT) {
                continue;
            }
            return token;
        }
        return null;
    }

    // ============================================================
    // 字符串字面量扫描
    // ============================================================

    /**
     * 扫描字符串字面量
     *
     * @param openQuote 开始引号字符（" 或 「）
     */
    private void scanString(char openQuote) {
        Position start = new Position(line, col);
        next(); // opening quote

        // 确定对应的闭合引号
        char closeQuote = (openQuote == '「') ? '」' : '"';

        int literalStart = index;
        while (!isAtEnd() && peek() != closeQuote) {
            if (peek() == '\\') {
                next(); // consume backslash
                if (isAtEnd()) {
                    throw LexerException.unterminatedString(start);
                }
                next(); // consume escaped char
            } else {
                next();
            }
        }

        if (isAtEnd() || peek() != closeQuote) {
            throw LexerException.unterminatedString(start);
        }

        String raw = input.substring(literalStart, index);
        String value;
        try {
            value = StringEscapes.unescape(raw);
        } catch (IllegalArgumentException ex) {
            throw LexerException.invalidEscape(ex.getMessage(), start);
        }

        next(); // closing quote
        push(TokenKind.STRING, value, start, null);
    }

    // ============================================================
    // 标识符和关键字扫描
    // ============================================================

    private void scanIdentifierOrKeyword() {
        Position start = new Position(line, col);
        StringBuilder word = new StringBuilder();

        while (isIdentifierChar(peek())) {
            word.append(next());
        }

        String text = word.toString();
        String lower = text.toLowerCase();

        // 检查是否为词法表中的关键词（布尔值、空值等）
        Optional<SemanticTokenKind> semanticKind = lexicon.findSemanticTokenKind(text);
        if (semanticKind.isPresent()) {
            SemanticTokenKind kind = semanticKind.get();

            // 处理布尔字面量
            if (kind == SemanticTokenKind.TRUE) {
                push(TokenKind.BOOL, true, start, null);
                return;
            }
            if (kind == SemanticTokenKind.FALSE) {
                push(TokenKind.BOOL, false, start, null);
                return;
            }
            // 处理空值字面量
            if (kind == SemanticTokenKind.NULL) {
                push(TokenKind.NULL, null, start, null);
                return;
            }
        }

        // 类型标识符（首字母大写）或普通标识符
        if (!text.isEmpty() && Character.isUpperCase(text.charAt(0))) {
            push(TokenKind.TYPE_IDENT, text, start, null);
        } else {
            push(TokenKind.IDENT, text, start, null);
        }
    }

    // ============================================================
    // 数字字面量扫描
    // ============================================================

    private void scanNumber() {
        Position start = new Position(line, col);
        StringBuilder num = new StringBuilder();

        while (isDigit(peek())) {
            num.append(next());
        }

        // Look for decimal part
        if (peek() == '.' && index + 1 < input.length() && isDigit(input.charAt(index + 1))) {
            num.append(next()); // '.'
            while (isDigit(peek())) {
                num.append(next());
            }
            double val = Double.parseDouble(num.toString());
            push(TokenKind.FLOAT, val, start, null);
            return;
        }

        // Look for long suffix 'L' or 'l'
        if (Character.toLowerCase(peek()) == 'l') {
            next();
            long val = Long.parseLong(num.toString());
            push(TokenKind.LONG, val, start, null);
            return;
        }

        int val = Integer.parseInt(num.toString());
        push(TokenKind.INT, val, start, null);
    }
}
