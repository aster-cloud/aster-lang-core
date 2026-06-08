
## 2026-06-06 — ADR 0013 #1b: 可选 `is` 前缀比较语法（is at least / is greater than 等）

### 决策
- **第 1 步做**：`is` 作为可选连接词出现在比较短语前 → `is at least`/`is greater than`/`is less than`/`is at most`/`is more than`/`is under`/`is over`，语义 ≡ 去掉 `is` 后的比较词。零歧义（is 后紧跟已识别比较词）。
- **第 2 步不做**（替用户决策）：bare `is`（`x is 5` = `==`）。理由：`is equal to` 已覆盖自然英语等于；bare `is` 与 result-binding（`The result is X`）+ MODULE_IS 重载，引歧义。企业级可预测性 > 语法糖。

### 架构证据（两引擎不同机制）
- **Java**：Canonicalizer 文本级。比较词→符号在 `translateKeywords`（keywordTranslationMap，step 8）。`preTranslationTransformers`（step 4.5）在其前。`ResultIsTransformer`（处理 `The result is X`→`Return X`）证明 result-binding 的 is 有特定文本模式（行首/逗号后 `The result is`），与 `<expr> is <comparator>` 完全可区分。
  - 方案：新建 `IsComparatorTransformer`（preTranslation），把 `is <comparator>` 的 `is ` 吸收掉（`is at least`→`at least`），后续 translateKeywords 再 `at least`→`>=`。注册到 TransformerRegistry + en-US.json preTranslationTransformers。
- **TS**：parser 级。`parseComparison`（expr-stmt-parser.ts）有序 longest-match 链，每比较词一分支。`is equal to`/`is not equal to` 已是显式 parts。
  - 方案：在每个比较分支识别可选前导 `is`（peek `is` + 后跟比较词时一起消费）。

### Guardrails
双引擎 + tier1-parity（parse PR-blocking + ir + eval）+ corpus + Codex 审 + doc 验证 + 逐仓库提交。parity 红不推。

## 2026-06-08 — ADR 0015 阶段2: Java core `@entry` Rule 注解 groundwork

### 决策
- `funcDecl` 在 `RULE` 前增加 `annotation*`，保持无注解 Rule 向后兼容。
- 同步 `src/main/antlr/AsterLexer.tokens`：该文件落后于 `AsterLexer.g4`（缺 `EQ`），会导致手动/Gradle 生成的 lexer/parser token 编号错位，`@entry Rule` 运行时无法进入顶层声明循环。
- `Decl.Func` 新增 `List<Annotation> annotations`，位置放在 `retType` 与 `retAnnotations` 之间；构造器归一化为不可变空列表。
- `CoreModel.Func` 新增 `annotations = Collections.emptyList()`，`CoreLowering.lowerFunc` 复用现有 `lowerAnnotations` 透传。
- `TypeChecker` 在模块级流程中执行 `validateEntryAnnotations`，统计 `CoreModel.Func.annotations` 中 `name == "entry"` 的函数；超过一个时报 `MULTIPLE_ENTRY_RULES`，消息 data 中保留 `rules`。
- `ErrorCode.java` 是生成文件，但当前 workspace 没有注释所指的 `shared/error_codes.json` / `scripts/generate_error_codes.ts`，因此本次直接补 `MULTIPLE_ENTRY_RULES("E102", Category.SCOPE, Severity.ERROR, ...)`，后续同步 TS/shared 时应回填源数据并重新生成。

### 验证
- ANTLR：Gradle 无法启动，改用本机缓存的 ANTLR 4.13.1 tool 手动生成到 `build/generated-src/antlr/main/aster/core/parser`；同步 `AsterLexer.tokens` 后生成无警告。
- `./gradlew generateGrammarSource compileJava compileTestJava`：失败，sandbox 禁止访问 `~/.gradle` wrapper lock；改 `GRADLE_USER_HOME=.gradle` 后因网络受限无法下载 Gradle 9.4.0。
- `gradle generateGrammarSource compileJava compileTestJava`：失败，sandbox 禁止 Gradle file-lock listener socket (`SocketException: Operation not permitted`)。
- 替代验证：`javac --release 25` 编译 `src/main/java` + `build/generated-src/antlr/main` 通过。
- 替代验证：`javac --release 25` 编译本次相关测试 `AstBuilderTest`、`TypeCheckerIntegrationTest`、`CoreLoweringTest`、`AstSerializationTest` 通过；仅 JUnit API 缺少 apiguardian 注解类导致 warning。
- 运行时 smoke：临时 main 解析 `@entry Rule main ...` 并构造两个 `@entry` Core Func，确认解析保留 `entry` 注解且 TypeChecker 产出 `MULTIPLE_ENTRY_RULES`。

