# 代码审查报告

**审查时间**: 2026-10-23
**审查人**: Codex（复审）
**被审查任务**: Java TypeChecker 与 TypeScript TypeChecker parity（三处差异及后续返工）
**任务 ID**: fix/typecheck-parity-enum-variant-and-untyped-produce
**审查范围**: `git diff main...HEAD`；提交 `d062a1c`、`5299ef2`、`ecfb012`
**工作树**: 干净；本次只读检查，未改源码、未运行测试。

---

## 执行摘要

**综合评分**: 78/100 分
**审查建议**: 退回
**品味评分**: 一般

本轮指定的旧退回项大部分已闭环：推断返回类型进入函数签名；字段路径对已知非 Data 中间类型报 E011；静态推断增加字段类型表与内置调用；枚举归属按声明顺序；补上 IR 读回和遮蔽等用例。`ecfb012` 将反推限制到 `TypeName`，与 TS 的对应分支相符。

仍有一个直接的 parity 偏差：`fieldPathType` 对首段大写名称和已注册的 `DATA_TYPE` 符号返回宽松的 Unknown/Verdict 类型，不报 E101；TS `expression.ts` 对所有带点 Name 都要求首段先在值符号表中命中，未命中即报 E101。因而非调用表达式 `MissingNamespace.value`、`Status.value` 在 Java 侧可能静默通过，TS 会报未定义变量。调用目标的内置命名空间应由调用检查路径处理，不应改变一般 Name 表达式的首段符号规则。该差异触及用户明确要求的首段行为检查，建议修正并增加回归用例后再通过。

### 复审退回项闭环状态

| 上轮问题 | 本轮证据 | 结论 |
|---|---|---|
| 推断返回类型未进入函数签名 | `TypeChecker.defineFunctionSignature` 对 `retTypeInferred` 调用 `inferFunctionType`；TS `module.ts` 同样在登记签名前推断 | ✅ 已闭环 |
| 点路径首段缺失及中途非 Data 未报诊断 | `fieldPathType` 首段缺失通常报 E101；中途类型非 `TypeName` 时报 E011；新增未知首段、List 中间类型用例 | ⚠️ 部分闭环：大写未定义首段仍有静默路径 |
| 枚举归属依赖无序 Map | 枚举表用 `LinkedHashMap`，`VisitorContext` 保序复制；遍历首个命中 | ✅ 已闭环 |
| 已知非 Data 中间类型被字段反推放行 | 仅 `expanded instanceof TypeName` 才调用 `inferDataDeclFromField`；List 回归用例 | ✅ 已闭环 |
| inferStaticType 缺 Name/fieldTypes 与 Call 推断 | 新增 dotted Name、算术、Verdict 与内置 Call；签名登记传入模块字段类型表 | ✅ 已闭环（限 TS 当前静态推断范围） |
| 缺 IR 读回行为用例 | `InferredReturnTypeParityTest.irJsonRoundTripPreservesInferredSignatureBehaviour` | ✅ 已闭环 |
| verdictFieldType 过时注释 | 注释已改为限定名兜底语义 | ✅ 已闭环 |

---

## 审查五层法应用结果

### 第一层：数据结构分析

**核心数据实体及关系**:
- AST `Decl.Func` 新增 `retTypeInferred`，由 `AstBuilder` 设置、`CoreLowering` 复制到 Core IR `Func`。
- Core IR `Func.retTypeInferred` 使用 `JsonInclude.NON_DEFAULT`，只在 true 时序列化；TypeChecker 用它区分显式返回类型与解析器占位类型。
- 模块字段类型通过 `TypeSystem.buildFieldTypeMap` 从 Data 声明构造，函数签名预登记时只读使用；异类型同名字段被剔除，避免歧义推断。
- 枚举和 Data 声明表由 `LinkedHashMap` 保存，枚举重名变体按声明遍历顺序选择首个归属。

**结论**: ✅ 通过

**发现**:
- IR 标记的所有权与数据流清楚；`false` 默认不输出，`true` 能被 Jackson 读回。
- `Decl.Func` 是 record，新组件可能影响依赖旧构造器参数列表的源码调用方。仓内检索显示已有 `AstSerializationTest` 调整，但本次没有运行全量编译验证；属于源码兼容风险，不是 IR JSON 兼容问题。
- `buildFieldTypeMap` 冲突字段删除策略与 TS 对齐；函数体参数名类型没有用于静态签名推断也与 TS 实现一致。

### 第二层：特殊情况识别

