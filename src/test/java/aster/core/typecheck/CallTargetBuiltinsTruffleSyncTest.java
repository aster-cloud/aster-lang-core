package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@link CallTargetBuiltins#NAMES} 与 truffle {@code Builtins} 中不带点的注册名逐项一致。
 * <p>
 * truffle 源码位置按顺序取：系统属性 {@code aster.truffle.dir}、环境变量 {@code ASTER_TRUFFLE_DIR}、
 * 与本仓同级的 {@code aster-lang-truffle} 目录。都找不到时跳过（core 不依赖 truffle）。
 */
class CallTargetBuiltinsTruffleSyncTest {

  private static final Pattern REGISTER = Pattern.compile("register\\(\"([^\".]+)\"");

  @Test
  void namesMatchTruffleBuiltins() throws Exception {
    var builtins = Stream.of(System.getProperty("aster.truffle.dir"), System.getenv("ASTER_TRUFFLE_DIR"), "../aster-lang-truffle")
      .filter(dir -> dir != null && !dir.isBlank())
      .map(dir -> Path.of(dir, "src/main/java/aster/truffle/runtime/Builtins.java"))
      .filter(Files::isRegularFile)
      .findFirst();
    assumeTrue(builtins.isPresent(), "找不到 aster-lang-truffle 源码，跳过核对");

    Set<String> truffle = new TreeSet<>();
    var matcher = REGISTER.matcher(Files.readString(builtins.get()));
    while (matcher.find()) {
      truffle.add(matcher.group(1));
    }
    assertEquals(truffle, new TreeSet<>(CallTargetBuiltins.NAMES));
  }
}
