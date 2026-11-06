package aster.core.parser;

import aster.core.typecheck.ErrorCode;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.FailedPredicateException;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ANTLR 语法错误 → 共享码表 E050–E069 的映射（ADR 0051 §16 R56 的 core 侧实现）。
 *
 * <p>映射清单（与 ADR §16.1 附件一致，按 ANTLR 事件逐一对应）：
 * <ul>
 *   <li>词法器 {@code token recognition error at: 'x'} → E050 {@code {char}}</li>
 *   <li>{@code extraneous input} 且多余 token 为 INDENT → E052</li>
 *   <li>{@code extraneous input 'x'} → E061 {@code {token}}（原 syntax-diagnostics 的 extraneous 句型并入）</li>
 *   <li>{@code missing '.' at} 换行 / DEDENT / EOF → E059 {@code {token}}</li>
 *   <li>{@code missing 'x' at 'y'} → E060 {@code {token, before}}</li>
 *   <li>{@code mismatched input 'y' expecting S}：S 为空 → E061；S 只有一项 → E055 {@code {token}}；
 *       否则 E062 {@code {token, expected}}</li>
 *   <li>{@code no viable alternative} → E063 {@code {token}}；前一 token 为 {@code is} 且出错 token 以符号开头时
 *       带 {@code hint=hint.isBeforeSymbol}</li>
 *   <li>{@code rule … failed predicate} → E063，{@code hint=hint.unknownOperator}（落在 {@code is} 后的符号上时
 *       为 {@code hint.isBeforeSymbol}）</li>
 *   <li>其它（ANTLR 未来新增的消息形态）→ E063 兜底</li>
 * </ul>
 * 参数取值约定见 {@link ParseDiagnostic}。本类只做分类，不渲染本地化文案；{@code message} 保留 ANTLR 原文。
 */
public final class SyntaxErrorCollector extends BaseErrorListener {

    private static final Pattern TOKEN_RECOGNITION = Pattern.compile("token recognition error at: '(.*)'", Pattern.DOTALL);
    private static final Pattern MISSING = Pattern.compile("missing '?(.+?)'? at '(.+?)'", Pattern.DOTALL);
    private static final Set<String> LAYOUT = Set.of("NEWLINE", "INDENT", "DEDENT");
    private static final Map<String, String> ABSTRACT_TOKENS = Map.of(
        "IDENT", "token.identifier",
        "TYPE_IDENT", "token.typeName",
        "INT_LITERAL", "token.integer",
        "LONG_LITERAL", "token.integer",
        "FLOAT_LITERAL", "token.number",
        "DECIMAL_LITERAL", "token.number",
        "STRING_LITERAL", "token.string",
        "BOOL_LITERAL", "token.boolean");

