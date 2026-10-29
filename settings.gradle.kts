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
            from("cloud.aster-lang:aster-lang-platform:1.0.35")
        }
    }
}

// Composite Build：用本地 aster-lang-locales 源码替换已发布的语言包（联调未发布的内置词汇改动，如 ADR 0051
// 要求枚举值带所属枚举）。显式开启才生效：-PasterLocalesDir=../aster-lang-locales 或环境变量 ASTER_LOCALES_DIR；
// 未设置时 CI 与默认构建仍解析 catalog 中已发布的 cloud.aster-lang:aster-lang-locales-{en,zh,de}。
val asterLocalesDir = (providers.gradleProperty("asterLocalesDir").orNull ?: System.getenv("ASTER_LOCALES_DIR"))
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
if (asterLocalesDir != null) {
    require(asterLocalesDir.isDirectory) { "asterLocalesDir 不是目录: $asterLocalesDir" }
    includeBuild(asterLocalesDir) {
        dependencySubstitution {
            substitute(module("cloud.aster-lang:aster-lang-locales-en")).using(project(":en"))
            substitute(module("cloud.aster-lang:aster-lang-locales-zh")).using(project(":zh"))
            substitute(module("cloud.aster-lang:aster-lang-locales-de")).using(project(":de"))
        }
    }
}
