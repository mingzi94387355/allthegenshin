package com.chapple.allthegenshin;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.forgespi.language.IModInfo;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端专用：进服务器的时候把自己加载的模组列表发过去，让服务端再验一轮。
 */
@Mod.EventBusSubscriber(modid = allthegenshin.MOD_ID, value = Dist.CLIENT)
public final class ClientNetworkHook {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ClientNetworkHook() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!ATGConfig.enabled()) {
            return;
        }
        try {
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener == null) {
                return;
            }
            Connection connection = listener.getConnection();
            if (!ATGNetwork.CHANNEL.isRemotePresent(connection)) {
                LOGGER.info("[All the Genshin] 服务端没装 All the Genshin，跳过联机验证");
                return;
            }

            List<String> mods = new ArrayList<>();
            for (IModInfo info : ModList.get().getMods()) {
                mods.add(info.getModId());
            }
            ATGNetwork.CHANNEL.sendToServer(new ATGNetwork.ModListMessage(mods));
            LOGGER.info("[All the Genshin] 已把 {} 个模组发给服务端验证", mods.size());
        } catch (Throwable t) {
            LOGGER.error("[All the Genshin] 发送模组列表失败", t);
        }
    }
}
