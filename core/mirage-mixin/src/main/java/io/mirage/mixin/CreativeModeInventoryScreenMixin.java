package io.mirage.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.ItemPickerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTab.Row;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 创造模式物品栏标签页分页（对齐 NeoForge 行为，纯 Mixin、无运行时反射）。
 *
 * <p>为什么不用 Accessor 改 {@link CreativeModeTab} 的 column/row 字段：
 * {@code MirageTransformer} 已用 ASM 对 {@code CreativeModeTab} 做注入（补无参 {@code builder()}），
 * 再对它下 Mixin 会触发「自研 ASM + Mixin 双转」冲突（见 AGENTS.md §9）。
 * 本类只对 {@code CreativeModeInventoryScreen} 生效——该目标类在 {@code MirageTransformer}
 * 里被显式 {@code return original}，Mixin 安全。
 *
 * <p>布局模型（与 NeoForge 一致）：
 * <ul>
 *   <li>右对齐标签（{@code isAlignedRight()}，原版 4 个：hotbar/search/op_blocks/inventory）
 *       常驻、位置不变，永不参与分页；</li>
 *   <li>其余标签按 {@code CreativeModeTabs.tabs()} 注册顺序（原版在前）每页 10 个：
 *       页内前 5 个放 TOP 行 0..4 列，后 5 个放 BOTTOM 行 0..4 列；</li>
 *   <li>由此「第 0 页」恰等于原版那 10 个普通标签，外观与 vanilla 完全一致。</li>
 * </ul>
 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen", remap = false)
public abstract class CreativeModeInventoryScreenMixin extends AbstractContainerScreen<ItemPickerMenu> {

    /** 每页可放的分页标签数（TOP 行 5 + BOTTOM 行 5）。 */
    @Unique
    private static final int MIRAGE_TABS_PER_PAGE = 10;
    /** 每行可用的普通列数（0..4；列 5/6 留给右对齐标签）。 */
    @Unique
    private static final int MIRAGE_COLS_PER_ROW = 5;
    /** 非当前页标签伪造的列号，令 {@code getTabX} 算出极负坐标，天然不可点/不可悬停。 */
    @Unique
    private static final int MIRAGE_OFFSCREEN_COLUMN = -100;

    @Shadow
    private static CreativeModeTab selectedTab;

    @Shadow
    protected abstract void selectTab(CreativeModeTab tab);

    /** 参与分页的标签（按注册顺序；原版 10 个在最前）。 */
    @Unique
    private static List<CreativeModeTab> mirage$paged = new ArrayList<>();
    /** 标签 -> 在 {@code mirage$paged} 中的下标。 */
    @Unique
    private static Map<CreativeModeTab, Integer> mirage$index = new IdentityHashMap<>();
    @Unique
    private static int mirage$currentPage = 0;
    @Unique
    private static int mirage$pageCount = 1;

    public CreativeModeInventoryScreenMixin(ItemPickerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    // ------------------------------------------------------------------
    // 1) 每次打开/重建界面时重新布局并挂翻页按钮
    // ------------------------------------------------------------------
    @Inject(method = "init", at = @At("TAIL"), require = 1)
    private void mirage$initPaging(CallbackInfo ci) {
        try {
            List<CreativeModeTab> all = CreativeModeTabs.tabs();
            List<CreativeModeTab> paged = new ArrayList<>();
            Map<CreativeModeTab, Integer> index = new IdentityHashMap<>();
            for (CreativeModeTab tab : all) {
                if (!tab.isAlignedRight()) {
                    index.put(tab, paged.size());
                    paged.add(tab);
                }
            }
            mirage$paged = paged;
            mirage$index = index;
            mirage$pageCount = Math.max(1, (paged.size() + MIRAGE_TABS_PER_PAGE - 1) / MIRAGE_TABS_PER_PAGE);

            // 让打开界面时停留在「上次选中标签所在的那一页」，避免选中项与页不一致。
            mirage$currentPage = 0;
            if (selectedTab != null && !selectedTab.isAlignedRight()) {
                Integer idx = index.get(selectedTab);
                if (idx != null) {
                    mirage$currentPage = Math.min(idx / MIRAGE_TABS_PER_PAGE, mirage$pageCount - 1);
                }
            }

            if (mirage$pageCount > 1) {
                int btnY = this.topPos + 10;
                addRenderableWidget(Button.builder(Component.literal("<"), b -> mirage$switchPage(-1))
                        .bounds(this.leftPos - 20, btnY, 18, 12).build());
                addRenderableWidget(Button.builder(Component.literal(">"), b -> mirage$switchPage(1))
                        .bounds(this.leftPos + this.imageWidth + 2, btnY, 18, 12).build());
            }

            System.out.println("[Mirage-Mixin] 创造标签分页已生效 pageCount=" + mirage$pageCount
                    + " pagedTabs=" + paged.size() + " allTabs=" + all.size()
                    + " currentPage=" + mirage$currentPage);
        } catch (Throwable t) {
            System.err.println("[Mirage-Mixin] 创造标签分页初始化失败: " + t);
            t.printStackTrace();
        }
    }

    // ------------------------------------------------------------------
    // 2) 非当前页的标签：直接跳过绘制（离屏 cancel，避免画到错误位置）
    // ------------------------------------------------------------------
    @Inject(method = "extractTabButton", at = @At("HEAD"), cancellable = true, require = 1)
    private void mirage$hideOffPageTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       CreativeModeTab tab, CallbackInfo ci) {
        if (!mirage$onCurrentPage(tab)) {
            ci.cancel();
        }
    }