    private final List<ParseDiagnostic> diagnostics = new ArrayList<>();

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                            String msg, RecognitionException e) {
        Token offending = offendingSymbol instanceof Token t ? t : null;
        Classified c = classify(recognizer, offending, msg, e);
        diagnostics.add(new ParseDiagnostic(c.code(), line, charPositionInLine + 1, c.params(), msg));
    }

    /** 按产生顺序的全部诊断（ANTLR 会级联报错，第一条通常是根因）。 */
    public List<ParseDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }

    private record Classified(ErrorCode code, Map<String, String> params) {}

    private static Classified of(ErrorCode code, String... kv) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        return new Classified(code, params);
    }

    static Classified classify(Recognizer<?, ?> recognizer, Token offending, String msg, RecognitionException e) {
        String text = msg == null ? "" : msg;
        if (recognizer instanceof Lexer) {
            Matcher m = TOKEN_RECOGNITION.matcher(text);
            String bad = m.find() ? m.group(1) : "";
            // 以引号开头却识别失败的只能是没闭合的字符串字面量（与 ts 词法器 L002 同义）
            if (bad.startsWith("\"") || bad.startsWith("「") || bad.startsWith("“")) {
                return of(ErrorCode.PARSE_UNTERMINATED_STRING);
            }
            return of(ErrorCode.PARSE_UNEXPECTED_CHARACTER, "char", bad.isEmpty() ? "token.unknown" : quote(bad));
        }
        if (AsterParser.EXPECTED_SET_TO_IN_CONSTRUCTION.equals(text)) {
            return of(ErrorCode.PARSE_EXPECTED_IN, "token", "kw.set", "context", "token.construction");
        }
        if (isIndentationError(offending)) {
            return of(ErrorCode.PARSE_INDENTATION);
        }
        Vocabulary vocab = recognizer == null ? null : recognizer.getVocabulary();
        if (e instanceof FailedPredicateException || text.contains("failed predicate")) {
            // 谓词失败落在 `is` 之后的符号上时，根因是「is 后直接跟符号」，比「未知比较词」更具体
            String hint = precededByIs(recognizer, offending) ? "hint.isBeforeSymbol" : "hint.unknownOperator";
            return of(ErrorCode.PARSE_NO_VIABLE, "token", param(vocab, offending), "hint", hint);
        }
        if (e instanceof NoViableAltException || text.startsWith("no viable alternative")) {
            return precededByIs(recognizer, offending)
                ? of(ErrorCode.PARSE_NO_VIABLE, "token", param(vocab, offending), "hint", "hint.isBeforeSymbol")
                : of(ErrorCode.PARSE_NO_VIABLE, "token", param(vocab, offending));
        }
        if (text.startsWith("extraneous input")) {
            if (offending != null && "INDENT".equals(symbolic(vocab, offending.getType()))) {
                return of(ErrorCode.PARSE_INDENTATION);
            }
            return of(ErrorCode.PARSE_UNEXPECTED_TOKEN, "token", param(vocab, offending));
        }
        Matcher missing = MISSING.matcher(text);
        if (text.startsWith("missing") && missing.find()) {
            String want = expectedParam(vocab, missing.group(1));
            if (".".equals(missing.group(1)) && isLineEnd(vocab, offending)) {
                return of(ErrorCode.PARSE_EXPECTED_STATEMENT_END, "token", want);
            }
            return of(ErrorCode.PARSE_EXPECTED_BEFORE, "token", want, "before", param(vocab, offending));
        }
        if (e instanceof InputMismatchException || text.startsWith("mismatched input")) {
            List<String> expected = expectedSet(recognizer, vocab, e);
            if (expected.isEmpty()) {
                return of(ErrorCode.PARSE_UNEXPECTED_TOKEN, "token", param(vocab, offending));
            }
            if (expected.size() == 1) {
                return of(ErrorCode.PARSE_EXPECTED, "token", expected.get(0));
            }
            return of(ErrorCode.PARSE_UNEXPECTED_EXPECTING,
                "token", param(vocab, offending), "expected", String.join(" | ", expected));
        }
        return of(ErrorCode.PARSE_NO_VIABLE, "token", param(vocab, offending));
    }

    /**
     * {@link AsterCustomLexer} 把非法缩进 / 不一致的回退发成文本以 {@code <ERROR: } 开头的错误 token，
     * 语法层随后报 extraneous / mismatched；这里按 token 文本识别为缩进错误 E052。
     */
    private static boolean isIndentationError(Token t) {
        String s = t == null ? null : t.getText();
        return s != null && s.startsWith("<ERROR: ") && (s.contains("indentation") || s.contains("dedent"));
    }

    /** 出错 token 的前一个默认通道 token 是 {@code is}，且出错 token 以比较符号开头。 */
    private static boolean precededByIs(Recognizer<?, ?> recognizer, Token bad) {
        if (bad == null || bad.getText() == null || bad.getText().isEmpty()
                || "<>!=".indexOf(bad.getText().charAt(0)) < 0 || !(recognizer instanceof Parser parser)) {
            return false;
        }
        TokenStream stream = parser.getInputStream();
        for (int i = bad.getTokenIndex() - 1; stream != null && i >= 0; i--) {
            Token prev = stream.get(i);
            if (prev.getChannel() == Token.DEFAULT_CHANNEL) {
                return "is".equalsIgnoreCase(prev.getText());
            }
        }
        return false;
    }

    private static boolean isLineEnd(Vocabulary vocab, Token t) {
        if (t == null || t.getType() == Token.EOF) {
            return true;
        }
        String name = symbolic(vocab, t.getType());
        return "NEWLINE".equals(name) || "DEDENT".equals(name);
    }

    private static List<String> expectedSet(Recognizer<?, ?> recognizer, Vocabulary vocab, RecognitionException e) {
        IntervalSet set = e != null ? e.getExpectedTokens()
            : recognizer instanceof Parser p ? p.getExpectedTokens() : null;
        List<String> out = new ArrayList<>();
        if (set == null) {
            return out;
        }
        for (int type : set.toList()) {
            String p = type == Token.EOF ? "token.eof" : typeParam(vocab, type);
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 实际出现的 token → 参数：布局 / EOF 为 token.*，关键词为 kw.*，其余为带引号的源码文本。 */
    static String param(Vocabulary vocab, Token t) {
        if (t == null) {
            return "token.unknown";
        }
        if (t.getType() == Token.EOF) {
            return "token.eof";
        }
        String name = symbolic(vocab, t.getType());
        if (name != null && LAYOUT.contains(name)) {
            return "token." + name.toLowerCase();
        }
        if (isKeyword(name)) {
            return "kw." + t.getText().toLowerCase();
        }
        return quote(t.getText());
    }

    /** 期望的 token 类型 → 参数：抽象 token 为 token.*，标点为带引号字面量，关键词为 kw.*。 */
    private static String typeParam(Vocabulary vocab, int type) {
        String name = symbolic(vocab, type);
        if (name != null && LAYOUT.contains(name)) {
            return "token." + name.toLowerCase();
        }
        if (name != null && ABSTRACT_TOKENS.containsKey(name)) {
            return ABSTRACT_TOKENS.get(name);
        }
        String literal = vocab == null ? null : vocab.getLiteralName(type);
        if (isKeyword(name)) {
            return "kw." + name.toLowerCase().replace('_', ' ');
        }
        if (literal != null) {
            return literal;
        }
        return "token." + (name == null ? "unknown" : name.toLowerCase());
    }

    /** missing 'X' 的 X 来自 ANTLR 字面量名或符号名。 */
    private static String expectedParam(Vocabulary vocab, String raw) {
        if (vocab != null) {
            for (int type = 1; type <= vocab.getMaxTokenType(); type++) {
                if (raw.equals(vocab.getSymbolicName(type)) || ("'" + raw + "'").equals(vocab.getLiteralName(type))) {
                    return typeParam(vocab, type);
                }
            }
        }
        return quote(raw);
    }

    private static boolean isKeyword(String symbolicName) {
        if (symbolicName == null || LAYOUT.contains(symbolicName) || ABSTRACT_TOKENS.containsKey(symbolicName)) {
            return false;
        }
        return symbolicName.chars().allMatch(ch -> Character.isUpperCase(ch) || ch == '_')
            && !Set.of("DOT", "COLON", "COMMA", "LPAREN", "RPAREN", "LBRACKET", "RBRACKET", "EQ", "LTE", "GTE",
                "NEQ", "EQUALS", "PLUS", "STAR", "MINUS", "SLASH", "LT", "GT", "QUESTION", "AT", "ARROW",
                "NULL_LITERAL", "COMMENT", "WS").contains(symbolicName);
    }

    private static String symbolic(Vocabulary vocab, int type) {
        return vocab == null ? null : vocab.getSymbolicName(type);
    }

    private static String quote(String s) {
        return "'" + (s == null ? "" : s.replace("\n", "\\n")) + "'";
    }
}
