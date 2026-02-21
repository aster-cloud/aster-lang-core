package aster.core.identifier;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gradle {@code exportVocabularies} 任务的入口点。
 * <p>
 * 用法：{@code java ... VocabularyExporterCli <version> <outputDir>}
 * <p>
 * 输出：{@code <outputDir>/vocabularies.json}
 */
public final class VocabularyExporterCli {

    private VocabularyExporterCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: VocabularyExporterCli <version> <outputDir>");
            System.exit(1);
        }

        String version = args[0];
        Path outputDir = Path.of(args[1]);
        Files.createDirectories(outputDir);

        Path outputFile = outputDir.resolve("vocabularies.json");

        VocabularyExporter exporter = new VocabularyExporter();
        VocabularyRegistry registry = VocabularyRegistry.getInstance();

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            exporter.export(version, registry, writer);
        }

        System.out.println("Exported vocabularies to: " + outputFile);
    }
}
