---
title: RW-Mod-Injector 开发者文档
description: 注入链路、装配器结构、快照与 restore 语义、zip 处理约束、热重载安全窗口与构建测试。
order: 2
tags:
  - rw-mod-injector
  - development
updatedAt: 2026-11-08
---

# RW-Mod-Injector — 开发

## 形态

注入器是注入客户端 JVM 的 **Java agent**（`premain`/`agentmain` + `Instrumentation`），与游戏同进程；不是 mods 目录里的 RW mod，也不是独立进程装配器。字节码目标 Java 8（客户端 JVM 是 Java 8），零第三方运行时依赖（JDK 自带 HTTP / `MessageDigest` SHA-256 / zip）；ASM 9.7 仅用于聊天入口注入桩，shade 进 agent jar。

## 代码结构

```
injector/src/main/java/com/corematch/rwmodinjector/
  InjectorAgent.java        agent 入口（premain/agentmain）
  ChatHookTransformer.java  聊天发送入口的 ASM 注入桩（只在方法体开头加拦截分支，不新增成员）
  Commands.java             /inj 命令分发（::tryHandle；命中即消费，不发往服务器）
  Assembler.java            装配主流程（::assembleAndJoin / ::restore / ::friendlyFailure）
  Engine.java               引擎接缝（::reloadUnits / ::modsDirectory / ::reconnectLast / ::systemMessage）
  Manifest.java             房间模组清单解析（::parse）
  Http.java                 清单与文件下载（含大小上限）
  Config.java               rw-mod-injector.properties 读取（::load）
  StateStore.java           装配快照持久化
  Core.java / Json.java     就绪门控 / JSON
installer/install.sh        安装器（游戏目录旁新增文件，不改客户端）
scripts/regress-e2e.sh      真环境回归
ci/build-ci.sh              CI 构建（stub 编译，不依赖 game-lib.jar）
```

## 构建与测试

```bash
./injector/build.sh <游戏目录> [asm-jar]
# 游戏目录须含 game-lib.jar 与 libs/：仅作编译期符号来源，不打进产物、不修改、不分发
# 产物：injector/build/rw-mod-injector.jar（Premain/Agent 双入口）
```

- CI 构建：`ci/build-ci.sh`（用 `ci/stubs` 替代 game-lib 符号）。
- 失败分类自测试：`injector/src/test/java/com/corematch/rwmodinjector/SelfTest.java::failureClassification`。
- 真环境回归：`bash scripts/regress-e2e.sh`，见 [README](./README.md) 的「真环境回归」节与[脚本](scripts/regress-e2e.sh)头注。

## 注入链路与线程模型

- 聊天入口：`Commands.java::tryHandle` 挂在聊天发送唯一入口的注入桩上；`/inj` 命令命中即消费，绝不发往服务器；命令层异常绝不影响正常聊天。
- 一切触碰引擎状态（单位表/模组/聊天/连接）的调用都经 `ScriptEngine.addRunnableToQueue` 投递 UI 线程（渲染循环每帧排空，大厅等所有状态可用）。
- **禁用** `gameFramework.q` 队列：该队列只在局内态排空，大厅投递永不执行（实测卡死证实）。
- 进度播报走引擎自己的系统消息 API（`Engine.java::systemMessage`）。

## 热重载的安全窗口

- 引擎门控：多人会话活跃时拒绝重载单位（"Need to keep network sync"）；局中重载会破坏模拟/脱同步，注入器代码强制此门。
- 服务端每秒广播 SERVER_INFO(106)，客户端每次收到都拿本地单位注册表核对；连接中热重载会命中半重建空窗被 "Missing unit" 断线。结论：**干净离房 → 主菜态重载 → 重连**，顺序不可换（`Assembler.java::assembleAndJoin`）。
- 注册 checksum 只覆盖基础单位，`Engine.java::reloadUnits` 调用的引擎全量重建入口不重算它；自定义单位核对在每次新连接收 SERVER_INFO 时执行——重载后的下一次连接按新单位表校验。"已注册但禁用"的模组不会被核对拒绝，只有条目完全缺失或重载空窗才被拒。

## 快照与 restore

- 快照时机是硬约束：**必须在物化之前**对当前全部已装模组的启用态拍快照并落盘（`StateStore`）。若在物化后扫描，新模组会从 preferences 继承旧启用态键被记成"原本启用"，restore 就禁不掉它（演练实测）。
- `/inj restore`（`Assembler.java::restore`）：离房 → 主菜态按快照恢复启用态、保存设置 → 重载单位、刷新玩家 → 重连；新装模组一律禁用；快照键恢复后全清。
- 重载失败回退：catch Throwable → 恢复快照并重载 → 把玩家送回原服务器（抛 `unit reload failed`）。

## zip 处理约束

- zip 只是传输形态：桌面客户端实际只从 `mods/units` 装载**目录型**模组，zip 原样落盘不被登记。已校验 zip 缓存在引擎模组目录之外的 `store/`（引擎扫描器会把模组目录里每个条目都登记成模组——缓存与临时文件绝不能落那里），再解压物化为目录模组。
- 解压用 `ZipFile`（严格校验中央目录），**不用** `ZipInputStream`——后者对非 zip 字节静默返回"无条目"，会让损坏内容物化成空模组冒充成功（演练实测暴露并修复）；且要求至少一条目。
- 物化先解压到同位置 `.inj-tmp-*` 临时目录，成功后整体改名就位；引擎扫描器永远看不到半解压状态。
- zip-slip 防护：逐条目落点必须在临时目录内（`unsafe path in zip` 拒绝）。
- `file` 字段白名单 `[A-Za-z0-9._-]`，禁 `../` 与绝对路径。
- 下载：临时文件（`.inj-*part`）→ 校验大小 → 校验 SHA-256 → 通过才改名进 store；哈希不符删除临时文件且**绝不重试**（内容不可信）。

## 信任模型与上限

- SHA-256 是唯一判定：清单对账、下载校验、"是否已具备"全部按内容哈希；绝不"同名即视为已具备"。
- 清单结构 `{mods:[{name,file,sha256,size}], sig, sigAlg}`：`sig`/`sigAlg` 为签名扩展位，v1 恒空未启用；校验走单一 `Verifier` 接缝。
- 大小上限：单模组（`MAX_SINGLE_MOD`）与单次装配总量（`Http.MAX_TOTAL_DOWNLOAD`），超限拒绝。
- 红线：凭据不入库、不进注入器、不进清单；不改客户端游戏文件；不碰引擎 kick/校验逻辑；不做反作弊绕过；v1 清单禁止收录覆写基础单位数据的 mod（会改变注册 checksum，重载无法修复）。
