package aster.core.canonicalizer.transformers;

import aster.core.canonicalizer.StringSegmenter;
import aster.core.lexicon.CanonicalizationConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 英语所有格改写：链式所有格一次到位、不跨行、不进字符串字面量。
 */
class EnglishPossessiveTransformerTest {

  private final StringSegmenter segmenter = new StringSegmenter("\"", "\"");
  private final CanonicalizationConfig config = CanonicalizationConfig.defaults();

  private String transform(String source) {
    return EnglishPossessiveTransformer.INSTANCE.transform(source, config, segmenter);
  }

  @Test
  void simplePossessiveBecomesMemberAccess() {
    assertThat(transform("Let a be driver's age.")).isEqualTo("Let a be driver.age.");
    assertThat(transform("Let a be _x's y.")).isEqualTo("Let a be _x.y.");
  }

  @Test
  void chainedPossessiveIsRewrittenInOnePass() {
    // issue #191：残留的 's 会撞上无撇号的词法规则，必须一次替换即达不动点。
    String once = transform("Let c be driver's car's color.");
    assertThat(once).isEqualTo("Let c be driver.car.color.");
    assertThat(transform(once)).isEqualTo(once);
    assertThat(transform("Let d be a's b's c's d."))
      .isEqualTo("Let d be a.b.c.d.");
  }

  @Test
  void possessiveDoesNotCrossLineBreak() {
    // issue #190：行尾所有格不能与下一行行首标识符拼成成员访问。
    String source = "Let a be driver's\nage";
    assertThat(transform(source)).isEqualTo(source);
    assertThat(transform("Let a be driver's\r\nage")).isEqualTo("Let a be driver's\r\nage");
    assertThat(transform("Let a be driver's\tage")).isEqualTo("Let a be driver.age");
  }

  @Test
  void stringLiteralsAreUntouched() {
    assertThat(transform("Let s be \"driver's car's color\"."))
      .isEqualTo("Let s be \"driver's car's color\".");
  }

  @Test
  void trailingPossessiveWithoutFollowingWordIsLeftAlone() {
    assertThat(transform("driver's ")).isEqualTo("driver's ");
    assertThat(transform("driver's 5")).isEqualTo("driver's 5");
  }
}
