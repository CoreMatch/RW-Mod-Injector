package com.corematch.rwmodinjector;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Injector 本地状态 (落在配置同目录, 不进引擎模组目录 —— 避免被引擎
 * 扫描器当作模组条目):
 *  - installed.&lt;file&gt;=&lt;sha256&gt;  本装配器装过的文件 (诊断用)
 *  - snapshot.taken / snapshot.&lt;模组名&gt;=enabled|disabled  装配前启用态快照
 */
public final class StateStore {

    private StateStore() {
    }

    private static File stateFile(Config config) {
        return new File(config.homeDir, "rw-mod-injector.state");
    }

    public static Properties load(Config config) {
        Properties p = new Properties();
        File f = stateFile(config);
        if (f.isFile()) {
            try {
                InputStream in = new FileInputStream(f);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
            } catch (Exception e) {
                System.err.println("[RW-Mod-Injector] state load failed: " + e);
            }
        }
        return p;
    }

    public static void save(Config config, Properties p) {
        File f = stateFile(config);
        try {
            OutputStream out = new FileOutputStream(f);
            try {
                p.store(out, "RW-Mod-Injector state");
            } finally {
                out.close();
            }
        } catch (Exception e) {
            System.err.println("[RW-Mod-Injector] state save failed: " + e);
        }
    }
}
