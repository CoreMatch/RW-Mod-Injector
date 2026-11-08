# RW-Mod-Injector

**RW-Mod-Injector** 是 Rusted Warfare（铁锈战争）客户端的一个模组装配器：
服务器房间需要什么模组，它在**进入游戏之前**自动帮你装好、启用并重载，
不用重启游戏，也不用手动往模组目录里丢文件。

它只安装**房间清单里列出、且内容校验（SHA-256）通过**的模组；
校验不过的文件一律不落地。不会修改你的任何游戏文件。

---

## 安装（Linux v1）

1. 把本仓库放到任意位置，运行安装器（双击或终端执行均可）：

   ```bash
   ./installer/install.sh
   ```

   找不到游戏目录时会提示你传路径：`./installer/install.sh /path/to/RustedWarfare`。

2. 安装器只在游戏目录**旁边新增**两样东西，不动任何原有文件：
   - `rw-mod-injector/`：装配器本体与配置；
   - `start-with-injector.sh`：新的启动脚本。

3. 以后启动游戏都用 **`start-with-injector.sh`**（双击即可），
   原来的启动方式保持不变、随时可用。

4. 配置在 `rw-mod-injector/rw-mod-injector.properties`，一般由服主提供：
   - `control.url`：服务器模组清单地址；
   - `mode`：`cascade`（跟服务器走，推荐）或 `direct`（直连）。

---

## 游戏内命令（聊天栏输入）

| 命令 | 作用 |
| --- | --- |
| `/inj join <房间码>` | 装配该房间的模组并进入。需要先连上大厅。整个过程约十几秒到一分钟（视网速与模组大小），进度会逐条显示在聊天栏 |
| `/inj restore` | 恢复到你装配前的模组状态（新装的停用、原来的恢复原样），并重载生效 |
| `/inj status` | 查看当前状态：是否正在装配、连接模式、模组目录 |
| `/inj help` | 显示命令列表 |

典型流程：连上大厅 → `/inj join <房间码>` → 看到 `done.` 提示后自动进入目标房间。
想恢复原来的模组配置时，在聊天栏输入 `/inj restore`。

装配期间请**不要手动操作多人菜单**（装配器会自动离开房间、换装、再重连）。

---

## 失败提示都是什么意思

装配失败时聊天栏会有一句英文提示，后面跟着你该做什么：

| 你看到的提示 | 意思 | 你该做什么 |
| --- | --- | --- |
| Couldn't fetch the mod list… | 拿不到房间的模组清单（服务器那边暂时不通） | 等一会儿再 `/inj join` 重试 |
| Downloading a mod file failed… | 模组文件下载失败（服务器侧问题） | 重试 `/inj join`；反复失败找服主 |
| A mod download arrived incomplete… | 下载不完整 | 重试 `/inj join` |
| Security check failed… NOT installed | 文件内容与房间清单**校验不符**，已拒绝安装 | 这不是你的问题——找服主 |
| A mod file is corrupt… NOT installed | 模组文件本身损坏，已拒绝安装 | 找服主 |
| The room's mod pack is too large… | 模组包超出安装上限 | 找服主 |
| Can't write to the mod folder… | 你的模组目录不可写（权限或磁盘满） | 检查游戏目录权限/磁盘空间，然后重试 |
| Connect to the lobby first… | 还没连上大厅 | 先连大厅，再 `/inj join` |
| The injector is not ready yet… | 游戏刚启动，装配器还没就绪 | 等几秒重试 |
| Applying the mod set failed during the unit reload… | 极少见：重载失败，已自动恢复你原来的模组 | 若游戏表现异常，重启游戏 |
| Mods are applied, but reconnecting… failed | 模组已装好，但自动重连失败 | 手动重连服务器 |
| Mod assembly failed unexpectedly… | 未预期的失败 | 把游戏日志发给服主/开发者 |

**什么时候该找服主**：提示里带 "tell the server operator" 的（校验不符、
文件损坏、体积超限），以及反复重试仍失败的下载问题——这些都在服务器一侧，
玩家本地无法解决。

---

## 卸载

删除游戏目录旁的 `rw-mod-injector/` 与 `start-with-injector.sh` 即可，
用回原启动脚本，游戏完全恢复原样（装配器装过的模组目录可自行在
`mods/units/` 下删除）。

---

开发者文档：设计定稿见 [`knowledge/0001-injector-design.md`](knowledge/0001-injector-design.md)，
失败文案的触发条件与日志细节见 [`knowledge/0002-failure-messaging.md`](knowledge/0002-failure-messaging.md)。
