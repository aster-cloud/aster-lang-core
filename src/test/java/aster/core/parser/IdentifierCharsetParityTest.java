package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

/**
 * 标识符字符集的双引擎一致性（2026-08-17 审计修复）。
 *
 * <p>此前 Java 的 {@code LatinExtChar} 收 {@code [Ā-ɏ]}（Extended-A/B），
 * 而 TS 的 {@code isLetter} 只到 {@code 0x017F}（Extended-A）。于是
 * {@code ș}(U+0219) / {@code ț}(U+021B) / {@code ǎ}(U+01CE) 等字符
 * <b>在 Java 合法、在 TS 抛 "Unexpected character"</b>——
 * 同一份源码一个引擎编译得过、另一个直接词法失败。
 *
 * <p>收窄 Java 而非放宽 TS：产品支持的四种语言 en/zh/de/hi 都不需要 Extended-B
 * （德语用 Latin-1 Supplement，Hindi 用天城文）。放宽 TS 会引入一批无人使用
 * 却必须双引擎同步维护的字符。
 */
class IdentifierCharsetParityTest {

  /** 词法能否接受该标识符（用错误监听器判定，不靠异常）。 */
  private boolean lexes(String identifier) {
    String src = "Module m.\n\nRule r given " + identifier + ", produce:\n  Return "
        + identifier + ".\n";
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    lexer.removeErrorListeners();
    final boolean[] failed = {false};
    lexer.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
          int line, int charPositionInLine, String msg, RecognitionException e) {
        failed[0] = true;
      }
    });
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    return !failed[0];
  }

  @Test
  void latinExtendedBIsRejected_matchingTs() {
    // ★这三个字符此前 Java 收、TS 拒 —— 双引擎行为分叉的实证
    assertFalse(lexes("șx"), "ș(U+0219) 属 Extended-B，TS 拒绝，Java 也必须拒绝");
    assertFalse(lexes("țx"), "ț(U+021B) 同上");
    assertFalse(lexes("ǎx"), "ǎ(U+01CE) 同上");
  }

  @Test
  void supportedLanguagesRemainUnaffected() {
    // ★同等重要的一半：收窄不得误伤任何**已支持**的语言。
    //   否则「把范围收成只剩 ASCII」也能让上面那条通过，那是假修复。
    assertTrue(lexes("größe"), "德语 ß/ö 属 Latin-1 Supplement，必须继续接受");
    assertTrue(lexes("über"), "德语 ü 同上");
    assertTrue(lexes("Prüfung"), "德语大写起头标识符同上");
    assertTrue(lexes("राशि"), "天城文（Hindi）必须继续接受");
    assertTrue(lexes("金额"), "中文必须继续接受");
  }

  @Test
  void latinExtendedABoundaryIsStillAccepted() {
    // 边界值：U+0101（Extended-A 内）应接受，确认收窄没有多切
    assertTrue(lexes("āx"), "ā(U+0101) 在 Extended-A 内，两侧都应接受");
  }
}
