package io.mirage.loader;

import io.mirage.api.log.MirageLog;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM transformer：织入 MC 加载界面。
 *
 * 1. SharedConstants.tryDetectVersion() 开头打日志（M3 验证）
 * 2. LoadingOverlay.extractRenderState() return 前画调试文字（M5.5）
 */
public class MirageTransformer {

    public byte[] transform(String className, byte[] original) {
        String cn = className.replace('.', '/');
        if ("net/minecraft/SharedConstants".equals(cn)) {
            return transformSharedConstants(original);
        }
        if ("net/minecraft/client/gui/screens/LoadingOverlay".equals(cn)) {
            return transformLoadingOverlay(original);
        }
        if ("net/minecraft/client/gui/screens/TitleScreen".equals(cn)) {
            return transformTitleScreen(original);
        }
        if ("com/mojang/blaze3d/platform/Window".equals(cn)) {
            return transformWindow(original);
        }
        if ("net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen".equals(cn)) {
            return original;
        }
        if ("net/minecraft/server/packs/VanillaPackResourcesBuilder".equals(cn)) {
            return transformVanillaPackBuilder(original);
        }
        if ("net/minecraft/world/item/CreativeModeTab".equals(cn)) {
            return transformCreativeModeTab(original);
        }
        return original;
    }

