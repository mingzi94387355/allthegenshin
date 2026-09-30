package com.chapple.allthegenshin;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * All the Genshin（纯客户端模组）。
 *
 * <p>mods.toml 里设了 {@code clientSideOnly=true}，所以专用服务端根本不加载它。</p>
 *
 * <p>启动流程：</p>
 * <ol>
 *   <li>游戏启动阶段（{@link FMLCommonSetupEvent}）：Windows 环境去注册表里查找原神安装路径。</li>
 *   <li>游戏启动完成后（{@link ClientStartupWatcher}）：for 循环遍历配置文件里的
 *       {@code crash_mod} 列表，检测这些模组是否被加载。</li>
 *   <li>命中 -> 报错 "Never gonna give you up..."（游戏不关闭）-> 启动原神。</li>
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

    /** 游戏启动阶段：是 Windows 环境就在注册表里查找原神路径。 */
    private void commonSetup(final FMLCommonSetupEvent event) {
        GenshinTakeover.lookupGenshinDuringStartup();
    }

    /** 模组加载完成。 */
    private void loadComplete(final FMLLoadCompleteEvent event) {
        LOGGER.info("[All the Genshin] 模组加载完成，crash_mod 检测列表: {}", ATGConfig.crashMods());
    }
}
