# 启动性能基线（自动生成）

> 由 `scripts/measure_startup.ps1` 生成于 2026-11-07 12:51:56
> 数据来源：`D:\bing\MC\mcmoderr-my\mc-shili\versions\26.2-mirage\logs\mirage-launch.log`

## 口径：绝对值是单次样本，不能当阈值

- 本批 14 个会话带 `titlescreen` 锚点：最小 **12030 ms** / 中位数 **23598 ms** / 最大 **33160 ms**（最大是最小的 **2.8 倍**）。
- 同一产物连续启动的耗时主要受机器状态与磁盘缓存影响，**跨度 2 倍属正常**。单次数字写成"基线值"会被误读成阈值。
- **能用**这些数字做的事：看锚点是否齐全（结构完整性）、看同一会话内各段的**相对占比**、定位哪一段是瓶颈。
- **不能用**这些数字做的事：当性能目标/回归判据、拿单次数字比两个包快慢、跨行比较不同会话（各会话可能来自不同 jar，见「体积」节的 jar 修改时间）。
- 要比两个包快慢：同机器状态各跑 3 次以上，取**中位数**并同时记离散度，否则结论不成立。


## 会话总览

| # | 横幅时刻 | 端到端 | 到 TitleScreen | 堆峰值 | 锚点 |
|---:|---|---:|---:|---:|---:|
| 1 | 2026-10-31 17:28:54 | 缺 | 缺 | 0 MB | 0 |
| 2 | 2026-10-31 17:29:08 | 34000 ms | 缺 | 0 MB | 0 |
| 3 | 2026-10-31 17:39:45 | 79000 ms | 缺 | 0 MB | 0 |
| 4 | 2026-10-31 17:49:00 | 缺 | 缺 | 0 MB | 0 |
| 5 | 2026-10-31 17:50:12 | 38000 ms | 缺 | 0 MB | 0 |
| 6 | 2026-10-31 17:52:18 | 97000 ms | 缺 | 0 MB | 0 |
| 7 | 2026-10-31 17:59:14 | 45000 ms | 缺 | 0 MB | 0 |
| 8 | 2026-10-31 22:15:45 | 缺 | 缺 | 0 MB | 0 |
| 9 | 2026-11-01 14:08:51 | 缺 | 缺 | 0 MB | 0 |
| 10 | 2026-11-01 15:30:17 | 缺 | 缺 | 0 MB | 0 |
| 11 | 2026-11-01 15:39:21 | 缺 | 缺 | 0 MB | 0 |
| 12 | 2026-11-01 16:42:50 | 21000 ms | 14401 ms | 466 MB | 16 |
| 13 | 2026-11-01 16:44:25 | 15000 ms | 12030 ms | 475 MB | 16 |
| 14 | 2026-11-07 09:45:12 | 37000 ms | 33160 ms | 488 MB | 16 |
| 15 | 2026-11-07 10:15:36 | 20000 ms | 13781 ms | 467 MB | 16 |
| 16 | 2026-11-07 10:16:02 | 57000 ms | 25736 ms | 486 MB | 16 |
| 17 | 2026-11-07 10:56:32 | 25000 ms | 20603 ms | 470 MB | 16 |
| 18 | 2026-11-07 11:21:05 | 25000 ms | 19811 ms | 471 MB | 16 |
| 19 | 2026-11-07 11:22:01 | 34000 ms | 25841 ms | 503 MB | 16 |
| 20 | 2026-11-07 11:22:39 | 35000 ms | 27638 ms | 476 MB | 16 |
| 21 | 2026-11-07 11:34:31 | 582000 ms | 29154 ms | 716 MB | 18 |
| 22 | 2026-11-07 12:00:23 | 21000 ms | 18126 ms | 476 MB | 16 |
| 23 | 2026-11-07 12:44:30 | 28000 ms | 24948 ms | 484 MB | 16 |
| 24 | 2026-11-07 12:45:13 | 30000 ms | 23827 ms | 469 MB | 16 |
| 25 | 2026-11-07 12:47:23 | 50000 ms | 23369 ms | 667 MB | 17 |

### 会话 1（Mirage 0.4.0，横幅 2026-10-31 17:28:54）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 124 ms；S2 Parse = 4 ms；S3 BytecodeTransform = 954 ms；S4 RegistryMerge = 9708 ms；S5 EventBusInit = 0 ms；S6 ModInit = 1684 ms；S7 Loaded = 缺 |
| 端到端（横幅 → S7 done） | 缺（缺 S7 done 的墙钟时间戳） |
| **到 TitleScreen**（用户能操作游戏） | 缺（无 TitleScreen 锚点） |
| 堆峰值（锚点内最大 heapPeak） | 0 MB |
| 锚点数 | 0 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 缺锚点 |
| S4 Bootstrap.bootStrap (MC 原版) | 缺锚点 |
| S4 解冻 MappedRegistry | 缺锚点 |
| S4 装配方 | 缺锚点 |
| S7 Mirage 侧前置开销 | 缺锚点 |
| S7 MC 窗口创建前 | 缺锚点 |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 缺锚点 |
| MC 资源重载 → TitleScreen | 缺锚点 |

**本会话测不了的维度**：
- S4 分段：缺锚点 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend
- S7 分段：缺锚点 s7.begin, s7.pre
- 早期窗口交接：缺锚点 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done
- TitleScreen 锚点：缺锚点 titlescreen

### 会话 2（Mirage 0.4.0，横幅 2026-10-31 17:29:08）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 135 ms；S2 Parse = 5 ms；S3 BytecodeTransform = 770 ms；S4 RegistryMerge = 40213 ms；S5 EventBusInit = 0 ms；S6 ModInit = 135 ms；S7 Loaded = 34278 ms |
| 端到端（横幅 → S7 done） | 34000 ms |
| **到 TitleScreen**（用户能操作游戏） | 缺（无 TitleScreen 锚点） |
| 堆峰值（锚点内最大 heapPeak） | 0 MB |
| 锚点数 | 0 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 缺锚点 |
| S4 Bootstrap.bootStrap (MC 原版) | 缺锚点 |
| S4 解冻 MappedRegistry | 缺锚点 |
| S4 装配方 | 缺锚点 |
| S7 Mirage 侧前置开销 | 缺锚点 |
| S7 MC 窗口创建前 | 缺锚点 |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 缺锚点 |
| MC 资源重载 → TitleScreen | 缺锚点 |