### 待主环境运行
- `./gradlew :aster-lang-core:generateGrammarSource compileJava compileTestJava`
- `./gradlew :aster-lang-core:test --tests "*Func*" --tests "*Annotation*" --tests "*Entry*"`

## 2026-06-08 — ADR 0015 阶段2修复: Canonicalizer 声明级注解行支持

### 根因
- 诊断 smoke 打印 `Canonicalizer.canonicalize()` 对复现源码的输出，确认 `@entry` 字符没有被关键词/标点替换成别的内容；输出形态是 `@entry\nRule main ...`。
- 当前 grammar 的 `funcDecl` 是 `annotation* RULE ...`，注解和 `Rule` 之间不能出现 `NEWLINE`。因此 canonicalize 后再交给 `AsterParser` 时，parser 在 `@entry\n` 处报 `no viable alternative at input '@entry\n'`。
- core 既有 `AstBuilderTest` 覆盖的是 `@entry Rule main ...` 同行形态，绕过了 standalone 注解行的 canonicalize+parse 端到端缺口。

### 修复
- 只改 `src/main/java/aster/core/canonicalizer/Canonicalizer.java`。
- 新增声明级注解行归一化：在进入最终空白规范化前，将无缩进的 annotation-only 顶层行并入后续 `Rule`/`type` 声明行，例如：
  - `@entry\nRule main ...` → `@entry Rule main ...`
  - `@entry\n@preview(source: "test")\nRule main ...` → `@entry @preview(source: "test") Rule main ...`
- 该规则仅处理无缩进顶层 annotation-only 行，并要求下一行是当前 grammar 已支持注解前缀的 `Rule`/`type` 声明起始，避免影响字段/参数/类型位置的内联 `@pii` 注解。

### 验证
- 临时 smoke：修复前 canonical 输出为 `@entry\nRule main ...`，随后 `AsterParser` 报 `line 8:6 no viable alternative at input '@entry\n'`。
- 临时 smoke：修复后 canonical 输出为 `@entry Rule main ...`，`AsterParser` `syntaxErrors=0`。
- `javac` 编译修改后的 `Canonicalizer.java` 通过。
- `javac` 编译修改后的 `CanonicalizerTest.java` 通过。
- 同包临时 runner 执行新增回归测试通过：
  - `testDeclarationAnnotationLineCanonicalizesToParseableFuncAnnotation`
  - `testInlineDeclarationAnnotationStillParses`
  - `testDeclarationAnnotationWithArgsLineCanonicalizesToParseableFuncAnnotation`
- `./gradlew test` 未能运行：wrapper 访问 `/Users/rpang/.gradle/wrapper/dists/gradle-9.4.0-bin/lcvyxq3t37f6mx9miaydrrgs/gradle-9.4.0-bin.zip.lck` 被 sandbox 拒绝。
- `./gradlew test --tests aster.core.canonicalizer.CanonicalizerTest --info` 未能运行：默认 wrapper 同样访问上述 lock 被 sandbox 拒绝；改 `GRADLE_USER_HOME=.gradle` 后需要下载 `https://services.gradle.org/distributions/gradle-9.4.0-bin.zip`，但当前网络受限 `UnknownHostException`。
- `gradle --version` 也未能运行：系统 Gradle 初始化 native services 失败，`libnative-platform.dylib` 无法加载。

## 2026-06-08 — ADR 0015 阶段2修复: 连续 `@entry Rule` 解析边界

### 决策
- 保持 `src/main/antlr/AsterLexer.tokens` 还原状态，不再修改该 checked-in 陈旧副本；本地手动 ANTLR 生成改用 `-Xexact-output-dir`，先生成 build 目录的 lexer tokens，再让 parser 通过 `-lib build/generated-src/antlr/main/aster/core/parser` 读取生成 tokens。
- 收紧 `block`：从 `INDENT stmt ((NEWLINE+ stmt) | stmt)* NEWLINE* DEDENT` 改为 `INDENT stmt (NEWLINE+ stmt)* NEWLINE* DEDENT`。这样块内语句序列与尾随空行/DEDENT 的职责分开，避免循环和尾部 `NEWLINE*` 争抢块尾空行。
- `funcDecl` 的 Rule 名改走 `nameIdent`，并把 `SECONDS` 加入 `nameIdent`。验证输入里的第二个 Rule 名 `second` 会被 lexer 识别为软关键字 `SECONDS`（`SECONDS: 'seconds' | 'second'`），此前 `funcDecl` 只接受 `IDENT | TYPE_IDENT`，导致第二个 `@entry Rule second ...` 进入错误恢复并生成残缺 FuncDeclContext。
- `visitFuncDecl` 改用 `ctx.nameIdent()` 取名；如果 grammar 错误恢复仍产生缺名上下文，则抛出 `Rule 声明缺失名称`，不再 NPE。

