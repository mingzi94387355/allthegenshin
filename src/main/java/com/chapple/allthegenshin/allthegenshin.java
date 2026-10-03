package com.chapple.allthegenshin;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * All the Genshin。
 *
 * <p>启动流程：</p>
 * <ol>
 *   <li>游戏启动阶段（{@link FMLCommonSetupEvent}）：注册联机验证通道；客户端另外还会去注册表里
 *       查找原神安装路径（专用服务端跳过这一步）。</li>
 *   <li>游戏启动完成后（{@link ClientStartupWatcher}）：for 循环遍历配置文件里的
 *       {@code crash_mod} 列表，检测这些模组是否被加载。</li>
 *   <li>进服务器时（{@link ClientNetworkHook} + {@link ATGNetwork}）：客户端把模组列表发给服务端，
 *       服务端拿自己的 {@code crash_mod} 再验一轮，把命中的模组回给客户端。</li>
 *   <li>任意一轮命中 -&gt; 报错 "Never gonna give you up..."（游戏不关闭）-&gt; 放歌 + 启动原神。</li>
 * </ol>
 */
@Mod(allthegenshin.MOD_ID)
public class allthegenshin {

    public static final String MOD_ID = "allthegenshin";
    private static final Logger LOGGER = LogUtils.getLogger();

    public allthegenshin(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // 配置文件：config/allthegenshin-common.toml
        context.registerConfig(ModConfig.Type.COMMON, ATGConfig.SPEC, "allthegenshin-common.toml");

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::loadComplete);
    }

    /** 游戏启动阶段：注册联机验证包；客户端顺便找一下原神在哪。 */
    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ATGNetwork::register);
        if (FMLEnvironment.dist.isDedicatedServer()) {
            // 专用服务端只负责"有人进服时验一轮"，不用找原神，也不会去启动什么
            LOGGER.info("[All the Genshin] 专用服务端：跳过原神路径查找，只做联机验证");
            return;
        }
        GenshinTakeover.lookupGenshinDuringStartup();
    }

    /** 模组加载完成。 */
    private void loadComplete(final FMLLoadCompleteEvent event) {
        LOGGER.info("[All the Genshin] 模组加载完成，crash_mod 检测列表: {}", ATGConfig.crashMods());
    }
}