**所有条件分支梳理**:
- 名称先查值符号，再查裸枚举变体，顺序和 TS 一致；遮蔽用例覆盖参数名与变体同名。
- 字段路径分支覆盖 Verdict 字段、Data 字段、TypeName 推断、已知非记录类型及未知字段诊断。
- 非符号首段使用 `knownQualifier` 或首字母大写判作限定名，属于当前剩余的不一致分支。

**结论**: ⚠️ 有问题

**发现**:
- TS `expression.ts` 对 dotted Name 直接 lookup `baseName`；未命中立即 E101。Java 对首字母大写首段无条件按限定名宽松返回。即使 `Decimal.round` 等调用由 `checkCall` 特判，普通 Name 表达式仍会走 `typeOfExpr`，因此这个兜底不是 TS 的等价行为。
- 首段为已知 `DATA_TYPE` 时也绕过字段访问检查；枚举变体限定写法 `Status.Approved` 是否有意支持，当前 TS Name 逻辑并未提供同等语义。需要把值表达式与调用目标/构造语法区分清楚。
- E011 一处访问路径循环到首个失败字段即返回，避免同一访问逐段追加诊断；新增测试锁定单次错误。

### 第三层：复杂度审查

**概念数量与嵌套层级**:
- 本次改动本质是：跨编译器 IR 上统一类型检查对枚举值、推断返回值和字段路径的语义。
- `fieldPathType` 将首段解析、逐段类型推进、Data 反推及诊断集中在一处；字段循环嵌套不超过三层。
- 返回静态推断按表达式类型分派，数值提升拆为辅助方法，复杂度可控。

**结论**: ✅ 通过

**发现**:
- 主要方法嵌套未超过三级；多层业务分支都有对应语义依据。
- `inferStaticType` Call 分支的算术、Verdict、内置函数映射清晰，但 Java 与 TS 的内置列表需要继续保持同步；目前范围与所读 TS 源一致。

### 第四层：破坏性分析

**向后兼容性检查**:
- 旧 Core IR JSON 不含 `retTypeInferred` 时，Java primitive boolean 默认 false；新序列化仅在 true 时输出，预期旧 IR JSON 和 canonical hash 不变。
- TS IR 的 `retTypeInferred: true` 有 Java 测试直接反序列化覆盖；推断函数签名行为也有 JSON round-trip 用例。
- `Decl.Func` 新增 record 参数会破坏仓外使用旧 canonical constructor 的源码编译兼容；该变化是公开 AST API 形状变化，需确认发布边界或给兼容构造器。

**结论**: ⚠️ 有问题

**发现**:
- IR JSON 向后兼容设计合理，新增 `RetTypeInferredSerializationTest` 覆盖 false 不输出、true 输出与读取。
- 仓内 `AstSerializationTest` 的适配只能证明本仓调用方调整，不能证明仓外 Java 调用者没有源码兼容影响。若 `Decl.Func` 并非稳定外部 API，可在发布说明中记录；否则建议保留旧参数构造器。
- 本次静态审查未验证 golden/canonical 哈希。应将 `./gradlew test` 或项目规定的 golden 检查作为合并前验证证据。

### 第五层：可行性校验

**问题真实性与严重度**:
- aster-api 直接检查 Java 解析器 Core IR 的 parity 是明确集成目标；三类差异均会造成真实误报或漏报。
- 本轮修复规模与问题匹配；测试集中于生产相关行为与协议边界。

**结论**: ⚠️ 有问题

**发现**:
- 剩余的 dotted Name 首段宽松行为会让未定义限定名在 Java 中静默为 Unknown，而 TS 报 E101，属于实际漏诊断，未满足“严格一致”。
- 当前只读复审没有运行测试；不能将 operations log 中历史通过记录当作本工作树此刻的验证结果。报告对此不作通过性假设。

---

## 技术维度评分

| 评分项 | 分数 (0-100) | 说明 |
|--------|--------------|------|
| 代码质量 | 82 | 责任分离合理，字段路径仍有首段宽松偏差 |
| 测试覆盖 | 82 | 旧退回点覆盖明显增强；缺大写未知首段普通 Name 的 parity 用例；未现场运行 |
| 规范遵循 | 80 | 中文注释与意图注释符合要求；`Decl.Func` API 兼容范围需确认 |
| **平均分** | **81** | - |

## 战略维度评分

| 评分项 | 分数 (0-100) | 说明 |
|--------|--------------|------|
| 需求匹配 | 76 | 核心场景已实现，仍未完全达到 TS dotted Name 首段语义 |
| 架构一致 | 83 | AST→Core IR→签名预登记路径一致，字段推断对齐 TS |
| 风险评估 | 75 | 发现真实 parity 漏诊断；源码兼容和当前验证尚未闭环 |
| **平均分** | **78** | - |

