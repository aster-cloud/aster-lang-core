package aster.core.typecheck.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 子进程的完整输出与退出码。
 * <p>
 * ★stdout 与 stderr 必须<b>同时</b>排空：管道缓冲区只有约 64KB，顺序先读满 stdout 再读 stderr
 * 时，子进程一旦把 stderr 写满就会阻塞在 write 上，而父进程还在等 stdout 的 EOF——两边互相等待，
 * CLI 永久挂起。stdout 是待解析的 Core IR JSON，也不能用 redirectErrorStream 把两路混在一起。
 *
 * @param stdout   标准输出全部字节
 * @param stderr   标准错误全部字节
 * @param exitCode 退出码
 */
record ProcessOutput(byte[] stdout, byte[] stderr, int exitCode) {

  /**
   * 并行排空两路输出并等待子进程退出；超过 {@code timeout} 则强制终止子进程。
   *
   * @throws IOException          子进程超时未退出（已被强制终止），或读取输出失败
   * @throws InterruptedException 等待期间被中断
   */
  static ProcessOutput capture(Process process, Duration timeout) throws IOException, InterruptedException {
    CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
    CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      throw new IOException("子进程超过 " + timeout.toSeconds() + "s 未退出，已强制终止");
    }
    return new ProcessOutput(await(stdout, timeout), await(stderr, timeout), process.exitValue());
  }

  private static byte[] readAll(InputStream stream) {
    try (stream) {
      return stream.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 进程已退出，管道即将关闭；这里的超时只兜底「孙进程仍持有管道写端」的情况。 */
  private static byte[] await(CompletableFuture<byte[]> output, Duration timeout)
      throws IOException, InterruptedException {
    try {
      return output.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new IOException("子进程已退出但输出管道超过 " + timeout.toSeconds() + "s 未关闭", e);
    } catch (ExecutionException e) {
      throw new IOException("读取子进程输出失败: " + e.getCause().getMessage(), e.getCause());
    }
  }
}
