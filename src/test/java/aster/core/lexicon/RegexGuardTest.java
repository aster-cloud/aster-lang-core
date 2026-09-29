package aster.core.lexicon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReDoS 防御单元测试。
 */
class RegexGuardTest {

  @Test
  void screenRejectsNestedQuantifierShapes() {
    assertThat(RegexGuard.screen("(a+)+")).isNotEmpty();
    assertThat(RegexGuard.screen("(a*)*")).isNotEmpty();
    assertThat(RegexGuard.screen("(a+)*")).isNotEmpty();
    assertThat(RegexGuard.screen("(.*)+")).isNotEmpty();
    assertThat(RegexGuard.screen("(a{2,})+")).isNotEmpty();
    assertThat(RegexGuard.screen("(a+)+$")).isNotEmpty();
  }

  @Test
  void screenAcceptsNormalPatterns() {
    assertThat(RegexGuard.screen("\\bfoo\\b")).isEmpty();
    assertThat(RegexGuard.screen("ue")).isEmpty();
    assertThat(RegexGuard.screen("(foo|bar)")).isEmpty();
    assertThat(RegexGuard.screen("a+b*")).isEmpty();
    assertThat(RegexGuard.screen("[a-z]{2,4}")).isEmpty();
  }

  @Test
  void screenRejectsOverlongPatterns() {
    String huge = "a".repeat(RegexGuard.MAX_PATTERN_LENGTH + 1);
    assertThat(RegexGuard.screen(huge))
      .anyMatch(e -> e.contains("too long"));
  }

  @Test
  void compileRejectsCatastrophicPattern() {
    assertThatThrownBy(() -> RegexGuard.compile("(a+)+$", 0))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("ReDoS");
  }

  @Test
  void compileAcceptsNormalPattern() {
    Pattern p = RegexGuard.compile("ue", 0);
    assertThat(p.matcher("blue").replaceAll("ü")).isEqualTo("blü");
  }

  /**
   * 灾难性回溯：{@code (.*a){20}$} 对一串不以 {@code a} 结尾的输入会指数级回溯
   * （JDK 25 实测 n=30 约 70s）。该形状用精确计数 {@code {20}} 绕过了静态筛查的
   * “开放量词”启发式，正好验证<b>匹配期看门狗</b>这一层纵深防御能 fail fast。
   * 测试本身设硬上限 10s，看门狗 1.5s 内应中断。
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void matchTimeoutFailsFastOnCatastrophicInput() {
    Pattern evil = Pattern.compile("(.*a){20}$"); // 直接编译，模拟绕过筛查
    String input = "a".repeat(30) + "!";          // 长且不匹配 -> 灾难性回溯
    assertThatThrownBy(() -> RegexGuard.replaceAllWithTimeout(evil, input, "X", 1500))
      .isInstanceOf(RegexGuard.RegexTimeoutException.class)
      .hasMessageContaining("ReDoS");
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void matchWithTimeoutSucceedsForNormalInput() {
    Pattern p = Pattern.compile("ue");
    assertThat(RegexGuard.replaceAllWithTimeout(p, "blue glue", "ü", 1500))
      .isEqualTo("blü glü");
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void shortInputsAreStillGuardedByDeadline() {
    // 看门狗不依赖输入长度：单个"词"也能触发指数回溯，逐词热路径同样受保护。
    Pattern evil = Pattern.compile("(.*a){20}$");
    String word = "a".repeat(28) + "!";
    assertThatThrownBy(() -> RegexGuard.replaceAllWithTimeout(evil, word, "X", 300))
      .isInstanceOf(RegexGuard.RegexTimeoutException.class)
      .hasMessageContaining("300ms");
  }

  @Test
  void hotPathDoesNotSpawnWatchdogThreads() {
    // issue #186：逐词 × 逐规则调用曾为每次调用新建线程池；看门狗改为调用线程上的截止时间，
    // 任何调用都不得再创建线程。
    Pattern p = Pattern.compile("ue");
    for (int i = 0; i < 5000; i++) {
      assertThat(RegexGuard.replaceAllWithTimeout(p, "blue", "ü")).isEqualTo("blü");
    }
    assertThat(Thread.getAllStackTraces().keySet())
      .noneMatch(t -> t.getName().startsWith("aster-regex-watchdog"));
  }
}