**本会话测不了的维度**：
- S4 分段：缺锚点 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend
- S7 分段：缺锚点 s7.begin, s7.pre
- 早期窗口交接：缺锚点 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done
- TitleScreen 锚点：缺锚点 titlescreen

### 会话 3（Mirage 0.4.0，横幅 2026-10-31 17:39:45）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 145 ms；S2 Parse = 4 ms；S3 BytecodeTransform = 1933 ms；S4 RegistryMerge = 22685 ms；S5 EventBusInit = 0 ms；S6 ModInit = 183 ms；S7 Loaded = 51799 ms |
| 端到端（横幅 → S7 done） | 79000 ms |
| **到 TitleScreen**（用户能操作游戏） | 缺（无 TitleScreen 锚点） |
| 堆峰值（锚点内最大 heapPeak） | 0 MB |
| 锚点数 | 0 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 缺锚点 |
| S4 Bootstrap.bootStrap (MC 原版) | 缺锚点 |
| S4 解冻 MappedRegistry | 缺锚点 |
| S4 装配方 | 缺锚点 |
| S7 Mirage 侧前置开销 | 缺锚点 |
| S7 MC 窗口创建前 | 缺锚点 |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 缺锚点 |
| MC 资源重载 → TitleScreen | 缺锚点 |

**本会话测不了的维度**：
- S4 分段：缺锚点 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend
- S7 分段：缺锚点 s7.begin, s7.pre
- 早期窗口交接：缺锚点 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done
- TitleScreen 锚点：缺锚点 titlescreen

### 会话 4（Mirage 0.4.0，横幅 2026-10-31 17:49:00）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 111 ms；S2 Parse = 4 ms；S3 BytecodeTransform = 741 ms；S4 RegistryMerge = 6764 ms；S5 EventBusInit = 0 ms；S6 ModInit = 416 ms；S7 Loaded = 缺 |
| 端到端（横幅 → S7 done） | 缺（缺 S7 done 的墙钟时间戳） |
| **到 TitleScreen**（用户能操作游戏） | 缺（无 TitleScreen 锚点） |
| 堆峰值（锚点内最大 heapPeak） | 0 MB |
| 锚点数 | 0 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 缺锚点 |
| S4 Bootstrap.bootStrap (MC 原版) | 缺锚点 |
| S4 解冻 MappedRegistry | 缺锚点 |
| S4 装配方 | 缺锚点 |
| S7 Mirage 侧前置开销 | 缺锚点 |
| S7 MC 窗口创建前 | 缺锚点 |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 缺锚点 |
| MC 资源重载 → TitleScreen | 缺锚点 |

**本会话测不了的维度**：
- S4 分段：缺锚点 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend
- S7 分段：缺锚点 s7.begin, s7.pre
- 早期窗口交接：缺锚点 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done
- TitleScreen 锚点：缺锚点 titlescreen

### 会话 5（Mirage 0.4.0，横幅 2026-10-31 17:50:12）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 103 ms；S2 Parse = 2 ms；S3 BytecodeTransform = 910 ms；S4 RegistryMerge = 11732 ms；S5 EventBusInit = 0 ms；S6 ModInit = 108 ms；S7 Loaded = 23594 ms |
| 端到端（横幅 → S7 done） | 38000 ms |
| **到 TitleScreen**（用户能操作游戏） | 缺（无 TitleScreen 锚点） |
| 堆峰值（锚点内最大 heapPeak） | 0 MB |
| 锚点数 | 0 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 缺锚点 |
| S4 Bootstrap.bootStrap (MC 原版) | 缺锚点 |
| S4 解冻 MappedRegistry | 缺锚点 |
| S4 装配方 | 缺锚点 |
| S7 Mirage 侧前置开销 | 缺锚点 |
| S7 MC 窗口创建前 | 缺锚点 |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 缺锚点 |
| MC 资源重载 → TitleScreen | 缺锚点 |

**本会话测不了的维度**：
- S4 分段：缺锚点 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend
- S7 分段：缺锚点 s7.begin, s7.pre
- 早期窗口交接：缺锚点 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done
- TitleScreen 锚点：缺锚点 titlescreen

### 会话 6（Mirage 0.4.0，横幅 2026-10-31 17:52:18）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 110 ms；S2 Parse = 5 ms；S3 BytecodeTransform = 574 ms；S4 RegistryMerge = 25918 ms；S5 EventBusInit = 0 ms；S6 ModInit = 257 ms；S7 Loaded = 68075 ms |
| 端到端（横幅 → S7 done） | 97000 ms |
| **到 TitleScreen**（用户能操作游戏） | 缺（无 TitleScreen 锚点） |
| 堆峰值（锚点内最大 heapPeak） | 0 MB |
| 锚点数 | 0 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 缺锚点 |
| S4 Bootstrap.bootStrap (MC 原版) | 缺锚点 |
| S4 解冻 MappedRegistry | 缺锚点 |
| S4 装配方 | 缺锚点 |
| S7 Mirage 侧前置开销 | 缺锚点 |
| S7 MC 窗口创建前 | 缺锚点 |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 缺锚点 |
| MC 资源重载 → TitleScreen | 缺锚点 |

**本会话测不了的维度**：
- S4 分段：缺锚点 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend
- S7 分段：缺锚点 s7.begin, s7.pre
- 早期窗口交接：缺锚点 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done
- TitleScreen 锚点：缺锚点 titlescreen

### 会话 7（Mirage 0.4.0，横幅 2026-10-31 17:59:14）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 97 ms；S2 Parse = 3 ms；S3 BytecodeTransform = 569 ms；S4 RegistryMerge = 7433 ms；S5 EventBusInit = 0 ms；S6 ModInit = 517 ms；S7 Loaded = 34972 ms |
| 端到端（横幅 → S7 done） | 45000 ms |
| **到 TitleScreen**（用户能操作游戏） | 缺（无 TitleScreen 锚点） |
| 堆峰值（锚点内最大 heapPeak） | 0 MB |
| 锚点数 | 0 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 缺锚点 |
| S4 Bootstrap.bootStrap (MC 原版) | 缺锚点 |
| S4 解冻 MappedRegistry | 缺锚点 |
| S4 装配方 | 缺锚点 |
| S7 Mirage 侧前置开销 | 缺锚点 |
| S7 MC 窗口创建前 | 缺锚点 |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 缺锚点 |
| MC 资源重载 → TitleScreen | 缺锚点 |

