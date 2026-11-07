# 安全策略

## 报告漏洞

本仓库已开启 **GitHub 私有漏洞报告（Private vulnerability reporting）**。请优先使用：

`仓库页面 → Security → Report a vulnerability`（或 `.../security/advisories/new`）

**请不要**在公开 Issue、Discussion、Pull Request 或 commit message 中直接贴出漏洞细节、复现脚本或 PoC，
以免在修复发布前被利用。若不方便使用私有报告入口，可以开一个**不含任何技术细节**的 Issue 请求私下联系方式。

为便于定位，报告中尽量包含：

- 受影响的版本 / commit，以及运行环境（Minecraft 26.2、Java 25、操作系统）；
- 复现步骤与最小复现用例；
- 影响面判断（是否可导致任意代码执行、崩溃、越权访问、信息泄露等）；
- 已知的相关日志，例如 `logs/mirage-launch.log`；涉及原生崩溃时附 `hs_err_pid*.log` 的故障帧。

我们不承诺固定的修复时限，但会确认收到报告并给出初步评估。修复发布前会与报告人沟通披露时间。

## 支持范围

当前为 **0.4.0 开发版**，只维护 `main` 分支上的最新版本；尚无正式发行版，因此不提供历史版本的安全更新分支。
项目仅支持 Minecraft 26.2（决策 D10）。

## 不在本仓库范围内

- Minecraft 原版、Java 运行时、PCL / 启动器本身的问题 → 向各自上游报告；
- 第三方 Mod 或第三方兼容层依赖的漏洞 → 向对应项目报告；
- `mc-shili/`（用户本地游戏目录）内的文件不属于本仓库，不受本策略覆盖。