### 验证
- 手动 ANTLR 生成到 `build/generated-src/antlr/main/aster/core/parser`，不改 `src/main/antlr/AsterLexer.tokens`。
- `javac --release 25` 编译 main + generated parser 通过。
- 临时 smoke：连续两个 `@entry Rule first/second` 解析为两个 `Decl.Func`，两个函数均保留 `entry` 注解。
- `javac --release 25` 编译相关测试 `AstBuilderTest`、`TypeCheckerIntegrationTest`、`CoreLoweringTest`、`AstSerializationTest` 通过；仅 JUnit API 缺 apiguardian 注解类 warning。

## 2026-06-08 — ADR 0015 阶段3b-1: Import `version N` 子句（Java core）

### 决策
- `AsterLexer.g4` 新增 `VERSION: 'version';`，不修改 `src/main/antlr/AsterLexer.tokens` 这个 checked-in 陈旧生成副本。
- `AsterParser.g4` 将 `importDecl` 扩为 `USE qualifiedName (VERSION INT_LITERAL)? (AS importAlias)? DOT`，支持 `Use x version 2 as Y.` 和 `Use x version 2.`。
- `nameIdent` 增加 `VERSION`，让 `version` 仍可作为普通标识符使用，锁定阶段2软关键字回归风险。
- `Decl.Import` 新增可空 `Integer version` JSON 字段；`CoreModel.Import` 新增 `public Integer version;`；`CoreLowering.lowerImport` 透传 `imp.version()`。
- `AstBuilder.visitImportDecl` 从 `ctx.INT_LITERAL()` 解析版本号；无 `version` 子句时保持 `null`。`nameIdentText` 增加 `VERSION` 分支。
- 未修改 linker / `TypeChecker.checkImport`，该逻辑留给后续阶段。

### 测试
- `AstBuilderTest` 增加：
  - `Use x version 2 as Y.` → `version=2, alias=Y`
  - `Use x version 2.` → `version=2, alias=null`
  - `Rule version produce Text: Return "ok".` → Rule 名为 `version`
- `Use x.`、`Use x as Y.`、`Use a.b.c.D.` 断言 `version=null`，锁定向后兼容。
- `AstSerializationTest.testImportDecl` 覆盖 `Decl.Import` JSON round-trip 中的 `version`。
- `CoreLoweringTest.testLowerImport` 覆盖 `CoreModel.Import.version` 透传。

### 验证
- `./gradlew clean generateGrammarSource compileJava compileTestJava` 未能运行：wrapper 访问 `/Users/rpang/.gradle/wrapper/dists/gradle-9.4.0-bin/lcvyxq3t37f6mx9miaydrrgs/gradle-9.4.0-bin.zip.lck` 被 sandbox 拒绝。
- `GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew clean generateGrammarSource compileJava compileTestJava` 未能运行：需要下载 Gradle 9.4.0，但当前网络受限，`UnknownHostException: services.gradle.org`。
- 直接使用本机已解压 Gradle 9.4.0 并设置工作区 `GRADLE_USER_HOME` 仍未能运行：Gradle 初始化 file-lock listener socket 被 sandbox 拒绝，`SocketException: Operation not permitted`。
- 替代验证：使用本地 ANTLR 4.13.1 缓存分步生成到 `/private/tmp/aster-antlr-check2`，lexer 与 parser 生成均无 warning。
- 替代验证：`javac` 编译临时生成的 `AsterLexer/AsterParser/*Visitor` 与本次改动的 `Decl.java`、`CoreModel.java`、`CoreLowering.java`、`AstBuilder.java` 通过。
- 全量 `./gradlew test` 未能运行，原因同 Gradle socket sandbox 限制；需主环境补跑。

## 2026-06-08 — ADR 0015 阶段3b-3: ModuleGraphLinker + checkImport（Java core）

### 决策
- 新建 `aster.core.module`：
  - `ModuleKey`：`record(String moduleName, int version)`，`mangle()` 规则为点号替换下划线 + `_vN__`，例如 `risk.Scoring v2 -> risk_Scoring_v2__`。
  - `ModuleGraph`：包含 root、已解析模块 map、`ImportEdge(fromKey, importAlias, toKey)`；`topologicalOrder()` 做依赖优先 DFS，并在环上抛 `LinkException`。
  - `LinkedProgram`：`CoreModel.Module merged` + `Map<String,String> traceNames`。
  - `ModuleGraphLinker`：纯数据 linker，不做 IO/DB；单模块无 import 直接返回 root module identity。
