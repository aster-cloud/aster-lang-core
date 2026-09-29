// HotPlugTest.java —— 单文件 Java 程序，验证 aster-lang 语言包真热插拔。
//
// 测试场景：
//   T0：仅 aster-lang-core + aster-lang-locales-en 在 classpath。
//       LexiconRegistry.get("zh-CN") → 应返回 empty（插件未上线时 registry 不假装它在）。
//   T1：运行时通过 URLClassLoader 加载 aster-lang-locales-zh jar，
//       setContextClassLoader 后调 discoverPlugins()。
//       LexiconRegistry.get("zh-CN") → 现在 target 真是 zh，
//       keyword 是中文（"模块"、"规则" 等）。
//   T2：再加 aster-lang-locales-de jar，验证 de-DE 同样接入。
//
// 参数：args[0] = zh 语言包 jar 路径，args[1] = de 语言包 jar 路径。
// 用法：./gradlew hotPlugTest（或 scripts/run-hot-plug-test.sh）——classpath 与 jar 路径
// 由 Gradle 从共享 catalog 解析，不要手写 ~/.m2 路径。

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import aster.core.lexicon.FallbackLexicon;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;

public class HotPlugTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            die("usage: HotPlugTest <zh-jar> <de-jar> (run via `./gradlew hotPlugTest`)");
        }
        Path zhJar = Path.of(args[0]);
        Path deJar = Path.of(args[1]);

        if (!Files.isRegularFile(zhJar)) {
            die("ZH jar missing at " + zhJar);
        }
        if (!Files.isRegularFile(deJar)) {
            die("DE jar missing at " + deJar);
        }

        LexiconRegistry registry = LexiconRegistry.getInstance();

        // -------- T0: 只有 en backbone --------
        // 设计语义：registry.get() 对未注册 locale 返回 Optional.empty。
        // FallbackLexicon 装饰 *registered-but-partial* 的 lexicon（已注册但
        // 缺 keyword），而 *fully-missing* 的 locale 由消费者自行降级到 default。
        // 此测试覆盖："插件未上线时 registry 不假装它在"这一关键不变式。
        section("T0: en-only baseline");
        assertTrue("en-US is registered", registry.has("en-US"));
        assertTrue("zh-CN NOT registered before plug-in", !registry.has("zh-CN"));
        assertTrue("de-DE NOT registered before plug-in", !registry.has("de-DE"));
        assertTrue("get(zh-CN) returns empty before plug-in",
            registry.get("zh-CN").isEmpty());
        // 默认 lexicon 是 en-US 自身，不被 FallbackLexicon 包装
        assertEquals("default lexicon MODULE_DECL is English",
            "Module",
            registry.getDefault().getKeywords().get(SemanticTokenKind.MODULE_DECL));

        // -------- T1: 热插入 aster-lang-zh.jar --------
        section("T1: hot-plug aster-lang-zh");
        hotLoadJar(zhJar);
        int loaded = registry.discoverPlugins();
        log("  discoverPlugins() returned loaded=" + loaded);

        Optional<Lexicon> zhT1 = registry.get("zh-CN");
        assertTrue("zh-CN resolves after plug-in", zhT1.isPresent());
        // 非 en-US 的 lexicon 应被 FallbackLexicon 装饰：缺 keyword 时回退 en
        assertTrue("zh-CN is wrapped in FallbackLexicon",
            zhT1.get() instanceof FallbackLexicon);
        String moduleT1 = zhT1.get().getKeywords().get(SemanticTokenKind.MODULE_DECL);
        assertEquals("zh-CN MODULE_DECL is real Chinese after plug-in",
            "模块", moduleT1);

        // de-DE 仍未插入，应继续返回 empty
        assertTrue("de-DE still NOT registered after zh-only plug-in",
            !registry.has("de-DE"));
        assertTrue("get(de-DE) returns empty after zh-only plug-in",
            registry.get("de-DE").isEmpty());

        // -------- T2: 再热插入 aster-lang-de.jar --------
        section("T2: hot-plug aster-lang-de");
        hotLoadJar(deJar);
        int loaded2 = registry.discoverPlugins();
        log("  discoverPlugins() returned loaded=" + loaded2);

        Optional<Lexicon> deT2 = registry.get("de-DE");
        assertTrue("de-DE resolves after plug-in", deT2.isPresent());
        String moduleT2 = deT2.get().getKeywords().get(SemanticTokenKind.MODULE_DECL);
        assertEquals("de-DE MODULE_DECL is real German after plug-in",
            "Modul", moduleT2);

        // 验证 zh-CN 仍然是真 zh（不被后续插入影响）
        assertEquals("zh-CN stays real Chinese after de plug-in",
            "模块",
            registry.get("zh-CN").orElseThrow().getKeywords().get(SemanticTokenKind.MODULE_DECL));

        // 总结
        System.out.println();
        System.out.println("================ SUMMARY ================");
        System.out.println("passed: " + pass);
        System.out.println("failed: " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 把一个 jar 加到 system ClassLoader 上。
     *
     * 注意：JDK 9+ 的 SystemClassLoader 不再是 URLClassLoader，必须用反射
     * 走 appClassLoader.addURL 的非标准路径。这里改用另一种安全方式：
     * 创建一个新的 URLClassLoader（父 = systemClassLoader），并把它装到
     * ContextClassLoader 上 —— ServiceLoader.load(Class) 默认就用 CCL。
     */
    private static void hotLoadJar(Path jar) throws Exception {
        URL url = jar.toUri().toURL();
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        URLClassLoader newLoader = new URLClassLoader(
            "aster-lang-hotplug-" + jar.getFileName(),
            new URL[]{ url },
            parent
        );
        Thread.currentThread().setContextClassLoader(newLoader);
        log("  ↑ plugged " + jar.getFileName());
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("==== " + title + " ====");
    }

    private static void log(String msg) {
        System.out.println(msg);
    }

    private static void assertTrue(String label, boolean cond) {
        if (cond) {
            System.out.println("  ✓ " + label);
            pass++;
        } else {
            System.out.println("  ✗ " + label);
            fail++;
        }
    }

    private static void assertEquals(String label, String expected, String actual) {
        if (java.util.Objects.equals(expected, actual)) {
            System.out.println("  ✓ " + label + " [=" + actual + "]");
            pass++;
        } else {
            System.out.println("  ✗ " + label
                + " expected=[" + expected + "] actual=[" + actual + "]");
            fail++;
        }
    }

    private static void die(String msg) {
        System.err.println("FATAL: " + msg);
        System.exit(2);
    }
}
