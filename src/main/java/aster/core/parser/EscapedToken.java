package aster.core.parser;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;

/**
 * 名字转义 {@code `x`} 产出的名字 token（ADR 0064 §3.2）：类型是 IDENT 或 TYPE_IDENT，文本是去掉反引号的内容，
 * {@code start} / {@code stop} 仍覆盖两个反引号。不另设 token 类型，文法里接受名字的位置不必改；
 * 关键词判定（解析器谓词、AstBuilder 的文本比较、E901）用 {@link #isEscaped(Token)} 排除它（N3）。
 */
public final class EscapedToken extends CommonToken {

    EscapedToken(org.antlr.v4.runtime.misc.Pair<TokenSource, CharStream> source, int type, int channel,
                 int start, int stop) {
        super(source, type, channel, start, stop);
    }

    /** 这个 token 是否来自名字转义。 */
    public static boolean isEscaped(Token t) {
        return t instanceof EscapedToken;
    }
}
