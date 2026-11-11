---
title: RW-Mod-Injector 部署者文档
description: 玩家视角：怎么拿到与安装注入器、/inj 命令怎么用、失败提示各是什么意思、怎么回退与卸载。
order: 1
tags:
  - rw-mod-injector
  - player
updatedAt: 2026-11-08
---

# RW-Mod-Injector — 玩家使用

RW-Mod-Injector 是 Rusted Warfare 客户端的模组装配器：房间需要什么模组，它在进入游戏之前自动装好、启用并重载，不重启游戏、不手动丢文件。只安装房间清单里列出且 SHA-256 校验通过的模组；校验不过的文件一律不落地；不修改任何游戏文件，不需要任何凭据。安装器当前支持 Linux。

## 获取与安装

1. 取得本仓库（服主提供），运行安装器（双击或终端执行）：

```bash
./installer/install.sh                 # 自动探测游戏目录
./installer/install.sh /path/to/RustedWarfare   # 手动指定
```

2. 安装器只在游戏目录**旁边新增**两样东西，不动任何原有文件：
   - `rw-mod-injector/`：装配器本体与配置；
   - `start-with-injector.sh`：新的启动脚本。
3. 以后启动游戏都用 `start-with-injector.sh`（双击即可）；原启动方式保持不变、随时可用。
4. 配置在 `rw-mod-injector/rw-mod-injector.properties`，一般由服主提供：

| 键 | 作用 |
|---|---|
| `control.url` | 服务器模组清单地址 |
| `mode` | `cascade`（跟服务器走，推荐）或 `direct`（直连） |
| `direct.target` | 直连模式的目标地址 |

## 命令（游戏内聊天栏输入）

| 命令 | 作用 |
|---|---|
| `/inj join <房间码>` | 装配该房间的模组并进入。需要先连上大厅。约十几秒到一分钟，进度逐条显示在聊天栏 |
| `/inj restore` | 恢复到装配前的模组状态（新装的停用、原来的恢复原样），并重载生效 |
| `/inj status` | 查看当前状态：是否正在装配、连接模式、模组目录 |
| `/inj help` | 显示命令列表 |

典型流程：连上大厅 → `/inj join <房间码>` → 看到 `done.` 提示后自动进入目标房间。装配期间**不要手动操作多人菜单**（装配器会自动离房、换装、重连）。

## 失败提示都是什么意思

装配失败时聊天栏有一句英文提示 + 你该做什么；技术细节（URL、哈希、堆栈）只进游戏日志，不进聊天栏。

| 你看到的提示 | 意思 | 你该做什么 |
|---|---|---|
| Couldn't fetch the mod list… | 拿不到房间模组清单（服务器侧暂时不通） | 等一会儿重试 `/inj join` |
| Downloading a mod file failed… | 模组文件下载失败（服务器侧问题） | 重试；反复失败找服主 |
| A mod download arrived incomplete… | 下载不完整 | 重试 `/inj join` |
| Security check failed… NOT installed | 文件内容与房间清单校验不符，已拒绝安装 | 不是你的问题——找服主 |
| A mod file is corrupt… NOT installed | 模组文件损坏，已拒绝安装 | 找服主 |
| The room's mod pack is too large… | 模组包超出安装上限 | 找服主 |
| Can't write to the mod folder… | 模组目录不可写（权限或磁盘满） | 检查权限/磁盘空间后重试 |
| Connect to the lobby first… | 还没连上大厅 | 先连大厅再 `/inj join` |
| The injector is not ready yet… | 游戏刚启动，装配器未就绪 | 等几秒重试 |
| Applying the mod set failed during the unit reload… | 极少见：重载失败，已自动恢复你原来的模组 | 游戏表现异常就重启游戏 |
| Mods are applied, but reconnecting… failed | 模组已装好，但自动重连失败 | 手动重连服务器 |
| Mod assembly failed unexpectedly… | 未预期的失败 | 把游戏日志发给服主/开发者 |

**什么时候找服主**：提示带 "tell the server operator" 的（校验不符、损坏、超限），以及反复重试仍失败的下载问题——这些在服务器一侧，玩家本地解决不了。报障时把游戏日志（stderr，`[Injector]` 前缀镜像到 stdout）一起发。

## 回退与卸载

- **回退**：聊天栏 `/inj restore`——原有模组回到装配前的启用/禁用状态，本次新装的模组一律停用，重载生效。
- **卸载**：删除游戏目录旁的 `rw-mod-injector/` 与 `start-with-injector.sh`，用回原启动脚本，游戏完全恢复原样（装配器装过的模组目录可自行在 `mods/units/` 下删除）。

## 相关

- [开发者文档](./development.md)：注入链路、快照与 zip 处理机制
- 真环境回归：见 [README](./README.md) 的「真环境回归」节（开发者/运维向）
