<div align="center">
  <h1>RW-Mod-Injector</h1>
  <p><strong>Join the room with its declared mods ready.</strong></p>
  <p><a href="./README.zh-CN.md">简体中文</a></p>
</div>

RW-Mod-Injector is a Linux Java agent that assembles a room's declared mods before you join. Its installer adds an injector directory and launch script inside the game directory.

## Why RW-Mod-Injector

- **Arrive mod-ready.** Declared room mods are assembled and reloaded before the join proceeds.
- **Check what arrives.** SHA-256 and size checks reject unexpected or incomplete mod artifacts.
- **Extract defensively.** Strict ZIP and path protections reduce unsafe archive extraction risks.
- **Change mods reversibly.** A pre-assembly snapshot supports restoration of the prior mod state.
- **Stay in control.** Hot reload and in-game `/inj` controls make the active mod set visible and manageable.

## Learn more

- [Deployment](deployment.md) — installation and runtime guidance
- [Development](development.md) — implementation and integration guidance
- [Avalon](https://github.com/CoreMatch/Avalon) — the server-side room runtime
- [Armorial Contract](https://github.com/CoreMatch/HA-Contract) — canonical ecosystem contracts

## Limits

Linux only: it does not bypass anti-cheat or guarantee join timing. Integrity checks do not authenticate publishers, Cascade delivery is not production-proven, and applying mods changes the active game state.

## License and provenance

[AGPL-3.0](LICENSE) · Derived from [RW-HPS](https://github.com/RW-HPS/RW-HPS); upstream copyright notices are retained.
