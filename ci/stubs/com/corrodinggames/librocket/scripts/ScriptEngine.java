package com.corrodinggames.librocket.scripts;

/**
 * CI 编译桩: 引擎 ScriptEngine 的最小签名替身.
 *
 * 真实 game-lib.jar 是闭源客户端字节码, 不入库不分发 (设计 §1 边界);
 * CI 里没有它, 用本目录的桩满足 javac 的符号解析. 桩只用于编译期,
 * 绝不打进产物 (ci/build-ci.sh 只打包 injector 类与 ASM).
 * 签名以真实 jar 的字节码核查为准 (knowledge/0001 §2).
 */
public class ScriptEngine {
    public static ScriptEngine getInstance() {
        return null;
    }

    public void addRunnableToQueue(Runnable r) {
    }

    public Root getRootNoCheck() {
        return null;
    }
}