**本会话测不了的维度**：
- S4 分段：缺锚点 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend
- S7 分段：缺锚点 s7.begin, s7.pre
- 早期窗口交接：缺锚点 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done
- TitleScreen 锚点：缺锚点 titlescreen

### 会话 8（Mirage 0.4.0，横幅 2026-10-31 22:15:45）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） |  |
| 端到端（横幅 → S7 done） | 缺（缺 S7 done 的墙钟时间戳） |
| **到 TitleScreen**（用户能操作游戏） | 缺（无 TitleScreen 锚点） |
| 堆峰值（锚点内最大 heapPeak） | 0 MB |
| 锚点数 | 0 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 缺锚点 |
| S4 Bootstrap.bootStrap (MC 原版) | 缺锚点 |
| S4 解冻 MappedRegistry | 缺锚点 |
| S4 装配方 | 缺锚点 |
| S7 Mirage 侧前置开销 | 缺锚点 |
| S7 MC 窗口创建前 | 缺锚点 |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 缺锚点 |
| MC 资源重载 → TitleScreen | 缺锚点 |

**本会话测不了的维度**：
- S4 分段：缺锚点 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend
- S7 分段：缺锚点 s7.begin, s7.pre
- 早期窗口交接：缺锚点 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done
- TitleScreen 锚点：缺锚点 titlescreen

### 会话 9（Mirage 0.4.0，横幅 2026-11-01 14:08:51）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） |  |
| 端到端（横幅 → S7 done） | 缺（缺 S7 done 的墙钟时间戳） |
| **到 TitleScreen**（用户能操作游戏） | 缺（无 TitleScreen 锚点） |
| 堆峰值（锚点内最大 heapPeak） | 0 MB |
| 锚点数 | 0 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 缺锚点 |
| S4 Bootstrap.bootStrap (MC 原版) | 缺锚点 |
| S4 解冻 MappedRegistry | 缺锚点 |
| S4 装配方 | 缺锚点 |
| S7 Mirage 侧前置开销 | 缺锚点 |
| S7 MC 窗口创建前 | 缺锚点 |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 缺锚点 |
| MC 资源重载 → TitleScreen | 缺锚点 |

**本会话测不了的维度**：
- S4 分段：缺锚点 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend
- S7 分段：缺锚点 s7.begin, s7.pre
- 早期窗口交接：缺锚点 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done
- TitleScreen 锚点：缺锚点 titlescreen

### 会话 10（Mirage 0.4.0，横幅 2026-11-01 15:30:17）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） |  |
| 端到端（横幅 → S7 done） | 缺（缺 S7 done 的墙钟时间戳） |
| **到 TitleScreen**（用户能操作游戏） | 缺（无 TitleScreen 锚点） |
| 堆峰值（锚点内最大 heapPeak） | 0 MB |
| 锚点数 | 0 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 缺锚点 |
| S4 Bootstrap.bootStrap (MC 原版) | 缺锚点 |
| S4 解冻 MappedRegistry | 缺锚点 |
| S4 装配方 | 缺锚点 |
| S7 Mirage 侧前置开销 | 缺锚点 |
| S7 MC 窗口创建前 | 缺锚点 |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 缺锚点 |
| MC 资源重载 → TitleScreen | 缺锚点 |

**本会话测不了的维度**：
- S4 分段：缺锚点 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend
- S7 分段：缺锚点 s7.begin, s7.pre
- 早期窗口交接：缺锚点 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done
- TitleScreen 锚点：缺锚点 titlescreen

### 会话 11（Mirage 0.4.0，横幅 2026-11-01 15:39:21）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） |  |
| 端到端（横幅 → S7 done） | 缺（缺 S7 done 的墙钟时间戳） |
| **到 TitleScreen**（用户能操作游戏） | 缺（无 TitleScreen 锚点） |
| 堆峰值（锚点内最大 heapPeak） | 0 MB |
| 锚点数 | 0 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 缺锚点 |
| S4 Bootstrap.bootStrap (MC 原版) | 缺锚点 |
| S4 解冻 MappedRegistry | 缺锚点 |
| S4 装配方 | 缺锚点 |
| S7 Mirage 侧前置开销 | 缺锚点 |
| S7 MC 窗口创建前 | 缺锚点 |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 缺锚点 |
| MC 资源重载 → TitleScreen | 缺锚点 |

**本会话测不了的维度**：
- S4 分段：缺锚点 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend
- S7 分段：缺锚点 s7.begin, s7.pre
- 早期窗口交接：缺锚点 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done
- TitleScreen 锚点：缺锚点 titlescreen

### 会话 12（Mirage 0.4.0，横幅 2026-11-01 16:42:50）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 485 ms；S2 Parse = 13 ms；S3 BytecodeTransform = 862 ms；S4 RegistryMerge = 5512 ms；S5 EventBusInit = 0 ms；S6 ModInit = 127 ms；S7 Loaded = 12581 ms |
| 端到端（横幅 → S7 done） | 21000 ms |
| **到 TitleScreen**（用户能操作游戏） | 14401 ms |
| 堆峰值（锚点内最大 heapPeak） | 466 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 1051 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 4433 ms |
| S4 解冻 MappedRegistry | 21 ms |
| S4 装配方 | 4 ms |
| S7 Mirage 侧前置开销 | 12 ms |
| S7 MC 窗口创建前 | 2437 ms |
| 早期窗口 等 renderLock | 1 ms |
| 早期窗口 等渲染线程退出 | 1 ms |
| 早期窗口 GL 交接 | 7 ms |
| MC 资源重载 → TitleScreen | 3750 ms |

### 会话 13（Mirage 0.4.0，横幅 2026-11-01 16:44:25）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 110 ms；S2 Parse = 6 ms；S3 BytecodeTransform = 487 ms；S4 RegistryMerge = 4758 ms；S5 EventBusInit = 0 ms；S6 ModInit = 201 ms；S7 Loaded = 8648 ms |
| 端到端（横幅 → S7 done） | 15000 ms |
| **到 TitleScreen**（用户能操作游戏） | 12030 ms |
| 堆峰值（锚点内最大 heapPeak） | 475 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 746 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 3993 ms |
| S4 解冻 MappedRegistry | 15 ms |
| S4 装配方 | 2 ms |
| S7 Mirage 侧前置开销 | 14 ms |
| S7 MC 窗口创建前 | 1719 ms |
| 早期窗口 等 renderLock | 0 ms |
| 早期窗口 等渲染线程退出 | 1 ms |
| 早期窗口 GL 交接 | 7 ms |
| MC 资源重载 → TitleScreen | 3764 ms |

