# 代码审查报告

**审查时间**: 2026-10-23  
**审查人**: Codex  
**被审查任务**: aster-lang-core TypeChecker 与 TS 对齐（枚举变体、推断返回类型、字段路径）  
**任务 ID**: d062a1c  
**审查范围**: `git diff main...HEAD`，分支 `fix/typecheck-parity-enum-variant-and-untyped-produce`  
**模式**: 只读；未修改源码

---

## 执行摘要

**综合评分**: 72 分  
**审查建议**: 退回  
**品味评分**: 需改进

补齐的正向路径有价值，新增测试也覆盖了常见成功/失败情形。但实现仍有至少三处明确的跨引擎语义偏差：Java 枚举归属遍历不保声明顺序；某些点路径在 Java 中静默返回 Unknown、TS 会报诊断；推断返回标记只让 Java 跳过比对，没有像 TS 一样用函数体推导并注册函数签名。最后一项会使调用者看到错误返回类型，不能视为已完成 parity。

---

## 审查五层法应用结果

### 第一层：数据结构分析

**核心数据实体及关系**:
- `Decl.Func.retTypeInferred` 经 `CoreLowering` 复制到 `CoreModel.Func`，再被类型检查器读取；默认 false 的 IR 序列化通过字段级 `NON_DEFAULT` 省略。
- 枚举归属依赖 `VisitorContext.enumDecls` 的遍历顺序。构造 map 时使用 `HashMap`，上下文又通过 `Map.copyOf` 保存，不能保证源码声明顺序。
- TS 在 `module.ts` 中对推断返回函数先调用 `TypeSystem.inferFunctionType` 并以推断结果注册 `funcSignatures`；Java 仍用占位 `func.ret` 注册函数签名。

**结论**: ❌ 严重问题

**发现**:
- `BaseTypeChecker.enumOwningVariant` 虽按 map 遍历“第一个”匹配项，但数据来源不是有序结构；重复变体归属时不等于 TS 的“声明顺序第一个”。证据：`TypeChecker.java` 构造枚举表使用 `HashMap`，`VisitorContext.java` 使用 `Map.copyOf`。
- 推断返回标记只影响函数体末尾的返回类型比较。前面注册签名时仍把名字猜出的 `func.ret` 放进 `FuncType`，与 TS 的返回类型推断注册不一致。比如 `produce:` 函数返回 Data，调用该函数的函数仍会在 Java 中观察到 AstBuilder 猜出的占位类型。
- 新增布尔标记对既有 JSON 的兼容性设计合理：Jackson 的 `NON_DEFAULT` 字段级注解对 false 值生效，true 才进入 JSON；缺字段反序列化为 Java primitive 默认值 false。提交未增加显式序列化断言，因此“逐字节不变/哈希不变”目前是基于 Jackson 行为的推断，未由本提交测试锁定。

### 第二层：特殊情况识别

**所有条件分支梳理**:
- 枚举处理符合“先查符号，再查变体”的代码顺序；但缺少符号与变体同名用例。
- `fieldPathType` 对首段缺失时只调用 `verdictFieldType` 并返回 Unknown；TS 会对首段缺失报 `UNDEFINED_VARIABLE`。
- Java 对中途非 Data 类型或不存在的 Data 声明直接返回 Unknown；TS 对非 TypeName 中间值报 `UNKNOWN_FIELD`，对未找到 Data 声明也报 `UNKNOWN_FIELD`。这是明确的漏报差异，不只是注释中描述的宽松策略。
- Verdict 内置字段：Java 支持 `Verdict.*` 的字段解析，并返回 `String`；TS 同样把 outcome/role/reason 映射为 `Text`，可接受。
- E011 使用整个 Name 的 `origin`，TS 的这些点路径分支同样使用整个 expression 的 origin，位置行为一致。

**结论**: ❌ 严重问题

**发现**:
- `application.foo.bar` 若 `foo` 是 Int，Java 在第二段返回 Unknown、不报 E011；TS 在解析第二段时进入非 TypeName 分支并报 E011。
- `missing.field` Java 不报未定义变量；TS 明确报首段 `missing` 的未定义变量。
- 声明顺序和名字遮蔽的测试缺口详见“改进方向”。

