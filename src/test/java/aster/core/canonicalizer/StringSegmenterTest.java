package aster.core.canonicalizer;

import aster.core.canonicalizer.transformers.ResultIsTransformer;
import aster.core.canonicalizer.transformers.SetToTransformer;
import aster.core.lexicon.CanonicalizationConfig;
import aster.core.lexicon.RegexGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字符串外替换必须以整个源码为上下文：锚点与 lookbehind 不能把字符串字面量之后当成行首。
 */
class StringSegmenterTest {

  private final StringSegmenter segmenter = new StringSegmenter("\"", "\"");
  private final CanonicalizationConfig config = CanonicalizationConfig.defaults();

  @Test
  void multilineAnchorDoesNotFireAfterStringLiteralOnSameLine() {
    // issue #189：逐段匹配时 "x" 之后的片段被当成新输入，^ 在行中触发。
    Pattern lineStart = Pattern.compile("^Set", Pattern.MULTILINE);
    assertThat(segmenter.replaceOutsideStrings("Let s be \"x\" Set y", lineStart, "LET"))
      .isEqualTo("Let s be \"x\" Set y");
    assertThat(segmenter.replaceOutsideStrings("Let s be \"x\"\nSet y", lineStart, "LET"))
      .isEqualTo("Let s be \"x\"\nLET y");
    Pattern lineEnd = Pattern.compile("y$", Pattern.MULTILINE);
    assertThat(segmenter.replaceOutsideStrings("y\"s\"y\ny", lineEnd, "Z"))
      .isEqualTo("y\"s\"Z\nZ");
  }

  @Test
  void setToAndResultIsAreNotRewrittenMidLineAfterString() {
    String setMidLine = "Let s be \"x\". Set y to 5.";
    assertThat(SetToTransformer.INSTANCE.transform(setMidLine, config, segmenter)).isEqualTo(setMidLine);
    assertThat(SetToTransformer.INSTANCE.transform("Let s be \"x\".\n  Set y to 5.", config, segmenter))
      .isEqualTo("Let s be \"x\".\n  Let y be 5.");

    String resultMidLine = "Let s be \"x\". The result is 7.";
    assertThat(ResultIsTransformer.INSTANCE.transform(resultMidLine, config, segmenter)).isEqualTo(resultMidLine);
    assertThat(ResultIsTransformer.INSTANCE.transform("Let s be \"x\".\nThe result is 7.", config, segmenter))
      .isEqualTo("Let s be \"x\".\nReturn 7.");
  }

  @Test
  void stringLiteralsStayUntouchedOnBothPaths() {
    Pattern ue = Pattern.compile("ue");
    assertThat(segmenter.replaceOutsideStrings("blue \"glue\" true", ue, "ü"))
      .isEqualTo("blü \"glue\" trü");
    assertThat(segmenter.replaceOutsideStrings("blue \"glue\" true", ue, "ü", true))
      .isEqualTo("blü \"glue\" trü");
    // 匹配不能跨越字符串边界：'s 在引号内，前瞻/后顾都不得把引号两侧拼起来。
    Pattern possessive = Pattern.compile("([a-z]+)'s ([a-z]+)");
    assertThat(segmenter.replaceOutsideStrings("driver\"'s \"age", possessive, "$1.$2"))
      .isEqualTo("driver\"'s \"age");
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void guardedPathStillTimesOutOnCatastrophicPattern() {
    Pattern evil = Pattern.compile("(.*a){20}$");
    String source = "\"s\" " + "a".repeat(30) + "!";
    assertThatThrownBy(() -> segmenter.replaceOutsideStrings(source, evil, "X", true))
      .isInstanceOf(RegexGuard.RegexTimeoutException.class);
  }
}
