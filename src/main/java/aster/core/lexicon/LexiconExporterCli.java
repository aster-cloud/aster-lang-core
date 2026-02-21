package aster.core.lexicon;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gradle {@code exportLexicons} 任务的入口点。
 * <p>
 * 用法：{@code java ... LexiconExporterCli <version> <outputDir>}
 * <p>
 * 输出：{@code <outputDir>/lexicons.json}
 */
public final class LexiconExporterCli {

    private LexiconExporterCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: LexiconExporterCli <version> <outputDir>");
            System.exit(1);
        }

        String version = args[0];
        Path outputDir = Path.of(args[1]);
        Files.createDirectories(outputDir);

        Path outputFile = outputDir.resolve("lexicons.json");

        LexiconExporter exporter = new LexiconExporter();
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            exporter.export(version, writer);
        }

        System.out.println("Exported lexicons to: " + outputFile);
    }
}
