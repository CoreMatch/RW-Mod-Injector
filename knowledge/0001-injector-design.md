# 0001. RW-Mod-Injector 设计定稿（mod 形态 · 自建注入 · 热重载）

## Status

Final（2026-11-08，按作者四项决策定稿）。热重载可行性已对闭源引擎
`game-lib.jar` 完成字节码级验证，结论：**可行**（证据链见 §2）。
2026-11-08 当日真客户端 + 真 Avalon/Cascade E2E 实测：主路径与全部失败
路径通过；实测修正（目录型物化、离房-重载-重连、UI 线程投递通道、
ZipFile 严格校验、快照时机）已折叠进对应章节，均以"E2E 实测修正"标注。
前置事实依据：Avalon `knowledge/0009-zero-mod-lobby-and-mod-assembly.md`
（机制①/②、零模组大厅、跳转包），本文不重复论证，只引用其结论。

## 0. 已定决策（不再复议）

| # | 决策 | 含义 |
| --- | --- | --- |
| D1 | **自建注入** | 不依赖 RWML 或任何第三方 mod 加载器；用 Java agent 打桩把 Injector 挂进游戏客户端进程。手法参照 Avalon-Helper 打桩引擎（`AgentAttachData` + ASM 重定向），**但不改 Helper** |
| D2 | **热重载** | 装配完成后不重启游戏，运行期让新模组集生效。硬要求；可行性验证通过（§2） |
| D3 | **只校验内容哈希** | SHA-256 为唯一判定；不做签名，签名留扩展位（§3） |
| D4 | **安装器引导** | 玩家双击一次安装器完成首个入口，不手动往目录丢文件（§4） |

边界（不变）：读房间模组清单、拉取、按哈希校验、落盘、生效；不碰引擎
kick/校验逻辑；不做反作弊绕过；凭据不入库；不改客户端游戏文件。

---

## 1. 形态：Injector 是什么

**Injector = 注入到 RW 客户端 JVM 的 Java agent**（`premain`/`agentmain` +
`Instrumentation`），与游戏同进程。它不是 mods 目录里的 RW mod，也不是
独立进程装配器（0009 的"外挂器"设想作废，本文档为唯一版本）。

选择理由：

- **独立进程装配器**需要跨进程协调"何时装配、何时重连"，无法拦截游戏内
  操作，玩家要在两个程序间来回切换；装配生效只能靠重启游戏，与 D2 冲突。
- **第三方加载器（RWML 等）**引入不可控依赖与信任链，且其加载语义不受
  我们约束，与"按内容哈希精确装配"冲突。
- **同进程注入**可以直接：调引擎自带的重载入口（§2）、读写模组目录、
  拦截聊天命令、用引擎自己的系统消息 API 报告进度、在进程内发起重连。
  与 Avalon-Helper 对引擎的打桩是同一手法（参考
  `Avalon-Helper: Server-Core/.../dependent/AgentAttachData.kt`、
  `ASM-Framework/` 与各 `*Redirections.kt`；只参考，不修改）。

运行环境事实（本机实测）：

- 官方 Linux 客户端：`/home/lnb/Downloads/Rusted Warfare/`，
  自带 `jvm-linux`（**Java 1.8.0_131**），启动脚本
  `rustedWarfareLinux.sh` 以
  `-cp "game-lib.jar:libs/*" com.corrodinggames.rts.java.Main "$@"` 启动。
- **注意（核查修正）**：`"$@"` 位于主类之后，是**程序参数**而非 JVM 参数
  —— Steam 启动项塞 `-javaagent` 无效。注入走安装器生成的**包装启动
  脚本**（复刻官方 java 命令 + `-javaagent`，新增文件，不改任何客户端
  文件），见 §4。
- Injector 字节码目标 Java 8（实测产物 major 52），零第三方运行时依赖
  （JDK 自带 HTTP / `MessageDigest` SHA-256 / zip）；ASM 9.7（字节码 49）
  仅用于聊天入口注入桩，shade 进 agent jar。

---

## 2. 热重载可行性验证（D2 的门）——结论：可行

