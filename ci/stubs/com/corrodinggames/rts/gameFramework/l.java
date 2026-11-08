package com.corrodinggames.rts.gameFramework;

import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * CI 编译桩: 游戏主控制器 (混淆名 l) 的最小签名替身.
 *
 * 字段类型用本类内嵌的桩类型承载 (名字与真实混淆名无关, 只需方法签名
 * 一致 —— 源码级访问都是经字段推断类型). 见 ScriptEngine.java 的说明.
 */
public class l {
    public ChatStub bS;
    public ModManagerStub bZ;
    public SettingsStub bQ;
    public NetStub bX;

    public static l B() {
        return null;
    }

    /** 聊天/系统消息面 */
    public static class ChatStub {
        public void b(String text) {
        }
    }

    /** ModManager (i.a) 面 */
    public static class ModManagerStub {
        public ArrayList k() {
            return new ArrayList();
        }

        public void e() {
        }
    }

    /** 设置持久化面 */
    public static class SettingsStub {
        public void save() {
        }
    }

    /** netEngine (j.ad) 面 */
    public static class NetStub {
        /** 连接队列: 非空 = 有活跃连接 */
        public ConcurrentLinkedQueue aM;

        /** 干净断连回主菜单 */
        public void b(String reason) {
        }

        /** 发聊天 */
        public void m(String text) {
        }

        /** reconnectToServer */
        public boolean V() {
            return false;
        }

        /** 裸 socket 接管 */
        public void a(Socket socket) {
        }
    }
}
