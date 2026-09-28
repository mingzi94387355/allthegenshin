package com.chapple.allthegenshin;

import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;

/**
 * 单独放一个类里，保证专用服务端（没有客户端类）不会因为加载 Minecraft 而炸掉。
 */
final class ClientCrasher {

    private ClientCrasher() {
    }

    static void crash(CrashReport report) {
        Minecraft.crash(report);
    }
}
