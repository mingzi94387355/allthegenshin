package com.chapple.allthegenshin;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 崩溃之后的“反应”：打开浏览器 / 启动原神。
 */
public final class GenshinActions {

    private static final Logger LOGGER = LogUtils.getLogger();

    private GenshinActions() {
    }

    /** 用系统默认浏览器打开一个网址。 */
    public static boolean openBrowser(String url) {
        LOGGER.info("[All the Genshin] 打开浏览器: {}", url);
        if (browseWithDesktop(url)) {
            return true;
        }
        if (browseWithCommand(url)) {
            return true;
        }
        LOGGER.error("[All the Genshin] 没能打开浏览器，请手动访问: {}", url);
        return false;
    }

    private static boolean browseWithDesktop(String url) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return false;
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return false;
            }
            desktop.browse(new URI(url));
            return true;
        } catch (Throwable t) {
            LOGGER.debug("[All the Genshin] Desktop.browse 不可用: {}", t.toString());
            return false;
        }
    }

    private static boolean browseWithCommand(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // rundll32 是 Windows 上最稳的“用默认程序打开这个东西”的方式
            return spawn(List.of("rundll32.exe", "url.dll,FileProtocolHandler", url));
        }
        if (os.contains("mac")) {
            return spawn(List.of("open", url));
        }
        return spawn(List.of("xdg-open", url));
    }

    /** 启动原神本体 / 启动器。 */
    public static boolean launchGenshin(Path exe) {
        try {
            Path absolute = exe.toAbsolutePath();
            ProcessBuilder builder = new ProcessBuilder(absolute.toString());
            File directory = absolute.getParent() != null ? absolute.getParent().toFile() : null;
            if (directory != null && directory.isDirectory()) {
                // 原神需要在自己目录下启动
                builder.directory(directory);
            }
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            LOGGER.info("[All the Genshin] 原神，启动！ {} (pid={})", absolute, process.pid());
            return true;
        } catch (Throwable t) {
            LOGGER.error("[All the Genshin] 启动原神失败: {}", t.toString());
            return false;
        }
    }

    private static boolean spawn(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.start();
            return true;
        } catch (Throwable t) {
            LOGGER.debug("[All the Genshin] 执行 {} 失败: {}", command, t.toString());
            return false;
        }
    }
}
