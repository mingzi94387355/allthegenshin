package com.chapple.allthegenshin;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * “游戏启动完成”的判定。
 *
 * <p>等到主菜单（或世界）真正显示出来之后，再执行 crash_mod 检测。</p>
 */
@Mod.EventBusSubscriber(modid = allthegenshin.MOD_ID, value = Dist.CLIENT)
public final class ClientStartupWatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 兜底：主界面被别的模组换掉时，最多等这么久（tick），之后照样检测。 */
    private static final int FALLBACK_TICKS = 600;

    private static boolean triggered;
    private static int ticks;

    private ClientStartupWatcher() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (triggered || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        ticks++;

        boolean mainMenuReady = minecraft.level != null || minecraft.screen instanceof TitleScreen;
        if (!mainMenuReady && ticks < FALLBACK_TICKS) {
            return;
        }

        triggered = true;
        LOGGER.info("[All the Genshin] 游戏启动完成，开始检测 crash_mod 列表");
        GenshinTakeover.checkAndCrash();
    }
}
