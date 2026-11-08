package com.corematch.rwmodinjector;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 装配器 (设计 §7/§8): 取清单 -> 按哈希对账 -> 拉缺的 -> 校验 -> 进 store ->
 * 解压物化成目录模组 -> 启用/禁用 -> 热重载 -> 出口动作. 任何一步失败都
 * 整体回退, 绝不把半装配状态带进对局.
 *
 * 信任模型: 一切"是否已具备"的判定只认内容哈希 (设计 §3, 红线 1).
 *
 * 落盘形态 (E2E 实测修正): 桌面客户端只从 mods/units 装载目录型模组,
 * zip 原样落盘不会被引擎登记 —— 因此 zip 只作传输形态, 校验过的 zip
 * 缓存在 store/ (配置同目录, 引擎扫描不到), 再解压成目录供引擎装载.
 */
public final class Assembler {

    private static final int TIMEOUT_MANIFEST_MS = 10_000;
    private static final int TIMEOUT_DOWNLOAD_MS = 60_000;
    private static final long MAX_SINGLE_MOD = 512L * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static volatile boolean busy;

    private Assembler() {
    }

    public static boolean isBusy() {
        return busy;
    }

    /** 异步执行装配+进入; 进度经引擎系统消息反馈 */
    public static void joinAsync(final String roomCode) {
        synchronized (LOCK) {
            if (busy) {
                Engine.systemMessage("assembly already in progress");
                return;
            }
            busy = true;
        }
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    assembleAndJoin(roomCode);
                } catch (Throwable t) {
                    // 玩家看到结论与下一步 (友好文案), 技术细节只进日志
                    Engine.systemMessage(friendlyFailure(t));
                    System.err.println("[RW-Mod-Injector] assembly failed: " + t);
                    t.printStackTrace();
                } finally {
                    synchronized (LOCK) {
                        busy = false;
                    }
                }
            }
        }, "rw-mod-injector-assemble");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 失败的玩家可见说明 (英语, 项目 i18n 基调): 每类失败一句人话 ——
     * 结论 + 下一步该做什么. 技术细节 (URL / 哈希 / 堆栈) 留在日志.
     * 分类按本装配器自己的报错文案匹配 (文案稳定, 见各步骤).
     */
    static String friendlyFailure(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage();
        if (m.contains("cannot fetch mod manifest")) {
            return "Couldn't fetch the mod list for this room (control server unreachable?). "
                    + "You stayed in the lobby - try /inj join again in a moment.";
        }
        if (m.contains("hash mismatch")) {
            return "Security check failed: a mod file did not match the room manifest and was NOT installed. "
                    + "The room's mod pack may be corrupted - tell the server operator.";
        }
        if (m.contains("size mismatch")) {
            return "A mod download arrived incomplete. You stayed in the lobby - try /inj join again.";
        }
        if (m.contains("not writable") || m.contains("cannot create mod store directory")
                || m.contains("cannot store mod file") || m.contains("cannot place mod directory")) {
            return "Can't write to the mod folder (permissions or disk full?). "
                    + "Fix it and try /inj join again.";
        }
        if (m.contains("exceeds the size limit")) {
            return "The room's mod pack is too large to install. Tell the server operator.";
        }
        if (m.toLowerCase().contains("zip")) {
            return "A mod file is corrupt and was NOT installed. "
                    + "The room's mod pack may be broken - tell the server operator.";
        }
        if (m.contains("HTTP ") || m.contains("download failed")) {
            return "Downloading a mod file failed (server problem). You stayed in the lobby - try /inj join again.";
        }
        if (m.contains("not connected to a lobby")) {
            return "Connect to the lobby first, then use /inj join <room code>.";
        }
        if (m.contains("unit reload failed")) {
            return "Applying the mod set failed during the unit reload. Your previous mod set was restored - "
                    + "if the game misbehaves, restart it.";
        }
        if (m.contains("cannot reconnect")) {
            return "Mods are applied, but reconnecting to the lobby failed - please reconnect manually.";
        }
        if (m.contains("game engine is not ready") || m.contains("config is not loaded")) {
            return "The injector is not ready yet - wait a moment and try /inj join again.";
        }
        return "Mod assembly failed unexpectedly - check the game log for details, then try /inj join again.";
    }

    private static void assembleAndJoin(String roomCode) throws Exception {
        Config config = Core.config();
        if (config == null) {
            throw new IllegalStateException("injector config is not loaded");
        }
        if (!Engine.gameReady()) {
            throw new IllegalStateException("game engine is not ready");
        }

        // 门控 (设计 §2.2): 必须已连接 (大厅态); 局中不得装配由流程纪律保证
        if (!Engine.multiplayerActive()) {
            throw new IllegalStateException("not connected to a lobby; connect to the lobby first");
        }

        // 1. 取清单
        Engine.systemMessage("fetching mod manifest for room " + roomCode + "...");
        Manifest manifest = fetchWithRetry(config, roomCode);

        // 2. 目录可写预检 (失败路径: 目标目录不可写)
        File modsDir = Engine.modsDirectory();
        if (!modsDir.canWrite()) {
            throw new IOException("mods directory is not writable: " + modsDir);
        }
        // store: 已校验 zip 的缓存, 放在引擎模组目录之外 (引擎扫描器会把
        // 模组目录里的每个条目都当模组登记, 缓存与临时文件绝不能落那里)
        File storeDir = new File(config.homeDir, "store");
        if (!storeDir.isDirectory() && !storeDir.mkdirs()) {
            throw new IOException("cannot create mod store directory: " + storeDir);
        }
        if (!storeDir.canWrite()) {
            throw new IOException("mod store directory is not writable: " + storeDir);
        }

        // 3. 按哈希对账 (对 store: 内容哈希命中即已具备, 与名字无关)
        Map<String, String> storeHashes = hashZips(storeDir);
        Properties state = StateStore.load(config);
        List<Manifest.Entry> missing = new ArrayList<Manifest.Entry>();
        Map<Manifest.Entry, File> sources = new LinkedHashMap<Manifest.Entry, File>();
        for (Manifest.Entry entry : manifest.mods) {
            if (entry.size > MAX_SINGLE_MOD) {
                throw new IOException("manifest entry " + entry.file + " exceeds the size limit");
            }
            String have = storeHashes.get(entry.file);
            if (have != null && have.equals(entry.sha256)) {
                sources.put(entry, new File(storeDir, entry.file));
                continue; // 已具备 (哈希命中)
            }
            File elsewhere = null;
            for (Map.Entry<String, String> stored : storeHashes.entrySet()) {
                if (stored.getValue().equals(entry.sha256)) {
                    elsewhere = new File(storeDir, stored.getKey()); // 别的文件名下存过同一内容
                    break;
                }
            }
            if (elsewhere != null) {
                sources.put(entry, elsewhere);
            } else {
                missing.add(entry);
            }
        }

        // 4. 拉缺的: 临时区下载 -> 校验大小+哈希 -> 才允许进 store
        long sessionBytes = 0;
        List<File> temps = new ArrayList<File>();
        try {
            for (Manifest.Entry entry : missing) {
                Engine.systemMessage("downloading " + entry.file + "...");
                File temp = new File(storeDir, ".inj-" + entry.file + ".part");
                temps.add(temp);
                downloadVerified(config, roomCode, entry, temp);
                File stored = new File(storeDir, entry.file);
                if (stored.exists() && !stored.delete()) {
                    throw new IOException("cannot replace stored file: " + entry.file);
                }
                if (!temp.renameTo(stored)) {
                    throw new IOException("cannot store mod file: " + entry.file);
                }
                sources.put(entry, stored);
                sessionBytes += entry.size;
                if (sessionBytes > Http.MAX_TOTAL_DOWNLOAD) {
                    throw new IOException("total download size exceeds the session limit");
                }
            }
        } catch (Exception e) {
            for (File temp : temps) {
                if (temp.exists()) {
                    temp.delete();
                }
            }
            throw e;
        }

        // 4a. 快照装配前启用态: 必须在物化之前 —— 此刻目录扫描只含玩家
        //     原有的模组, 新装的进不了快照, restore 时一律禁用 (设计 §8
        //     回退语义). 若在物化后扫描, 新模组会从 preferences 继承旧
        //     启用态键被记成"原本启用", restore 就禁不掉它 (演练实测).
        if (!"true".equals(state.getProperty("snapshot.taken"))) {
            for (Object o : Engine.rescanMods()) {
                state.setProperty("snapshot." + Engine.modName(o),
                        Engine.isDisabled(o) ? "disabled" : "enabled");
            }
            state.setProperty("snapshot.taken", "true");
            StateStore.save(config, state); // 快照拍完即落盘, 后续步骤失败也不丢
        }

        // 4b. 物化进引擎模组目录: 桌面客户端只认目录型模组 (workshop 模组
        //     也是目录, 实测 zip 原样落盘不会被登记), zip 只是传输形态.
        //     从 store 里校验过的 zip 解压出 mods/<名字>/ 供引擎装载.
        cleanStrayInjectorFiles(modsDir);
        for (Manifest.Entry entry : manifest.mods) {
            File target = new File(modsDir, modDirName(entry.file));
            if (!target.isDirectory()) {
                Engine.systemMessage("installing " + target.getName() + "...");
                extractZip(sources.get(entry), target);
            }
        }

        // 5. 先离开再换装 (E2E 实测修正): 服务端每秒广播 SERVER_INFO(106),
        //    客户端每次收到都拿本地单位表核对服务端清单; 连接中热重载时
        //    单位表半重建, 核对命中空窗即 "Missing unit" 断线. 所以:
        //    干净离开 (服务端正常回收玩家) -> 主菜态重载 -> 重连.
        final boolean wasConnected = Engine.multiplayerActive();
        if (wasConnected) {
            Engine.systemMessage("leaving the room to apply the mod set...");
            Engine.runOnUIThreadAndWait(new Runnable() {
                public void run() {
                    Engine.leaveToMenu("rw-mod-injector: applying mod set");
                }
            }, 15_000);
            for (int i = 0; i < 50 && Engine.multiplayerActive(); i++) {
                Thread.sleep(100);
            }
        }

        // 6. 启用/禁用 (精确匹配房间模组集); 快照已在物化前拍好 (步骤 4a)
        Engine.systemMessage("applying mod set (" + manifest.mods.size() + " mods)...");
        applyModSet(config, manifest);

        // 7. 热重载 (主菜态: 无连接即无入包, 不存在核对竞态).
        //    在 UI 线程执行 (与引擎自己的 Mods 界面"应用变更"同线程模型).
        Engine.systemMessage("hot-reloading units...");
        try {
            Engine.runOnUIThreadAndWait(new Runnable() {
                public void run() {
                    Engine.reloadUnits();
                    Engine.refreshPlayers();
                }
            }, 120_000);
        } catch (Throwable reloadError) {
            // 重载失败: 恢复快照并再重载, 尽量回到原状, 然后把玩家送回原服务器
            Engine.systemMessage("reload failed, rolling back...");
            try {
                restore(config, false);
            } catch (Throwable ignored) {
                // 回退失败只能提示重启
            }
            if (wasConnected) {
                try {
                    reconnectAndWait();
                } catch (Throwable ignored) {
                    // 重连失败另有提示, 这里只保回退结果
                }
            }
            throw new IllegalStateException("unit reload failed: " + reloadError
                    + " (mod set restored; if the game misbehaves, restart it)");
        }

        // 8. 出口动作: 装配完成才发起连接 (红线结构性成立)
        if ("direct".equals(config.mode)) {
            Engine.systemMessage("connecting to " + config.directTarget + "...");
            Engine.connectDirect(config.directTarget);
        } else {
            if (wasConnected) {
                Engine.systemMessage("reconnecting to the lobby...");
                reconnectAndWait();
            }
            Engine.systemMessage("assembled. jumping via /goto " + roomCode);
            Engine.sendChat("/goto " + roomCode);
        }
        StateStore.save(config, state);
        Engine.systemMessage("done. /inj restore returns to your previous mod set.");
    }

    /**
     * 用引擎自己的 reconnectToServer 重连上一个服务器 (地址它自己记着),
     * 然后等握手/身份绑定落定 —— 绑定完成前发聊天会被代理门控掐断,
     * 必须等 (时长取自 E2E 实测: 握手 <1s, 留足裕量).
     */
    private static void reconnectAndWait() throws Exception {
        Boolean ok = (Boolean) Engine.callOnUIThread(new java.util.concurrent.Callable<Object>() {
            public Object call() {
                return Boolean.valueOf(Engine.reconnectLast());
            }
        }, 20_000);
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException(
                    "cannot reconnect to the previous server; mods are applied — reconnect manually");
        }
        for (int i = 0; i < 100 && !Engine.multiplayerActive(); i++) {
            Thread.sleep(200);
        }
        Thread.sleep(2500); // 等注册/身份绑定落定
    }

    /** 恢复装配前的启用态快照 (+重载). 无快照则提示 */
    public static void restoreAsync() {
        synchronized (LOCK) {
            if (busy) {
                Engine.systemMessage("assembly in progress; try again later");
                return;
            }
            busy = true;
        }
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    Config config = Core.config();
                    if (config == null) {
                        Engine.systemMessage("injector config is not loaded");
                        return;
                    }
                    // 先确认有快照再动连接态 (绝不为无操作的恢复断开玩家)
                    if (!"true".equals(StateStore.load(config).getProperty("snapshot.taken"))) {
                        Engine.systemMessage("no assembly snapshot to restore");
                        return;
                    }
                    // 与装配同款: 离开 -> 主菜态恢复+重载 -> 重连 (防 SERVER_INFO 核对竞态)
                    final boolean wasConnected = Engine.multiplayerActive();
                    if (wasConnected) {
                        Engine.systemMessage("leaving the room to restore the mod set...");
                        Engine.runOnUIThreadAndWait(new Runnable() {
                            public void run() {
                                Engine.leaveToMenu("rw-mod-injector: restoring mod set");
                            }
                        }, 15_000);
                        for (int i = 0; i < 50 && Engine.multiplayerActive(); i++) {
                            Thread.sleep(100);
                        }
                    }
                    try {
                        if (restore(config, true)) {
                            Engine.systemMessage("previous mod set restored.");
                        }
                    } finally {
                        if (wasConnected) {
                            try {
                                reconnectAndWait();
                            } catch (Throwable t) {
                                Engine.systemMessage("restore done, but reconnect failed: " + t.getMessage());
                            }
                        }
                    }
                } catch (Throwable t) {
                    Engine.systemMessage("restore failed: " + t.getMessage());
                } finally {
                    synchronized (LOCK) {
                        busy = false;
                    }
                }
            }
        }, "rw-mod-injector-restore");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean restore(final Config config, boolean announce) throws Exception {
        final Properties state = StateStore.load(config);
        if (!"true".equals(state.getProperty("snapshot.taken"))) {
            if (announce) {
                Engine.systemMessage("no assembly snapshot to restore");
            }
            return false;
        }
        try {
            final ArrayList<?> entries = Engine.rescanMods();
            Engine.runOnUIThreadAndWait(new Runnable() {
                public void run() {
                    for (Object o : entries) {
                        String name = Engine.modName(o);
                        String flag = state.getProperty("snapshot." + name);
                        if (flag != null) {
                            Engine.setDisabled(o, "disabled".equals(flag));
                        } else {
                            // 快照里没有 = 装配之后才出现的模组 (本装配器装的):
                            // 恢复 = 回到装配前, 一律禁用.
                            Engine.setDisabled(o, true);
                        }
                    }
                    Engine.saveModSettings();
                    Engine.reloadUnits();
                    Engine.refreshPlayers();
                }
            }, 120_000);
        } catch (Throwable t) {
            throw new IllegalStateException("restore failed: " + t);
        }
        state.remove("snapshot.taken");
        java.util.Iterator<Object> it = state.keySet().iterator();
        while (it.hasNext()) {
            if (String.valueOf(it.next()).startsWith("snapshot.")) {
                it.remove(); // 陈旧快照键一并清掉, 状态文件不留垃圾
            }
        }
        StateStore.save(config, state);
        return true;
    }

    // --- 内部步骤 ---

    private static Manifest fetchWithRetry(Config config, String roomCode) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return Manifest.fetch(config, roomCode, TIMEOUT_MANIFEST_MS);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("cannot fetch mod manifest: "
                + (last == null ? "unknown error" : last.getMessage()));
    }

    /** 目录内 zip 的内容哈希表 (文件名 -> sha256 hex); 隐藏文件不参与 */
    private static Map<String, String> hashZips(File dir) throws Exception {
        Map<String, String> out = new LinkedHashMap<String, String>();
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || name.startsWith(".") || !name.toLowerCase().endsWith(".zip")) {
                continue;
            }
            out.put(name, sha256Hex(f));
        }
        return out;
    }

    /** 模组目录名: 清单文件名去掉 .zip 后缀 (与 workshop 模组的存放形态一致) */
    private static String modDirName(String file) {
        if (file.toLowerCase().endsWith(".zip")) {
            return file.substring(0, file.length() - 4);
        }
        return file;
    }

    /** 清掉上次失败装配留在模组目录里的 .inj-* 临时物 (引擎扫描器会把它们登记成坏模组条目) */
    private static void cleanStrayInjectorFiles(File modsDir) {
        File[] files = modsDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.getName().startsWith(".inj-")) {
                deleteRecursively(f);
            }
        }
    }

    /**
     * 解压 zip 为目录模组: 先解压到同文件系统临时目录, 成功后整体改名
     * 就位 —— 引擎扫描器永远看不到半解压状态.
     *
     * 解析用 ZipFile 而非 ZipInputStream: 后者对非 zip 字节过于宽容
     * (首部签名不符时静默返回"无条目"), 会让"过了哈希但损坏的内容"
     * 物化成空模组目录冒充装配成功 (失败路径演练实测); ZipFile 严格
     * 校验中央目录, 坏 zip 直接抛错, 且强制至少含一个条目.
     */
    private static void extractZip(File zip, File targetDir) throws IOException {
        File temp = new File(targetDir.getParentFile(), ".inj-tmp-" + targetDir.getName());
        deleteRecursively(temp);
        if (!temp.mkdirs()) {
            throw new IOException("cannot create temp directory: " + temp);
        }
        try {
            String tempRoot = temp.getCanonicalPath();
            ZipFile zf = new ZipFile(zip);
            try {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                byte[] buf = new byte[64 * 1024];
                boolean any = false;
                while (entries.hasMoreElements()) {
                    ZipEntry ze = entries.nextElement();
                    File out = new File(temp, ze.getName());
                    // zip-slip 防护: 解压目标必须落在临时目录内
                    if (!out.getCanonicalPath().startsWith(tempRoot + File.separator)) {
                        throw new IOException("unsafe path in zip: " + ze.getName());
                    }
                    if (ze.isDirectory()) {
                        if (!out.isDirectory() && !out.mkdirs()) {
                            throw new IOException("cannot create directory: " + out);
                        }
                        continue;
                    }
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("cannot create directory: " + parent);
                    }
                    InputStream in = zf.getInputStream(ze);
                    try {
                        FileOutputStream fo = new FileOutputStream(out);
                        try {
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                fo.write(buf, 0, n);
                            }
                        } finally {
                            fo.close();
                        }
                    } finally {
                        in.close();
                    }
                    any = true;
                }
                if (!any) {
                    throw new IOException("zip archive contains no entries: " + zip.getName());
                }
            } finally {
                zf.close();
            }
        } catch (IOException e) {
            deleteRecursively(temp); // 失败不留半解压状态
            throw e;
        }
        if (targetDir.exists()) {
            deleteRecursively(targetDir);
        }
        if (!temp.renameTo(targetDir)) {
            deleteRecursively(temp);
            throw new IOException("cannot place mod directory: " + targetDir.getName());
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }

    private static void downloadVerified(Config config, String roomCode,
                                         Manifest.Entry entry, File temp)
            throws Exception {
        Exception last = null;
        String url = config.controlUrl + "/rooms/" + roomCode + "/mods/" + entry.file;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                FileOutputStream out = new FileOutputStream(temp);
                long written;
                try {
                    written = Http.download(url, out, entry.size + 1024, TIMEOUT_DOWNLOAD_MS);
                } finally {
                    out.close();
                }
                if (written != entry.size) {
                    throw new IOException("size mismatch for " + entry.file
                            + ": got " + written + ", want " + entry.size);
                }
                String hash = sha256Hex(temp);
                if (!hash.equals(entry.sha256)) {
                    throw new SecurityException("hash mismatch for " + entry.file
                            + ": got " + hash + ", want " + entry.sha256);
                }
                return;
            } catch (Exception e) {
                last = e;
                if (temp.exists()) {
                    temp.delete();
                }
                if (e instanceof SecurityException) {
                    break; // 哈希不符不重试 (内容不可信)
                }
            }
        }
        throw last == null ? new IOException("download failed") : last;
    }

    /**
     * 重扫 -> 精确设置启用集 -> 持久化. (装配前启用态快照在物化之前
     * 已拍好, 见 assembleAndJoin 步骤 4a.)
     * 所有引擎触碰都在 UI 线程: rescanMods 内部等待; 标志位+持久化
     * 打包为一个 UI 任务投递.
     * 引擎对新增 mod 有自动禁用策略 (首载全禁 / 一次多出新模组不启用),
     * 所以必须由我们显式定启用态 (设计 §2.5).
     */
    private static void applyModSet(final Config config, final Manifest manifest)
            throws Exception {
        final ArrayList<?> entries = Engine.rescanMods();
        try {
            Engine.runOnUIThreadAndWait(new Runnable() {
                public void run() {
                    for (Object o : entries) {
                        Engine.setDisabled(o, !required(manifest, o));
                    }
                    Engine.saveModSettings();
                }
            }, 10_000);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot apply mod set: " + t);
        }
    }

    /** 条目是否房间要求: 按源路径文件名与清单 file 匹配 (哈希已在落盘时保证) */
    private static boolean required(Manifest manifest, Object entry) {
        String source = Engine.modSourcePath(entry);
        if (source == null) {
            return false;
        }
        String fileName = source.replace('\\', '/');
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) {
            fileName = fileName.substring(slash + 1);
        }
        if (manifest.byFile(fileName) != null) {
            return true;
        }
        // 目录型条目: 引擎以目录名登记, 清单里是 <目录名>.zip
        if (manifest.byFile(fileName + ".zip") != null) {
            return true;
        }
        return false;
    }

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte x : digest) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }
}
