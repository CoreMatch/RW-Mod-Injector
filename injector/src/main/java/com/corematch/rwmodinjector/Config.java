package com.corematch.rwmodinjector;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Injector 配置 (设计 §4/§6): 控制面 URL、出口形态、直连目标.
 * 凭据不入库也不进配置: 进房走游戏自己的连接, 清单/内容端点只读公开.
 */
public final class Config {

    /** Avalon 控制面基址, 如 http://127.0.0.1:5125/v1 */
    public final String controlUrl;
    /** 出口形态: cascade = 装配完成后发 /goto 聊天命令; direct = 直连目标 */
    public final String mode;
    /** direct 形态的目标地址 host:port */
    public final String directTarget;
    /** E2E 辅助: 非空时连上大厅后自动执行一次 /inj join <该房间码> */
    public final String e2eAutoJoin;
    /** E2E 辅助: 非空时引擎就绪后自动直连该地址 (host:port), 代替手动进大厅 */
    public final String e2eAutoConnect;
    /** E2E 辅助: 非空时连上大厅后不装配、直接发 /goto <该房间码> (跳转路径对照实验) */
    public final String e2eAutoGoto;
    /** E2E 辅助: autojoin 之后等待该秒数再自动执行一次 /inj restore */
    public final int e2eAutoRestoreAfterSec;
    /** 配置文件所在目录 (状态文件落这里) */
    public final File homeDir;

    private Config(String controlUrl, String mode, String directTarget,
                   String e2eAutoJoin, String e2eAutoConnect, String e2eAutoGoto,
                   int e2eAutoRestoreAfterSec, File homeDir) {
        this.controlUrl = controlUrl;
        this.mode = mode;
        this.directTarget = directTarget;
        this.e2eAutoJoin = e2eAutoJoin;
        this.e2eAutoConnect = e2eAutoConnect;
        this.e2eAutoGoto = e2eAutoGoto;
        this.e2eAutoRestoreAfterSec = e2eAutoRestoreAfterSec;
        this.homeDir = homeDir;
    }

    public static Config load(String agentArg) throws Exception {
        File file;
        if (agentArg != null && !agentArg.trim().isEmpty()) {
            file = new File(agentArg.trim());
        } else {
            file = new File(jarDir(), "rw-mod-injector.properties");
        }
        Properties p = new Properties();
        if (file.isFile()) {
            InputStream in = new FileInputStream(file);
            try {
                p.load(in);
            } finally {
                in.close();
            }
        }
        String controlUrl = p.getProperty("control.url", "http://127.0.0.1:5125/v1").trim();
        while (controlUrl.endsWith("/")) {
            controlUrl = controlUrl.substring(0, controlUrl.length() - 1);
        }
        String mode = p.getProperty("mode", "cascade").trim().toLowerCase();
        if (!mode.equals("cascade") && !mode.equals("direct")) {
            mode = "cascade";
        }
        String directTarget = p.getProperty("direct.target", "127.0.0.1:5123").trim();
        String e2eAutoJoin = p.getProperty("e2e.autojoin", "").trim();
        String e2eAutoConnect = p.getProperty("e2e.autoconnect", "").trim();
        String e2eAutoGoto = p.getProperty("e2e.autogoto", "").trim();
        int e2eAutoRestoreAfterSec = 0;
        try {
            e2eAutoRestoreAfterSec = Integer.parseInt(
                    p.getProperty("e2e.autorestore", "0").trim());
        } catch (NumberFormatException ignored) {
            // 非法值按 0 (禁用) 处理
        }
        File home = file.getAbsoluteFile().getParentFile();
        if (home == null) {
            home = new File(".");
        }
        return new Config(controlUrl, mode, directTarget, e2eAutoJoin, e2eAutoConnect,
                e2eAutoGoto, e2eAutoRestoreAfterSec, home);
    }

    private static File jarDir() {
        try {
            String path = InjectorAgent.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            File f = new File(path);
            return f.isFile() ? f.getParentFile() : f;
        } catch (Throwable t) {
            return new File(".");
        }
    }
}
