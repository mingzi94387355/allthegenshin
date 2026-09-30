package com.chapple.allthegenshin;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 报错之后的“反应”：打开浏览器 / 启动原神。
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
            return spawn(List.of("rundll32.exe", "url.dll,FileProtocolHandler", url), null);
        }
        if (os.contains("mac")) {
            return spawn(List.of("open", url), null);
        }
        return spawn(List.of("xdg-open", url), null);
    }

    // ------------------------------------------------------------------
    // 启动原神
    // ------------------------------------------------------------------

    /**
     * 启动原神本体。
     *
     * <p>原神的可执行文件带 {@code requireAdministrator} 清单，普通权限
     * {@code CreateProcess} 直接拉会失败（{@code error=740 请求的操作需要提升}）。
     * 所以直接启动失败时改走 {@code cmd /c start}（走 ShellExecuteEx，会弹 UAC 提权窗口），
     * 再不行就退而求其次开启动器。</p>
     */
    public static boolean launchGenshin(Path exe) {
        if (launchProcess(exe, List.of())) {
            return true;
        }
        if (launchViaShell(exe, List.of())) {
            return true;
        }
        Path launcher = findLauncherNear(exe);
        if (launcher != null) {
            LOGGER.info("[All the Genshin] 本体起不来，改为启动米哈游启动器 {}", launcher);
            return launchLauncher(launcher);
        }
        LOGGER.error("[All the Genshin] 原神怎么都拉不起来，麻烦手动打开一下吧");
        return false;
    }

    /**
     * 启动米哈游启动器，并带上原神的游戏参数。
     *
     * <p>参数是从桌面 / 开始菜单那个“原神”快捷方式里读出来的（本机是
     * {@code launcher.exe --game=hk4e_cn}），读不到就用注册表里的游戏名兜底。</p>
     */
    public static boolean launchLauncher(Path launcher) {
        List<String> arguments = GenshinLocator.findGenshinLaunchArguments()
                .map(GenshinActions::splitArguments)
                .orElseGet(List::of);
        LOGGER.info("[All the Genshin] 启动米哈游启动器: {} {}", launcher, arguments);
        if (launchProcess(launcher, arguments)) {
            return true;
        }
        return launchViaShell(launcher, arguments);
    }

    /** 直接 CreateProcess 拉起（会用 exe 自己的目录当工作目录）。 */
    private static boolean launchProcess(Path exe, List<String> arguments) {
        try {
            Path absolute = exe.toAbsolutePath();
            List<String> command = new ArrayList<>();
            command.add(absolute.toString());
            command.addAll(arguments);

            ProcessBuilder builder = new ProcessBuilder(command);
            File directory = absolute.getParent() != null ? absolute.getParent().toFile() : null;
            if (directory != null && directory.isDirectory()) {
                // 原神和启动器都要在自己目录下启动
                builder.directory(directory);
            }
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            LOGGER.info("[All the Genshin] 原神，启动！ {} {} (pid={})", absolute, arguments, process.pid());
            return true;
        } catch (Throwable t) {
            LOGGER.info("[All the Genshin] 直接启动 {} 失败（{}）", exe, t.getMessage());
            return false;
        }
    }

    /** 交给 shell：{@code start} 走 ShellExecuteEx，权限不够会自动弹 UAC 提权窗口。 */
    private static boolean launchViaShell(Path exe, List<String> arguments) {
        Path absolute = exe.toAbsolutePath();
        List<String> command = new ArrayList<>(List.of("cmd.exe", "/c", "start", ""));
        command.add(absolute.toString());
        command.addAll(arguments);
        File directory = absolute.getParent() != null ? absolute.getParent().toFile() : null;
        if (spawn(command, directory)) {
            LOGGER.info("[All the Genshin] 已通过 shell 拉起 {}，请在 UAC 窗口点“是”", absolute);
            return true;
        }
        return false;
    }

    /** 从游戏本体所在目录往上找启动器（米哈游启动器目录结构：{@code <根>\launcher.exe} + {@code <根>\games\...}）。 */
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

    /** 把 {@code --game=hk4e_cn -x "a b"} 这种命令行拆成参数表。 */
    private static List<String> splitArguments(String arguments) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char character : arguments.toCharArray()) {
            if (character == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && Character.isWhitespace(character)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(character);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
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
