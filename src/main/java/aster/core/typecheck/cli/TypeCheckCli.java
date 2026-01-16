package aster.core.typecheck.cli;

import aster.core.ir.CoreModel;
import aster.core.typecheck.TypeChecker;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Java 版类型检查 CLI：复用 TypeScript emit-core 前端，调用 Java TypeChecker 产出诊断 JSON。
 */
public final class TypeCheckCli {

  private static final ObjectMapper MAPPER = new ObjectMapper()
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    .enable(SerializationFeature.INDENT_OUTPUT)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  public static void main(String[] args) {
    try {
      new TypeCheckCli().run(args);
    } catch (CliException ex) {
      System.err.println(ex.getMessage());
      System.exit(ex.exitCode());
    } catch (Exception ex) {
      ex.printStackTrace(System.err);
      System.exit(1);
    }
  }

  private void run(String[] args) throws IOException {
    CliOptions options = parseArgs(args);
    if (options.helpRequested) {
      printUsage();
      return;
    }

    if (options.command == null || !"typecheck".equals(options.command)) {
      throw new CliException("未知命令: " + (options.command == null ? "<missing>" : options.command), 2);
    }
    if (options.file == null) {
      throw new CliException("缺少 .aster 源文件路径", 2);
    }

    Path sourcePath = options.file;
    if (!Files.exists(sourcePath)) {
      throw new CliException("文件不存在: " + sourcePath, 1);
    }

    var result = typecheck(sourcePath, options.filterCodes);
    System.out.println(MAPPER.writeValueAsString(result));
  }

  private TypecheckResult typecheck(Path sourcePath, Set<String> filterCodes) throws IOException {
    CoreModel.Module module = parseWithEmitCore(sourcePath);
    TypeChecker checker = new TypeChecker();
    List<Diagnostic> diagnostics = checker.typecheckModule(module);
    List<DiagnosticPayload> payloads = new ArrayList<>(diagnostics.size());
    for (Diagnostic diagnostic : diagnostics) {
      payloads.add(toPayload(diagnostic, sourcePath.toString()));
    }
    List<DiagnosticPayload> filtered = filterDiagnostics(payloads, filterCodes);
    Summary summary = summarize(filtered);
    return new TypecheckResult(sourcePath.toString(), filtered, summary);
  }

