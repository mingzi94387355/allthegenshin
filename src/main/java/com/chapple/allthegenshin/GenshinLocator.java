package com.chapple.allthegenshin;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在游戏启动阶段负责“找到原神”。
 *
 * <p>Windows 下通过 {@code reg query} 读注册表：miHoYo / HYP 启动器的游戏键，以及
 * “程序和功能”的卸载键。把所有看起来像路径的字符串收集起来，再从中解析出原神可执行文件。
 * 注册表里什么都找不到时，退回扫描常见安装位置。</p>
 *
 * <p>注意：原神自己的配置键（{@code HKCU\Software\miHoYo\原神}）里塞了几百 KB 的十六进制大字段，
 * 对它执行 {@code reg query /s} 会卡十几秒，所以这里对大键只按“值名”逐个取值，
 * 并且给每次查询都加了超时和大小上限。</p>
 */
public final class GenshinLocator {

    /** 非 Windows 环境用浏览器打开的云原神。 */
    public static final String CLOUD_URL = "https://ys.mihoyo.com/cloud/?utm_source=default#/";
    /** Windows 但没装原神时打开的原神下载页。 */
    public static final String DOWNLOAD_URL =
            "https://ys-api.mihoyo.com/event/download_porter/link/ys_cn/official/pc_backup322";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单次 reg query 的超时时间（管道方式）：超过就认为这个环境下管道读不出来，改用临时文件。 */
    private static final long QUERY_TIMEOUT_MS = 1000L;
    /** 改用临时文件方式时的超时时间。 */
    private static final long FILE_TIMEOUT_MS = 3000L;
    /** 整个注册表扫描的总预算，超了就放弃、改用文件夹扫描。 */
    private static final long SCAN_BUDGET_MS = 8000L;
    /** 单次 reg query 的输出上限：防止把几百 KB 的配置字段整个读进来。 */
    private static final int MAX_OUTPUT_BYTES = 512 * 1024;
    /** 每一层最多看多少个子键。 */
    private static final int MAX_SUB_KEYS = 8;

    /** 本次扫描的截止时间（0 表示没在扫描）。 */
    private static volatile long scanDeadline;
    /** 管道方式一旦超时过，后面就直接走临时文件方式，不再浪费时间去等。 */
    private static volatile boolean preferFileMode;

    /** 第一层：米哈游启动器自己的键（现在最主流的安装方式）。 */
    private static final String[] LAUNCHER_KEYS = {
            "HKCU\\Software\\miHoYo\\HYP",
    };

    /** 第二层：卸载信息 / 机器级安装信息，这些键里都是小字段，可以整份列出来。 */
    private static final String[] LIST_KEYS = {
            "HKLM\\SOFTWARE\\miHoYo",
            "HKLM\\SOFTWARE\\WOW6432Node\\miHoYo",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\原神",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Genshin Impact",
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\原神",
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Genshin Impact",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\原神",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Genshin Impact",
    };

    /** 固定要探测的键（里面可能有几十万个字符的大字段，只能按值名单独取）。 */
    private static final String[] PROBE_KEYS = {
            "HKCU\\Software\\miHoYo\\原神",
            "HKCU\\Software\\miHoYo\\Genshin Impact",
            "HKCU\\Software\\Cognosphere\\原神",
            "HKCU\\Software\\Cognosphere\\Genshin Impact",
    };

    /** 会在这些根键下面找和原神有关的子键名，再按值名探测。 */
    private static final String[] PROBE_ROOTS = {
            "HKCU\\Software\\miHoYo",
            "HKLM\\SOFTWARE\\miHoYo",
            "HKLM\\SOFTWARE\\WOW6432Node\\miHoYo",
    };

    /** 探测的值名，基本覆盖了各家安装器会写的字段。 */
    private static final String[] PROBE_VALUE_NAMES = {
            "GameInstallPath",
            "InstallPath",
            "InstallLocation",
            "GamePath",
            "UninstallString",
            "DisplayIcon",
    };

