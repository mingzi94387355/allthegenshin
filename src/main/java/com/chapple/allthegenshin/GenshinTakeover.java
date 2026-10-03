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
 *   <li>命中任意一个 -&gt; 抛出报错 "Never gonna give you up..."，把报错显示在屏幕上（<b>游戏不关闭</b>），
 *       然后在后台启动原神 / 打开下载页 / 打开云原神。</li>
 * </ol>
 *
 * <p>注意：这里刻意<b>不用</b> {@code Minecraft.crash()}，也不把异常裸抛给原版 ——
 * 那两条路最后都会 {@code System.exit} 把游戏关掉，原神还没来得及启动，窗口就先没了。
 * allcrash 那种"直接抛异常"在 1.20.1 里也是被 {@code Minecraft.run()} 接住后走 {@code crash()} 退出，
 * 所以想要"不关游戏"，只能自己写报告、自己显示报错界面。</p>
 */
public final class GenshinTakeover {

    /** 崩溃报错台词（参考 allcrash-forge）。 */
    public static final String CRASH_MESSAGE = "Never gonna give you up...";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 本地检测只跑一次。 */
    private static final AtomicBoolean LOCAL_CHECKED = new AtomicBoolean(false);
    /** 报错流程只跑一次（本地检测和服务端验证共用）。 */
    private static final AtomicBoolean FIRED = new AtomicBoolean(false);
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
    // 第二步：检测 crash_mod（本地一次 + 服务端验证一次）
    // ------------------------------------------------------------------

    /** 游戏启动完成后调用。检测到 crash_mod 列表里的模组就报错 + 原神，启动！ */
    public static void checkAndCrash() {
        if (!ATGConfig.enabled()) {
            LOGGER.info("[All the Genshin] 模组已被配置禁用（enable = false）");
            return;
        }
        if (!LOCAL_CHECKED.compareAndSet(false, true)) {
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
            LOGGER.info("[All the Genshin] 本地检测完毕，crash_mod 列表 {} 一个都没加载，这次放过你", crashMods);
            return;
        }

        fire("本地检测", hits);
    }

    /**
     * 服务端验证回来的结果（进服务器时服务端拿它自己的 crash_mod 配置比对）。
     * 命中的模组走和本地检测一模一样的报错流程。
     */
    public static void handleServerValidation(List<String> hits) {
        if (FMLEnvironment.dist.isDedicatedServer() || !ATGConfig.enabled()) {
            return;
        }
        if (hits == null || hits.isEmpty()) {
            LOGGER.info("[All the Genshin] 服务端验证通过");
            return;
        }
        LOGGER.error("[All the Genshin] 服务端验证不通过，本机装了: {}", hits);
        fire("服务端验证", hits);
    }

    /** 真正报错那一下：本地检测和服务端验证都走这里，保证只崩一次。 */
    private static void fire(String source, List<String> hits) {
        if (FMLEnvironment.dist.isDedicatedServer()) {
            // 服务端只负责验证，永远不触发报错
            LOGGER.info("[All the Genshin] 专用服务端不报错，只做验证");
            return;
        }
        if (!FIRED.compareAndSet(false, true)) {
            return;
        }

        LOGGER.error("================================================");
        LOGGER.error("[All the Genshin] {} 发现 crash_mod 列表中的模组: {}", source, hits);
        LOGGER.error("[All the Genshin] {}", CRASH_MESSAGE);
        LOGGER.error("================================================");

        // 抛出报错：Never gonna give you up...
        RuntimeException error = new GenshinCrashException(
                CRASH_MESSAGE + " （" + source + "发现 crash_mod 列表中的模组：" + String.join(", ", hits) + "）");
        CrashReport report = CrashReport.forThrowable(error, "All the Genshin");

        // 报错显示在屏幕上，游戏保持运行（这里刻意不调用 Minecraft.crash，那会直接关掉游戏）
        ClientCrashPresenter.present(report, hits, source);
        // 然后：先放首歌，再原神，启动！丢到后台线程，免得卡住界面
        if (ATGConfig.reactEnabled()) {
            runAsync("All the Genshin - react", GenshinTakeover::react);
        } else {
            LOGGER.info("[All the Genshin] react_enable = false，只报错，不做任何动作");
        }
    }

    /**
     * 报错之后一起做的事：先用浏览器放首歌（Never gonna give you up），
     * 再启动原神 / 打开下载页 / 打开云原神。
     */
    public static void react() {
        GenshinActions.openBrowser(GenshinLocator.RICKROLL_URL);
        launchGenshinNow();
    }

    /**
     * 该打开什么：非 Windows 打开云原神；只找到米哈游启动器就带原神参数启动它；
     * 找到游戏本体就启动本体；什么都没找到就打开官方下载页。
     */
    public static void launchGenshinNow() {
        try {
            if (!GenshinLocator.isWindows()) {
                LOGGER.info("[All the Genshin] 非 Windows 环境 -> 打开云原神");
                GenshinActions.openBrowser(GenshinLocator.CLOUD_URL);
                return;
            }

            Optional<Path> found = genshin();
            if (found.isEmpty()) {
                LOGGER.info("[All the Genshin] 没找到原神安装路径 -> 打开原神下载页");
                GenshinActions.openBrowser(GenshinLocator.DOWNLOAD_URL);
                return;
            }

            Path path = found.get();
            if (GenshinLocator.isLauncher(path)) {
                // 只有启动器：带上原神的游戏参数启动它（桌面快捷方式就是 launcher.exe --game=hk4e_cn）
                GenshinActions.launchLauncher(path);
            } else {
                GenshinActions.launchGenshin(path);
            }
        } catch (Throwable t) {
            LOGGER.error("[All the Genshin] 打开原神/浏览器时出错", t);
        }
    }

    /** 丢到后台线程去跑，别卡住游戏主线程。 */
    public static void runAsync(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** 崩溃用的异常，方便在崩溃报告里一眼认出来。 */
    public static class GenshinCrashException extends RuntimeException {
        public GenshinCrashException(String message) {
            super(message);
        }
    }
}