以下全部来自对 `Server-Core/libs/game-lib.jar`（Avalon-Helper 仓库）的
javap 反汇编，符号为 jar 内混淆名。

### 2.1 引擎自带运行期重载管线（不是我们发明的）

| 符号 | 语义 | 证据 |
| --- | --- | --- |
| `units.custom.ag.h()` | **全量重建自定义单位**：清注册表 `custom/l.c`（synchronized）→ 重载 vanilla units → 按 ModManager 列表从磁盘逐个加载启用的 mod（本地 + workshop 两轮）→ 收尾 `System.gc()` | Helper 的 `reloadUnitData()` 就是调它（`GameUnitData.kt:34`） |
| `gameFramework.i.a.k()` | **磁盘重扫**：扫 `ag.m()`（用户 mod 目录）、`builtin_mods`、`builtin_mods_enabled` 三个目录；新 mod 入列、删除"系统上已不存在"的 mod、管理新 mod 启用状态 | 游戏内 Mods 界面 `librocket.scripts.Mods.loadMods()` 运行期就调它 |
| `gameFramework.i.a.a(boolean,boolean)` | **引擎内置"应用 mod 变更"**：`k()` 重扫 + `ag.h()`（全量）或 `ag.b()`（只快_reload_ 地图上存活单位——引擎原生支持运行期热补单位数据） | 引擎自带开发者命令 "Reload units" / "Reload active only" 运行期执行此序列，完成后对每个玩家调 `game.a.a.al()` 刷新 |
| `gameFramework.l.a(Runnable)` | 入 `gameFramework.q` 队列，由 `game.i.b(float,int)` 每帧排空执行 | **E2E 实测修正：q 队列只局内态排空，大厅投递永不执行（死锁），Injector 禁用**；UI 线程投递用 `ScriptEngine.addRunnableToQueue`（§2.3） |

**Helper 先例**：`ModManage.reLoadMods` = `reBootServer { clean();
reloadUnitData() }`，其中 `reBootServer` 是"踢掉玩家→重载→重新监听"
（`LinkGameNet.kt:133`），**不是重启进程**。服务端要断玩家是因为
SERVER_INFO 已发过/可能在局中；客户端同理——E2E 实测修正：连接中重载
会被 SERVER_INFO 单位核对打下来，须**离房后在主菜态重载**（§2.2）。

### 2.2 安全窗口：何时能重载、何时绝对不能

引擎自己的重载命令带门控：多人会话活跃标志
`gameFramework.ba.P`（连上服务器即置真，`ba.d(String)` 等）为真时拒绝，
文案 **"Not reloading units: Need to keep network sync"**。同步风险只在
**模拟运行时**成立。结合 0009 §1.3：

- **大厅态（注册后、START_GAME 前）引擎不模拟、不 spawn 任何单位** →
  重载安全。装配在玩家连上零模组大厅后开始（取清单/下载），
  **重载一步在离房后的主菜态执行**（§7，E2E 实测修正）。
- **局中禁止重载**（会破坏模拟/脱同步）——Injector 代码强制此门。
- 机制①（注册 checksum）只算基础单位枚举、启动期算一次，
  `ag.h()` **不重算**它 → 热重载不改变注册 checksum（前提：无覆写型
  mod，见 §9）。
- 机制②（customUnits）在**每次新连接**收 SERVER_INFO 时由客户端自检 →
  重载后的下一次连接（跳目标房）按新单位表校验，正是我们要的效果。
- **E2E 实测修正**：服务端每秒广播 SERVER_INFO(106)，客户端**每次收到
  都拿本地单位表核对**；连接中热重载时单位表半重建，核对命中空窗即
  "Missing unit" 断线。"大厅连接重载期间不断线"的原假设不成立 →
  重载必须**干净离房（服务端正常回收玩家）→ 主菜态执行 → 重连**（§7）。
- **E2E 实测补充**：SERVER_INFO 单位核对的对象是客户端的**模组注册表
  （含禁用条目）**而非活单位表 → "已注册但禁用"的模组不会导致断线；
  只有条目完全缺失（从未安装）或重载空窗才被拒绝。

