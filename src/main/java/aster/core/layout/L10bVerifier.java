package aster.core.layout;

/**
 * L10b 的编译接缝（ADR 0059 修订 5 §2.3）：按与规范源码 {@code C} 相同的编译输入（locale、aliasSet、{@code allowStructural}、
 * 词汇）编译一份源码，返回其 Core IR 的 {@code positionFreeSha256}；编译失败（任何错误级诊断）返回 null。
 *
 * <p>编译器不在 core 的 layout 包里：CLI 与 aster-api 各自提供实现。
 */
@FunctionalInterface
public interface L10bVerifier {

    /**
     * 特殊值：调用方自己在 display-ir 阶段调用 {@link PackageLayoutRules#checkHiddenEscapes}（CLI 的 {@code verify} 把 L10a
     * 记在 {@code display} 检查项、L10b 记在 {@code display-ir}）。传它的 {@code checkPackage} 只跑 L10a。
     */
    L10bVerifier DEFERRED = source -> {
        throw new IllegalStateException("L10bVerifier.DEFERRED 不编译：调用方须自行调用 checkHiddenEscapes");
    };

    /** {@code source} 的 positionFreeSha256；编译失败返回 null。 */
    String positionFreeSha256(String source);
}