### 第三层：复杂度审查

**概念数量与嵌套层级**:
- 本次本质是让 Java Name 类型推断和函数返回类型签名遵循 TS。
- 新增字段路径解析为线性遍历，主体嵌套不超过三层；复杂度适中。
- `BaseTypeChecker` 保留了紧邻的旧 `verdictFieldType` Javadoc，随后又出现新方法 Javadoc，旧注释没有明确绑定实现，容易误导。

**结论**: ⚠️ 有问题

**发现**:
- 主要问题不是算法复杂，而是新旧两套点路径语义的边界处理不完整。
- 删除或合并旧 Javadoc，使职责描述只对应实际方法。

### 第四层：破坏性分析

**向后兼容性检查**:
- `retTypeInferred=false` 时新增 IR 字段不会输出；旧 JSON 缺省读为 false。AST record 新增 primitive boolean 也有默认值语义。
- `CoreLowering` 显式传递标记，未见 `ModuleGraphLinker` 或 Truffle Loader 对此字段进行重建、过滤或冲突处理；这些消费者以对象字段复制/保留为主。但本次未运行其测试，不能将此项记为验证通过。
- 真正兼容风险在运行语义：TS IR 的 `retTypeInferred=true` 到 Java 后只会跳过返回比较，而不会基于函数体更新签名，因而两边调用点可能推断不同。

**结论**: ⚠️ 有问题

**发现**:
- 保持既有序列化的意图成立，但缺少默认 false 不序列化以及 true/TS IR 反序列化后等价行为的直接测试。
- 没有证据表明新增字段破坏 ModuleGraphLinker 或 Truffle Loader 的结构，但 inferred return 在 Java 中并未完整消费 TS IR 协议。

### 第五层：可行性校验

**问题真实性与严重度**:
- 三项偏差均对应用户明确给出的 Java IR 接入场景，且会导致错误接受、漏报或错误类型传播。
- 新增测试覆盖裸变体匹配/不匹配、直接返回、未知裸名、已知字段、未知字段，以及推断返回的正反面；没有补充跨函数调用或相关点路径边界用例。
- 未执行测试，仅做静态代码审查；`git diff --check main...HEAD` 通过，工作树保持干净。

**结论**: ❌ 严重问题

**发现**:
- 已声明 Data 上字段拼写错误的 E011 场景有测试；其他 E011 分支和调用点签名传播未覆盖。
- 同名参数/局部符号优先于枚举变体的断言缺失；按代码当前顺序看符号优先成立，但没有回归保护。
- 同一 Name 多次调用 `typeOfExpr` 时会否重复添加诊断，静态阅读不能确认所有调用路径；当前提交未提供去重机制或重复调用测试。应确认 AST 节点在一次检查中是否可能重复访问，避免 E011/E101 重复报告。

---

## 技术维度评分

| 评分项 | 分数 (0-100) | 说明 |
|--------|--------------|------|
| 代码质量 | 72 | 实现范围清晰，但字段路径语义分支不完整，旧 Javadoc 残留 |
| 测试覆盖 | 68 | 主路径有覆盖；缺少遮蔽、枚举顺序、推断返回调用点及非 Data 中间路径 |
| 规范遵循 | 82 | 新增注释为简体中文且描述意图；未验证消费者和 IR 序列化回归 |
| **平均分** | **74** | - |

## 战略维度评分

| 评分项 | 分数 (0-100) | 说明 |
|--------|--------------|------|
| 需求匹配 | 68 | 已对齐部分场景，但仍有可复现 parity 差异 |
| 架构一致 | 74 | 字段标记沿 IR 传递合理；签名推断仅跳过而没有复用 TS 推断语义 |
| 风险评估 | 66 | 声明顺序、未知根路径、中间类型及调用点风险未被测试覆盖 |
| **平均分** | **69** | - |

## 致命问题

**是否存在致命问题**: 是