### 会话 14（Mirage 0.4.0，横幅 2026-11-07 09:45:12）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 868 ms；S2 Parse = 149 ms；S3 BytecodeTransform = 1567 ms；S4 RegistryMerge = 12265 ms；S5 EventBusInit = 0 ms；S6 ModInit = 530 ms；S7 Loaded = 18767 ms |
| 端到端（横幅 → S7 done） | 37000 ms |
| **到 TitleScreen**（用户能操作游戏） | 33160 ms |
| 堆峰值（锚点内最大 heapPeak） | 488 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 1958 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 10259 ms |
| S4 解冻 MappedRegistry | 37 ms |
| S4 装配方 | 5 ms |
| S7 Mirage 侧前置开销 | 18 ms |
| S7 MC 窗口创建前 | 5799 ms |
| 早期窗口 等 renderLock | 194 ms |
| 早期窗口 等渲染线程退出 | 46 ms |
| 早期窗口 GL 交接 | 89 ms |
| MC 资源重载 → TitleScreen | 7709 ms |

### 会话 15（Mirage 0.4.0，横幅 2026-11-07 10:15:36）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 139 ms；S2 Parse = 12 ms；S3 BytecodeTransform = 805 ms；S4 RegistryMerge = 5824 ms；S5 EventBusInit = 0 ms；S6 ModInit = 100 ms；S7 Loaded = 12510 ms |
| 端到端（横幅 → S7 done） | 20000 ms |
| **到 TitleScreen**（用户能操作游戏） | 13781 ms |
| 堆峰值（锚点内最大 heapPeak） | 467 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 1295 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 4510 ms |
| S4 解冻 MappedRegistry | 12 ms |
| S4 装配方 | 3 ms |
| S7 Mirage 侧前置开销 | 8 ms |
| S7 MC 窗口创建前 | 1682 ms |
| 早期窗口 等 renderLock | 1 ms |
| 早期窗口 等渲染线程退出 | 0 ms |
| 早期窗口 GL 交接 | 4 ms |
| MC 资源重载 → TitleScreen | 3975 ms |

### 会话 16（Mirage 0.4.0，横幅 2026-11-07 10:16:02）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 122 ms；S2 Parse = 8 ms；S3 BytecodeTransform = 522 ms；S4 RegistryMerge = 10049 ms；S5 EventBusInit = 0 ms；S6 ModInit = 422 ms；S7 Loaded = 44683 ms |
| 端到端（横幅 → S7 done） | 57000 ms |
| **到 TitleScreen**（用户能操作游戏） | 25736 ms |
| 堆峰值（锚点内最大 heapPeak） | 486 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 1000 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 8994 ms |
| S4 解冻 MappedRegistry | 33 ms |
| S4 装配方 | 16 ms |
| S7 Mirage 侧前置开销 | 24 ms |
| S7 MC 窗口创建前 | 7553 ms |
| 早期窗口 等 renderLock | 32 ms |
| 早期窗口 等渲染线程退出 | 11 ms |
| 早期窗口 GL 交接 | 87 ms |
| MC 资源重载 → TitleScreen | 5897 ms |

### 会话 17（Mirage 0.4.0，横幅 2026-11-07 10:56:32）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 167 ms；S2 Parse = 16 ms；S3 BytecodeTransform = 1236 ms；S4 RegistryMerge = 9361 ms；S5 EventBusInit = 0 ms；S6 ModInit = 152 ms；S7 Loaded = 12409 ms |
| 端到端（横幅 → S7 done） | 25000 ms |
| **到 TitleScreen**（用户能操作游戏） | 20603 ms |
| 堆峰值（锚点内最大 heapPeak） | 470 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 1759 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 7577 ms |
| S4 解冻 MappedRegistry | 17 ms |
| S4 装配方 | 4 ms |
| S7 Mirage 侧前置开销 | 13 ms |
| S7 MC 窗口创建前 | 2389 ms |
| 早期窗口 等 renderLock | 0 ms |
| 早期窗口 等渲染线程退出 | 5 ms |
| 早期窗口 GL 交接 | 5 ms |
| MC 资源重载 → TitleScreen | 5348 ms |

### 会话 18（Mirage 0.4.0，横幅 2026-11-07 11:21:05）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 180 ms；S2 Parse = 17 ms；S3 BytecodeTransform = 1050 ms；S4 RegistryMerge = 8796 ms；S5 EventBusInit = 0 ms；S6 ModInit = 139 ms；S7 Loaded = 12923 ms |
| 端到端（横幅 → S7 done） | 25000 ms |
| **到 TitleScreen**（用户能操作游戏） | 19811 ms |
| 堆峰值（锚点内最大 heapPeak） | 471 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 1920 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 6852 ms |
| S4 解冻 MappedRegistry | 17 ms |
| S4 装配方 | 3 ms |
| S7 Mirage 侧前置开销 | 10 ms |
| S7 MC 窗口创建前 | 2256 ms |
| 早期窗口 等 renderLock | 2 ms |
| 早期窗口 等渲染线程退出 | 1 ms |
| 早期窗口 GL 交接 | 5 ms |
| MC 资源重载 → TitleScreen | 5019 ms |

### 会话 19（Mirage 0.4.0，横幅 2026-11-07 11:22:01）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 199 ms；S2 Parse = 24 ms；S3 BytecodeTransform = 891 ms；S4 RegistryMerge = 12427 ms；S5 EventBusInit = 0 ms；S6 ModInit = 238 ms；S7 Loaded = 19367 ms |
| 端到端（横幅 → S7 done） | 34000 ms |
| **到 TitleScreen**（用户能操作游戏） | 25841 ms |
| 堆峰值（锚点内最大 heapPeak） | 503 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 1564 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 10818 ms |
| S4 解冻 MappedRegistry | 30 ms |
| S4 装配方 | 6 ms |
| S7 Mirage 侧前置开销 | 19 ms |
| S7 MC 窗口创建前 | 4354 ms |
| 早期窗口 等 renderLock | 6 ms |
| 早期窗口 等渲染线程退出 | 2 ms |
| 早期窗口 GL 交接 | 9 ms |
| MC 资源重载 → TitleScreen | 6044 ms |

