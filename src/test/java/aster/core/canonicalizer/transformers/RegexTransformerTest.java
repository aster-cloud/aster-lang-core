package aster.core.canonicalizer.transformers;

import aster.core.canonicalizer.StringSegmenter;
import aster.core.lexicon.CanonicalizationConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RegexTransformer 安全性测试：构造期拒绝 ReDoS 形状；正常规则仍可用。
 */
class RegexTransformerTest {

  @Test
  void rejectsCatastrophicPatternAtConstruction() {
    assertThatThrownBy(() -> new RegexTransformer("evil", "(a+)+$", "X"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("ReDoS");
  }

  @Test
  void normalRuleStillTransforms() {
    var transformer = new RegexTransformer("umlaut", "ue", "ü");
    var segmenter = new StringSegmenter("\"", "\"");
    var config = CanonicalizationConfig.defaults();
    // 字符串字面量外替换，字符串内保持原样。
    assertThat(transformer.transform("blue \"glue\"", config, segmenter))
      .isEqualTo("blü \"glue\"");
  }
}
