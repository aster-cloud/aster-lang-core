package aster.core.lexicon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 语言包加载失败必须留下线索（issue aster-lang-core#130 正文）。
 *
 * <p>★真实缺陷：{@code loadFromDirectory} 对单个文件失败是**空 catch**——
 * 用户目录（{@code ~/.aster/lexicons/}）里损坏或不合规的语言包 JSON 会**无声消失**：
 * 无日志、无失败清单，返回值只有成功计数。用户无从得知是哪个文件、为何失败，
 * 查起来只能靠猜。
 *
 * <p>「单个文件失败不中断其余文件」这个行为是对的，错的是**不留痕迹**。
 */
class LexiconLoadFailureLoggingTest {

    /** 捕获 LexiconRegistry 的日志输出。 */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();
        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
    }

    @Test
    void corruptLexiconFileIsLoggedWithFileNameAndReason(@TempDir Path dir) throws Exception {
        // 一个语法就坏的 JSON —— 必然加载失败
        Path bad = dir.resolve("broken-pack.json");
        Files.writeString(bad, "{ this is not valid json");

        Logger logger = Logger.getLogger(LexiconRegistry.class.getName());
        CapturingHandler handler = new CapturingHandler();
        logger.addHandler(handler);
        try {
            int loaded = LexiconRegistry.getInstance().loadFromDirectory(dir);
            assertEquals(0, loaded, "坏文件不应被计入成功数");

            boolean logged = handler.records.stream()
                .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .anyMatch(r -> String.valueOf(r.getMessage()).contains("broken-pack.json"));
            assertTrue(logged,
                "加载失败必须记 WARNING 并**点名文件**，否则用户无从得知是哪个文件坏了；"
                    + "实际日志：" + handler.records.stream().map(LogRecord::getMessage).toList());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void loadingContinuesAfterOneFailure(@TempDir Path dir) throws Exception {
        // ★「不中断其余文件」这个既有行为必须保住——修「不留痕迹」不能顺手改成「一坏全停」。
        Files.writeString(dir.resolve("a-broken.json"), "{ nope");
        Files.writeString(dir.resolve("b-also-broken.json"), "also not json");

        Logger logger = Logger.getLogger(LexiconRegistry.class.getName());
        CapturingHandler handler = new CapturingHandler();
        logger.addHandler(handler);
        try {
            LexiconRegistry.getInstance().loadFromDirectory(dir);
            long warned = handler.records.stream()
                .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .filter(r -> String.valueOf(r.getMessage()).contains("broken"))
                .count();
            assertTrue(warned >= 2,
                "两个坏文件都应被处理并各记一条日志（证明第一个失败没有中断循环）；实际 " + warned);
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void emptyDirectoryProducesNoWarnings(@TempDir Path dir) {
        // ★反向护栏：没有坏文件时不得产生噪声告警。
        //   没有这条，把实现写成「无条件记一条 WARNING」也能让上面变绿。
        Logger logger = Logger.getLogger(LexiconRegistry.class.getName());
        CapturingHandler handler = new CapturingHandler();
        logger.addHandler(handler);
        try {
            assertEquals(0, LexiconRegistry.getInstance().loadFromDirectory(dir));
            boolean anyLoadWarning = handler.records.stream()
                .anyMatch(r -> String.valueOf(r.getMessage()).contains("语言包加载失败"));
            assertTrue(!anyLoadWarning, "空目录不应产生加载失败告警");
        } finally {
            logger.removeHandler(handler);
        }
    }
}