  private CoreModel.Module parseWithEmitCore(Path sourcePath) throws IOException {
    Path projectRoot = locateProjectRoot();
    Path emitCore = projectRoot.resolve("dist/scripts/emit-core.js");
    if (!Files.exists(emitCore)) {
      throw new CliException("未找到 dist/scripts/emit-core.js，请先运行 npm run build。", 1);
    }

    ProcessBuilder builder = new ProcessBuilder(
      "node",
      emitCore.toString(),
      sourcePath.toString()
    );
    builder.directory(projectRoot.toFile());

    Process process;
    try {
      process = builder.start();
    } catch (IOException ex) {
      throw new CliException("无法启动 Node 进程，请确认已安装 Node.js: " + ex.getMessage(), 1);
    }

    byte[] stdout;
    byte[] stderr;
    try (var out = process.getInputStream(); var err = process.getErrorStream()) {
      stdout = out.readAllBytes();
      stderr = err.readAllBytes();
    }

    try {
      int exit = process.waitFor();
      if (exit != 0) {
        String errorText = new String(stderr, StandardCharsets.UTF_8).trim();
        if (errorText.isEmpty()) {
          errorText = "emit-core 返回非零退出码: " + exit;
        }
        throw new CliException(errorText, exit);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new CliException("等待 emit-core 结果时被中断", 1);
    }

    try {
      return MAPPER.readValue(stdout, CoreModel.Module.class);
    } catch (IOException ex) {
      throw new CliException("无法解析 emit-core 输出: " + ex.getMessage(), 1);
    }
  }

  private Path locateProjectRoot() {
    Path current = Path.of("").toAbsolutePath();
    for (int i = 0; i < 6; i++) {
      if (Files.exists(current.resolve("package.json")) && Files.exists(current.resolve("dist"))) {
        return current;
      }
      current = current.getParent();
      if (current == null) {
        break;
      }
    }
    throw new CliException("无法定位项目根目录，请在仓库根目录中运行命令。", 1);
  }

  private DiagnosticPayload toPayload(Diagnostic diagnostic, String fallbackFile) {
    String severity = diagnostic.severity().name().toLowerCase(Locale.ROOT);
    String code = diagnostic.code().code();
    String message = diagnostic.message();
    String help = diagnostic.help().orElse(null);
    Map<String, Object> data = diagnostic.data();
    SpanPayload span = toSpan(diagnostic.span(), fallbackFile);
    return new DiagnosticPayload(severity, code, message, span, help, data);
  }

  private SpanPayload toSpan(Optional<CoreModel.Origin> origin, String fallbackFile) {
    if (origin.isEmpty()) {
      return null;
    }
    CoreModel.Origin span = origin.get();
    if (span.start == null || span.end == null) {
      return null;
    }
    String file = span.file;
    if (file == null || file.isBlank()) {
      file = fallbackFile;
    }
    return new SpanPayload(
      file,
      span.start.line,
      span.start.col,
      span.end.line,
      span.end.col
    );
  }

  private List<DiagnosticPayload> filterDiagnostics(List<DiagnosticPayload> diagnostics, Set<String> filterCodes) {
    if (filterCodes == null || filterCodes.isEmpty()) {
      return diagnostics;
    }
    var normalized = new LinkedHashSet<String>();
    for (String code : filterCodes) {
      normalized.add(code.toUpperCase(Locale.ROOT));
    }
    return diagnostics.stream()
      .filter(diag -> normalized.contains(diag.code().toUpperCase(Locale.ROOT)))
      .toList();
  }

  private Summary summarize(List<DiagnosticPayload> diagnostics) {
    int errors = 0;
    int warnings = 0;
    int infos = 0;
    for (DiagnosticPayload payload : diagnostics) {
      switch (payload.severity()) {
        case "error" -> errors++;
        case "warning" -> warnings++;
        case "info" -> infos++;
        default -> {
          // 忽略未知级别
        }
      }
    }
    int total = diagnostics.size();
    return new Summary(total, errors, warnings, infos);
  }

  private CliOptions parseArgs(String[] args) {
    if (args.length == 0) {
      return new CliOptions(null, null, true, Set.of());
    }
    boolean help = false;
    List<String> positional = new ArrayList<>();
    LinkedHashSet<String> filterCodes = new LinkedHashSet<>();

    for (String arg : args) {
      if (Objects.equals(arg, "--help") || Objects.equals(arg, "-h")) {
        help = true;
        continue;
      }
      if (arg.startsWith("--filter-codes=")) {
        String[] parts = arg.split("=", 2);
        if (parts.length == 2) {
          for (String code : parts[1].split(",")) {
            if (!code.isBlank()) {
              filterCodes.add(code.trim().toUpperCase(Locale.ROOT));
            }
          }
        }
        continue;
      }
      positional.add(arg);
    }

    String command = positional.size() > 0 ? positional.get(0) : null;
    Path file = positional.size() > 1 ? Path.of(positional.get(1)).toAbsolutePath() : null;
    return new CliOptions(command, file, help, filterCodes);
  }

  private void printUsage() {
    System.out.println("""
用法: ./gradlew :aster-core:run --args="typecheck <file.aster>"

命令:
  typecheck <file.aster>   对指定源文件执行类型检查并输出 JSON 诊断。

选项:
  --filter-codes=E1,E2     仅输出指定错误码（逗号分隔）
  -h, --help               显示本帮助并退出。

输出:
  {
    "source": "<源文件路径>",
    "diagnostics": [...],
    "summary": {
      "total": <诊断数量>,
      "error": <错误数>,
      "warning": <警告数>,
      "info": <提示数>
    }
  }

环境变量:
  ASTER_MANIFEST_PATH      指向 manifest JSON，若存在则在类型检查时自动注入。
""");
  }

  private record DiagnosticPayload(
    String severity,
    String code,
    String message,
    SpanPayload span,
    String help,
    Map<String, Object> data
  ) {}

  private record SpanPayload(
    String file,
    int startLine,
    int startCol,
    int endLine,
    int endCol
  ) {}

  private record Summary(
    int total,
    int error,
    int warning,
    int info
  ) {}

  private record TypecheckResult(
    String source,
    List<DiagnosticPayload> diagnostics,
    Summary summary
  ) {}

  private record CliOptions(
    String command,
    Path file,
    boolean helpRequested,
    Set<String> filterCodes
  ) {}

  private static final class CliException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int exitCode;

    CliException(String message, int exitCode) {
      super(message);
      this.exitCode = exitCode;
    }

    int exitCode() {
      return exitCode;
    }
  }
}
