package com.corematch.rwmodinjector;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * 聊天发送入口的注入桩 (设计 §1/§7, 决策 D1 自建注入).
 *
 * 挂点: com.corrodinggames.librocket.scripts.Root.sendChatMessage(String)
 * —— 聊天框回车发送的唯一入口 (UI 脚本 "[onenter]Send:
 * sendChatMessage(getPopupText())"). 在方法体开头注入:
 *
 *   if (Commands.tryHandle(text)) return;   // /inj 命令被消费
 *   <原逻辑不动>
 *
 * 只注入方法体、不新增成员: 与 retransform 约束兼容, 启动期装载
 * (-javaagent) 与运行期 attach 两条通道都能用. 变换失败一律放行原
 * 字节码 —— 聊天功能绝不能被 Injector 弄坏.
 */
public final class ChatHookTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS =
            "com/corrodinggames/librocket/scripts/Root";
    private static final String TARGET_METHOD = "sendChatMessage";
    private static final String TARGET_DESC = "(Ljava/lang/String;)V";

    private ChatHookTransformer() {
    }

    public static void install(Instrumentation inst, boolean attached) {
        ChatHookTransformer transformer = new ChatHookTransformer();
        inst.addTransformer(transformer, attached);
        if (attached) {
            // 运行期 attach: Root 多半已装载, 需要重转换
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (c.getName().equals(TARGET_CLASS.replace('/', '.'))
                        && inst.isModifiableClass(c)) {
                    try {
                        inst.retransformClasses(c);
                    } catch (Throwable t) {
                        System.err.println("[RW-Mod-Injector] retransform Root failed: " + t);
                    }
                    break;
                }
            }
        }
    }

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            // COMPUTE_FRAMES: 注入的分支需要新 stackmap 帧 (客户端是 Java 8
            // 字节码, 校验器强制要求帧); SKIP_FRAMES 丢弃旧帧整体重算.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
            reader.accept(new HookClassVisitor(writer), ClassReader.SKIP_FRAMES);
            System.out.println("[RW-Mod-Injector] chat hook installed on " + className);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[RW-Mod-Injector] chat hook transform failed, passing through: " + t);
            return null;
        }
    }

    private static final class HookClassVisitor extends ClassVisitor {
        HookClassVisitor(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (TARGET_METHOD.equals(name) && TARGET_DESC.equals(descriptor)) {
                return new HookMethodVisitor(next);
            }
            return next;
        }
    }

    private static final class HookMethodVisitor extends MethodVisitor {
        HookMethodVisitor(MethodVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            Label skip = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/corematch/rwmodinjector/Commands", "tryHandle",
                    "(Ljava/lang/String;)Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, skip);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(skip);
        }
    }
}
