package aster.core.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QuantityIr 占位去重的 <b>O(m²) 修复</b>守卫（ADR 0037 §12.10 ④）。
 *
 * <h2>★为什么现有 ReDoS 门禁抓不到它</h2>
 *
 * 现有门禁的载荷是 {@code "$" + "1".repeat(40000)}——<b>只有一个匹配</b>，
 * {@code claimed} 恒为 1，O(m²) 项永不激活。门禁量的是对的东西（增长率），
 * 但<b>语料有盲区</b>。本文件用<b>多匹配</b>载荷补上这个盲区。
 */
class QuantityIrOverlapTest {

    /** 取 3 次最小值——最接近真实计算量。 */
    private static double minMillis(Runnable r) {
        r.run();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t = System.nanoTime();
            r.run();
            best = Math.min(best, (System.nanoTime() - t) / 1e6);
        }
        return best;
    }

    @Test
    @DisplayName("★多匹配载荷下必须呈次二次增长（单匹配载荷测不到）")
    void manyMatchesAreSubQuadratic() {
        int base = 8000;
        String small = "1% ".repeat(base);
        String large = "1% ".repeat(base * 2);

        double tSmall = minMillis(() -> QuantityIr.extract(small));
        double tLarge = minMillis(() -> QuantityIr.extract(large));

        // 前置：确认载荷**真的**产生了大量匹配，否则本条空洞。
        assertTrue(QuantityIr.extract(small).size() > base / 2,
            "载荷未产生足量匹配，本断言空洞：" + QuantityIr.extract(small).size());

        if (tLarge < 1.0) {
            assertTrue(tLarge < 1.0, "耗时 " + tLarge + "ms");
            return;
        }
        double ratio = tLarge / Math.max(tSmall, 0.001);
        assertTrue(ratio < 3.0, String.format(
            "多匹配载荷 %d→%d（翻倍）耗时 %.1fms→%.1fms，增长 %.1f× —— 应 <3×。%n"
            + "★接近 4× 说明占位去重退回了「每个匹配线性扫 claimed」的 O(m²) 写法。",
            base, base * 2, tSmall, tLarge, ratio));
    }

    @Test
    @DisplayName("★TreeMap 去重必须与原线性扫描逐字节等价")
    void treeMapMatchesLinearScanSemantics() {
        // 对照基准：复刻**原**的 List + anyMatch 实现。
        String[] pool = {"1%", "$10", "2026-01-02", "3 天", "5 hours", "1.5%",
                         " ", "x", ".", ",", "12.34.56%", "$1.5%", "00", "a"};
        Random rnd = new Random(20260913L);   // 固定种子 → 失败可复现

        int compared = 0;
        int nonEmpty = 0;
        for (int i = 0; i < 50000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + rnd.nextInt(8);
            for (int j = 0; j < n; j++) sb.append(pool[rnd.nextInt(pool.length)]);
            String doc = sb.toString();

            List<String> actual = new ArrayList<>();
            for (QuantityIr.Quantity q : QuantityIr.extract(doc)) {
                actual.add(q.kind() + "@" + q.span().start() + ":" + q.text());
            }
            if (!actual.isEmpty()) nonEmpty++;
            compared++;
            assertEquals(legacyExtract(doc), actual,
                "TreeMap 去重与原线性扫描不一致，输入 " + escape(doc));
        }

        // ★反向守卫：若样本从不产生抽取，上面的相等断言全是空洞的。
        assertTrue(nonEmpty > compared / 10,
            "只有 " + nonEmpty + "/" + compared + " 组有抽取 —— 样本判别力不足。");
    }

    /**
     * ★真正独立的对照基准：<b>自己跑一遍正则</b> + 原 List 线性扫描去重。
     *
     * <p>我第一版把这个函数写成「调用 {@code QuantityIr.extract} 再从它的输出
     * 重算」——那是**循环论证**，基准永远不可能与被测对象分歧，2/2 全绿却
     * 什么都没证明。这正是本轮反复在抓的假绿形态，我自己又犯了一次。
     */
    private static List<String> legacyExtract(String doc) {
        record Hit(String kind, int start, int end, String text) {}
        // 与 QuantityIr.RULES 同序、同模式（改动时必须同步，否则本基准失效）
        Object[][] rules = {
            {"MONEY",    "[$€£¥]\\s?\\d[\\d,]*(?:\\.\\d+)?"},
            {"DATE",     "\\d{4}-\\d{2}-\\d{2}"},
            {"PERCENT",  "(?<![\\d.])\\d+(?:\\.\\d+)?\\s?%"},
            {"DURATION", "(?<![\\d.])\\d+(?:\\.\\d+)?\\s?(?:小时|分钟|天|秒|hours?|minutes?|days?|seconds?)"},
        };
        List<int[]> claimed = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        for (Object[] r : rules) {
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile((String) r[1]).matcher(doc);
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                boolean overlap = false;
                for (int[] c : claimed) {          // ← 原 O(m²) 线性扫描
                    if (start < c[1] && end > c[0]) { overlap = true; break; }
                }
                if (overlap) continue;
                if (QuantityIr.normalizeForTest((String) r[0], m.group()) == null) continue;
                claimed.add(new int[]{start, end});
                hits.add(new Hit((String) r[0], start, end, m.group()));
            }
        }
        hits.sort(java.util.Comparator.comparingInt(Hit::start));
        List<String> res = new ArrayList<>();
        for (Hit h : hits) res.add(h.kind() + "@" + h.start() + ":" + h.text());
        return res;
    }

    private static String escape(String s) {
        return "\"" + s.replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
}