### 会话 20（Mirage 0.4.0，横幅 2026-11-07 11:22:39）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 198 ms；S2 Parse = 29 ms；S3 BytecodeTransform = 1229 ms；S4 RegistryMerge = 12888 ms；S5 EventBusInit = 0 ms；S6 ModInit = 322 ms；S7 Loaded = 18932 ms |
| 端到端（横幅 → S7 done） | 35000 ms |
| **到 TitleScreen**（用户能操作游戏） | 27638 ms |
| 堆峰值（锚点内最大 heapPeak） | 476 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 2139 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 10712 ms |
| S4 解冻 MappedRegistry | 25 ms |
| S4 装配方 | 7 ms |
| S7 Mirage 侧前置开销 | 48 ms |
| S7 MC 窗口创建前 | 3888 ms |
| 早期窗口 等 renderLock | 3 ms |
| 早期窗口 等渲染线程退出 | 11 ms |
| 早期窗口 GL 交接 | 8 ms |
| MC 资源重载 → TitleScreen | 6931 ms |

### 会话 21（Mirage 0.4.0，横幅 2026-11-07 11:34:31）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 508 ms；S2 Parse = 122 ms；S3 BytecodeTransform = 2389 ms；S4 RegistryMerge = 12062 ms；S5 EventBusInit = 1 ms；S6 ModInit = 188 ms；S7 Loaded = 560983 ms |
| 端到端（横幅 → S7 done） | 582000 ms |
| **到 TitleScreen**（用户能操作游戏） | 29154 ms |
| 堆峰值（锚点内最大 heapPeak） | 716 MB |
| 锚点数 | 18 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 3526 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 8501 ms |
| S4 解冻 MappedRegistry | 21 ms |
| S4 装配方 | 7 ms |
| S7 Mirage 侧前置开销 | 15 ms |
| S7 MC 窗口创建前 | 2779 ms |
| 早期窗口 等 renderLock | 8 ms |
| 早期窗口 等渲染线程退出 | 4 ms |
| 早期窗口 GL 交接 | 7 ms |
| MC 资源重载 → TitleScreen | 5848 ms |

### 会话 22（Mirage 0.4.0，横幅 2026-11-07 12:00:23）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 163 ms；S2 Parse = 13 ms；S3 BytecodeTransform = 839 ms；S4 RegistryMerge = 7995 ms；S5 EventBusInit = 0 ms；S6 ModInit = 141 ms；S7 Loaded = 10766 ms |
| 端到端（横幅 → S7 done） | 21000 ms |
| **到 TitleScreen**（用户能操作游戏） | 18126 ms |
| 堆峰值（锚点内最大 heapPeak） | 476 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 1533 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 6436 ms |
| S4 解冻 MappedRegistry | 19 ms |
| S4 装配方 | 5 ms |
| S7 Mirage 侧前置开销 | 11 ms |
| S7 MC 窗口创建前 | 2473 ms |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 5 ms |
| MC 资源重载 → TitleScreen | 4551 ms |

**本会话测不了的维度**：
- 早期窗口交接：缺锚点 early.handoff.lock

### 会话 23（Mirage 0.4.0，横幅 2026-11-07 12:44:30）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 388 ms；S2 Parse = 55 ms；S3 BytecodeTransform = 1452 ms；S4 RegistryMerge = 10897 ms；S5 EventBusInit = 0 ms；S6 ModInit = 200 ms；S7 Loaded = 13314 ms |
| 端到端（横幅 → S7 done） | 28000 ms |
| **到 TitleScreen**（用户能操作游戏） | 24948 ms |
| 堆峰值（锚点内最大 heapPeak） | 484 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 2280 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 8583 ms |
| S4 解冻 MappedRegistry | 23 ms |
| S4 装配方 | 7 ms |
| S7 Mirage 侧前置开销 | 18 ms |
| S7 MC 窗口创建前 | 2967 ms |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 4 ms |
| MC 资源重载 → TitleScreen | 5778 ms |

**本会话测不了的维度**：
- 早期窗口交接：缺锚点 early.handoff.lock

### 会话 24（Mirage 0.4.0，横幅 2026-11-07 12:45:13）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 274 ms；S2 Parse = 13 ms；S3 BytecodeTransform = 1052 ms；S4 RegistryMerge = 10230 ms；S5 EventBusInit = 0 ms；S6 ModInit = 188 ms；S7 Loaded = 15987 ms |
| 端到端（横幅 → S7 done） | 30000 ms |
| **到 TitleScreen**（用户能操作游戏） | 23827 ms |
| 堆峰值（锚点内最大 heapPeak） | 469 MB |
| 锚点数 | 16 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 1729 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 8468 ms |
| S4 解冻 MappedRegistry | 20 ms |
| S4 装配方 | 10 ms |
| S7 Mirage 侧前置开销 | 14 ms |
| S7 MC 窗口创建前 | 3086 ms |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 5 ms |
| MC 资源重载 → TitleScreen | 5787 ms |

**本会话测不了的维度**：
- 早期窗口交接：缺锚点 early.handoff.lock

### 会话 25（Mirage 0.4.0，横幅 2026-11-07 12:47:23）

| 项 | 值 |
|---|---|
| 分阶段（LS 骨架行自带耗时） | S1 Discovery = 283 ms；S2 Parse = 24 ms；S3 BytecodeTransform = 1051 ms；S4 RegistryMerge = 10302 ms；S5 EventBusInit = 0 ms；S6 ModInit = 196 ms；S7 Loaded = 36335 ms |
| 端到端（横幅 → S7 done） | 50000 ms |
| **到 TitleScreen**（用户能操作游戏） | 23369 ms |
| 堆峰值（锚点内最大 heapPeak） | 667 MB |
| 锚点数 | 17 |

| 段 | 耗时 |
|---|---:|
| S4 SharedConstants.tryDetectVersion | 1635 ms |
| S4 Bootstrap.bootStrap (MC 原版) | 8637 ms |
| S4 解冻 MappedRegistry | 22 ms |
| S4 装配方 | 6 ms |
| S7 Mirage 侧前置开销 | 21 ms |
| S7 MC 窗口创建前 | 3190 ms |
| 早期窗口 等 renderLock | 缺锚点 |
| 早期窗口 等渲染线程退出 | 缺锚点 |
| 早期窗口 GL 交接 | 6 ms |
| MC 资源重载 → TitleScreen | 5506 ms |

