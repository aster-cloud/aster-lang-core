package aster.core.lexicon;

/**
 * Lexicon SPI ABI 版本。
 *
 * <p>第三方 lexicon 通过实现 {@link LexiconPlugin#getAbiVersion()} 声明兼容的 ABI 版本。
 * Core 启动时会校验，不兼容的 lexicon 会被 skip + 告警，但不影响其他 lexicon 加载。
 *
 * <p><b>承诺</b>：ABI v1 至少保证 18 个月不变更（直到 2027-12-01）。
 * Breaking change 提前 6 个月通告，新 ABI 与旧 ABI 共存至少 1 个版本周期。
 */
public enum LexiconAbiVersion {
    V1("1.0", "2026-05-11", "2027-12-01");

    public final String version;
    public final String releasedAt;
    public final String guaranteedUntil;

    LexiconAbiVersion(String version, String releasedAt, String guaranteedUntil) {
        this.version = version;
        this.releasedAt = releasedAt;
        this.guaranteedUntil = guaranteedUntil;
    }

    /**
     * 检查 lexicon 报告的 ABI 版本是否与当前 core 兼容。
     *
     * @param reportedVersion lexicon 报告的版本字符串；null 或空字符串视为旧版未声明（兼容）
     * @return true 若兼容
     */
    public static boolean isCompatible(String reportedVersion) {
        if (reportedVersion == null || reportedVersion.isBlank()) {
            // 未声明 ABI 的旧 lexicon 按 v1 兼容处理（向后兼容）
            return true;
        }
        // V1 接受 "1", "1.0", "1.0.x", "1.x"（语义化前缀匹配）
        return reportedVersion.equals("1") || reportedVersion.startsWith("1.");
    }
}
