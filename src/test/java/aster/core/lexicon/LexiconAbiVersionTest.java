package aster.core.lexicon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LexiconAbiVersion 兼容性矩阵")
class LexiconAbiVersionTest {

    @Test
    @DisplayName("V1 元数据已声明")
    void v1MetadataIsDeclared() {
        assertThat(LexiconAbiVersion.V1.version).isEqualTo("1.0");
        assertThat(LexiconAbiVersion.V1.releasedAt).isEqualTo("2026-05-11");
        assertThat(LexiconAbiVersion.V1.guaranteedUntil).isEqualTo("2027-12-01");
    }

    @Test
    @DisplayName("null 和空字符串视为兼容（向后兼容旧 lexicon）")
    void nullAndBlankAreCompatible() {
        assertThat(LexiconAbiVersion.isCompatible(null)).isTrue();
        assertThat(LexiconAbiVersion.isCompatible("")).isTrue();
        assertThat(LexiconAbiVersion.isCompatible("   ")).isTrue();
    }

    @Test
    @DisplayName("接受 v1 系列版本")
    void acceptsV1Family() {
        assertThat(LexiconAbiVersion.isCompatible("1")).isTrue();
        assertThat(LexiconAbiVersion.isCompatible("1.0")).isTrue();
        assertThat(LexiconAbiVersion.isCompatible("1.0.0")).isTrue();
        assertThat(LexiconAbiVersion.isCompatible("1.0.5")).isTrue();
        assertThat(LexiconAbiVersion.isCompatible("1.5")).isTrue();
        assertThat(LexiconAbiVersion.isCompatible("1.99.99")).isTrue();
    }

    @Test
    @DisplayName("拒绝 v2+ 版本")
    void rejectsV2Plus() {
        assertThat(LexiconAbiVersion.isCompatible("2")).isFalse();
        assertThat(LexiconAbiVersion.isCompatible("2.0")).isFalse();
        assertThat(LexiconAbiVersion.isCompatible("2.0.0")).isFalse();
        assertThat(LexiconAbiVersion.isCompatible("3.0")).isFalse();
    }

    @Test
    @DisplayName("拒绝 v0 / 非法字符串")
    void rejectsZeroAndGarbage() {
        assertThat(LexiconAbiVersion.isCompatible("0.9")).isFalse();
        assertThat(LexiconAbiVersion.isCompatible("garbage")).isFalse();
        assertThat(LexiconAbiVersion.isCompatible("v1.0")).isFalse();
        assertThat(LexiconAbiVersion.isCompatible("1.0-snapshot")).isTrue(); // 前缀匹配仍接受
    }
}
