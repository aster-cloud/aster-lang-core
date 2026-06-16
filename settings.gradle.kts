rootProject.name = "aster-lang-core"

dependencyResolutionManagement {
    // 共享版本目录（aster-lang-platform，ADR 0012）：aster-lang 生态依赖
    // 版本的单一来源。catalog 本身需要从仓库解析，故这里声明仓库；
    // RepositoriesMode 保持默认 PREFER_PROJECT，build.gradle.kts 里现有的
    // repositories {} 仍然生效（不改动 core 既有的仓库行为）。
    @Suppress("UnstableApiUsage")
    repositories {
        mavenLocal()
        mavenCentral()
    }
    versionCatalogs {
        create("asterLibs") {
            from("cloud.aster-lang:aster-lang-platform:1.0.2")
        }
    }
}