### 2.3 线程与竞态（E2E 实测修正）

- **UI 线程投递只走 `ScriptEngine.addRunnableToQueue`**：渲染循环每帧
  排空，大厅等所有状态可用；一切触碰引擎状态（单位表/模组/聊天/连接）
  的调用都经它投递。
- **禁用 `l.a(Runnable)`（gameFramework.q 队列）**：q 队列只在局内态
  排空，大厅投递永不执行（E2E 实测卡死证实）。
- SERVER_INFO 核对竞态由"先离房再重载"结构性消除：主菜态无连接、
  无入包、无核对（§2.2）；重载完成并落定后才重连。

### 2.4 模组目录与形态

- 桌面客户端 mod 目录：`ag.m()` = `/SD/rustedWarfare/units`
  （`l.aU` 置位时 `/SD/mods/units`；本机客户端实测为后者，
  `<游戏目录>/mods/units`，已含一个 workshop mod `2296940532`）。
  `/SD` 由引擎 FileLoader 解析，Injector 在进程内直接问引擎，不硬编码。
- 扫描器（`i.a.a(String,Z,Z)`）建在 FileLoader 抽象上，字节码层面接受
  目录/zip/单文件 `.ini`；但 **E2E 实测：桌面客户端实际只从 mods/units
  装载目录型模组，zip 原样落盘不被登记**（workshop 模组即目录形态）→
  zip 只能作传输形态，装配必须解压成目录（§7）。
- `l.aJ`（跳过 mod 加载开关）**仅由 `-nomods` 启动参数置真**
  （`java.Main.a(String[])`）→ 桌面默认加载 mod，方案不被此门控杀死。
- 新 mod 自动启用策略：`k()` 在 `lastModCount==-1`/`modSettingsVersion<1`
  时禁用全部新 mod；一次多出 >4 个新 mod 时不自动启用 →
  **Injector 重扫后必须显式设置受管 mod 的启用态并持久化**（§7 步骤5）。

---

## 3. 信任模型（D3）：只认内容哈希

- **SHA-256 是唯一判定**：清单对账、下载校验、"是否已具备"判定全部按
  内容哈希；**绝不"同名即视为已具备"**。
- "跳过已有的"：对已安装 mod 文件逐个算哈希，与清单条目命中即视为已具备
  （与文件名、目录名无关）。
- **签名扩展位**：清单结构预留 `sig` / `sigAlg` 字段（v1 恒空）；
  Injector 校验走单一 `Verifier` 接缝，v1 实现哈希校验，未来加签名实现
  不动装配流程。
- v1 只分发 **zip 形态** mod（单文件可哈希、便于传输与校验）；目录型
  mod 由清单产生侧打 zip（§5）。**E2E 实测修正**：引擎并不直接挂载
  zip（§2.4）——校验过的 zip 缓存在引擎目录外的 store/，再**解压**物化
  为目录模组；zip-slip 防护在解压时做（逐条目落点必须在临时目录内）。

---

## 4. Bootstrap（D4）：安装器与"第一个入口"

Injector 是 agent，"第一个 mod 怎么装"退化为"agent 怎么首次挂上"：

- **主通道：包装启动脚本 + `-javaagent`**。官方启动脚本把参数传给主类
  （程序参数），启动项注入不可行（§1 核查修正）；安装器在游戏目录旁
  **新增** `start-with-injector.sh`（复刻官方 java 命令 + `-javaagent`，
  不修改任何现有客户端文件），玩家此后双击它启动。启动期装载具备完整
  类变换能力。
- **备用通道：运行期 Attach API**（`loadAgent` 到已运行进程）。
  retransform 只许改方法体——我们的注入桩只在方法体开头加拦截分支、
  不新增成员，两条通道通用。
- **安装器（双击一次）**：定位游戏安装 → 落 `rw-mod-injector/`
  （jar + 配置：控制面 URL / 出口形态）→ 生成包装启动脚本。v1 做
  Linux（E2E 环境），Windows 版是后续增量。玩家侧动作只有"双击一次"，
  此后全自动。
