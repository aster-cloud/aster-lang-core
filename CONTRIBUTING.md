# 贡献指南 · Contributing to aster-lang-core

感谢你有意为 Aster 语言核心编译器贡献力量！

Thanks for your interest in contributing to the Aster core compiler.

## 开始之前 · Before You Start

- 阅读 [README](README.md) 了解编译管线与核心模块。
- 遵守 [行为准则](CODE_OF_CONDUCT.md)。
- 安全问题请走 [SECURITY.md](SECURITY.md)（**不要**开公开 issue）。

## 环境 · Prerequisites

见 README 的「环境要求」一节（JDK 版本、Gradle）。本仓被 `aster-lang-truffle`、
`aster-lang-runtime` 等依赖；跨仓构建时需先把本仓发布到 mavenLocal（`./gradlew publishToMavenLocal`）。

## 本地验证 · Local Verification

```bash
./gradlew build              # 构建
./gradlew test               # 单元测试
./gradlew goldenTest         # 黄金测试（Java ↔ TypeScript 编译器输出对比）
```

改动**必须**在本地跑通 `build` + `test` + `goldenTest` 后再提 PR。改到跨引擎行为时
（词法/语法/IR），`goldenTest` 是 parity 的硬门槛——双引擎输出必须一致。

## 提交流程 · Pull Request Flow

1. 从 `main` 切分支（`fix/…`、`feat/…`、`docs/…`）。
2. 小步提交，保持每次可编译。
3. 提交信息用祈使语气，说明「做了什么 + 为什么」。
4. PR 描述里附本地验证结果（哪些命令跑过、结果如何）。
5. 等 CI 全绿（`.github/workflows/`）后再请求合并。

## 代码风格 · Code Style

沿用仓内既有风格（导入顺序、命名、格式化）。新实现前先找 2–3 处相似实现参照，复用既有
模式与工具函数，不要另起炉灶。

## 许可证 · License

贡献即表示你同意你的贡献按本仓 [LICENSE](LICENSE)（Apache-2.0）授权。

By contributing, you agree that your contributions are licensed under the repository's
[LICENSE](LICENSE) (Apache-2.0).