**本会话测不了的维度**：
- 早期窗口交接：缺锚点 early.handoff.lock

## 体积

| 项 | 值 |
|---|---|
| D:\bing\MC\mcmoderr-my\mc-shili\libraries\io\mirage\mirage-launch\0.1.0\mirage-launch-0.1.0.jar | 2.78 MB（2916558 字节） |
| 修改时间 | 11/07/2026 11:46:17 |

## 本次测不了的维度

- S4 分段（缺 s4.begin, s4.version, s4.bootstrap, s4.unfreeze, s4.backend）
- S7 分段（缺 s7.begin, s7.pre）
- 早期窗口交接（缺 early.handoff.begin, early.handoff.lock, early.handoff.thread, early.handoff.done）
- TitleScreen 锚点（缺 titlescreen）
- 早期窗口交接（缺 early.handoff.lock）

> 说明：缺锚点通常意味着**部署的 jar 比源码旧**——锚点由 `io.mirage.loader.MiragePerf` 打出，
> 需要重新构建并部署 fat jar 后再启动一次游戏才有数据。

## 本轮核对说明（2026-11-07 人工追加，非脚本产物）

> 本节及下一节是 `-OutFile` 写盘后人工追加的，**不是 `scripts/measure_startup.ps1` 生成的**。
> 注意：下次再带 `-OutFile` 运行会整体覆盖本文件，这两节会被抹掉，需按规则 9「只标不删」重新追加。

- **自检**：`-SelfTest` 实跑 **PASS 26 / FAIL 0，退出码 0**。派单口径写的「23 断言」是 2026-11-01 版脚本的旧数字，现已扩到 26 条（补了「段定义表自洽」「渲染层缺锚点显示」「不含 0 ms 假数据」等护栏），以实跑为准。
- **锚点齐全判定**：09:45:12 会话（会话 14）四组必需锚点全在 —— S4 五点（`s4.begin` / `s4.version` / `s4.bootstrap` / `s4.unfreeze` / `s4.backend`）、S7 两点、早期窗口交接四点、`titlescreen`；共 16 个锚点，10 条段定义**全部算出耗时**，该会话下没有「本会话测不了的维度」段。实机锚点与派单一致：`s4.begin 6496` → `s4.bootstrap 18713` → `s7.begin 19305` → 交接 `25122~25451` → `titlescreen 33160`（同帧第二次 `33209`）→ `s7.post 38067`，堆峰值 **488 MB**。
- **别误读顶部的「本次测不了的维度」**：该节是全量模式的**跨会话聚合**，来源是会话 1–11（2026-10-31 ~ 11-01 埋点部署前的旧 jar，本来就没有 `[LS] perf` 行），不是 09:45 那次缺锚点。脚本按设计如实报缺、不填 0，所以旧会话会把这四组顶出来。要看单次干净视图用 `-LastSession`。
- **`titlescreen` 打了两次**：日志里同一帧出现 `33160` / `33209` 两行，`Get-AnchorMap` 取首次出现，所以「到 TitleScreen」= 33160 ms；`33209` 只作旁证，不参与分段计算。
- **写盘期间日志又增长了**：写盘时 `mirage-launch.log` 里新增了 10:15:36 与 10:16:02 两次启动，都是 16 锚点齐全，因此一并进了总览（会话 15 / 16）。09:45 那次仍是**会话 14**，编号未变。**这两次是否影响「进世界 8~30s 自断」的手测判定由用户拍板，本轮不代判。**
- **体积行的口径**：脚本读的是**当次运行时磁盘上那一份** `mirage-launch-0.1.0.jar`（2 910 948 字节，修改时间 2026-11-07 10:09），并非 09:45 那次启动所加载的 jar。两者不是同一份构建产物，不要把该行当成 09:45 会话的实测体积（时间线三方对齐，见 `AGENTS.md` 技术注意事项 §5）。
- **报告未改脚本、未改任何 `.java`**：纯观测，零代码改动。
- **【2026-11-07 12:52 补】口径段已进生成器**：顶部「## 口径：绝对值是单次样本，不能当阈值」由脚本 `Format-DispersionSection` 生成，**不再是手写段** ⇒ 下次 `-OutFile` 不会再把这句话丢掉。本批 14 个带 `titlescreen` 的会话：最小 **12030 ms** / 中位数 **23598 ms** / 最大 **33160 ms**，跨度 **2.8 倍**，与台账「六次同产物 13781~27638 ms、跨度 2 倍」互相印证 ⇒ 绝对耗时**不可当阈值/回归判据**。
- **【2026-11-07 12:52 补】自检扩到 33 条**：原先 26 条，本轮新增离散度 7 条（只统计有 `titlescreen` 的会话、min/median/max/倍率、无样本返回 `null` 而非 0、口径段必须含「不能当阈值」与倍率）。变异检验两次确认非白写：去掉 null 过滤 → 红 6 条；口径标题改词 → 红 1 条。
- **【2026-11-07 12:52 补】总览已增至 25 个会话**：12:00 / 12:44 / 12:45 / 12:47 又各启动一次（用户实机），09:45 那次仍是**会话 14**、编号未变。会话 21 起的 `锚点` 列出现 18/17，是因为 `titlescreen` 同帧被打多行（18 = 15 个不同标签 + 3 行重复）加上新埋点 `s6.patch` / `s6.close`，**不是**判定口径变化。
- **【2026-11-07 12:52 补】`s6.patch` / `s6.close` 报了但没算**：这两个锚点（包住 `window.refreeze()` 注册表重新冻结）自 11:34 后的包开始输出，但脚本既没定义对应段、也没进必需组 ⇒ 等于白埋。**本轮不擅自扩段定义**（会把「10 条段」的既有口径改成 11 条并让旧会话多一行「缺锚点」），已记入待核对清单待裁决。

---

## 附录：2026-11-01 人工版基线全文（原文保留 · 只标不删）

