package com.chapple.allthegenshin;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
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

    /**
     * 启动原神本体 / 启动器。
     *
     * <p>注意：原神的可执行文件带 {@code requireAdministrator} 清单，用普通权限
     * {@code CreateProcess} 直接拉会失败（{@code error=740 请求的操作需要提升}）。
     * 所以直接启动失败时，改走 {@code cmd /c start}（内部走 ShellExecuteEx，
     * 权限不够会自动弹 UAC 提权窗口），再不行就退而求其次开启动器。</p>
     */
    public static boolean launchGenshin(Path exe) {
        Path absolute = exe.toAbsolutePath();
        File directory = absolute.getParent() != null ? absolute.getParent().toFile() : null;

        // 1) 直接启动：Minecraft 自己是管理员运行时最干脆
        try {
            ProcessBuilder builder = new ProcessBuilder(absolute.toString());
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
            LOGGER.info("[All the Genshin] 直接启动失败（{}），改用 shell 启动（会弹 UAC 提权窗口）", t.getMessage());
        }

        // 2) 交给 shell：start 走 ShellExecuteEx，能触发 UAC 提权
        if (spawn(List.of("cmd.exe", "/c", "start", "", absolute.toString()), directory)) {
            LOGGER.info("[All the Genshin] 原神，启动！（已通过 shell 拉起，请在 UAC 窗口点“是”）");
            return true;
        }

        // 3) 最后的兜底：启动器
        Path launcher = findLauncherNear(absolute);
        if (launcher != null && spawn(List.of(launcher.toAbsolutePath().toString()), launcher.getParent().toFile())) {
            LOGGER.info("[All the Genshin] 本体起不来，改为启动启动器 {}", launcher);
            return true;
        }

        LOGGER.error("[All the Genshin] 原神怎么都拉不起来，麻烦手动打开一下吧");
        return false;
    }

    /** 从游戏本体所在目录往上找启动器（米哈游启动器目录结构：<根>\launcher.exe + <根>\games\Genshin Impact Game\）。 */
    private static Path findLauncherNear(Path exe) {
        Path directory = exe.getParent();
        for (int level = 0; level < 3 && directory != null; level++) {
            for (String name : new String[]{"launcher.exe", "Genshin Impact Launcher.exe"}) {
                Path candidate = directory.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            directory = directory.getParent();
        }
        return null;
    }

    private static boolean spawn(List<String> command) {
        return spawn(command, null);
    }

    private static boolean spawn(List<String> command, File directory) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (directory != null && directory.isDirectory()) {
                builder.directory(directory);
            }
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
