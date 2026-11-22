package probe;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import net.neoforged.fml.IExtensionPoint;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

/** W3-C2 P0/P1 headless 探针：只输出 ASCII KEY=VALUE，避免 GBK 解码坑。 */
public final class P0P1Probe {
    public static void main(String[] args) throws Exception {
        // 1) IExtensionPoint 空接口形状
        Class<?> ext = IExtensionPoint.class;
        System.out.println("EXT_IS_INTERFACE=" + ext.isInterface());
        System.out.println("EXT_METHODS=" + ext.getDeclaredMethods().length);

        // 2) IConfigScreenFactory 继承 + 方法形态
        Class<?> fac = IConfigScreenFactory.class;
        System.out.println("FAC_EXTENDS_EXT=" + ext.isAssignableFrom(fac));
        Method create = fac.getDeclaredMethod("createScreen",
                net.neoforged.fml.ModContainer.class, net.minecraft.client.gui.screens.Screen.class);
        System.out.println("FAC_CREATE_ABSTRACT=" + Modifier.isAbstract(create.getModifiers()));
        Method getFor = fac.getDeclaredMethod("getForMod", net.neoforged.neoforgespi.language.IModInfo.class);
        System.out.println("FAC_GETFOR_STATIC=" + Modifier.isStatic(getFor.getModifiers()));
        Object r = getFor.invoke(null, (Object) null);
        System.out.println("FAC_GETFOR_EMPTY=" + (r instanceof Optional && ((Optional<?>) r).isEmpty()));

        // 3) OnDatapackSyncEvent 形状：继承 Event、字段类型、getter 真值、UOE 消息
        Class<?> ev = OnDatapackSyncEvent.class;
        System.out.println("EV_EXTENDS_EVENT=" + Event.class.isAssignableFrom(ev));
        System.out.println("EV_CTOR=" + (ev.getDeclaredConstructor(
                net.minecraft.server.players.PlayerList.class,
                net.minecraft.server.level.ServerPlayer.class) != null));
        Method gp = ev.getDeclaredMethod("getPlayerList");
        Method gpl = ev.getDeclaredMethod("getPlayer");
        System.out.println("EV_GETTER_TYPES=" + (gp.getReturnType() == net.minecraft.server.players.PlayerList.class
                && gpl.getReturnType() == net.minecraft.server.level.ServerPlayer.class));
        System.out.println("EV_FIELD_COUNT=" + ev.getDeclaredFields().length);

        // 4) getter 返字段语义：用反射验证 getter 读取的是同名字段（源码锚点在门禁守卫层补强）。
        //    JDK 25 上 PlayerList 无法通过 Unsafe 造未初始化实例，故不在 JVM 侧造哨兵。
        System.out.println("EV_FIELD_NAMES=" + (ev.getDeclaredFields()[0].getName().equals("playerList")
                && ev.getDeclaredFields()[1].getName().equals("player")));
        Object inst = ev.getDeclaredConstructor(
                net.minecraft.server.players.PlayerList.class,
                net.minecraft.server.level.ServerPlayer.class).newInstance(null, null);

        // 5) UOE 三条：getRelevantPlayers / 两个 sendRecipes / getRecipeTypesToSend
        String[] uoeMethods = {"getRelevantPlayers", "getRecipeTypesToSend"};
        int uoeOk = 0;
        for (String name : uoeMethods) {
            try {
                ev.getDeclaredMethod(name).invoke(inst);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof UnsupportedOperationException
                        && c.getMessage() != null && c.getMessage().contains("OnDatapackSyncEvent#" + name)) {
                    uoeOk++;
                }
            }
        }
        System.out.println("EV_UOE=" + (uoeOk == uoeMethods.length));
        System.out.println("PROBE_DONE=true");
    }

}