> **【已过时·2026-11-07】** 以下为 `-OutFile` 覆盖前 `docs/perf-baseline.md` 的全文原文，按 `AGENTS.md` 规则 9「只标不删」原样保留，用于追溯「当时为什么这么判断」。**上方脚本生成部分才是当前数据源**。
> 原文中「到 TitleScreen 该列仍是『缺』」「峰值内存：锚点已就位，数据待采」「数据待下一次实机冒烟」等表述**已被本次实机数据取代**：09:45:12 会话到 TitleScreen = **33160 ms**、堆峰值 = **488 MB**、16 个锚点齐全、四组必需锚点零缺失。
> 原文的 §1 测量口径、§5 结论与 Q9 重定建议、§7 第一版错误更正记录仍然有效，不受覆盖影响。

# 性能与体积基线（Phase 1 验收标准 4）

> **本文件的唯一数据源是 `scripts/measure_startup.ps1`，不要手抄。**
> 手抄会漏数据——第一版就是手抄的，漏了整个 S7 阶段和一个 40 秒的会话（见 §7 更正记录）。
>
> 采集时间：2026-10-31 ~ 2026-11-01
> 数据来源：本机实机日志 `mc-shili/versions/26.2-mirage/logs/mirage-launch.log`（8 个会话）
> 环境：Windows 11 / Intel UHD Graphics / JDK 25.0.4 / MC 26.2 / 4 个测试 mod（examplemod / testtab / addonmod / expore）
> **本文只记实测数字，不记目标值。** 目标由 Q9 裁决后另立（见 §5）。

---

## 1. 测量口径（先说清楚，免得数字被误读）

| 指标 | 起点 | 终点 |
|---|---|---|
| 端到端启动 | `Launcher` 打印启动横幅（`===== Mirage Launch 0.4.0 @ ...`） | `[LS] stage S7 Loaded done` |
| MG 自身阶段 | `S1 Discovery start` | `S6 ModInit done` |
| 单阶段 | 该阶段 `start` 行 | 该阶段 `done (N ms)` 行 |
| **到 TitleScreen** | 启动横幅 | `TitleScreen.init()` 触发（**用户真正能操作游戏的时刻**） |

**注意**：`S7 Loaded done` 是「mod 加载管线结束」，此时 MC 主类刚启动、**资源重载与 TitleScreen 尚未完成**。
所以「端到端启动」这一列**不是**用户感知的"点开游戏到能玩"。

**「到 TitleScreen」这一项需要锚点**。第一版没有锚点，本轮已在
`io.mirage.loader.MiragePerf` 里补上（`[LS] perf titlescreen t=... heapUsed=... heapPeak=...`），
**但数据要等新 jar 部署后由用户跑一次游戏才有**——当前表里该列仍是"缺"。

---

## 2. 分阶段实测（毫秒，8 个会话全量）

> 由 `scripts/measure_startup.ps1` 从 `mirage-launch.log` 解析生成。
> "缺"= 该会话的日志行里没有这一阶段（或没有带墙钟时间戳）。

| 会话 | 横幅时刻 | S1 发现 | S2 解析 | S3 转译 | **S4 注册表合并** | S5 事件总线 | S6 mod 初始化 | **S7 Loaded** |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 17:28:54 | 124 | 4 | 954 | 9 708 | 0 | 1 684 | 缺 |
| 2 | 17:29:08 | 135 | 5 | 770 | **40 213** | 0 | 135 | 34 278 |
| 3 | 17:39:45 | 145 | 4 | 1 933 | 22 685 | 0 | 183 | **51 799** |
| 4 | 17:49:00 | 111 | 4 | 741 | 6 764 | 0 | 416 | 缺 |
| 5 | 17:50:12 | 103 | 2 | 910 | 11 732 | 0 | 108 | 23 594 |
| 6 | 17:52:18 | 110 | 5 | 574 | 25 918 | 0 | 257 | **68 075** |
| 7 | 17:59:14 | 97 | 3 | 569 | 7 433 | 0 | 517 | 34 972 |
| 8 | 22:15:45 | — | — | — | — | — | — | — |

（会话 8 只打了横幅，阶段行缺失——大概是启动早期就退出了，不计入统计。）

**区间**：
- S1 发现 97 ~ 145 ms｜S2 解析 2 ~ 5 ms｜S3 转译 569 ~ 1 933 ms｜S5 恒为 0 ms（空操作）
- **S4 注册表合并 6 764 ~ 40 213 ms**（波动 6 倍）
- **S7 Loaded 23 594 ~ 68 075 ms**（波动 2.9 倍）← **第一版整段漏掉了**
- **MG 自身计算开销**（S1+S2+S3+S5+S6，不含 S4/S7）：**946 ~ 2 766 ms**

---

## 3. 端到端实测（横幅 → S7 done）

| 会话 | 端到端 | 其中 S4 | 其中 S7 |
|---:|---:|---:|---:|
| 2 | 34 000 ms | 40 213 ms | 34 278 ms |
| 3 | 79 000 ms | 22 685 ms | 51 799 ms |
| 5 | 38 000 ms | 11 732 ms | 23 594 ms |
| 6 | 97 000 ms | 25 918 ms | 68 075 ms |
| 7 | 45 000 ms | 7 433 ms | 34 972 ms |

区间 **34 ~ 97 秒**，波动 2.9 倍。（会话 1/4/8 缺 S7 done 的墙钟时间戳——log4j 尚未接管时打的行没有前缀。）

---

## 4. 体积

| 项 | 实测 |
|---|---|
| `mirage-launch-0.1.0.jar`（fat jar，含 Mixin / ASM / Gson / fabric-compat stub） | **2.73 MB**（2 857 612 字节，含本轮新增的 `MiragePerf`） |
| 其中 `net.fabricmc.*` 运行时 stub（11 个类） | 数十 KB 量级 |
| 空壳模块 `mirage-config` / `mirage-kotlin` / `mirage-network` | 各产一个 0.1.0 的**空 jar**（Phase 1 模块懒加载落地后可改为按需加载） |

**峰值内存**：**锚点已就位，数据待采**。
本轮新增的 `MiragePerf.mark` 每个锚点都带 `heapUsed` / `heapPeak`（堆峰值由 JMX 各堆池
`getPeakUsage()` 求和，是**近似值**——G1 的 Eden 与 Survivor 峰值会重复计入同一批对象）。
下一次实机冒烟后由脚本自动填入，不需要用户再手跑命令。