**问题描述**:
1. 推断返回函数仍以猜测占位类型注册 Java 函数签名；TS 使用函数体推断返回类型注册签名。调用者会获得不一致类型，破坏本分支目标中的 TS/Java parity。
2. 点路径首段未定义时 Java 不产生 E101；路径中间不是 Data 时 Java 不产生 TS 对应的 E011。
3. 枚举归属顺序依赖无序 map，不能保证重复变体场景取源码中第一个声明。

## 改进方向

1. 推断并注册 Java 的函数返回签名，确保与 TS `TypeSystem.inferFunctionType` 的可观察语义一致。
   - 原因: 仅跳过比较没有实现返回类型推断；调用者类型仍错误。
   - 影响: 修复跨函数调用类型和后续返回类型检查的一致性。
   - 优先级: 高

2. 对点路径首段缺失和中途非 Data 增加与 TS 一致的诊断。
   - 原因: 当前会静默返回 Unknown，漏掉 E101/E011。
   - 影响: Java 与 TS 的诊断数量及行为一致。
   - 优先级: 高

3. 保留枚举声明顺序，避免通过 HashMap/Map.copyOf 决定重复变体归属；增加重复变体和参数同名遮蔽用例。
   - 原因: TS 明确以 Map 插入顺序取第一个，Java 当前容器不保证该顺序。
   - 影响: 归属结果确定且可回归验证。
   - 优先级: 高

4. 增加 Jackson 测试：false 不输出字段、true 输出字段；读取 TS 风格 IR 后行为正确；并补充同一 Name 多次访问时诊断计数检查。
   - 原因: 现有 AST 序列化测试只走默认 false，未验证新增协议字段。
   - 影响: 锁定 IR 兼容和诊断计数。
   - 优先级: 中

## 风险与阻塞

**风险清单**:
- 无序枚举 map 在不同运行时/构建版本可能产生不同变体归属。
- 静默 Unknown 可能掩盖 Java IR 的未定义根标识符和错误字段访问。
- 推断返回函数的调用点类型与 TS 不同，差异可传播至调用者的字段访问和返回检查。
- 默认字段省略符合 Jackson 注解语义，但 canonical 哈希逐字节稳定性未在本提交的测试中证明。

**阻塞问题**:
- 函数签名推断需与 TS 对齐或明确记录两侧可接受的协议差异；当前目标写明严格 parity，因此阻塞通过。
- 字段路径分支与枚举顺序需修复并补测试。

## 核对结果

**需求完整性**: ❌  
**交付物完整性**: ✅（本次改动及新增测试均在 diff 范围内）  
**测试覆盖**: ❌（未运行；且上述关键用例缺失）  
**文档完整性**: ✅（新增注释为简体中文；发现一处旧 Javadoc 残留）  
**依赖评估完成**: ⚠️（静态检查 AST/CoreModel、CoreLowering、TypeChecker、TS module/expression；未执行下游加载器验证）

## 审查依据

**参考文档**:
- `.claude/CLAUDE.md` - 用户要求采用的审查模板（本环境中实际可读的规则来自仓库/会话提供的审查标准）
- `src/main/java/aster/core/typecheck/TypeChecker.java`
- `src/main/java/aster/core/typecheck/checkers/BaseTypeChecker.java`
- `src/main/java/aster/core/typecheck/model/VisitorContext.java`
- `/Users/rpang/IdeaProjects/aster-lang-ts/src/typecheck/expression.ts`
- `/Users/rpang/IdeaProjects/aster-lang-ts/src/typecheck/module.ts`
- `/Users/rpang/IdeaProjects/aster-lang-ts/src/typecheck/type_system.ts`

**关键原则应用**:
- [⚠️] “好品味”原则 - 主要数据流清楚，但边界分支与实际 TS 行为未完全对齐
- [✅] 实用主义 - 指向实际 Java IR 接入时的错误诊断/类型传播
- [⚠️] 简单性执念 - 复杂度可控，注释与重复语义需要收敛
- [✅] 审查五层法完整应用

## 反馈通道

**问题讨论**: 需要确认 Java 推断函数签名的实现边界是否按 TS 行为完全对齐。  
**后续行动**: 修复上述高优先级偏差，补齐测试后重新审查。

---

**报告生成时间**: 2026-10-23  
**审查标准版本**: 本次用户提供的 AGENTS.md / 审查清单