    /** 游戏本体可执行文件（国服 / 国际服）。 */
    private static final String[] GAME_EXE_NAMES = {"YuanShen.exe", "GenshinImpact.exe"};
    /** 启动器可执行文件。 */
    private static final String[] LAUNCHER_EXE_NAMES = {"launcher.exe", "Genshin Impact Launcher.exe"};
    /** 安装目录下可能存放游戏本体的子目录。 */
    private static final String[] GAME_SUB_DIRS = {
            "Genshin Impact Game", "Genshin Impact game", "GenshinImpact Game", "原神",
            // 米哈游启动器的目录结构：<启动器目录>\games\Genshin Impact Game\
            "games\\Genshin Impact Game", "games\\Genshin Impact",
    };

    /** reg query 输出形如：{@code     InstallPath    REG_SZ    D:\Genshin Impact} */
    private static final Pattern REG_LINE =
            Pattern.compile("^\\s+(.+?)\\s{2,}(REG_[A-Z_]+)\\s{2,}(.*)$");

    private GenshinLocator() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 找到原神可执行文件。
     *
     * @return 可执行文件路径；找不到时为空
     */
    public static Optional<Path> findGenshin() {
        boolean preferLauncher = ATGConfig.preferLauncher();

        String override = ATGConfig.genshinPath();
        if (!override.isEmpty()) {
            Optional<Path> fromConfig = resolve(override, preferLauncher);
            if (fromConfig.isPresent()) {
                LOGGER.info("[All the Genshin] 使用配置文件中的原神路径: {}", fromConfig.get());
                return fromConfig;
            }
            LOGGER.warn("[All the Genshin] 配置项 genshin_path 无效: {}", override);
        }

        if (!isWindows()) {
            LOGGER.info("[All the Genshin] 当前不是 Windows 环境，跳过注册表查找");
            return Optional.empty();
        }

        long start = System.currentTimeMillis();
        Optional<Path> fromRegistry = registryPick(preferLauncher);
        LOGGER.info("[All the Genshin] 注册表查找完成，耗时 {} ms", System.currentTimeMillis() - start);
        if (fromRegistry.isPresent()) {
            LOGGER.info("[All the Genshin] 在注册表里找到原神: {}", fromRegistry.get());
            return fromRegistry;
        }

        LOGGER.info("[All the Genshin] 注册表里没找到可用的原神路径，改为扫描常见安装目录");
        Optional<Path> fromDisk = pick(commonCandidates(), preferLauncher);
        fromDisk.ifPresent(path -> LOGGER.info("[All the Genshin] 在常见安装目录里找到原神: {}", path));
        return fromDisk;
    }

    /**
     * 分三层扫注册表，哪一层先找到就立刻收工，保证常见的安装方式只需要几十毫秒。
     *
     * <ol>
     *   <li>米哈游启动器：{@code HKCU\Software\miHoYo\HYP\1_1\hk4e_cn} -> GameInstallPath</li>
     *   <li>卸载键 / 机器级键</li>
     *   <li>按值名探测可能有几百 KB 大字段的键</li>
     * </ol>
     */
    private static Optional<Path> registryPick(boolean preferLauncher) {
        scanDeadline = System.currentTimeMillis() + SCAN_BUDGET_MS;
        preferFileMode = false;
        Path game = null;
        Path launcher = null;
        for (int tier = 0; tier < 3; tier++) {
            if (System.currentTimeMillis() > scanDeadline) {
                LOGGER.warn("[All the Genshin] 注册表扫描超出总预算，跳过剩余查找");
                break;
            }
            List<String> values;
            if (tier == 0) {
                values = launcherCandidates();
            } else if (tier == 1) {
                values = listCandidates();
            } else {
                values = probeCandidates();
            }

            for (String value : values) {
                Optional<Path> resolved = resolve(value, preferLauncher);
                if (resolved.isEmpty()) {
                    continue;
                }
                Path path = resolved.get();
                if (isGameExe(fileName(path))) {
                    if (game == null) {
                        game = path;
                    }
                } else if (launcher == null) {
                    launcher = path;
                }
            }

            // 这一层已经拿到想要的东西了，不用再往下扫
            if (preferLauncher ? launcher != null : game != null) {
                break;
            }
        }
        if (preferLauncher) {
            return Optional.ofNullable(launcher != null ? launcher : game);
        }
        return Optional.ofNullable(game != null ? game : launcher);
    }