> 堆峰值 ≠ 进程工作集。进程级 `PeakWorkingSet64` 仍需在游戏运行时采样，
> 脚本支持 `-PeakMemoryPid <pid>` 直接采（PowerShell 侧一条命令）。

启动参数里的 `-Xmx1740m -Xmn261m` 是**上限配置**，不是实测占用，两者不要混。

---

## 5. 结论与建议（建议，待 Q9 裁决）

1. **Q9 原来的「< 2s 启动」不成立**——它从提出起就没被测量过。实测端到端是 **34 ~ 97 秒**，
   与目标差一个多数量级。**Q9 必须重定**，否则 Phase 1 的验收标准 4 无法客观判定"达标"。
2. **建议把目标拆成三项可分别判定**：
   - **MG 自身计算开销**（S1+S2+S3+S5+S6，不含 S4/S7）：**当前 946 ~ 2 766 ms**
     → 目标可定 **< 3 s**（已达成；原建议的 < 2 s 在冷启动会话里会差一点）。
   - **S4 注册表合并**：**6.8 ~ 40.2 s**。
   - **S7 Loaded**：**23.6 ~ 68.1 s**。
3. **S4 与 S7 是两个并列的大头，S7 更大**（第一版说"S4 是唯一的大头"，那是漏了 S7 的误判，见 §7）。
   更关键的是：**这两个大头很可能大部分都不是 Mirage 的开销**——
   - S4 内部主要是 `Bootstrap.bootStrap()`，那是 **MC 原版**的注册表填充与冻结；
   - S7 内部主要是 MC 的窗口创建与资源重载，Mirage 只占早期窗口交接那几秒。
   本轮加的锚点正是为了把这两段拆开（`s4.version` / `s4.bootstrap` / `s4.unfreeze` /
   `s4.backend` / `early.handoff.*` / `titlescreen`）。**锚点数据回来之前，不对优化方向下结论。**
4. **补 TitleScreen 锚点**：已完成（`MiragePerf.mark("titlescreen")`，挂在 `TitleScreen.init()`），
   数据待下一次实机冒烟。
5. **体积**：2.73 MB，与 `< 3 MB` 的既有目标相安有余（余量约 9%）。若后续引入 KubeJS 这类
   重依赖，按 AG 总计划 Q3 的口径应做成**独立可选模块**，不要进核心 fat jar。

---

## 6. 怎么重新采

```powershell
# 跑完游戏后（用户双击 ..\启动 26.2-mirage.bat），重新生成基线：
powershell -ExecutionPolicy Bypass -File .\scripts\measure_startup.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\measure_startup.ps1 -OutFile ..\docs\perf-baseline-auto.md

# 只看最后一次会话：
powershell -ExecutionPolicy Bypass -File .\scripts\measure_startup.ps1 -LastSession

# 游戏正在运行时顺带采进程级峰值内存：
powershell -ExecutionPolicy Bypass -File .\scripts\measure_startup.ps1 -PeakMemoryPid <pid>

# 脚本自身的回归（合成日志，不依赖真实日志）：
powershell -ExecutionPolicy Bypass -File .\scripts\measure_startup.ps1 -SelfTest
```

脚本会**如实报告哪些维度测不了**（缺锚点时列出来），而不是填 0 糊过去——
"0 ms" 会被读成"这段不花时间"，是假数据。

---

## 7. 更正记录（只标不删）

### 【已过时·2026-11-01】第一版的分阶段表（漏了 S7，且漏了一个 40 秒的会话）

第一版只列 S1~S6、只记了 6 个会话中的 4 个，S4 记为 6 764 ~ 25 918 ms。
**错在两处**：

1. **整段漏掉 S7 Loaded**。当时把 "S7 Loaded done" 当成终点而不是一个阶段，
   于是占了 23.6 ~ 68.1 秒的 S7 从表里消失，得出"S4 是唯一的大头"的结论。
2. **漏掉会话 2**（S4 = 40 213 ms，S3 = 1 933 ms，S6 = 135 ms），
   于是 S4 的上界被记成 25 918 ms，比实测小了 55%。

第一版表格（原文保留）：

| 阶段 | 会话 1 | 会话 2 | 会话 3 | 会话 4 | 性质 |
|---|---:|---:|---:|---:|---|
| S1 Discovery（mod 发现） | 111 | 103 | 110 | 97 | 毫秒级，稳 |
| S2 Parse（描述符解析） | 4 | 2 | 5 | 3 | 毫秒级，稳 |
| S3 BytecodeTransform（字节码转译） | 741 | 910 | 574 | 569 | **亚秒级**，随 mod 数与类数增长 |
| S4 RegistryMerge（注册表合并） | 6764 | 11732 | **25918** | 7433 | **⚠ 唯一的大头，且波动 4 倍** |
| S5 EventBusInit | 0 | 0 | 0 | 0 | 空操作 |
| S6 ModInit（mod 初始化） | 416 | 108 | 257 | 517 | 亚秒级 |

第一版端到端结论（原文保留）：「区间 **23.6 ~ 70.3 秒**，波动 3 倍。」

**更正后**：端到端 **34 ~ 97 秒**（第 2、3、7 节表格），S4 **6.8 ~ 40.2 秒**，S7 **23.6 ~ 68.1 秒**。
**根因不是"抄错了数字"，是"抄的时候只看了自己关心的阶段"**——
这正是把测量写成脚本的理由（§6）。

### 【已过时·2026-11-01】第一版「峰值内存：未测」

第一版写「峰值内存：未测……因此这里不填估计值」。本轮补了 `MiragePerf` 的
`heapUsed` / `heapPeak` 锚点，**数据待下一次实机冒烟自动填入**，见 §4。

---

## 8. 变更记录

| 日期 | 动作 | 说明 |
|---|---|---|
| 2026-11-01 | 创建 | Phase 1「性能与体积基线测量」首版：6 次实机日志的分阶段数据、体积、结论与 Q9 重定建议。峰值内存因未自行启动游戏而标为待测，给出复现方法。 |
| 2026-11-01 | **修订** | 改用 `scripts/measure_startup.ps1` 为唯一数据源；补全 8 个会话的 S1~S7；标注第一版两处错误（漏 S7、漏会话 2）；新增 §6 采集方法、§7 更正记录。新增 `MiragePerf` 锚点（TitleScreen / S4 四段 / 早期窗口交接三段 / S7 两段），数据待实机冒烟。 |