- 安装器与 Injector 均**不需要任何玩家凭据**：进房走游戏自己的连接。

---

## 5. 与 Avalon 的接口：清单谁产生、怎么暴露

**权威来源是子服 `data/mods` 目录内容**（D-20"房间即服务器"：一个子服 =
一个 Avalon + 一个 helper = 一个模组集）。

**产生者：Avalon 侧直接枚举，不改 Helper、不改 helper 协议**：

- Avalon 已有 `/data` 文件层（`internal/data/global/data.go` 已定义
  `PluginModsPath = "/data/mods"`；自定义地图流程已在用同一文件层读写
  `/data/maps`，helper 是 Avalon 同机子进程）。
- Avalon 枚举 `data/mods`：zip → 原样服务；目录 → 确定性打 zip 后服务。
  每个条目产出 `file`（安全文件名）/ `sha256` / `size`。
  清单在子服启动/模组集变化时生成并缓存，保证"清单哈希"与"下载字节"
  严格一致。
- 清单反映的是**磁盘内容**而非引擎解析结果；最终裁决者是机制②
  （服务端解析失败的 mod 不会进 SERVER_INFO，客户端多装不碍校验；
  服务端装载的 mod 客户端必须有——清单覆盖此方向）。
  引擎级 `unitChecksum` 高级核对需要动 Helper，按 D1 边界**不做**，留 backlog。

**暴露：Avalon 控制面（Cascade-Backend-v1 契约）新增两个只读端点**：

| 端点 | 说明 |
| --- | --- |
| `GET /v1/rooms/{code}/mods` | 清单 JSON：`{mods:[{name,file,sha256,size}], sig:null, sigAlg:null}` |
| `GET /v1/rooms/{code}/mods/{file}` | 模组文件字节（流式，`file` 必须命中清单，否则 404） |

- **契约先行（硬约束）**：两端点先在 HA-Contract 的 Cascade-Backend-v1
  登记、升版本、跑 drift 通过，再动 Avalon 代码。
- 权限边界：这两端点面向玩家、只读、不回显任何敏感字段（清单与 `/rooms`
  房间列表同公开级别）；控制面现有信任档位（Cascade 面向）不变，
  玩家面走独立路由。

---

## 6. 与 Cascade 的接口：完全复用现状，零新增

- `/rooms`（聊天命令）：浏览房间目录——已有（`proxy/session.go::commandRooms`）。
- `/goto <code>`（聊天命令）：Cascade 解析后端 →
  `PACKET_RECONNECT_TO(178)` → `public_game_host:<jump_port>`——已有
  （Cascade `knowledge/0007`；jump_port 路由、后端地址不外露）。
- **Injector 的出口动作 = 装配成功后替玩家发一条 `/goto <code>` 聊天包**
  （进程内天然保证"装配完成才发起连接"——0009 的红线结构性成立）。
- **直连形态（无代理，v1 E2E）**：Injector 直接调引擎连接入口连目标地址。
- **生产形态跟进项（不阻塞 v1 闭环）**：后端不公开（Cascade 唯一入口），
  生产部署下 Injector 的 HTTP 取不到 → 需给 Cascade 加一个公开只读 HTTP
  面代理上述两端点。v1 E2E 用直连形态验证闭环。

---

## 7. 装配流程（happy path，E2E 实测修正版）

1. 玩家在零模组大厅输入 `/inj join <code>`——Injector 的聊天输入 hook
   拦截（不发往服务器），经引擎系统消息 API（引擎自己报告重载结果用的
   同一接口）反馈进度。门控：必须已连接（大厅态）；局中不得装配。
2. **取清单**：`GET /v1/rooms/{code}/mods`。失败（404/超时/JSON 非法）→
   重试一次后报错停留，不离开大厅。
3. **对账（按哈希）**：对 store/（已校验 zip 的缓存目录，在引擎模组目录
   之外）逐个算 SHA-256 与清单比对：内容哈希命中即已具备（与文件名无关，
   别的文件名下存过同一内容也算已具备）。
