package aster.core.ast;

/**
 * Core IR schema 版本（AST → Core IR 序列化契约）。
 *
 * <p>Core IR 是 Aster 编译器前端（Java ANTLR / TS 手写）与执行后端（Truffle / TS 解释器）
 * 之间的稳定中间表示。双引擎对整个 tier1-equivalence corpus 已 100% 等价
 * （见 aster-lang-test tier1-parity），本版本号把这一等价性升级为**版本化 ABI 契约**：
 * 消费方可声明所兼容的 schema 版本，core 校验后决定是否接受。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>同一 major 版本内，已发布节点的 JSON 形态（字段名、嵌套结构、kind 标签）只增不改、不删；</li>
 *   <li>新增可选字段不算 breaking（旧消费方忽略未知字段）；</li>
 *   <li>删除字段、改字段名、改 kind 标签、改语义 = breaking → 必须 bump major；</li>
 *   <li>breaking change 提前 6 个月通告，新旧 schema 共存至少 1 个版本周期。</li>
 * </ul>
 *
 * <p><b>承诺</b>：v1 schema 至少保证到 {@code guaranteedUntil} 不发生 breaking change。
 *
 * <p>对齐 {@code LexiconAbiVersion} 的版本化模式（同仓 lexicon SPI ABI）。
 */
public enum CoreIrSchemaVersion {
    V1("1.0", "2026-06-09", "2027-12-01");

    public final String version;
    public final String releasedAt;
    public final String guaranteedUntil;

    CoreIrSchemaVersion(String version, String releasedAt, String guaranteedUntil) {
        this.version = version;
        this.releasedAt = releasedAt;
        this.guaranteedUntil = guaranteedUntil;
    }

    /** 当前 core 产出的 Core IR schema 版本。 */
    public static final CoreIrSchemaVersion CURRENT = V1;

    /**
     * 检查消费方声明的 schema 版本是否与当前 core 产出的 IR 兼容。
     *
     * @param reportedVersion 消费方声明的版本字符串；null 或空视为旧版未声明（按 v1 兼容处理，向后兼容）
     * @return true 若兼容
     */
    public static boolean isCompatible(String reportedVersion) {
        if (reportedVersion == null || reportedVersion.isBlank()) {
            // 未声明 schema 版本的旧消费方按 v1 兼容处理（向后兼容）
            return true;
        }
        // V1 接受 "1", "1.0", "1.0.x", "1.x"（语义化 major 前缀匹配）
        return reportedVersion.equals("1") || reportedVersion.startsWith("1.");
    }
}