    // ------------------------------------------------------------------
    // 3) + 4) 把 column() 改写成「页内列号」（getTabX 定位 / extractTabButton 精灵索引）
    // ------------------------------------------------------------------
    @Redirect(method = "getTabX",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/CreativeModeTab;column()I"),
            require = 1)
    private int mirage$columnInGetTabX(CreativeModeTab tab) {
        return mirage$columnFor(tab);
    }

    @Redirect(method = "extractTabButton",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/CreativeModeTab;column()I"),
            require = 1)
    private int mirage$columnInExtractTabButton(CreativeModeTab tab) {
        return mirage$columnFor(tab);
    }

    // ------------------------------------------------------------------
    // 5) + 6) 把 row() 改写成「页内行」（getTabY 定位 / extractTabButton 精灵与 y）
    // ------------------------------------------------------------------
    @Redirect(method = "getTabY",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/CreativeModeTab;row()Lnet/minecraft/world/item/CreativeModeTab$Row;"),
            require = 1)
    private Row mirage$rowInGetTabY(CreativeModeTab tab) {
        return mirage$rowFor(tab);
    }

    @Redirect(method = "extractTabButton",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/CreativeModeTab;row()Lnet/minecraft/world/item/CreativeModeTab$Row;"),
            require = 1)
    private Row mirage$rowInExtractTabButton(CreativeModeTab tab) {
        return mirage$rowFor(tab);
    }

    // ------------------------------------------------------------------
    // 翻页
    // ------------------------------------------------------------------
    @Unique
    private void mirage$switchPage(int delta) {
        int next = mirage$currentPage + delta;
        if (next < 0 || next >= mirage$pageCount) {
            return;
        }
        mirage$currentPage = next;
        // 若原选中标签不在新页，则选中新页第一个标签（NeoForge/Fabric 同款 UX）。
        if (selectedTab == null || !mirage$onCurrentPage(selectedTab)) {
            int start = next * MIRAGE_TABS_PER_PAGE;
            if (start < mirage$paged.size()) {
                selectTab(mirage$paged.get(start));
            }
        }
    }

    // ------------------------------------------------------------------
    // 映射逻辑
    // ------------------------------------------------------------------
    /** 该标签在「当前页」是否可见（原版这一帧应把它画出来）。 */
    @Unique
    private static boolean mirage$onCurrentPage(CreativeModeTab tab) {
        if (tab.isAlignedRight()) {
            return true; // 右对齐标签常驻，永远可见
        }
        Integer i = mirage$index.get(tab);
        if (i == null) {
            return true; // 布局表里没有（异常兜底），放行原版逻辑
        }
        return i / MIRAGE_TABS_PER_PAGE == mirage$currentPage;
    }

    /** 该标签应使用的列号：右对齐→原列号；在当前页→页内列 0..4；不在当前页→离屏列。 */
    @Unique
    private static int mirage$columnFor(CreativeModeTab tab) {
        if (tab.isAlignedRight()) {
            return tab.column();
        }
        Integer i = mirage$index.get(tab);
        if (i == null) {
            return tab.column();
        }
        if (i / MIRAGE_TABS_PER_PAGE != mirage$currentPage) {
            return MIRAGE_OFFSCREEN_COLUMN;
        }
        return (i % MIRAGE_TABS_PER_PAGE) % MIRAGE_COLS_PER_ROW;
    }

    /** 该标签应使用的行：右对齐→原行；分页标签→页内位置 < 5 为 TOP，否则 BOTTOM。 */
    @Unique
    private static Row mirage$rowFor(CreativeModeTab tab) {
        if (tab.isAlignedRight()) {
            return tab.row();
        }
        Integer i = mirage$index.get(tab);
        if (i == null) {
            return tab.row();
        }
        return (i % MIRAGE_TABS_PER_PAGE) < MIRAGE_COLS_PER_ROW ? Row.TOP : Row.BOTTOM;
    }
}
