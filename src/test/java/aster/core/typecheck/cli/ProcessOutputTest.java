package aster.core.typecheck.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 子进程输出捕获：stderr 写满管道时不得与 stdout 读取互相等待（issue #184）。
 */
class ProcessOutputTest {

  private static final int STDERR_BYTES = 512 * 1024; // 远大于 64KB 管道缓冲

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void drainsStderrConcurrentlySoLargeStderrDoesNotDeadlock() throws Exception {
    // 子进程先把 stderr 灌满再写 stdout：顺序读 stdout 的实现会在此永久挂起。
    var process = new ProcessBuilder("sh", "-c",
      "head -c " + STDERR_BYTES + " /dev/zero | tr '\\0' x >&2; printf '{\"ok\":true}'; exit 3")
      .start();

    ProcessOutput output = ProcessOutput.capture(process, Duration.ofSeconds(20));

    assertThat(new String(output.stdout(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
    assertThat(output.stderr()).hasSize(STDERR_BYTES);
    assertThat(output.exitCode()).isEqualTo(3);
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void killsChildAndFailsWhenItOutlivesTimeout() throws Exception {
    var process = new ProcessBuilder("sh", "-c", "sleep 30").start();

    assertThatThrownBy(() -> ProcessOutput.capture(process, Duration.ofMillis(300)))
      .isInstanceOf(IOException.class)
      .hasMessageContaining("未退出");
    process.waitFor(5, TimeUnit.SECONDS);
    assertThat(process.isAlive()).isFalse();
  }
}
