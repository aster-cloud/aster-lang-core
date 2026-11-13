package aster.core.ir;

import aster.core.typecheck.governance.ControlRegistrySiblingParityTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 定位 aster-lang-locales 中的 Core IR 真相源（schema 与夹具，ADR 0055 §3）。
 * 依次查找 {@code $ASTER_LOCALES_DIR}、兄弟目录 {@code ../aster-lang-locales}、CI 工作区子目录
 * {@code ./aster-lang-locales}；CI 中都不存在即失败（与控制注册表副本同一规则），本地返回空以便跳过。
 */
final class CoreIrLocales {

    private CoreIrLocales() {}

    static Optional<Path> locate(String relative) {
        List<Path> candidates = new ArrayList<>();
        String env = System.getenv("ASTER_LOCALES_DIR");
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env).resolve(relative));
        }
        candidates.add(Path.of("..", "aster-lang-locales").resolve(relative));
        candidates.add(Path.of("aster-lang-locales").resolve(relative));
        return ControlRegistrySiblingParityTest.locate(candidates, System.getenv("CI"));
    }
}