4. **拉缺的**：下载到 store 临时文件 → 校验大小 → 校验 SHA-256 →
   通过才改名进 store。下载失败重试一次后放弃；**哈希不符不重试**
   （内容不可信），即删临时文件。
5. **快照装配前启用态（必须在物化之前）**：重扫模组目录，记录玩家原有
   全部模组的启用态并即落盘。新装模组进不了快照 → restore 时一律禁用。
   （若在物化后扫描，新模组会从 preferences 继承旧启用态键被记成
   "原本启用"，restore 就禁不掉它——演练实测。）
6. **物化**：从 store 里校验过的 zip 解压进 `mods/units/<名字>/`
   （先解压到同位置 `.inj-tmp-*` 临时目录，成功后整体改名就位，引擎
   扫描器永远看不到半解压状态；解析用 ZipFile——ZipInputStream 对非
   zip 字节静默返回"无条目"，会让损坏内容物化成空模组冒充成功，
   演练实测后改）。
7. **先离房再换装**：干净离开当前大厅（服务端正常回收玩家）→ 等主菜态。
   规避 SERVER_INFO 单位核对竞态（§2.2）。
8. **启用/禁用**：显式设置每个条目的 `i.b.f`（禁用位）——启用房间要求的、
   禁用清单之外的（0009 §4"禁多的"，精确匹配房间模组集），持久化到引擎
   设置。
9. **热重载**：`ag.h()` 全量重建单位表 + 玩家刷新，投递 UI 线程执行
   （§2.3）；主菜态无连接、无核对竞态。重载失败 → 恢复快照并重载 →
   把玩家送回原服务器。
10. **进入**：重连原大厅（引擎自己的 reconnectToServer；等握手/身份
    绑定落定，绑定前发聊天会被代理门控掐断）→ 发 `/goto <code>`
    （Cascade 形态）；或直连目标（直连形态）。
11. **失败回退**：任一步失败 → 清临时文件 → 系统消息给出可读原因；
    绝不把半装配状态带进对局。`/inj restore` 按步骤 5 的快照回到装配前
    启用态（同款"先离房 → 主菜态恢复+重载 → 重连"），快照键恢复后全清。

---

## 8. 失败路径（2026-11-08 全部演练实测：假控制面 + 真客户端）

| # | 路径 | 演练结果 |
| --- | --- | --- |
| 1 | 清单拿不到（404） | ✅ `cannot fetch mod manifest: HTTP 404 …`，重试一次，停留大厅 |
| 2 | 下载失败（文件端点 404） | ✅ 重试一次后放弃（共 2 次请求），临时文件清掉 |
| 3 | 哈希不符 | ✅ 落盘前拦截，**不重试**（内容不可信，仅 1 次请求） |
| 4 | 目标目录不可写 | ✅ 下载前预检 `mods directory is not writable` |
| 5 | 重载异常 | 代码路径：catch Throwable → 恢复快照并重载 → 送回原服务器（未演练真实触发） |
| 6 | 连接后被机制②拒绝 | 实测修正：**"已注册但禁用"不会被拒**（§2.2）；仅条目完全缺失或重载空窗被拒 |
| 7 | zip 畸形 | ✅ **实测暴露真缺陷并修复**：ZipInputStream 对非 zip 字节静默返回"无条目"，损坏内容被物化成空模组冒充装配成功；改用 ZipFile（严格校验中央目录）+ 至少一条目检查，临时目录自清无残留 |

加测：`/inj restore` 快照语义——新装模组恢复时一律禁用、原有模组回快照
态、快照键恢复后全清；preferences 存在陈旧启用态键时新装模组仍被正确
禁用（前提：快照在物化前拍）。

---

## 9. 红线清单

继承 0009 §7，另加：

1. 只认内容哈希；任何"同名视为已具备"的实现都是缺陷。
2. **局中禁止热重载**（引擎自己的门控语义，§2.2）。
3. v1 清单禁止收录**覆写基础单位数据的 mod**（会改变机制①注册
   checksum，重载无法修复）；检测手段留 backlog，先靠录入纪律。