    /**
     * 在一堆候选字符串里挑一个最合适的可执行文件：
     * 优先游戏本体，实在没有才用启动器（受 {@code prefer_launcher} 影响）。
     */
    private static Optional<Path> pick(List<String> candidates, boolean preferLauncher) {
        Path game = null;
        Path launcher = null;
        for (String candidate : candidates) {
            Optional<Path> resolved = resolve(candidate, preferLauncher);
            if (resolved.isEmpty()) {
                continue;
            }
            Path path = resolved.get();
            if (isGameExe(fileName(path))) {
                if (game == null) {
                    game = path;
                }
            } else if (launcher == null) {
                launcher = path;
            }
            if (game != null && launcher != null) {
                break;
            }
        }
        if (preferLauncher && launcher != null) {
            return Optional.of(launcher);
        }
        if (game != null) {
            return Optional.of(game);
        }
        return Optional.ofNullable(launcher);
    }

    // ------------------------------------------------------------------
    // 注册表
    // ------------------------------------------------------------------

    /**
     * 第一层：启动器相关。整个 {@code HYP} 子树一次 {@code /s} 查完即可，
     * 里面既有启动器自己的 {@code InstallPath}，也有
     * {@code HYP\1_1\hk4e_cn} 这种“每个游戏一个键”的 {@code GameInstallPath}。
     * 这个键里只有小字段（不像原神自己的配置键），一次查询就够。
     */
    private static List<String> launcherCandidates() {
        Set<String> values = new LinkedHashSet<>();
        for (String key : LAUNCHER_KEYS) {
            for (String line : regQuery(key, "/s")) {
                collectFromLine(line, values);
            }
        }
        return new ArrayList<>(values);
    }

    /** 第二层：卸载键 / 机器级键，整份列值。 */
    private static List<String> listCandidates() {
        Set<String> values = new LinkedHashSet<>();
        for (String key : LIST_KEYS) {
            collectValues(key, values);
        }
        return new ArrayList<>(values);
    }

    /**
     * 第三层：其它命名（原神 / Genshin Impact / 以后可能改的名字）。
     * 这些键里可能塞了几百 KB 的配置字段，所以只按值名一个个取，绝不整份打印。
     */
    private static List<String> probeCandidates() {
        Set<String> values = new LinkedHashSet<>();
        Set<String> probeKeys = new LinkedHashSet<>(List.of(PROBE_KEYS));
        for (String root : PROBE_ROOTS) {
            for (String sub : subKeys(root)) {
                if (looksLikeGenshinKey(sub)) {
                    probeKeys.add(root + "\\" + sub);
                }
            }
        }
        for (String key : probeKeys) {
            for (String name : PROBE_VALUE_NAMES) {
                for (String line : regQuery(key, "/v", name)) {
                    collectFromLine(line, values);
                }
            }
        }
        return new ArrayList<>(values);
    }

    private static boolean looksLikeGenshinKey(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("genshin") || lower.contains("yuanshen") || lower.contains("hk4e")
                || name.contains("原神");
    }

    /** 把一个键里所有 REG_SZ / REG_EXPAND_SZ 值收进集合。 */
    private static void collectValues(String key, Set<String> values) {
        for (String line : regQuery(key)) {
            collectFromLine(line, values);
        }
    }

    private static void collectFromLine(String line, Set<String> values) {
        Matcher matcher = REG_LINE.matcher(line);
        if (!matcher.matches()) {
            return;
        }
        String type = matcher.group(2);
        if (!"REG_SZ".equals(type) && !"REG_EXPAND_SZ".equals(type) && !"REG_MULTI_SZ".equals(type)) {
            return;
        }
        String value = cleanValue(matcher.group(3));
        if (!value.isEmpty()) {
            values.add(value);
        }
    }

