package aster.core.lexicon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
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

  @Test
  void replaceRegionsSeesRealContextAcrossRegions() {
    // 区间之外原样保留；MULTILINE ^ 只在真实行首起效，不把区间开头当行首。
    Pattern lineStart = Pattern.compile("^x", Pattern.MULTILINE);
    String input = "x\"x\"x\nx";
    var regions = List.of(new RegexGuard.Region(0, 1), new RegexGuard.Region(4, 7));
    assertThat(RegexGuard.replaceRegions(lineStart, input, regions, "Y")).isEqualTo("Y\"x\"x\nY");
    // lookbehind 透过区间边界看到前文。
    Pattern afterQuote = Pattern.compile("(?<=\")x");
    assertThat(RegexGuard.replaceRegions(afterQuote, input, regions, "Y")).isEqualTo("x\"x\"Y\nx");
  }

  @Test
  void replaceRegionsExpandsReplacementTemplateLikeMatcher() {
    Pattern p = Pattern.compile("(?<word>[a-z]+)'s (\\d)");
    String input = "cat's 1 \"dog's 2\" cow's 3";
    var regions = List.of(new RegexGuard.Region(0, 8), new RegexGuard.Region(16, input.length()));
    assertThat(RegexGuard.replaceRegions(p, input, regions, "${word}.$2\\$\\\\"))
      .isEqualTo("cat.1$\\ \"dog's 2\" cow.3$\\");
    // 与 Matcher 一致：$12 在只有 2 个组时读作 $1 后接字面量 2。
    assertThat(RegexGuard.replaceRegions(p, "cat's 1", List.of(new RegexGuard.Region(0, 7)), "$12"))
      .isEqualTo("cat2");
    assertThat(p.matcher("cat's 1").replaceAll("$12")).isEqualTo("cat2");
    assertThatThrownBy(() -> RegexGuard.replaceRegions(p, "cat's 1", List.of(new RegexGuard.Region(0, 7)), "$3"))
      .isInstanceOf(IndexOutOfBoundsException.class);
  }
}
