# aster-lang-core

Aster CNL 的 Java 核心编译器，提供 ANTLR4 语法定义和 5 阶段编译管线。

## 概述

aster-lang-core 是 Aster 受控自然语言（CNL）的核心编译器实现。它将多语言 CNL 源码编译为类型安全的中间表示（Core IR），供运行时或策略引擎消费。核心通过 SPI 机制加载语言包（Lexicon），实现对英语、中文、德语等自然语言的统一编译支持。

## 编译管线

```
源码 (.aster)
  |
  v
+--------------------+
| 1. Canonicalizer   |  规范化：换行符/缩进/引号统一，多语言关键词翻译为英语
+--------------------+
  |
  v
+--------------------+
| 2. Lexer           |  词法分析：生成 Token 流，处理 INDENT/DEDENT 缩进语法
+--------------------+
  |
  v
+--------------------+
| 3. Parser          |  语法分析：ANTLR4 驱动，生成 AST
+--------------------+
  |
  v
+--------------------+
| 4. Lowering        |  IR 生成：AST 降低为 Core IR 数据结构
+--------------------+
  |
  v
+--------------------+
| 5. TypeCheck       |  类型检查：类型推断与约束验证
+--------------------+
  |
  v
Core IR (类型安全)
```

## 核心模块

| 包                            | 职责                                      |
|-------------------------------|-------------------------------------------|
| `aster.core.canonicalizer`    | 多语言规范化：关键词翻译、全角转半角、冠词去除 |
| `aster.core.lexer`            | 词法分析：Token 流生成、INDENT/DEDENT 处理   |
| `aster.core.parser`           | ANTLR4 语法解析：生成 AST                   |
| `aster.core.ir`               | Core IR 数据结构：模块、规则、表达式等         |
| `aster.core.inference`        | 类型推断：约束求解与类型检查                   |
| `aster.core.lexicon`          | 词法表定义：多语言关键词、标点、语法配置        |
| `aster.core.identifier`       | 标识符处理：领域词汇注册与翻译                 |
| `aster.core.typecheck.cli`    | 类型检查 CLI 入口                            |

## 构建与测试

```bash
# 构建项目
./gradlew build

# 运行单元测试
./gradlew test

# 运行黄金测试（TypeScript/Java 编译器输出对比）
./gradlew goldenTest

# 导出词法表 JSON（供 aster-lang-ts / aster-cloud 消费）
./gradlew exportLexicons

# 导出领域词汇表 JSON
./gradlew exportVocabularies

# 运行 JMH 性能基准测试
./gradlew jmh

# 生成测试覆盖率报告
./gradlew jacocoTestReport
```

## 跨项目关系

```
aster-lang-en/zh/de  (语言包，通过 SPI 插件机制加载)
       |
       v
aster-lang-core  <--- 本项目
       |
       +---> aster-lang-truffle  (GraalVM Truffle 运行时，依赖 core)
       +---> aster-api           (REST/GraphQL 策略 API，依赖 core)
       +---> aster-lang-ts       (TypeScript 移植版，对齐的解析器实现)
```

语言包通过 `publishToMavenLocal` 发布后，core 在测试和导出任务中通过 SPI 自动发现并加载。

## 依赖

主要依赖：

- **ANTLR4 4.13.1** -- 语法定义与解析器生成
- **Jackson 2.18.2** -- JSON 序列化（黄金测试与跨语言对比）
- **JUnit Jupiter 6.0** / **jqwik 1.8.2** -- 单元测试与属性测试

## 环境要求

- Java 25+
- Gradle 9.2+

## 许可证

Apache License 2.0
