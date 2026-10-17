package io.mirage.loader;

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
        if ("net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen".equals(cn)) {
            return transformCreativeInventory(original);
        }
        if ("net/minecraft/server/packs/VanillaPackResourcesBuilder".equals(cn)) {
            return transformVanillaPackBuilder(original);
        }
        return original;
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
            System.err.println("[Mirage] transform SharedConstants failed: " + t);
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
                        System.out.println("[Mirage] 织入 LoadingOverlay.extractRenderState()");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            private int lineY = 10;

                            private void drawLine(MethodVisitor mv, String text, int y) {
                                // gge
                                mv.visitVarInsn(Opcodes.ALOAD, 1);
                                // font = this.minecraft.font
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitFieldInsn(Opcodes.GETFIELD,
                                        "net/minecraft/client/gui/screens/LoadingOverlay",
                                        "minecraft", "Lnet/minecraft/client/Minecraft;");
                                mv.visitFieldInsn(Opcodes.GETFIELD,
                                        "net/minecraft/client/Minecraft",
                                        "font", "Lnet/minecraft/client/gui/Font;");
                                // text（编译期固定字符串）
                                mv.visitLdcInsn(text);
                                mv.visitIntInsn(Opcodes.SIPUSH, 10);
                                mv.visitIntInsn(Opcodes.SIPUSH, y);
                                mv.visitLdcInsn(0xFFFFFFFF);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "io/mirage/loader/MirageDebug",
                                        "drawTextSafe",
                                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;III)V",
                                        false);
                            }

                            /** 运行时动态读 MirageDebug.xxx() 再画。 */
                            private void drawDynamicLine(MethodVisitor mv, String getterMethod, int y) {
                                mv.visitVarInsn(Opcodes.ALOAD, 1);
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitFieldInsn(Opcodes.GETFIELD,
                                        "net/minecraft/client/gui/screens/LoadingOverlay",
                                        "minecraft", "Lnet/minecraft/client/Minecraft;");
                                mv.visitFieldInsn(Opcodes.GETFIELD,
                                        "net/minecraft/client/Minecraft",
                                        "font", "Lnet/minecraft/client/gui/Font;");
                                // 运行时调 MirageDebug.getterMethod()
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "io/mirage/loader/MirageDebug",
                                        getterMethod, "()Ljava/lang/String;", false);
                                mv.visitIntInsn(Opcodes.SIPUSH, 10);
                                mv.visitIntInsn(Opcodes.SIPUSH, y);
                                mv.visitLdcInsn(0xFFFFFFFF);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "io/mirage/loader/MirageDebug",
                                        "drawTextSafe",
                                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;III)V",
                                        false);
                            }

                            @Override public void visitCode() {
                                super.visitCode();
                                try {
                                    // 半透明黑底面板
                                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 5);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 5);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 260);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 135);
                                    mv.visitLdcInsn(0x90000000);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                            "net/minecraft/client/gui/GuiGraphicsExtractor",
                                            "fill", "(IIIII)V", false);
                                    // 注意：LoadingOverlay 阶段不能调 gge.text()！
                                    // 那会提前触发 gui_text shader 管线编译，而此时 core/text
                                    // shader 还在资源重载中找不到，导致管线永久失败、后续所有文字不显示。
                                    // 这里只用 fill 画一条进度条，宽度随 MirageDebug.progress 变化。
                                    // 进度条槽位（灰色底）
                                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 5);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 96);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 265);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 100);
                                    mv.visitLdcInsn(0xFF444444);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                            "net/minecraft/client/gui/GuiGraphicsExtractor",
                                            "fill", "(IIIII)V", false);
                                    // 进度条填充（绿色）：x2 = 5 + progress*2
                                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 5);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 96);
                                    mv.visitFieldInsn(Opcodes.GETSTATIC,
                                            "io/mirage/loader/MirageDebug",
                                            "progress", "I");
                                    mv.visitIntInsn(Opcodes.SIPUSH, 2);
                                    mv.visitInsn(Opcodes.IMUL);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 5);
                                    mv.visitInsn(Opcodes.IADD);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 100);
                                    mv.visitLdcInsn(0xFF00CC44);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                            "net/minecraft/client/gui/GuiGraphicsExtractor",
                                            "fill", "(IIIII)V", false);
                                } catch (Throwable t) {
                                    System.err.println("[Mirage] 绘制失败: " + t);
                                }
                            }
                        };
                    }
                    return mv;
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[Mirage] transform LoadingOverlay failed: " + t);
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

                    // 织入 init()：TitleScreen 第一次显示时标记 loaded=true，
                    // 这样 extractRenderState 里的调试面板会自动隐藏
                    if ("init".equals(name) && "()V".equals(desc)) {
                        System.out.println("[Mirage] 织入 TitleScreen.init() -> 置 loaded=true");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitCode() {
                                super.visitCode();
                                mv.visitInsn(Opcodes.ICONST_1);
                                mv.visitFieldInsn(Opcodes.PUTSTATIC,
                                        "io/mirage/loader/MirageDebug", "loaded", "Z");
                            }
                        };
                    }

                    if ("extractRenderState".equals(name)) {
                        System.out.println("[Mirage] 织入 TitleScreen.extractRenderState()");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitInsn(int opcode) {
                                if (opcode == Opcodes.RETURN) {
                                    // 如果 showMods，画全屏 Mod 列表（始终保留）
                                    mv.visitFieldInsn(Opcodes.GETSTATIC,
                                            "io/mirage/loader/MirageDebug", "showMods", "Z");
                                    org.objectweb.asm.Label skip = new org.objectweb.asm.Label();
                                    mv.visitJumpInsn(Opcodes.IFEQ, skip);

                                    // 全屏半透明黑底
                                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 0);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 0);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 400);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 240);
                                    mv.visitLdcInsn(0xCC000000);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                            "net/minecraft/client/gui/GuiGraphicsExtractor",
                                            "fill", "(IIIII)V", false);
                                    drawLineHelper(mv, "=== Mirage Mod List ===", 20);
                                    drawDynamicHelper(mv, "modListLine", 40);
                                    drawLineHelper(mv, "(click Mods button to close)", 200);

                                    mv.visitLabel(skip);

                                    // ====== 左上角调试面板：只在 loaded=false 时画 ======
                                    org.objectweb.asm.Label skipPanel = new org.objectweb.asm.Label();
                                    mv.visitFieldInsn(Opcodes.GETSTATIC,
                                            "io/mirage/loader/MirageDebug", "loaded", "Z");
                                    mv.visitJumpInsn(Opcodes.IFNE, skipPanel);

                                    // 半透明黑底
                                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 5);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 5);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 260);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 135);
                                    mv.visitLdcInsn(0x90000000);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                            "net/minecraft/client/gui/GuiGraphicsExtractor",
                                            "fill", "(IIIII)V", false);
                                    drawLineHelper(mv, "[Mirage Loader] v0.4.0 · MC 26.2", 10);
                                    drawDynamicHelper(mv, "memLine", 24);
                                    drawDynamicHelper(mv, "cpuLine", 38);
                                    drawDynamicHelper(mv, "stageLine", 52);
                                    drawDynamicHelper(mv, "modLine", 66);
                                    drawDynamicHelper(mv, "progressLine", 80);
                                    drawDynamicHelper(mv, "modListLine", 94);
                                    // Mod 按钮（放在 Realms 下面，和原生按钮对齐）
                                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 100);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 180);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 300);
                                    mv.visitIntInsn(Opcodes.SIPUSH, 200);
                                    mv.visitLdcInsn(0xFF666666);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                            "net/minecraft/client/gui/GuiGraphicsExtractor",
                                            "fill", "(IIIII)V", false);
                                    drawLineHelper(mv, "Mods", 185);

                                    mv.visitLabel(skipPanel);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    // 织入 mouseClicked(double x, double y, int button) 检测 Mod 按钮
                    if ("mouseClicked".equals(name) && "(DDI)Z".equals(desc)) {
                        System.out.println("[Mirage] 织入 TitleScreen.mouseClicked()");
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            private final org.objectweb.asm.Label notInside = new org.objectweb.asm.Label();
                            @Override public void visitCode() {
                                super.visitCode();
                                mv.visitVarInsn(Opcodes.DLOAD, 1);
                                mv.visitLdcInsn(100.0);
                                mv.visitInsn(Opcodes.DCMPL);
                                mv.visitJumpInsn(Opcodes.IFLT, notInside);
                                mv.visitVarInsn(Opcodes.DLOAD, 1);
                                mv.visitLdcInsn(300.0);
                                mv.visitInsn(Opcodes.DCMPL);
                                mv.visitJumpInsn(Opcodes.IFGT, notInside);
                                mv.visitVarInsn(Opcodes.DLOAD, 3);
                                mv.visitLdcInsn(180.0);
                                mv.visitInsn(Opcodes.DCMPL);
                                mv.visitJumpInsn(Opcodes.IFLT, notInside);
                                mv.visitVarInsn(Opcodes.DLOAD, 3);
                                mv.visitLdcInsn(200.0);
                                mv.visitInsn(Opcodes.DCMPL);
                                mv.visitJumpInsn(Opcodes.IFGT, notInside);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "io/mirage/loader/MirageDebug", "toggleMods", "()V", false);
                                mv.visitInsn(Opcodes.ICONST_1);
                                mv.visitInsn(Opcodes.IRETURN);
                                mv.visitLabel(notInside);
                            }
                        };
                    }
                    return mv;
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[Mirage] transform TitleScreen failed: " + t);
            return original;
        }
    }

    private static void drawLineHelper(MethodVisitor mv, String text, int y) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        // Screen 有 minecraft 字段？不，TitleScreen 没有 minecraft 字段。
        // 用 Minecraft.getInstance().font
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft",
                "getInstance", "()Lnet/minecraft/client/Minecraft;", false);
        mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/client/Minecraft",
                "font", "Lnet/minecraft/client/gui/Font;");
        mv.visitLdcInsn(text);
        mv.visitIntInsn(Opcodes.SIPUSH, 10);
        mv.visitIntInsn(Opcodes.SIPUSH, y);
        mv.visitLdcInsn(0xFFFFFFFF);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "io/mirage/loader/MirageDebug",
                "drawTextSafe",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;III)V", false);
    }

    private static void drawDynamicHelper(MethodVisitor mv, String getter, int y) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft",
                "getInstance", "()Lnet/minecraft/client/Minecraft;", false);
        mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/client/Minecraft",
                "font", "Lnet/minecraft/client/gui/Font;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "io/mirage/loader/MirageDebug",
                getter, "()Ljava/lang/String;", false);
        mv.visitIntInsn(Opcodes.SIPUSH, 10);
        mv.visitIntInsn(Opcodes.SIPUSH, y);
        mv.visitLdcInsn(0xFFFFFFFF);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "io/mirage/loader/MirageDebug",
                "drawTextSafe",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;III)V", false);
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
                        System.out.println("[Mirage] 织入 VanillaPackResourcesBuilder.build()");
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
            System.err.println("[Mirage] transform VanillaPackBuilder failed: " + t);
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
                        System.out.println("[Mirage] 织入 extractTabButton -> 页面过滤 + 重映射");
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
                        System.out.println("[Mirage] 织入 getTabX -> 重映射列");
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
                        System.out.println("[Mirage] 织入 extractBackground -> 绘制翻页控件");
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
                        System.out.println("[Mirage] 织入 mouseClicked -> 翻页按钮检测");
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
            System.err.println("[Mirage] transform CreativeInventory failed: " + t);
            return original;
        }
    }
}
