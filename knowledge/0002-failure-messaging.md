# 0002. 装配失败的玩家可见文案（触发条件 / 玩家所见 / 日志所留 / 下一步）

## Status

Final（2026-11-08）。分类入口 `Assembler.friendlyFailure`，由
`Assembler.joinAsync` 的兜底捕获调用；玩家侧经 `Engine.systemMessage`
投递（聊天栏系统消息 + stdout 镜像 `[Injector] <文案>`）；技术细节
（URL / 哈希 / 堆栈）只进 stderr 日志，不进聊天栏。

原则：

- 玩家看到 **结论 + 下一步**，英语（项目 i18n 基调），每类失败一句；
- 分类按装配器自己的报错文案匹配——文案由代码各步骤稳定产出
  （`fetchWithRetry` / `downloadVerified` / `extractZip` / 门控检查），
  不依赖引擎异常措辞；
- 匹配顺序即下表顺序：先具体后宽泛（清单 404 优先于通用 "HTTP …"，
  哈希不符优先于下载失败），避免误分类。

## 失败文案表

| 类别 | 触发条件（符号锚点） | 玩家看到 | 日志里留 | 建议下一步 |
| --- | --- | --- | --- | --- |
| 清单拉不到 | `fetchWithRetry` 重试一次后放弃（404 / 超时 / JSON 非法）→ `cannot fetch mod manifest` | Couldn't fetch the mod list for this room (control server unreachable?). You stayed in the lobby - try /inj join again in a moment. | HTTP 状态码、清单 URL、异常栈 | 稍后重试 /inj join |
| 哈希不符 | `downloadVerified` SHA-256 校验失败 → SecurityException `hash mismatch`（**不重试**，内容不可信） | Security check failed: a mod file did not match the room manifest and was NOT installed. The room's mod pack may be corrupted - tell the server operator. | got/want 哈希对照、异常栈 | 找服务端运营 |
| 下载截断 | `downloadVerified` 大小校验失败 → `size mismatch` | A mod download arrived incomplete. You stayed in the lobby - try /inj join again. | got/want 字节数 | 重试 /inj join |
| 磁盘不可写 | `assembleAndJoin` 的 `modsDir.canWrite` 预检、store 创建/改名、`extractZip` 就位失败 → `not writable` / `cannot create mod store directory` / `cannot store mod file` / `cannot place mod directory` | Can't write to the mod folder (permissions or disk full?). Fix it and try /inj join again. | 具体路径 + IOException | 修权限/磁盘后重试 |
| 体积超限 | 单模组超 `MAX_SINGLE_MOD` 或累计超 `Http.MAX_TOTAL_DOWNLOAD` → `exceeds the size limit` | The room's mod pack is too large to install. Tell the server operator. | 条目名 / 大小 | 找服务端运营 |
| zip 损坏 | `extractZip` 失败：ZipFile 解析错（非 zip / 中央目录坏）、`zip archive contains no entries`、`unsafe path in zip`（zip-slip 防护） | A mod file is corrupt and was NOT installed. The room's mod pack may be broken - tell the server operator. | ZipException 细节、临时目录自清记录 | 找服务端运营 |
| 下载失败 | 文件端点非 200 / 连接中断 → `HTTP <code> from …` / `download failed`（重试一次） | Downloading a mod file failed (server problem). You stayed in the lobby - try /inj join again. | HTTP 状态码、文件 URL | 重试 /inj join |
| 未连大厅 | `assembleAndJoin` 门控 `Engine.multiplayerActive` 为假 → `not connected to a lobby` | Connect to the lobby first, then use /inj join \<room code\>. | IllegalStateException 栈 | 先连大厅再 /inj join |
| 重载失败 | `Engine.reloadUnits` 抛出 → 恢复快照并重载、送回原服务器后抛 `unit reload failed` | Applying the mod set failed during the unit reload. Your previous mod set was restored - if the game misbehaves, restart it. | 原始异常、回退动作记录 | 重启游戏 |
| 重连失败 | `reconnectAndWait` 中 `Engine.reconnectLast` 返回假或超时 → `cannot reconnect` | Mods are applied, but reconnecting to the lobby failed - please reconnect manually. | 异常信息 | 手动重连 |
| 未就绪 | `Engine.gameReady` / `Core.config` 未就位 | The injector is not ready yet - wait a moment and try /inj join again. | 异常信息 | 稍等重试 |
| 兜底 | 以上皆不匹配 | Mod assembly failed unexpectedly - check the game log for details, then try /inj join again. | 异常消息 + 完整栈 | 查日志后重试 |

## 非失败的玩家可见消息（进度与门控）

| 场景 | 文案 | 锚点 |
| --- | --- | --- |
| 装配进行中再触发 | assembly already in progress | `joinAsync` / `restoreAsync` 的 `busy` 门控 |
| 无快照可恢复 | no assembly snapshot to restore | `restoreAsync` / `restore` 的 `snapshot.taken` 检查 |
| 进度播报 | fetching / downloading / installing / leaving the room / applying mod set / hot-reloading / reconnecting / assembled. jumping via /goto / done | `assembleAndJoin` 各步骤 |
| 恢复播报 | leaving the room to restore / previous mod set restored | `restoreAsync` |

## 实测记录

- 2026-11-08 假控制面演练：manifest-404 与 hash-mismatch 两场景的聊天栏
  文案逐字确证（`[Injector]` stdout 镜像）。
- 2026-11-08 真环境回归（真客户端 + 双真 Avalon + 真 Cascade）：
  主链路（连大厅 → 装配 → 离房重载 → 重连 → /goto → 在位）与 60s 自动
  restore 全程**零误报**——最易错报的"未连大厅"与"重载失败"两类在正常
  时序下未出现；restore 回快照态验证通过（新装模组禁用、内置模组回
  快照态、快照键全清）。
