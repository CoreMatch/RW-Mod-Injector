<div align="center">
  <h1>RW-Mod-Injector</h1>
  <p><strong>进入房间之前，让声明的模组准备就绪。</strong></p>
  <p><a href="./README.md">English</a></p>
</div>

RW-Mod-Injector 是 Linux Java agent，会在你加入房间之前装配该房间声明的模组。安装器会在游戏目录内新增 injector 目录与启动脚本。

## 为什么选择 RW-Mod-Injector

- **带着模组入场。** 房间声明的模组会在加入流程继续前完成装配与重载。
- **校验接收内容。** SHA-256 与大小检查会拒绝意外或不完整的模组文件。
- **防御性解压。** 严格的 ZIP 与路径保护降低不安全归档解压风险。
- **可逆地切换模组。** 装配前的快照支持恢复此前的模组状态。
- **保持掌控。** 热重载与游戏内 `/inj` 控制让当前模组集清晰可见、便于管理。

## 进一步了解

- [部署文档](deployment.md) — 安装与运行时指南
- [开发文档](development.md) — 实现与集成指南
- [Avalon](https://github.com/CoreMatch/Avalon) — 服务端房间运行时
- [Armorial 契约](https://github.com/CoreMatch/HA-Contract) — 生态权威契约

## 使用边界

仅支持 Linux：不绕过反作弊，也不保证加入时机。完整性检查不等于发布者身份认证，Cascade 分发尚未经过生产验证，应用模组也会改变当前游戏状态。

## 许可证与来源

[AGPL-3.0](LICENSE) · 衍生自 [RW-HPS](https://github.com/RW-HPS/RW-HPS)，保留上游版权声明。