- 非 root 模块顶层 `Func/Data/Enum` 声明名统一前缀化，root 保持原名；合并时丢弃所有 `Import` decl。
- `traceNames` 记录 `mangled -> 原 module.symbol`，用于诊断/trace 还原。
- `SymbolRewriter` 使用局部绑定栈保护 `Func/Lambda` 参数、`Let` 名、`PatName`/`PatCtor.names` 绑定，避免误改同名局部变量引用。
- dotted alias 符号（如 `L.f`、`L.User`）按当前模块 import edge 解析到目标模块 `mangle()` 前缀。无点号的表达式 Name 仅在不与本模块顶层冲突且唯一命中导入模块顶层名时解析。
- `TypeChecker.checkImport` 做单模块上下文可确定的轻量检查：重复 import visible name、import visible name 与本模块顶层 `Func/Data/Enum` 冲突时报 `IMPORT_SYMBOL_CONFLICT` warning。外部模块存在性/导出符号集合仍留给 aster-api 构造 ModuleGraph 后强化。
- `ErrorCode.java` 直接补 `IMPORT_SYMBOL_CONFLICT("E103", ...)`。该文件标注生成，但当前 workspace 仍没有 shared 源数据/生成脚本，沿用阶段2处理方式；后续同步 TS/shared 时需回填源数据。

### SymbolRewriter 覆盖
- Decl：`Func`（name/params/ret/body）、`Data`（name/fields.type）、`Enum`（name）、`Import` 跳过。
- Expr 15：`Name`、`Bool`、`IntE`、`LongE`、`DoubleE`、`StringE`、`NullE`、`Ok`、`Err`、`Some`、`NoneE`、`Construct`、`Call`、`Lambda`、`Await` 全部 switch 覆盖；字面量/None/Null 跳过，容器递归。
- Stmt 11：`Let`、`Set`、`Return`、`If`、`Match`、`Scope`、`Block`、`Start`、`Wait`、`Workflow` 全部 switch 覆盖；`Wait.names` 和 workflow step dependency/name 作为局部任务/步骤名跳过，`Step.body/compensate` 递归。
- Pattern 4：`PatNull`、`PatCtor`、`PatName`、`PatInt` 全部 switch 覆盖；`PatCtor.typeName` 重写，`PatName` 只作为局部绑定收集。
- Type 10：`TypeName`、`TypeVar`、`TypeApp`、`Result`、`Maybe`、`Option`、`ListT`、`MapT`、`FuncType`、`PiiType` 全部 switch 覆盖；`TypeVar` 跳过。

### 测试
- 新增 `ModuleGraphLinkerTest`：
  - 单模块无 import -> `linked.merged() == root`，无 trace。
  - root `Use lib version 1 as L` 调 `L.f` -> `lib_v1__f`，合并后无 Import。
  - root/lib 同名 `helper` -> lib 改为 `lib_v1__helper`。
  - 类型跨模块：`TypeName("L.User")`、`Construct("L.User")`、`PatCtor("L.User")` 改写为 `lib_v1__User`。
  - 嵌套覆盖：Lambda body、If 分支、Match case、Start、Workflow body/compensate、Ok/Err/Some/Await、TypeApp/FuncType/Option 等，序列化 linked Core JSON 断言不再含 `L.*`，并保留局部 param/pattern 名。
  - import cycle -> `LinkException`。
- `TypeCheckerIntegrationTest` 新增 import alias 与本地顶层声明冲突、重复 import alias 的 `IMPORT_SYMBOL_CONFLICT` 回归。
- Truffle 端到端：core 仓库当前没有 aster-lang-truffle 依赖，无法直接 invoke Truffle loader；本阶段用 linked Core JSON 断言覆盖跨模块引用改写。Truffle 端到端验证留 aster-api 集成（PR-3c）。

### 验证
- `./gradlew clean compileJava compileTestJava test` 未能运行：wrapper 访问 `/Users/rpang/.gradle/wrapper/dists/gradle-9.4.0-bin/lcvyxq3t37f6mx9miaydrrgs/gradle-9.4.0-bin.zip.lck` 被 sandbox 拒绝。
- `GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew clean compileJava compileTestJava test` 未能运行：需要下载 Gradle 9.4.0，但当前网络受限，`UnknownHostException: services.gradle.org`。
- 替代验证：使用本地缓存 Jackson + ANTLR runtime，带 `build/generated-src/antlr/main` 执行 `javac --release 25` 编译全部 main Java 通过。
- 替代验证：使用本地缓存 JUnit/Jackson，`javac --release 25` 编译 `ModuleGraphLinkerTest` 与 `TypeCheckerIntegrationTest` 通过。
- 未运行 JUnit：本地没有 JUnit Platform console 6.0.0 jar（只有 metadata 与旧 1.8.x console），避免用版本不匹配 runner 产生误判；全量 Gradle/JUnit 需主环境补跑。
