package com.chapple.allthegenshin;

import com.mojang.logging.LogUtils;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 客户端专用：把报错显示出来，但**不关闭游戏**。
 *
 * <p>为什么不能用 {@code Minecraft.crash()}：它会写完崩溃报告就
 * {@code ServerLifecycleHooks.handleExit(-1)} 退出游戏；
 * 直接在 tick 里抛异常也一样 —— 原版 {@code Minecraft.run()} 会把它接住，
 * 然后照样走 {@code crash()} 退出。所以这里自己写报告、自己显示报错界面。</p>
 */
final class ClientCrashPresenter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 和原版 crash-reports 文件名一个格式。 */
    private static final DateTimeFormatter FILE_NAME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private ClientCrashPresenter() {
    }

    /** 落盘崩溃报告 + 打日志 + 把报错摆到屏幕上（游戏继续运行）。 */
    static void present(CrashReport report, List<String> hits, String source) {
        Minecraft minecraft = Minecraft.getInstance();
        saveReport(minecraft, report);
        LOGGER.error("[All the Genshin] 崩溃报告如下：\n{}", report.getFriendlyReport());

        GenshinCrashScreen screen = new GenshinCrashScreen(hits, source);
        if (minecraft.isSameThread()) {
            minecraft.setScreen(screen);
        } else {
            // 理论上（单人世界的集成服务端线程）会走到这里，交给主线程去显示
            minecraft.execute(() -> minecraft.setScreen(screen));
        }
    }

    private static void saveReport(Minecraft minecraft, CrashReport report) {
        try {
            File directory = new File(minecraft.gameDirectory, "crash-reports");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                LOGGER.warn("[All the Genshin] 无法创建 crash-reports 目录，跳过保存");
                return;
            }
            File file = new File(directory, "crash-" + LocalDateTime.now().format(FILE_NAME) + "-client.txt");
            if (report.saveToFile(file)) {
                LOGGER.error("[All the Genshin] 崩溃报告已保存到 {}", file.getAbsolutePath());
            }
        } catch (Throwable t) {
            LOGGER.warn("[All the Genshin] 保存崩溃报告失败", t);
        }
    }
}
