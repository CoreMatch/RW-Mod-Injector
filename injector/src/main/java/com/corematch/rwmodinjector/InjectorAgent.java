package com.corematch.rwmodinjector;

import java.lang.instrument.Instrumentation;

/**
 * RW-Mod-Injector agent 入口 (设计: knowledge/0001 §1/§4).
 *
 * 两个装载通道:
 *  - premain: 启动参数 -javaagent (安装器主通道, 具备装载期类变换能力);
 *  - agentmain: 运行期 Attach API 挂载 (备用通道, 只做方法体注入,
 *    与 retransform 约束兼容).
 *
 * agent 参数 = 配置文件路径 (可选; 缺省取 jar 同目录的
 * rw-mod-injector.properties).
 */
public final class InjectorAgent {

    private InjectorAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        boot(args, inst, false);
    }

    public static void agentmain(String args, Instrumentation inst) {
        boot(args, inst, true);
    }

    private static void boot(String args, Instrumentation inst, boolean attached) {
        try {
            Config config = Config.load(args);
            ChatHookTransformer.install(inst, attached);
            Core.init(config);
            System.out.println("[RW-Mod-Injector] agent loaded ("
                    + (attached ? "attached" : "premain") + "), control=" + config.controlUrl);
        } catch (Throwable t) {
            // agent 失败绝不能拖垮游戏启动
            System.err.println("[RW-Mod-Injector] boot failed: " + t);
            t.printStackTrace();
        }
    }
}