    /**
     * NeoForge 兼容：原版只有 builder(Row, int)，mod 用的是无参 builder()。
     * 织入一个无参静态方法，委托给 builder(Row.TOP, 0)。
     */
    private byte[] transformCreativeModeTab(byte[] original) {
        try {
            ClassReader cr = new ClassReader(original);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public void visitEnd() {
                    MethodVisitor mv = visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            "builder", "()Lnet/minecraft/world/item/CreativeModeTab$Builder;",
                            null, null);
                    mv.visitCode();
                    mv.visitFieldInsn(Opcodes.GETSTATIC,
                            "net/minecraft/world/item/CreativeModeTab$Row",
                            "BOTTOM", "Lnet/minecraft/world/item/CreativeModeTab$Row;");
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "net/minecraft/world/item/CreativeModeTab",
                            "builder",
                            "(Lnet/minecraft/world/item/CreativeModeTab$Row;I)Lnet/minecraft/world/item/CreativeModeTab$Builder;",
                            false);
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                    super.visitEnd();
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("transform CreativeModeTab 失败（创造模式标签页分页 退回原版行为）", t);
            return original;
        }
    }

    private byte[] transformSharedConstants(byte[] original) {
        try {
            ClassReader cr = new ClassReader(original);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    if ("tryDetectVersion".equals(name)) {
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitCode() {
                                super.visitCode();
                                visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out",
                                        "Ljava/io/PrintStream;");
                                visitLdcInsn("[Mirage] mixin applied");
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
                                        "println", "(Ljava/lang/String;)V", false);
                            }
                        };
                    }
                    return mv;
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("transform SharedConstants 失败（版本注入 退回原版行为）", t);
            return original;
        }
    }

    private byte[] transformLoadingOverlay(byte[] original) {
        try {
            ClassReader cr = new ClassReader(original);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    if ("extractRenderState".equals(name)) {
                        MirageLog.MIRAGE.debug("织入 LoadingOverlay.extractRenderState()");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitInsn(int opcode) {
                                if (opcode == Opcodes.RETURN) {
                                    // 必须画在方法末尾：原版先提交全屏背景 fill，
                                    // 旧的 visitCode 注入会被背景整块盖住。
                                    // 这里画的是「Forge 式」加载界面（与早期窗口同一套布局）：
                                    // 只能用 MirageDebug 的像素字 + fill，
                                    // 绝不调用 gge.text()（重载期 gui_text 管线不可用）。
                                    // 参数 0 = this（LoadingOverlay，读淡出状态与重载进度）
                                    // 参数 1 = GuiGraphicsExtractor
                                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "io/mirage/loader/MirageDebug",
                                            "drawLoadingOverlay",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    return mv;
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("transform LoadingOverlay 失败（加载遮罩绘制 退回原版行为）", t);
            return original;
        }
    }

    private byte[] transformTitleScreen(byte[] original) {
        try {
            ClassReader cr = new ClassReader(original);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);

                    // 织入 init()：TitleScreen 出现后停止加载遮罩上的调试卡片
                    if ("init".equals(name) && "()V".equals(desc)) {
                        MirageLog.MIRAGE.debug("织入 TitleScreen.init() -> onTitleScreenShown()");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitCode() {
                                super.visitCode();
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "io/mirage/loader/MirageDebug",
                                        "onTitleScreenShown", "()V", false);
                            }
                        };
                    }

                    if ("extractRenderState".equals(name)) {
                        MirageLog.MIRAGE.debug("织入 TitleScreen.extractRenderState()");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitInsn(int opcode) {
                                if (opcode == Opcodes.RETURN) {
                                    // 必须在方法末尾画：原版先提交全屏背景，visitCode 注入会被盖住
                                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "net/minecraft/client/Minecraft",
                                            "getInstance", "()Lnet/minecraft/client/Minecraft;", false);
                                    mv.visitFieldInsn(Opcodes.GETFIELD,
                                            "net/minecraft/client/Minecraft",
                                            "font", "Lnet/minecraft/client/gui/Font;");
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "io/mirage/loader/MirageDebug",
                                            "drawTitleOverlay",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    // 26.2 签名：mouseClicked(MouseButtonEvent, boolean)
                    if ("mouseClicked".equals(name)
                            && "(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z".equals(desc)) {
                        MirageLog.MIRAGE.debug("织入 TitleScreen.mouseClicked(MouseButtonEvent)");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitCode() {
                                super.visitCode();
                                org.objectweb.asm.Label notHandled = new org.objectweb.asm.Label();
                                mv.visitVarInsn(Opcodes.ALOAD, 1);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "net/minecraft/client/input/MouseButtonEvent",
                                        "x", "()D", false);
                                mv.visitVarInsn(Opcodes.ALOAD, 1);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "net/minecraft/client/input/MouseButtonEvent",
                                        "y", "()D", false);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "io/mirage/loader/MirageDebug",
                                        "handleTitleClick", "(DD)Z", false);
                                mv.visitJumpInsn(Opcodes.IFEQ, notHandled);
                                mv.visitInsn(Opcodes.ICONST_1);
                                mv.visitInsn(Opcodes.IRETURN);
                                mv.visitLabel(notHandled);
                            }
                        };
                    }
                    return mv;
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("transform TitleScreen 失败（标题界面 HUD 退回原版行为）", t);
            return original;
        }
    }

    /**
     * 织入 {@code com.mojang.blaze3d.platform.Window#createGlfwWindow}：
     * 把 {@code GLFW.glfwCreateWindow} 换成 Mirage 的早期窗口交接逻辑。
     *
     * <p>早期窗口在 Launcher 最早期就创建并显示；这里把同一个 GLFW window
     * handle（以及已经 current 的 GL 上下文）交给 Minecraft，实现 Forge 式
     * “先渲染加载窗口，再交接给 MC”的流程。
     */
    private byte[] transformWindow(byte[] original) {
        try {
            ClassReader cr = new ClassReader(original);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    if ("createGlfwWindow".equals(name) && desc.startsWith("(IILjava/lang/String;J")) {
                        MirageLog.MIRAGE.debug("织入 Window.createGlfwWindow() -> 早期窗口交接");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String mname,
                                                       String mdesc, boolean itf) {
                                if (opcode == Opcodes.INVOKESTATIC
                                        && "org/lwjgl/glfw/GLFW".equals(owner)
                                        && "glfwCreateWindow".equals(mname)
                                        && "(IILjava/lang/CharSequence;JJ)J".equals(mdesc)) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "io/mirage/earlydisplay/MirageEarlyWindow",
                                            "createOrBorrow",
                                            "(IILjava/lang/CharSequence;JJ)J", false);
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
                            }
                        };
                    }
                    // 退出游戏时 Window.close() 会 glfwDestroyWindow(handle)。早期窗口是在
                    // Launcher 侧 GLFW 实例（进程内加载的另一份 glfw.dll）里创建的，交给
                    // MC 侧实例销毁会在摘窗口链表时解引用空 head → glfw.dll+0x77a0 原生崩溃。
                    // 改道到 MirageEarlyWindow.destroyWindow：谁创建谁销毁，非早期窗口句柄原样透传。
                    if ("close".equals(name) && "()V".equals(desc)) {
                        MirageLog.MIRAGE.debug("织入 Window.close() -> 早期窗口销毁改道");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String mname,
                                                       String mdesc, boolean itf) {
                                if (opcode == Opcodes.INVOKESTATIC
                                        && "org/lwjgl/glfw/GLFW".equals(owner)
                                        && "glfwDestroyWindow".equals(mname)
                                        && "(J)V".equals(mdesc)) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "io/mirage/earlydisplay/MirageEarlyWindow",
                                            "destroyWindow", "(J)V", false);
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
                            }
                        };
                    }
                    return mv;
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("transform Window 失败（窗口交接/销毁改道 退回原版行为）", t);
            return original;
        }
    }

    /**
     * 织入 VanillaPackResourcesBuilder.build()：
     * 在构建资源包前，把 mod 命名空间加入 exposeNamespace，
     * 这样 MC 资源系统会从 mod jar 加载 assets/<modid>/ 下的贴图、模型、语言等。
     */
    private byte[] transformVanillaPackBuilder(byte[] original) {
        try {
            ClassReader cr = new ClassReader(original);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    if ("build".equals(name)) {
                        MirageLog.MIRAGE.debug("织入 VanillaPackResourcesBuilder.build()");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitCode() {
                                super.visitCode();
                                // this（builder 对象）传给静态方法
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "io/mirage/loader/MirageDebug",
                                        "exposeModNamespaces", "(Ljava/lang/Object;)V", false);
                            }
                        };
                    }
                    return mv;
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("transform VanillaPackBuilder 失败（mod 资源包挂载 退回原版行为）", t);
            return original;
        }
    }

    /**
     * 织入 CreativeModeInventoryScreen：便签页（CreativeModeTab）分页切换。
     *
     * 原版 TOP/BOTTOM 行各 7 槽位，Mod tab 超过 7 个时越界。这里：
     *  1. extractTabButton：不在当前页的 tab 直接 return 不绘制
     *  2. getTabX：重写返回值为 displayCol*27（不在当前页则离屏 -1000）
     *  3. extractBackground：return 前画翻页箭头和页码
     *  4. mouseClicked / mouseReleased：开头检测翻页按钮点击
     */
    private byte[] transformCreativeInventory(byte[] original) {
        try {
            ClassReader cr = new ClassReader(original);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);

                    // ---- extractTabButton(gge, x, y, tab)：分页过滤 + 列号重映射 ----
                    if ("extractTabButton".equals(name) && desc.contains("CreativeModeTab")) {
                        MirageLog.MIRAGE.debug("织入 extractTabButton -> 页面过滤 + 重映射");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitCode() {
                                super.visitCode();
                                // shouldDrawTab(tab) ? continue : return
                                mv.visitVarInsn(Opcodes.ALOAD, 4);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "io/mirage/loader/MirageDebug",
                                        "shouldDrawTab", "(Ljava/lang/Object;)Z", false);
                                org.objectweb.asm.Label visible = new org.objectweb.asm.Label();
                                mv.visitJumpInsn(Opcodes.IFNE, visible);
                                mv.visitInsn(Opcodes.RETURN);
                                mv.visitLabel(visible);
                            }

                            @Override public void visitVarInsn(int opcode, int var) {
                                // istore 7（存 column）：POP 原版 column，替换为 remapColumn(tab)
                                if (opcode == Opcodes.ISTORE && var == 7) {
                                    mv.visitInsn(Opcodes.POP);
                                    mv.visitVarInsn(Opcodes.ALOAD, 4);
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "io/mirage/loader/MirageDebug",
                                            "remapColumn", "(Ljava/lang/Object;)I", false);
                                }
                                super.visitVarInsn(opcode, var);
                            }
                        };
                    }

                    // ---- getTabX(tab)I：重写 x 位置 ----
                    if ("getTabX".equals(name) && desc.contains("CreativeModeTab")) {
                        MirageLog.MIRAGE.debug("织入 getTabX -> 重映射列");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitInsn(int opcode) {
                                if (opcode == Opcodes.IRETURN) {
                                    // POP 原版 x，压入 remapTabX(tab)
                                    mv.visitInsn(Opcodes.POP);
                                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "io/mirage/loader/MirageDebug",
                                            "remapTabX", "(Ljava/lang/Object;)I", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }

                    // ---- extractBackground(gge, mouseX, mouseY, partialTick)：画翻页按钮 ----
                    if ("extractBackground".equals(name) && desc.contains("GuiGraphicsExtractor")) {
                        MirageLog.MIRAGE.debug("织入 extractBackground -> 绘制翻页控件");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitInsn(int opcode) {
                                if (opcode == Opcodes.RETURN) {
                                    // gge, leftPos, topPos, imageWidth, imageHeight, font
                                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                                    mv.visitFieldInsn(Opcodes.GETFIELD,
                                            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
                                            "leftPos", "I");
                                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                                    mv.visitFieldInsn(Opcodes.GETFIELD,
                                            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
                                            "topPos", "I");
                                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                                    mv.visitFieldInsn(Opcodes.GETFIELD,
                                            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
                                            "imageWidth", "I");
                                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                                    mv.visitFieldInsn(Opcodes.GETFIELD,
                                            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
                                            "imageHeight", "I");
                                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                                    mv.visitFieldInsn(Opcodes.GETFIELD,
                                            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
                                            "font", "Lnet/minecraft/client/gui/Font;");
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "io/mirage/loader/MirageDebug",
                                            "drawTabPageControls",
                                            "(Ljava/lang/Object;IIIILjava/lang/Object;)V", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }

                    // ---- mouseClicked(event, doubleClicked)Z：开头检测翻页 ----
                    if ("mouseClicked".equals(name) && "(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z".equals(desc)) {
                        MirageLog.MIRAGE.debug("织入 mouseClicked -> 翻页按钮检测");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitCode() {
                                super.visitCode();
                                org.objectweb.asm.Label notPage = new org.objectweb.asm.Label();
                                // 只处理左键
                                mv.visitVarInsn(Opcodes.ALOAD, 1);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "net/minecraft/client/input/MouseButtonEvent",
                                        "button", "()I", false);
                                mv.visitJumpInsn(Opcodes.IFNE, notPage);
                                // handlePageClick(event.x(), event.y(), leftPos, topPos, imageWidth, imageHeight)
                                mv.visitVarInsn(Opcodes.ALOAD, 1);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "net/minecraft/client/input/MouseButtonEvent",
                                        "x", "()D", false);
                                mv.visitVarInsn(Opcodes.ALOAD, 1);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "net/minecraft/client/input/MouseButtonEvent",
                                        "y", "()D", false);
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitFieldInsn(Opcodes.GETFIELD,
                                        "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
                                        "leftPos", "I");
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitFieldInsn(Opcodes.GETFIELD,
                                        "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
                                        "topPos", "I");
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitFieldInsn(Opcodes.GETFIELD,
                                        "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
                                        "imageWidth", "I");
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitFieldInsn(Opcodes.GETFIELD,
                                        "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
                                        "imageHeight", "I");
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "io/mirage/loader/MirageDebug",
                                        "handlePageClick", "(DDIIII)Z", false);
                                mv.visitJumpInsn(Opcodes.IFEQ, notPage);
                                mv.visitInsn(Opcodes.ICONST_1);
                                mv.visitInsn(Opcodes.IRETURN);
                                mv.visitLabel(notPage);
                            }
                        };
                    }

                    // ---- mouseReleased(event)Z：不处理翻页（避免双击翻两页） ----
                    // 翻页只在 mouseClicked 里处理，mouseReleased 不拦截

                    return mv;
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("transform CreativeInventory 失败（创造物品栏分页控件 退回原版行为）", t);
            return original;
        }
    }
}
