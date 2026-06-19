# 代码审查报告 — LexiconRegistry SPI 加载竞态修复（多副本 lexicon 不一致）

**审查时间**: 2026-06-20
**审查人**: Codex（生成者=Claude，交叉审查，无自审）
**被审查任务**: 修复 aster-api 多副本 /api/v1/lexicons 随机丢 locale（前端"刷新随机 on/off"根因）

---

## 执行摘要

**综合评分**: 92/100（第二轮，修复后）
**审查建议**: 通过
**品味评分**: 好品味（重试控制流与共享诊断映射解耦，iterator 失败信号本轮局部化）

第一轮 78/100 **退回**（抓出 1 致命 + 2 真问题）；按建议重构后第二轮 92/100 通过。

---

## 根因（生产实测）

aster-api K3S 6 副本，逐 pod /api/v1/lexicons：3/6 副本各丢**不同的一个** locale（zh/de/hi）→
前端忠实渲染命中 pod → 语言开关"刷新随机 on/off"。en-US 永远在（core 内嵌默认）；zh/de/hi
走 SPI 语言包 jar。`LexiconRegistry.discoverPluginsDetailed` 用 ServiceLoader lazy 迭代，旧容错
= best-effort + 连续 2 次失败 abort：多副本并行启动 + 堆压力（maxHeap=384MB）下 lazy iter.next()
偶发 ServiceConfigurationError → 静默丢插件，无重试、无完整性校验。

---

## 第一轮抓出的 3 个问题（已全部修复）

1. **【致命】带 provider hint 的 iterator 失败不重试**：旧 retry 判定依赖失败 key 的
   `startsWith("spi-iter#")`，但 `extractProviderHint` 成功时 key=provider class —— 而生产**最常见**
   的 "Provider &lt;fqcn&gt; could not be instantiated" 正带 provider class → 漏判 → **不重试** →
   根因没修。**修**：iterator catch 现**无条件** `++iterFailures`，provider hint 只影响诊断 key。
2. **【并发】重试控制流读/removeIf 共享 discoveryFailures**：与 hot-plug 并发写竞争 + stale key 污染。
   **修**：新增 private record `DiscoveryPass(newlyRegistered, iteratorFailures)`，`discoverPlugins`
   重试判定用**本轮局部** `iteratorFailures`，**不再**碰共享 map。public `discoverPluginsDetailed`
   签名不变（委托 private `discoverPluginsPass`）。
3. **【测试】缺 provider-hint 瞬时失败覆盖**：**补** `testTransientSpiFailureWithProviderHintRecoveredByRetry`
   （首轮 service 文件插不存在的 provider class → iter.next() 抛带 hint 的错 → 第二轮恢复，断言
   zh-CN 加齐；旧逻辑此测试必失败）。

## 设计要点
- iterator 级失败（hasNext/next 抛错=瞬时类加载/解析）→ 计入 iterFailures → 重试。
- provider 级失败（iter.next 成功后的 ABI/validate/register 失败=确定性）→ 不计入 → 不重试。
- 重试上限 MAX_DISCOVERY_PASSES=3 + 单轮 maxIterFailures=8（防真卡死）→ 有界终止。
- 构造器加 logStartupLexiconSummary（多副本诊断，坏副本此前连日志都没有）。

---

## 五层法（第二轮）
- 第一层 数据结构：✅ entries.putIfAbsent 幂等；DiscoveryPass 本轮局部信号。
- 第二层 特殊情况：✅ iterator-vs-provider 失败分类正确（瞬时重试 / 确定性 skip）。
- 第三层 复杂度：✅ public/private 拆分干净，hot-plug 行为不变。
- 第四层 破坏性：✅ public API 签名不变；读路径 availableIds/get 未动。
- 第五层 可行性：✅ 真修根因（带 hint 瞬时失败也重试），有界终止。

---

## 致命问题
无（第一轮的致命问题已修）。

## 剩余非阻断（已知，未改）
- 无 provider hint 的瞬时失败恢复后，匿名 spi-iter# 诊断可能残留触发 summary WARN（不影响控制流）。
- deterministic provider instantiation failure 在 iter.next() 时也会重试最多 3 轮（成本有界，可接受）。

---

## 验证
core 全 **1220 测试 0 fail**；LexiconRegistryTest **36 测试**（含 2 个新重试测试：无 hint + 带 hint）全绿。

**结论**: 可以合入。
