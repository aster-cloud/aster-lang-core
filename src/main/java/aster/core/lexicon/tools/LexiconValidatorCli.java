package aster.core.lexicon.tools;

import aster.core.lexicon.DynamicLexicon;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;

import java.nio.file.Path;
import java.util.List;

/**
 * 命令行入口：用于 lexicon 贡献者本地校验。
 *
 * <p>使用示例：
 * <pre>
 *   java -cp ... aster.core.lexicon.tools.LexiconValidatorCli
 *       src/main/resources/lexicons/ja-JP.json
 * </pre>
 *
 * <p>退出码：
 * <ul>
 *   <li>0 — 校验通过（无 ERROR）</li>
 *   <li>1 — 校验失败（有 ERROR）</li>
 *   <li>2 — 用法错误（参数缺失 / 文件不存在）</li>
 * </ul>
 */
public final class LexiconValidatorCli {

    private LexiconValidatorCli() {}

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: lexicon-validator <path-to-lexicon.json> [<reference-lexicon-id>]");
            System.err.println("  reference defaults to 'en-US' (must be registered via SPI)");
            System.exit(2);
        }

        Path lexiconPath = Path.of(args[0]);
        String referenceId = args.length >= 2 ? args[1] : "en-US";

        Lexicon target;
        try {
            target = DynamicLexicon.fromJson(lexiconPath);
        } catch (Exception e) {
            System.err.println("Failed to load lexicon JSON: " + e.getMessage());
            System.exit(2);
            return;
        }

        // Reference lexicon 通过 SPI 注册（aster-lang-en 是 runtime dep）
        Lexicon reference = LexiconRegistry.getInstance().get(referenceId).orElse(null);
        if (reference == null) {
            System.err.println(
                "Warning: reference lexicon '" + referenceId + "' not found via SPI. "
                + "Keyword completeness check will use the full SemanticTokenKind enum instead.");
        }

        LexiconValidationReport report = new LexiconContributorValidator()
            .validate(target, reference, /* abiVersion */ null);

        printReport(report);
        System.exit(report.passed() ? 0 : 1);
    }

    private static void printReport(LexiconValidationReport report) {
        System.out.println("==============================================");
        System.out.println("Lexicon validation: " + report.lexiconId());
        System.out.println("==============================================");

        List<LexiconValidationReport.Issue> errors = report.errors();
        List<LexiconValidationReport.Issue> warnings = report.warnings();

        if (errors.isEmpty() && warnings.isEmpty()) {
            System.out.println("  ✓ PASSED — no issues found.");
            return;
        }

        for (LexiconValidationReport.Issue i : errors) {
            System.out.println("  [ERROR " + i.code() + "] " + i.message());
            if (i.suggestion() != null && !i.suggestion().isBlank()) {
                System.out.println("           → " + i.suggestion());
            }
        }
        for (LexiconValidationReport.Issue i : warnings) {
            System.out.println("  [WARN  " + i.code() + "] " + i.message());
            if (i.suggestion() != null && !i.suggestion().isBlank()) {
                System.out.println("           → " + i.suggestion());
            }
        }

        System.out.println();
        System.out.println("  Errors: " + errors.size() + " | Warnings: " + warnings.size());
        if (report.passed()) {
            System.out.println("  ✓ PASSED — warnings are advisory only.");
        } else {
            System.out.println("  ✗ FAILED — fix the errors above and re-run.");
        }
    }
}
