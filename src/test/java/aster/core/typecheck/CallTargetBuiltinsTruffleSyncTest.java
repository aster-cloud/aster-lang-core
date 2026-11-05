package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@link CallTargetBuiltins#NAMES} 与 truffle {@code Builtins} 中不带点的注册名逐项一致；
 * {@link CallTargetBuiltins#ALIASES} 与 truffle {@code Builtins.canonicalName} 中的单词别名逐项一致（#234）。
 * <p>
 * truffle 源码位置按顺序取：系统属性 {@code aster.truffle.dir}、环境变量 {@code ASTER_TRUFFLE_DIR}、
 * 与本仓同级的 {@code aster-lang-truffle} 目录。都找不到时跳过（core 不依赖 truffle）。
 */
class CallTargetBuiltinsTruffleSyncTest {

  private static final Pattern REGISTER = Pattern.compile("register\\(\"([^\".]+)\"");
  /** canonicalName 中的一个分支：{@code case "/", "divide", "divided by" -> "div";} */
  private static final Pattern ALIAS_CASE = Pattern.compile("case ((?:\"[^\"]*\",?\\s*)+)->\\s*\"([a-z]+)\";");
  private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

  private static Optional<Path> truffleBuiltins() {
    return Stream.of(System.getProperty("aster.truffle.dir"), System.getenv("ASTER_TRUFFLE_DIR"), "../aster-lang-truffle")
      .filter(dir -> dir != null && !dir.isBlank())
      .map(dir -> Path.of(dir, "src/main/java/aster/truffle/runtime/Builtins.java"))
      .filter(Files::isRegularFile)
      .findFirst();
  }

  @Test
  void namesMatchTruffleBuiltins() throws Exception {
    var builtins = truffleBuiltins();
    assumeTrue(builtins.isPresent(), "找不到 aster-lang-truffle 源码，跳过核对");

    Set<String> truffle = new TreeSet<>();
    var matcher = REGISTER.matcher(Files.readString(builtins.get()));
    while (matcher.find()) {
      truffle.add(matcher.group(1));
    }
    assertEquals(truffle, new TreeSet<>(CallTargetBuiltins.NAMES));
  }

  @Test
  void aliasesMatchTruffleCanonicalName() throws Exception {
    var builtins = truffleBuiltins();
    assumeTrue(builtins.isPresent(), "找不到 aster-lang-truffle 源码，跳过核对");

    String source = Files.readString(builtins.get());
    String body = source.substring(source.indexOf("public static String canonicalName("));
    body = body.substring(0, body.indexOf("default ->"));
    Map<String, String> truffle = new TreeMap<>();
    var cases = ALIAS_CASE.matcher(body);
    while (cases.find()) {
      var quoted = QUOTED.matcher(cases.group(1));
      while (quoted.find()) {
        String alias = quoted.group(1);
        // 只有单个单词才可能出现在调用目标位置；运算符符号与多词短语由解析器处理
        if (alias.matches("[a-z]+")) {
          truffle.put(alias, cases.group(2));
        }
      }
    }
    assertEquals(truffle, new TreeMap<>(CallTargetBuiltins.ALIASES));
  }
}