4. 引擎模组目录只放物化好的目录模组；已校验 zip 缓存放引擎目录外的
   store/（引擎扫描器会把模组目录里每个条目都登记成模组——缓存与
   临时文件绝不能落那里）；`file` 字段白名单校验（`[A-Za-z0-9._-]`，
   禁 `../`、绝对路径）；解压做 zip-slip 防护（§7 步骤 6）。
5. 单模组与单次装配总量设上限，超限拒绝。
6. 凭据不入库、不进 Injector、不进清单。
7. 不改客户端游戏文件；不碰引擎 kick/校验逻辑；不做反作弊绕过。

---

## 10. 临时形态与真通道

- **v1 内容通道（临时）**：Avalon 只读 HTTP 端点（§5），直连形态。
- **真通道（后续）**：独立内容服务/CDN——分块 + `Range` 断点续传 +
  块哈希 + 发布者签名（接入 §3 扩展位）+ 大小上限；清单走控制路、
  内容走内容路。Injector 的下载层按接口隔离，换通道不动装配逻辑。
- 生产 Cascade 形态的公开 HTTP 面（§6 跟进项）同属后续增量。

---

## 11. Phase 2 最小闭环验收（真实跑出来才算数）

本地环境已具备：官方 Linux 客户端（自带 JRE 1.8.0_131）、
可本地起真实 Avalon + helper（`data/mods` 放测试 mod）。

1. 对一个真实运行的 Avalon 实例读到指定房间的模组清单；
2. 装配：拉缺的 + 跳过已有的（哈希命中）+ 禁多的，文件落对位置，
   哈希校验全部通过；
3. **热重载生效：不重启游戏**，读回单位表核对 + 实际连入房间通过机制②；
4. §8 全部失败演练：可读报告 + 干净回退；
5. 回归：零模组/默认行为不受影响；无清单时玩家正常停留大厅；
   未装 Injector 的原版客户端行为完全不变。

**2026-11-08 实测：全部通过**。主路径（连大厅 → 清单 → 下载 → 哈希 →
store → 解压物化 → 离房 → 主菜态重载 → 重连 → `/goto` → 目标房玩家
稳定在位；干净离房服务端正常回收、无幽灵玩家）；§8 失败演练 5/5 通过
（并暴露修复 ZipInputStream 宽容解析缺陷）；`/inj restore` 快照语义
验证通过。

## 12. 并行协作约束（2026-11-08 作者补充）

- 另一路正在给 Avalon 落**插件 v2**，会动 `internal/plugin` 与相关文档/契约。
  本设计对 Avalon 的改动（§5 清单端点）**只落在 `internal/plugin` 以外**
  （`internal/net/gateway` + 新增清单产生代码），且动手前列改动点报一声。
- HA-Contract 两路都会碰（插件契约 vs Cascade-Backend-v1 契约），
  各自改各自的契约文件，分支作业、避免同分支互踩。
- Cascade 当前只有本路在动，可按需改；本仓（RW-Mod-Injector）自由。

## 13. 工作拆分（Phase 2 顺序）

1. **HA-Contract**：Cascade-Backend-v1 登记两端点 → 升版本 → drift 通过。
2. **Avalon**：清单产生（枚举 `/data/mods` + SHA-256 + 目录打 zip + 缓存）
   + 控制面两端点 + 测试；build/vet/test -race 全绿后按规范合并。
3. **Injector**（本仓）：Java 8 agent jar——配置、清单 HTTP 客户端、
   装配器（哈希对账 / 临时区落盘 / 启用态快照与恢复）、热重载调用
   （游戏线程投递）、聊天命令 hook（`/inj join|status|restore`）、
   系统消息报告、出口动作（`/goto` 或直连）。
4. **安装器**（本仓）：Linux v1——落 jar + 配置 + 注册 `-javaagent` 启动钩子。
5. **E2E**：按 §11 清单全量实测，附真实结果回报。
