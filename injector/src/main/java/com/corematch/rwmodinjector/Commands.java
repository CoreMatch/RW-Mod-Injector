package com.corematch.rwmodinjector;

/**
 * /inj 命令分发. tryHandle 被 Root.sendChatMessage 的注入桩调用
 * (聊天发送的唯一入口): 命中即消费 (不再发往服务器), 未命中原样放行.
 *
 * 命令集 (设计 §7):
 *   /inj join <code>  装配房间所需模组并进入
 *   /inj restore      恢复装配前的模组启用态
 *   /inj status       当前状态
 *   /inj help         帮助
 */
public final class Commands {

    private Commands() {
    }

    /** @return true = 命令已被消费, 聊天文本不得再发往服务器 */
    public static boolean tryHandle(String text) {
        // 聊天发送入口运行在 UI 线程: 记录下来供同线程判定 (防投递死锁)
        Engine.noteUIThread(Thread.currentThread());
        try {
            if (text == null) {
                return false;
            }
            String trimmed = text.trim();
            if (!trimmed.startsWith("/inj")) {
                return false;
            }
            String[] parts = trimmed.split("\\s+");
            String cmd = parts.length > 1 ? parts[1].toLowerCase() : "help";

            if (!Core.ready()) {
                Engine.systemMessage("[Injector] engine not ready yet, try again in a moment");
                return true;
            }
            if (cmd.equals("join")) {
                if (parts.length < 3) {
                    Engine.systemMessage("[Injector] usage: /inj join <room code>");
                    return true;
                }
                Assembler.joinAsync(parts[2]);
                return true;
            }
            if (cmd.equals("restore")) {
                Assembler.restoreAsync();
                return true;
            }
            if (cmd.equals("status")) {
                status();
                return true;
            }
            help();
            return true;
        } catch (Throwable t) {
            // 命令层异常绝不影响正常聊天
            System.err.println("[RW-Mod-Injector] command error: " + t);
            return false;
        }
    }

    private static void status() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Injector] busy=").append(Assembler.isBusy());
        Config config = Core.config();
        if (config != null) {
            sb.append(", mode=").append(config.mode)
              .append(", control=").append(config.controlUrl);
        }
        try {
            sb.append(", modsDir=").append(Engine.modsDirectory());
        } catch (Throwable t) {
            sb.append(", modsDir=<unresolved>");
        }
        Engine.systemMessage(sb.toString());
    }

    private static void help() {
        Engine.systemMessage("[Injector] commands: /inj join <code> | /inj restore | /inj status");
    }
}
