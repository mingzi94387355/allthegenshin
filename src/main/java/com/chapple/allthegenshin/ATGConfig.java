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

        builder.comment("All the Genshin 配置。").push("allthegenshin");

        ENABLE = builder
                .comment("总开关，false = 什么都不做。")
                .define("enable", true);

        REACT_ENABLE = builder
                .comment("报错后是否启动原神 / 打开浏览器。")
                .define("react_enable", true);

        CRASH_MOD = builder
                .comment("要检测的模组 id 列表，命中任意一个就报错并启动原神。")
                .defineList("crash_mod", List.of(DEFAULT_CRASH_MOD), o -> o instanceof String);

        GENSHIN_PATH = builder
                .comment("手动指定原神路径（目录或 exe），留空 = 自动查找。")
                .define("genshin_path", "");

        PREFER_LAUNCHER = builder
                .comment("true = 优先启动启动器 launcher.exe。")
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
