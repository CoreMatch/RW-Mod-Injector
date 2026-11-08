package com.corematch.rwmodinjector;

import com.corrodinggames.librocket.scripts.ScriptEngine;
import com.corrodinggames.rts.game.units.custom.ag;
import com.corrodinggames.rts.gameFramework.l;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 引擎胶水: 所有对游戏进程内引擎符号的直接引用集中在这里.
 * 符号依据见 knowledge/0001 §2 (game-lib.jar 字节码核查).
 *
 * 命名冲突说明: 混淆后的引擎在 gameFramework 顶层同时存在类 e/i/j 与
 * 同名子包, 源码级限定名 (gameFramework.i.b 等) 会被 javac 解析到类上.
 * 因此: 字段访问与已知类型的方法调用直接编译 (类型从类文件推断, 无需
 * 在源码里写出冲突名); 冲突包内的类型 (模组条目 i.b、FileLoader e.a、
 * NetEngine j.ad 静态方法、玩家类 game.a.a) 一律走反射.
 *
 * 线程纪律: 一切触碰引擎状态 (单位表/模组/聊天/连接) 的调用必须在
 * UI 线程上执行 —— 经 runOnUIThread (ScriptEngine.addRunnableToQueue,
 * 渲染循环每帧排空, 大厅等所有状态可用) 投递. q 队列 (l.a) 只在局内
 * 态排空, 绝不可用 (E2E 实测卡死证实).
 */
public final class Engine {

    private static final String FILE_LOADER_CLASS = "com.corrodinggames.rts.gameFramework.e.a";
    private static final String NET_ENGINE_CLASS = "com.corrodinggames.rts.gameFramework.j.ad";
    private static final String PLAYER_CLASS = "com.corrodinggames.rts.game.a.a";

    private Engine() {
    }

