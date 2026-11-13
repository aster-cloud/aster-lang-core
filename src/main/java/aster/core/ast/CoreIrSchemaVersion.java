package aster.core.ast;

/**
 * Core IR schema 版本（ADR 0055 D3）。
 *
 * <p>Core IR 的形状由 aster-lang-locales {@code core-ir/core-ir.v2.schema.json} 唯一定义（core 的类路径副本在
 * {@code core-ir/core-ir.v2.schema.json}）。{@code Module.schemaVersion} 是 JSON 整数，读方遇到缺失、非整数或
 * 不等于当前版本的值即拒收（{@code unsupported_schema_version}）。
 *
 * <p>只有一个版本：项目上线前没有需要兼容的旧 IR，v1 及其「保证到 2027-12-01」的承诺随 ADR 0055 作废，
 * 不保留 v1 读方。今后任何形状变化都先改 locales 中的 schema，并随引擎列车发布。
 */
public enum CoreIrSchemaVersion {
    V2(2);

    /** {@code Module.schemaVersion} 的整数值。 */
    public final int version;

    CoreIrSchemaVersion(int version) {
        this.version = version;
    }

    /** 当前 core 产出并接受的 Core IR schema 版本。 */
    public static final CoreIrSchemaVersion CURRENT = V2;

    /**
     * 判断 IR 声明的 {@code schemaVersion} 是否可读：只接受当前版本。
     *
     * @param reportedVersion IR 中的 {@code schemaVersion}
     * @return 等于 {@link #CURRENT} 时为 true
     */
    public static boolean isCompatible(int reportedVersion) {
        return reportedVersion == CURRENT.version;
    }
}
