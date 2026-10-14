package aster.core.cedar;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CedarTextTest {

  @Test
  void quoteEscapes() {
    assertThat(CedarText.quote("a\"b\\c\nd")).isEqualTo("\"a\\\"b\\\\c\\nd\"");
    assertThat(CedarText.quote("审批人")).isEqualTo("\"审批人\"");
    assertThat(CedarText.quote("x\u0001")).isEqualTo("\"x\\u{1}\"");
    assertThat(CedarText.entity("Action", "read")).isEqualTo("Aster::Action::\"read\"");
  }

  @Test
  void quoteEscapesRemainingControls() {
    assertThat(CedarText.quote("\r\t\0")).isEqualTo("\"\\r\\t\\0\"");
    assertThat(CedarText.quote("\u001f\u007f")).isEqualTo("\"\\u{1f}\\u{7f}\"");
    assertThat(CedarText.quote("")).isEqualTo("\"\"");
  }

  @Test
  void entityEscapesId() {
    assertThat(CedarText.entity("Principal", "a\"b")).isEqualTo("Aster::Principal::\"a\\\"b\"");
  }
}
