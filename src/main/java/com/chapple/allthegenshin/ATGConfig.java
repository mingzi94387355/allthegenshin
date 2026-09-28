package com.chapple.allthegenshin;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * All the Genshin 的配置文件。
 *
 * <p>配置文件位置：{@code config/allthegenshin-common.toml}，首次启动时由 Forge 自动生成。</p>
 */
public final class ATGConfig {

    /** 默认要检测的模组 id（配置里没写 / 读不出来时用这个）。 */
    public static final String DEFAULT_CRASH_MOD = "examplemod";

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue ENABLE;
    private static final ForgeConfigSpec.BooleanValue REACT_ENABLE;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> CRASH_MOD;
    private static final ForgeConfigSpec.ConfigValue<String> GENSHIN_PATH;
    private static final ForgeConfigSpec.BooleanValue PREFER_LAUNCHER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment(
                "All the Genshin —— 在原神面前，一切模组冲突都要让路。",
                "启动流程：游戏启动阶段（Windows）在注册表里查找原神安装路径 ->",
                "游戏启动完成后遍历 crash_mod 列表检测这些模组是否被加载 ->",
                "命中则报错 \"Never gonna give you up...\"（游戏不关闭），然后启动原神 /",
                "打开下载页 / 打开云原神网页。"
        ).push("allthegenshin");

        ENABLE = builder
                .comment("总开关。设为 false 时本模组完全不做事。")
                .define("enable", true);

        REACT_ENABLE = builder
                .comment("报错之后是否执行“启动原神 / 打开浏览器”的动作。",
                        "设为 false 则只会报错，不会打开任何东西。")
                .define("react_enable", true);

        CRASH_MOD = builder
                .comment("要检测的模组 id 列表（对应 mods.toml 里的 modId，不是文件名）。",
                        "游戏启动完成后会逐个检查它们是否被加载，任意一个命中就会报错并启动原神。",
                        "参考 allcrash-forge 的用法，例如：crash_mod = [\"examplemod\", \"allcrash\"]")
                .defineList("crash_mod", List.of(DEFAULT_CRASH_MOD), o -> o instanceof String);

        GENSHIN_PATH = builder
                .comment("手动指定原神安装目录或可执行文件（YuanShen.exe / GenshinImpact.exe / launcher.exe）。",
                        "留空表示自动在注册表里查找。")
                .define("genshin_path", "");

        PREFER_LAUNCHER = builder
                .comment("true = 优先启动 launcher.exe（走启动器，可以检查更新）；",
                        "false = 优先直接启动游戏本体 YuanShen.exe / GenshinImpact.exe（可能弹 UAC 提权窗口）。")
                .define("prefer_launcher", false);

        builder.pop();

        SPEC = builder.build();
    }

    private ATGConfig() {
    }

    public static boolean enabled() {
        try {
            return ENABLE.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean reactEnabled() {
        try {
            return REACT_ENABLE.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 配置文件里的 crash_mod 列表（副本，已去掉空白项）。 */
    public static List<String> crashMods() {
        List<String> result = new ArrayList<>();
        try {
            for (String id : CRASH_MOD.get()) {
                if (id != null && !id.isBlank()) {
                    result.add(id.trim());
                }
            }
            if (result.isEmpty()) {
                result.add(DEFAULT_CRASH_MOD);
            }
        } catch (Throwable t) {
            result.add(DEFAULT_CRASH_MOD);
        }
        return result;
    }

    public static String genshinPath() {
        try {
            String value = GENSHIN_PATH.get();
            return value == null ? "" : value.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean preferLauncher() {
        try {
            return PREFER_LAUNCHER.get();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
