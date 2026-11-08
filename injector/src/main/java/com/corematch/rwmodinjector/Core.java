package com.corematch.rwmodinjector;

/**
 * 引导: 等引擎主控制器出现后就绪 (agent 装载早于游戏初始化).
 * 所有引擎交互都经 Engine, 且只在 ready 之后发生.
 */
public final class Core {

    private static volatile Config config;
    private static volatile boolean ready;

    private Core() {
    }

    public static void init(final Config cfg) {
        config = cfg;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (!Engine.gameReady()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                ready = true;
                Engine.systemMessage("RW-Mod-Injector ready. /inj help");
                e2eAutoFlow(cfg);
            }
        }, "rw-mod-injector-boot");
        t.setDaemon(true);
        t.start();
    }

    /**
     * E2E 辅助流 (仅测试配置启用):
     * 1) e2e.autoconnect 非空 -> 引擎就绪后直接连入大厅 (代替手动操作);
     * 2) e2e.autojoin 非空 -> 连上后自动执行一次 /inj join <房间码>;
     *    e2e.autogoto 非空 -> 连上后不装配、直接 /goto <房间码> (对照实验).
     */
    private static void e2eAutoFlow(final Config cfg) {
        final boolean autoConnect = cfg.e2eAutoConnect != null && !cfg.e2eAutoConnect.isEmpty();
        final boolean autoJoin = cfg.e2eAutoJoin != null && !cfg.e2eAutoJoin.isEmpty();
        final boolean autoGoto = cfg.e2eAutoGoto != null && !cfg.e2eAutoGoto.isEmpty();
        if (!autoConnect && !autoJoin && !autoGoto) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    if (autoConnect) {
                        // 等 UI 层就绪: 连接的投递通道 = ScriptEngine 队列
                        for (int i = 0; !Engine.uiReady() && i < 120; i++) {
                            Thread.sleep(500);
                        }
                        Thread.sleep(2000); // 等首帧稳定
                        Engine.systemMessage("e2e auto-connect: " + cfg.e2eAutoConnect);
                        try {
                            Engine.connectDirect(cfg.e2eAutoConnect);
                        } catch (Throwable t) {
                            System.err.println("[RW-Mod-Injector] auto-connect failed: " + t);
                        }
                    }
                    if (autoJoin) {
                        while (!Engine.multiplayerActive()) {
                            Thread.sleep(1000);
                        }
                        Thread.sleep(3000); // 等大厅态稳定 (SERVER_INFO/队伍列表落定)
                        Engine.systemMessage("e2e auto-join: " + cfg.e2eAutoJoin);
                        Assembler.joinAsync(cfg.e2eAutoJoin);
                        if (cfg.e2eAutoRestoreAfterSec > 0) {
                            Thread.sleep(cfg.e2eAutoRestoreAfterSec * 1000L);
                            Engine.systemMessage("e2e auto-restore");
                            Assembler.restoreAsync();
                        }
                    } else if (autoGoto) {
                        while (!Engine.multiplayerActive()) {
                            Thread.sleep(1000);
                        }
                        Thread.sleep(3000); // 等大厅态稳定
                        Engine.systemMessage("e2e auto-goto: " + cfg.e2eAutoGoto);
                        Engine.sendChat("/goto " + cfg.e2eAutoGoto);
                    }
                } catch (InterruptedException ignored) {
                    // 线程被中断即放弃自动流程
                }
            }
        }, "rw-mod-injector-e2e");
        t.setDaemon(true);
        t.start();
    }

    public static Config config() {
        return config;
    }

    public static boolean ready() {
        return ready && Engine.gameReady();
    }
}
