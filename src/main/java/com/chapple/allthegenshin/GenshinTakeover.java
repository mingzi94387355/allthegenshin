package com.chapple.allthegenshin;

import com.mojang.logging.LogUtils;
import net.minecraft.CrashReport;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 核心流程：
 *
 * <ol>
 *   <li><b>游戏启动阶段</b>：Windows 环境下在注册表里查找原神路径并缓存（{@link #lookupGenshinDuringStartup()}）。</li>
 *   <li><b>游戏启动完成后</b>：用 for 循环遍历配置文件里的 {@code crash_mod} 列表，逐个检查是否被加载。</li>
 *   <li>命中任意一个 -> 游戏崩溃，然后按平台/原神是否存在决定打开哪个东西。</li>
 * </ol>
 */
public final class GenshinTakeover {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 保证整套流程只执行一次。 */
    private static final AtomicBoolean TRIGGERED = new AtomicBoolean(false);
    /** 启动阶段是否已经查过注册表。 */
    private static final AtomicBoolean PRELOOKUP_DONE = new AtomicBoolean(false);

    private static volatile Path genshinExe;

    private GenshinTakeover() {
    }

    // ------------------------------------------------------------------
    // 第一步：启动阶段查注册表
    // ------------------------------------------------------------------

    /** 在游戏启动阶段调用：Windows 环境就去注册表里查找原神路径。 */
    public static void lookupGenshinDuringStartup() {
        if (!GenshinLocator.isWindows()) {
            LOGGER.info("[All the Genshin] 当前不是 Windows 环境，跳过注册表查找");
            return;
        }
        if (!PRELOOKUP_DONE.compareAndSet(false, true)) {
            return;
        }
        try {
            Optional<Path> found = GenshinLocator.findGenshin();
            genshinExe = found.orElse(null);
            if (found.isPresent()) {
                LOGGER.info("[All the Genshin] 启动阶段注册表查找完成: {}", found.get());
            } else {
                LOGGER.info("[All the Genshin] 启动阶段注册表查找完成: 未找到原神");
            }
        } catch (Throwable t) {
            LOGGER.error("[All the Genshin] 注册表查找出错，稍后会再试一次", t);
            PRELOOKUP_DONE.set(false);
        }
    }

    /** 已缓存的原神路径（没找到过就再查一次）。 */
    public static Optional<Path> genshin() {
        Path cached = genshinExe;
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            Optional<Path> found = GenshinLocator.findGenshin();
            genshinExe = found.orElse(null);
            return found;
        } catch (Throwable t) {
            LOGGER.error("[All the Genshin] 查找原神路径失败", t);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // 第二步：启动完成后检测 crash_mod
    // ------------------------------------------------------------------

    /** 游戏启动完成后调用。检测到 crash_mod 列表里的模组就崩溃 + 原神，启动！ */
    public static void checkAndCrash() {
        if (!ATGConfig.enabled()) {
            LOGGER.info("[All the Genshin] 模组已被配置禁用（enable = false）");
            return;
        }
        if (!TRIGGERED.compareAndSet(false, true)) {
            return;
        }

        final List<String> crashMods = ATGConfig.crashMods();
        final List<String> hits = new ArrayList<>();

        // 就是这里：for 循环遍历配置文件里的 crash_mod 列表
        for (String modId : crashMods) {
            if (ModList.get().isLoaded(modId)) {
                hits.add(modId);
            }
        }

        if (hits.isEmpty()) {
            LOGGER.info("[All the Genshin] 检测完毕，crash_mod 列表 {} 一个都没加载，这次放过你", crashMods);
            return;
        }

        LOGGER.error("================================================");
        LOGGER.error("[All the Genshin] 检测到 crash_mod 列表中的模组: {}", hits);
        LOGGER.error("[All the Genshin] 游戏即将崩溃，然后……原神，启动！");
        LOGGER.error("================================================");

        // Minecraft.crash() 会直接 System.exit，之后的代码不会再执行，
        // 所以“崩溃之后要做的事”必须在触发崩溃之前做完。
        if (ATGConfig.reactEnabled()) {
            try {
                react();
            } catch (Throwable t) {
                LOGGER.error("[All the Genshin] 打开原神/浏览器时出错", t);
            }
        } else {
            LOGGER.info("[All the Genshin] react_enable = false，只崩溃，不做任何动作");
        }

        crashTheGame(hits);
    }

    /** 崩溃之后该打开什么。 */
    private static void react() {
        if (!GenshinLocator.isWindows()) {
            // 不是 Windows：云原神，走你
            LOGGER.info("[All the Genshin] 非 Windows 环境 -> 打开云原神");
            GenshinActions.openBrowser(GenshinLocator.CLOUD_URL);
            return;
        }

        Optional<Path> exe = genshin();
        if (exe.isPresent()) {
            GenshinActions.launchGenshin(exe.get());
        } else {
            LOGGER.info("[All the Genshin] 没找到原神安装路径 -> 打开原神下载页");
            GenshinActions.openBrowser(GenshinLocator.DOWNLOAD_URL);
        }
    }

    /** 真正把游戏搞崩（会写出 crash-reports 里的崩溃报告）。 */
    private static void crashTheGame(List<String> hits) {
        String message = "[All the Genshin] 检测到 crash_mod 列表中的模组：" + String.join(", ", hits)
                + "\n原神，启动！";
        CrashReport report = CrashReport.forThrowable(new GenshinCrashException(message), "All the Genshin");

        if (FMLEnvironment.dist.isClient()) {
            // 客户端：走原版的崩溃流程（写 crash-reports 并退出游戏）
            ClientCrasher.crash(report);
        }
        // 兜底：不管上面那步做了什么，这里再抛一次，保证游戏一定会崩
        throw new GenshinCrashException(message);
    }

    /** 崩溃用的异常，方便在崩溃报告里一眼认出来。 */
    public static class GenshinCrashException extends RuntimeException {
        public GenshinCrashException(String message) {
            super(message);
        }
    }
}