    /** 列出一个键下面的子键名（不递归）。 */
    private static List<String> subKeys(String key) {
        List<String> result = new ArrayList<>();
        String self = fullPath(key);
        for (String line : regQuery(key)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("HKEY_")) {
                continue;
            }
            if (trimmed.equalsIgnoreCase(self)) {
                // 键自己有值的时候，reg 会先打印一行键自己的路径
                continue;
            }
            int index = trimmed.lastIndexOf('\\');
            if (index <= 0 || index >= trimmed.length() - 1) {
                continue;
            }
            String name = trimmed.substring(index + 1).trim();
            if (!name.isEmpty() && !result.contains(name)) {
                result.add(name);
            }
            if (result.size() >= MAX_SUB_KEYS) {
                break;
            }
        }
        return result;
    }

    /** 把 {@code HKCU\...} 这种简写补成 reg 输出的完整路径，方便比较。 */
    private static String fullPath(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        if (upper.startsWith("HKCU\\")) {
            return "HKEY_CURRENT_USER" + key.substring(4);
        }
        if (upper.startsWith("HKLM\\")) {
            return "HKEY_LOCAL_MACHINE" + key.substring(4);
        }
        if (upper.startsWith("HKCR\\")) {
            return "HKEY_CLASSES_ROOT" + key.substring(4);
        }
        if (upper.startsWith("HKU\\")) {
            return "HKEY_USERS" + key.substring(3);
        }
        if (upper.startsWith("HKCC\\")) {
            return "HKEY_CURRENT_CONFIG" + key.substring(4);
        }
        return key;
    }

    /**
     * 执行 {@code reg query}，带超时与输出上限。
     *
     * <p>先用管道读输出；个别环境（沙箱 / 安全软件）下子进程的输出管道读不出来，
     * 这时改用 {@code cmd /c "reg query ... > 临时文件"} 的方式再试一次。</p>
     *
     * @return 输出行；键不存在、超时、输出过大都返回空列表
     */
    private static List<String> regQuery(String key, String... extraArgs) {
        if (scanDeadline != 0 && System.currentTimeMillis() > scanDeadline) {
            return List.of();
        }
        if (!preferFileMode) {
            List<String> lines = regQueryViaPipe(key, extraArgs);
            if (lines != null) {
                return lines;
            }
            // 这个环境下管道读不出子进程输出，之后一律走临时文件方式
            preferFileMode = true;
        }
        List<String> viaFile = regQueryViaFile(key, extraArgs);
        LOGGER.debug("[All the Genshin] {} 用临时文件方式读取，拿到 {} 行", key, viaFile.size());
        return viaFile;
    }

    /** 管道方式；超时/输出过大返回 null，让调用方换一种方式。 */
    private static List<String> regQueryViaPipe(String key, String... extraArgs) {
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add("reg");
            command.add("query");
            command.add(key);
            command.addAll(List.of(extraArgs));

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();

            byte[] output = readBounded(process, key);
            process.waitFor(200, TimeUnit.MILLISECONDS);
            if (output == null) {
                return null;
            }
            return List.of(decode(output).split("\\R"));
        } catch (Throwable t) {
            LOGGER.debug("[All the Genshin] 注册表查询失败 {}: {}", key, t.toString());
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 临时文件方式：不碰子进程的输出管道，用 waitFor 等它结束再读文件。 */
    private static List<String> regQueryViaFile(String key, String... extraArgs) {
        Path temp = null;
        try {
            temp = Files.createTempFile("allthegenshin-reg-", ".txt");
            StringBuilder command = new StringBuilder();
            command.append("reg query \"").append(key).append('"');
            for (String arg : extraArgs) {
                command.append(' ').append(arg);
            }
            command.append(" > \"").append(temp.toAbsolutePath()).append("\" 2>&1");

            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", command.toString());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            if (!process.waitFor(FILE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                LOGGER.debug("[All the Genshin] 注册表查询 {} 超时（临时文件方式）", key);
                return List.of();
            }
            byte[] bytes = Files.readAllBytes(temp);
            if (bytes.length > MAX_OUTPUT_BYTES) {
                LOGGER.debug("[All the Genshin] 注册表键 {} 输出过大，跳过", key);
                return List.of();
            }
            return List.of(decode(bytes).split("\\R"));
        } catch (Throwable t) {
            LOGGER.debug("[All the Genshin] 注册表查询失败 {}: {}", key, t.toString());
            return List.of();
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // 删不掉就留给系统清理
                }
            }
        }
    }

    /**
     * 读取子进程输出，超过 {@link #MAX_OUTPUT_BYTES} 或 {@link #QUERY_TIMEOUT_MS} 就放弃。
     *
     * @return 输出内容；放弃时返回 null
     */
    private static byte[] readBounded(Process process, String key) throws Exception {
        InputStream in = process.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long deadline = System.currentTimeMillis() + QUERY_TIMEOUT_MS;

        while (true) {
            if (in.available() > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, in.available()));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                if (out.size() > MAX_OUTPUT_BYTES) {
                    return null;
                }
                continue;
            }
            if (!process.isAlive()) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_OUTPUT_BYTES) {
                        return null;
                    }
                }
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                LOGGER.debug("[All the Genshin] 管道方式读 {} 超时（已读 {} 字节，进程仍在运行={}）",
                        key, out.size(), process.isAlive());
                return null;
            }
            Thread.sleep(1L);
        }
        return out.toByteArray();
    }

    /**
     * reg.exe 是按控制台代码页（简体中文 Windows 上是 GBK）输出文本的，
     * 而 Java 17 的默认字符集是 ANSI 代码页、Java 18+ 又变成了 UTF-8，
     * 所以这里挑一个“解出来没有乱码问号”的字符集来解码。
     */
    private static String decode(byte[] bytes) {
        String[] candidates = {Charset.defaultCharset().name(), "GB18030", "GBK", "UTF-8"};
        for (String name : candidates) {
            try {
                String decoded = new String(bytes, Charset.forName(name));
                if (decoded.indexOf('\uFFFD') < 0) {
                    return decoded;
                }
            } catch (Throwable ignored) {
                // 这个字符集不可用，换下一个
            }
        }
        return new String(bytes, Charset.defaultCharset());
    }

    /** 把注册表里的原始值洗干净，只留下可能是安装路径的部分。 */
    private static String cleanValue(String raw) {
        String value = expandEnv(raw.trim());
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            if (end > 1) {
                value = value.substring(1, end);
            }
        } else {
            int exe = value.toLowerCase(Locale.ROOT).indexOf(".exe");
            if (exe >= 0) {
                value = value.substring(0, exe + 4);
            }
        }
        // 去掉 DisplayIcon 那种 ",0" 后缀
        int comma = value.lastIndexOf(',');
        if (comma > 2 && value.substring(comma + 1).trim().matches("\\d+")) {
            value = value.substring(0, comma);
        }
        value = value.trim();
        if (value.isEmpty() || value.startsWith("http") || value.startsWith("{") || value.startsWith("[")) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        boolean looksLikePath = value.contains(":\\") || value.contains("\\\\");
        boolean mentionsGenshin = lower.contains("genshin") || lower.contains("yuanshen")
                || lower.contains("launcher") || value.contains("原神");
        return looksLikePath || mentionsGenshin ? value : "";
    }

    /** 展开 {@code %LOCALAPPDATA%} 这类环境变量。 */
    private static String expandEnv(String value) {
        if (!value.contains("%")) {
            return value;
        }
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            int start = value.indexOf('%', index);
            if (start < 0) {
                out.append(value, index, value.length());
                break;
            }
            int end = value.indexOf('%', start + 1);
            if (end < 0) {
                out.append(value, index, value.length());
                break;
            }
            out.append(value, index, start);
            String name = value.substring(start + 1, end);
            String env = System.getenv(name);
            out.append(env != null ? env : value.substring(start, end + 1));
            index = end + 1;
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 路径解析
    // ------------------------------------------------------------------

    /** 常见安装位置（注册表翻车时的兜底）。 */
    private static List<String> commonCandidates() {
        List<String> roots = new ArrayList<>();
        for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "ProgramData",
                "LOCALAPPDATA", "USERPROFILE", "SystemDrive"}) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) {
                roots.add(value);
            }
        }
        for (File root : File.listRoots()) {
            roots.add(root.getPath());
        }

        String[] folders = {
                "Genshin Impact", "原神", "GenshinImpact",
                "Games\\Genshin Impact", "Games\\原神",
                "miHoYo\\Genshin Impact", "miHoYo\\原神",
                "miHoYo Launcher\\games\\Genshin Impact Game",
                "miHoYo Launcher\\games\\Genshin Impact",
                // 常见情况：E:\Program Files\miHoYo Launcher\games\Genshin Impact Game
                "Program Files\\miHoYo Launcher\\games\\Genshin Impact Game",
                "Program Files\\miHoYo Launcher\\games\\Genshin Impact",
                "Program Files\\Genshin Impact", "Program Files\\原神",
                "Program Files\\Genshin Impact\\Genshin Impact Game",
        };
        List<String> candidates = new ArrayList<>();
        for (String root : roots) {
            for (String folder : folders) {
                String base = root.endsWith("\\") || root.endsWith("/") ? root : root + File.separator;
                candidates.add(base + folder);
            }
        }
        return candidates;
    }

    /** 把一个“疑似原神路径”的字符串解析成可执行文件路径。 */
    public static Optional<Path> resolve(String raw, boolean preferLauncher) {
        Path path = toPath(raw);
        if (path == null) {
            return Optional.empty();
        }

        if (Files.isRegularFile(path)) {
            String name = fileName(path);
            if (isGameExe(name)) {
                return Optional.of(path);
            }
            if (isLauncherExe(name)) {
                if (preferLauncher) {
                    return Optional.of(path);
                }
                Path game = findInDirectory(path.getParent(), false);
                return Optional.of(game != null ? game : path);
            }
            // 不是可执行文件（比如 config.ini / 图标），退回到它所在的目录里找
            path = path.getParent();
        }

        if (path == null || !Files.isDirectory(path)) {
            return Optional.empty();
        }
        Path found = findInDirectory(path, preferLauncher);
        return found == null ? Optional.empty() : Optional.of(found);
    }

    private static Path findInDirectory(Path directory, boolean preferLauncher) {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        List<Path> directories = new ArrayList<>();
        directories.add(directory);
        for (String sub : GAME_SUB_DIRS) {
            Path child = directory.resolve(sub);
            if (Files.isDirectory(child)) {
                directories.add(child);
            }
        }

        List<String> order = new ArrayList<>();
        if (preferLauncher) {
            order.addAll(List.of(LAUNCHER_EXE_NAMES));
            order.addAll(List.of(GAME_EXE_NAMES));
        } else {
            order.addAll(List.of(GAME_EXE_NAMES));
            order.addAll(List.of(LAUNCHER_EXE_NAMES));
        }

        for (String name : order) {
            for (Path dir : directories) {
                Path exe = dir.resolve(name);
                if (Files.isRegularFile(exe)) {
                    return exe;
                }
            }
        }
        // 名字对不上的（比如自改名的），退一步在目录里扫一层
        for (Path dir : directories) {
            try (var stream = Files.list(dir)) {
                Optional<Path> any = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> isGameExe(fileName(p)) || isLauncherExe(fileName(p)))
                        .findFirst();
                if (any.isPresent()) {
                    return any.get();
                }
            } catch (Exception ignored) {
                // 目录读不了就算了
            }
        }
        return null;
    }

    private static boolean isGameExe(String lowerName) {
        for (String name : GAME_EXE_NAMES) {
            if (name.toLowerCase(Locale.ROOT).equals(lowerName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLauncherExe(String lowerName) {
        for (String name : LAUNCHER_EXE_NAMES) {
            if (name.toLowerCase(Locale.ROOT).equals(lowerName)) {
                return true;
            }
        }
        return lowerName.endsWith(".exe") && lowerName.contains("launcher");
    }

    private static String fileName(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static Path toPath(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replace("\"", " ").trim();
        // "C:\xx\yy.exe" /S 这种带参数的卸载命令，只取 .exe 那一段
        int exe = value.toLowerCase(Locale.ROOT).indexOf(".exe");
        if (exe > 0) {
            value = value.substring(0, exe + 4);
        }
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Paths.get(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