    public static boolean gameReady() {
        try {
            return l.B() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static l game() {
        l g = l.B();
        if (g == null) {
            throw new IllegalStateException("game engine is not initialized");
        }
        return g;
    }

    private static volatile Thread uiThread;

    /** UI 层 (ScriptEngine) 是否就绪 —— 投递通道可用的前提 */
    public static boolean uiReady() {
        try {
            return ScriptEngine.getInstance() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记录 UI 线程; 聊天钩子入口 (Commands.tryHandle) 运行时调用 */
    public static void noteUIThread(Thread t) {
        uiThread = t;
    }

    /**
     * 投递到 UI 线程: ScriptEngine 的 runnable 队列, 渲染循环每帧排空
     * (ScriptEngine.update -> Action.run), 大厅等所有状态可用.
     * UI 未就绪时抛异常, 由调用方决定降级方式.
     */
    public static void runOnUIThread(Runnable r) {
        ScriptEngine se = ScriptEngine.getInstance();
        if (se == null) {
            throw new IllegalStateException("UI thread is not available");
        }
        se.addRunnableToQueue(new UITask(r));
    }

    /**
     * 等待变体: 阻塞到 UI 线程执行完毕并传播异常. 若调用方已在
     * UI 线程 (如聊天钩子内), 直接内联执行, 避免死锁.
     */
    public static Object callOnUIThread(final Callable<Object> task, long timeoutMs)
            throws Exception {
        Thread ui = uiThread;
        if (ui != null && Thread.currentThread() == ui) {
            return task.call();
        }
        final Object[] box = new Object[2]; // [0]=结果 [1]=异常
        final CountDownLatch latch = new CountDownLatch(1);
        runOnUIThread(new Runnable() {
            public void run() {
                try {
                    box[0] = task.call();
                } catch (Throwable t) {
                    box[1] = t;
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("UI thread task timed out after " + timeoutMs + " ms");
        }
        if (box[1] instanceof Exception) {
            throw (Exception) box[1];
        }
        if (box[1] != null) {
            throw new IllegalStateException("UI task failed: " + box[1]);
        }
        return box[0];
    }

    public static void runOnUIThreadAndWait(Runnable r, long timeoutMs) throws Exception {
        callOnUIThread(new RunnableAdapter(r), timeoutMs);
    }

    /** UI 任务包装: 记录 UI 线程并隔离异常 (绝不能拖垮渲染循环) */
    private static final class UITask implements Runnable {
        private final Runnable delegate;

        UITask(Runnable delegate) {
            this.delegate = delegate;
        }

        public void run() {
            uiThread = Thread.currentThread();
            try {
                delegate.run();
            } catch (Throwable t) {
                System.err.println("[RW-Mod-Injector] UI task failed: " + t);
                t.printStackTrace();
            }
        }
    }

    private static final class RunnableAdapter implements Callable<Object> {
        private final Runnable r;

        RunnableAdapter(Runnable r) {
            this.r = r;
        }

        public Object call() {
            r.run();
            return null;
        }
    }

    /**
     * 聊天区系统消息 (引擎报告重载结果用的同一接口); 同时镜像到 stdout
     * 便于日志观测. 游戏内部分投递到 UI 线程; UI 未就绪 (启动早期)
     * 仅 stdout.
     */
    public static void systemMessage(final String text) {
        System.out.println("[Injector] " + text);
        try {
            runOnUIThread(new Runnable() {
                public void run() {
                    game().bS.b(text);
                }
            });
        } catch (Throwable t) {
            // UI 未就绪: 仅 stdout
        }
    }

    /**
     * 是否有活跃连接 (netEngine 连接队列 aM 非空). 作为装配门控:
     * 玩家必须已连入大厅 (设计 §2.2 安全窗口); 局中禁装配由流程纪律保证.
     */
    public static boolean multiplayerActive() {
        try {
            if (!gameReady()) {
                return false;
            }
            Object queue = l.B().bX.aM;
            return queue != null && !((java.util.concurrent.ConcurrentLinkedQueue<?>) queue).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    // --- 模组目录 ---

    /**
     * 客户端模组目录 (物理路径). 引擎给的是 /SD 虚拟路径 (ag.m), 先经
     * FileLoader 解析; 解析结果不是真实目录时按候选根兜底 (FileLoader
     * 根 / 进程工作目录 / 用户主目录). 全部落空则装配失败 —— 绝不猜测落盘.
     */
    public static File modsDirectory() {
        String virtualPath = ag.m();
        try {
            String resolved = (String) Class.forName(FILE_LOADER_CLASS)
                    .getMethod("p", String.class).invoke(null, virtualPath);
            File dir = new File(resolved);
            if (dir.isDirectory()) {
                return dir;
            }
        } catch (Throwable ignored) {
            // 落入候选根兜底
        }
        // /SD 前缀映射兜底: 桌面客户端的 /SD 对应游戏目录 (本机实测
        // <游戏目录>/mods/units 与 preferences.ini 同目录)
        String tail = virtualPath.startsWith("/SD") ? virtualPath.substring(3) : virtualPath;
        String[] roots = new String[]{fileLoaderRoot(), System.getProperty("user.dir"),
                System.getProperty("user.home")};
        for (String root : roots) {
            if (root == null || root.isEmpty()) {
                continue;
            }
            File candidate = new File(root + tail);
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "mods directory does not exist after resolution: " + virtualPath);
    }

    /** FileLoader 后端根路径 (e.a.c), 拿不到返回 null */
    private static String fileLoaderRoot() {
        try {
            return (String) Class.forName(FILE_LOADER_CLASS).getMethod("c").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // --- 模组列表与启用态 (条目类型 i.b 冲突, 走反射) ---

    /** 重扫模组目录 (引擎 Mods 界面同款操作), 返回活的模组条目列表; UI 线程执行并等待结果 */
    public static ArrayList<?> rescanMods() throws Exception {
        return (ArrayList<?>) callOnUIThread(new Callable<Object>() {
            public Object call() {
                return game().bZ.k();
            }
        }, 10_000);
    }

    public static String modName(Object entry) {
        try {
            return (String) entry.getClass().getMethod("a").invoke(entry);
        } catch (Throwable t) {
            return String.valueOf(entryField(entry, "q"));
        }
    }

    public static String modSourcePath(Object entry) {
        Object v = entryField(entry, "q");
        return v == null ? null : v.toString();
    }

    public static boolean isDisabled(Object entry) {
        Object v = entryField(entry, "f");
        return v instanceof Boolean && (Boolean) v;
    }

    /** 设置条目禁用标志 (仅 UI 线程调用) */
    public static void setDisabled(Object entry, boolean disabled) {
        try {
            Field f = entry.getClass().getField("f");
            f.setBoolean(entry, disabled);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot set mod disabled flag: " + t);
        }
    }

    private static Object entryField(Object entry, String name) {
        try {
            return entry.getClass().getField(name).get(entry);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 持久化模组启用态到引擎设置 (仅 UI 线程调用) */
    public static void saveModSettings() {
        game().bZ.e();
        try {
            game().bQ.save();
        } catch (Throwable t) {
            System.err.println("[RW-Mod-Injector] settings save failed: " + t);
        }
    }

    // --- 热重载 (设计 §2) ---

    /**
     * 全量重建自定义单位表 (ag.h): 清注册表 -> 重载基础单位 -> 按启用态
     * 从磁盘加载模组. 只在大厅态调用; 必须在 UI 线程执行
     * (调用方用 runOnUIThreadAndWait 投递).
     */
    public static void reloadUnits() {
        ag.h();
    }

    /** 引擎自身重载命令之后的玩家侧刷新 (逐个 al(); 仅 UI 线程调用) */
    public static void refreshPlayers() {
        try {
            ArrayList<?> players = com.corrodinggames.rts.game.n.c();
            if (players == null) {
                return;
            }
            Class<?> playerClass = Class.forName(PLAYER_CLASS);
            Method al = playerClass.getMethod("al");
            for (Object p : players) {
                if (playerClass.isInstance(p)) {
                    al.invoke(p);
                }
            }
        } catch (Throwable t) {
            System.err.println("[RW-Mod-Injector] player refresh failed: " + t);
        }
    }

    /**
     * 干净离开当前房间回主菜单 (netEngine.b(String): 停收发线程、发断开
     * 通知、清连接态). 服务端会正常回收玩家. 仅 UI 线程调用.
     */
    public static void leaveToMenu(String reason) {
        game().bX.b(reason);
    }

    /**
     * 引擎自带的 reconnectToServer (netEngine.V): 重连 lastConnectedTo
     * (bv 只在连接时赋值、从不清空, 断开后仍可用). 返回是否成功.
     * 仅 UI 线程调用.
     */
    public static boolean reconnectLast() {
        return game().bX.V();
    }

    // --- 出口动作 ---

    /** 发送聊天文本 (走引擎自己的聊天发送, Cascade 按聊天命令拦截); 投递 UI 线程 */
    public static void sendChat(final String text) {
        runOnUIThread(new Runnable() {
            public void run() {
                game().bX.m(text);
            }
        });
    }

    /**
     * 连接目标服务器 (投递 UI 线程, fire-and-forget; 结果用
     * multiplayerActive 观测): 优先走客户端正规入口 Root.joinServer
     * (多人菜单"加入服务器"的同一方法, 含 Preregister/Register 完整握手,
     * native UI 调用栈要求必须在 UI 线程); 不可用时退回裸 socket 接管
     * (Helper LinkGameNet.newConnect 同款). 参数形如 "host:port".
     */
    public static void connectDirect(final String target) {
        runOnUIThread(new Runnable() {
            public void run() {
                try {
                    ScriptEngine se = ScriptEngine.getInstance();
                    if (se != null) {
                        com.corrodinggames.librocket.scripts.Root root = se.getRootNoCheck();
                        if (root != null) {
                            root.joinServer(target);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[RW-Mod-Injector] joinServer failed, falling back: " + t);
                }
                try {
                    Class<?> netEngine = Class.forName(NET_ENGINE_CLASS);
                    Object socket = netEngine.getMethod("b", String.class, boolean.class)
                            .invoke(null, target, false);
                    game().bX.a((java.net.Socket) socket);
                } catch (Throwable t) {
                    System.err.println("[RW-Mod-Injector] direct connect failed: " + t);
                }
            }
        });
    }
}
