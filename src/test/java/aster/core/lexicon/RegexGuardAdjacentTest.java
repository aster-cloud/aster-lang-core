package aster.core.lexicon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 相邻量词歧义守卫（ADR 0037 §12.10 ⑤）。与 TS 侧 {@code regex-guard.test.ts} 对等。
 */
class RegexGuardAdjacentTest {

    @Test
    @DisplayName("★相邻量词歧义必须拒绝（嵌套量词检查抓不到它）")
    void adjacentAmbiguousQuantifierIsRejected() {
        // NESTED_QUANTIFIER 只看「被量词修饰的分组」，而歧义无需分组即可产生。
        // 实测 a*a*a*a*a*a*a*a*a*a*b 在 24 字符输入上 1705ms，每 +2 字符翻倍。
        for (String evil : new String[]{
            "a*a*a*a*a*a*a*a*a*a*b", "a+a+a+a+a+a+a+a+b",
            "\\d*\\d*x", "[ab]*[ab]*c", "a{1,}a{1,}b"}) {
            List<String> errs = RegexGuard.screen(evil);
            assertFalse(errs.isEmpty(), "相邻量词模式未被拒绝: " + evil);
            assertTrue(errs.stream().anyMatch(e -> e.contains("adjacent-ambiguous-quantifier")),
                "拒绝原因不对: " + errs);
        }
    }

    @Test
    @DisplayName("★合法模式不得被误伤（误伤比漏网更糟）")
    void legitimatePatternsSurvive() {
        // 守卫收得过紧会**静默丢掉**用户的合法 overlay 规则，比漏一个 ReDoS 更难发现。
        for (String ok : new String[]{
            "a*b*c", "\\d+\\w+", "[a-z]+[0-9]*", "(ab)+c", "a+b",
            "\\bfoo\\b", "greater\\s+than", "x{2,5}y", "a*a", "aa*",
            "\\s+\\S+", "^(#{1,6})\\s"}) {
            assertTrue(RegexGuard.screen(ok).isEmpty(),
                "合法模式被误伤: " + ok + " -> " + RegexGuard.screen(ok));
        }
    }
}