**综合评分计算**: 技术维度 81 × 50% + 战略维度 78 × 50% = 79.5，考虑未闭环的 parity 缺陷按 78 分裁定。
**评分口径**: 评分用于复审决策，不代表执行了未运行的测试。

---

## 致命问题

**是否存在致命问题**: 否（存在需退回的功能性 parity 缺陷）

**问题描述**:
`BaseTypeChecker.fieldPathType` 中 `head.isEmpty()` 的大写首段豁免，以及首段符号为 `DATA_TYPE` 时的豁免，偏离 TS `expression.ts`：TS dotted Name 要求首段是值符号，否则报告 E101。Java 仅因首字母大写便返回 Unknown，会使 `MissingNamespace.value` 一类 Name 表达式不产生诊断。需要区分 call target 的限定名处理与普通表达式 Name 的字段访问。

---

## 改进方向

1. 收紧一般 dotted Name 首段处理，并保持内置调用可用。
   - 原因: TS `TypeOfExprVisitor` 先 lookup base symbol，失败就报 E101；大小写不是例外条件。
   - 影响: 消除跨引擎漏诊断，避免合法内置调用回归。
   - 优先级: 高
   - 验收建议: 增加 `missingNamespace.value` 普通字段表达式应报 E101 的用例；同时验证 `Decimal.round(...)`、`Verdict.allow(...)` 等调用仍通过，并与 TS 结果逐项对照。

2. 明确 `Decl.Func` record 新构造器的外部兼容策略。
   - 原因: 新增 record component 改变 canonical constructor 签名。
   - 影响: 仓外 Java 源码调用方可能编译失败，虽不影响缺省 IR JSON 解析。
   - 优先级: 中
   - 验收建议: 如需源码兼容，提供旧签名构造器并将 `retTypeInferred` 默认 false；否则在破坏性版本说明中列明。

3. 在修复后执行本地项目验证。
   - 原因: 本轮只读复审未运行测试，也未独立确认 golden/canonical 哈希。
   - 影响: 编译、序列化和历史行为目前仅有代码/测试审阅证据。
   - 优先级: 高
   - 建议命令: `./gradlew test`，并执行仓库既定 golden/canonical 验证任务；失败时记录原始输出。

---

## 风险与阻塞

**风险清单**:
- 大写或类型名首段的普通 dotted Name 可能绕过 E101：高，构成当前退回理由。
- `Decl.Func` canonical constructor 变化影响仓外源码调用方：中，需确认 API 稳定级别。
- 本轮没有执行测试：中，无法现场确认历史验证记录和当前 HEAD 完全对应。

**阻塞问题**:
- 无外部阻塞；修正和验证均可在仓库内完成。

---

## 核对结果

**需求完整性**: ❌ dotted Name 首段规则未严格对齐 TS
**交付物完整性**: ✅ 三提交范围与新增实现/测试均可见
**测试覆盖**: ⚠️ 重点返工项有用例；缺大写首段 Name 用例且本次未执行
**文档完整性**: ✅ 源码注释为简体中文且描述语义，无明显修改说明式注释
**依赖评估完成**: ⚠️ 仓内消费者已检索；仓外 `Decl.Func` 源码 API 兼容未确认

---

## 审查依据

**参考文档/代码**:
- `.claude/operations-log.md`：前两轮退回及第三轮修复记录（背景证据，不替代本次验证）
- `/Users/rpang/IdeaProjects/aster-lang-ts/src/typecheck/expression.ts`：TS dotted Name 与裸枚举变体解析
- `/Users/rpang/IdeaProjects/aster-lang-ts/src/typecheck/module.ts`、`type_system.ts`：推断返回类型的签名登记与静态推断
- `git diff main...HEAD`：本次复审变更范围

**关键原则应用**:
- [✅] 好品味原则：按语义分支处理数据，不再让已知非记录类型借字段反推放行
- [✅] 实用主义：聚焦 aster-api Java IR 检查的真实 parity 差异
- [✅] 简单性执念：字段路径与推断逻辑保持有限层级
- [✅] 审查五层法完整应用

---

## 反馈通道

**问题讨论**: 无需讨论；剩余语义差异可直接按 TS 行为修正。
**后续行动**: 修正 dotted Name 首段的宽松处理，补充回归并执行本地验证，然后重新复审。

---

**报告生成时间**: 2026-10-23
**审查标准版本**: AGENTS.md（代码审查指南章节）
