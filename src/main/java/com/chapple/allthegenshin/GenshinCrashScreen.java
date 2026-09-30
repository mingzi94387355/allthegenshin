package com.chapple.allthegenshin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 崩溃报错界面。
 *
 * <p>和原版崩溃不一样：这里**不会关闭游戏**，只是把 "Never gonna give you up..."
 * 摆在屏幕上（完整崩溃报告照样写进 {@code crash-reports/} 和日志），
 * 然后原神在后台启动。</p>
 */
public final class GenshinCrashScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Component TITLE =
            Component.literal("Never gonna give you up...").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);

    private final List<String> hits;
    private MultiLineLabel body;

    public GenshinCrashScreen(List<String> hits) {
        super(TITLE);
        this.hits = new ArrayList<>(hits);
    }

    @Override
    protected void init() {
        LOGGER.info("[All the Genshin] 报错界面已显示：{}（游戏保持运行）", TITLE.getString());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Never gonna let you down,").withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("Never gonna run around and desert you...").withStyle(ChatFormatting.GOLD));
        lines.add(Component.empty());
        lines.add(Component.literal("检测到 crash_mod 列表中的模组：").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(String.join(", ", this.hits))
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        lines.add(Component.literal("游戏没有关闭 —— 因为原神更重要。").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("顺便用浏览器放了一首歌，请欣赏。").withStyle(ChatFormatting.GRAY));
        lines.add(Component.empty());
        lines.add(Component.literal("完整崩溃报告已写入 crash-reports/，也打印在 logs/latest.log 里。")
                .withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.empty());
        lines.add(Component.literal("原神，启动！").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        MutableComponent text = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                text.append(Component.literal("\n"));
            }
            text.append(lines.get(i));
        }
        this.body = MultiLineLabel.create(this.font, text, Math.max(120, this.width - 80));

        int buttonWidth = 160;
        int y = this.height - 44;
        this.addRenderableWidget(Button.builder(Component.literal("原神，启动！"), button ->
                        GenshinTakeover.runAsync("All the Genshin - launch", GenshinTakeover::launchGenshinNow))
                .bounds(this.width / 2 - buttonWidth - 5, y, buttonWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("退出游戏"), button -> {
                    if (this.minecraft != null) {
                        this.minecraft.stop();
                    }
                })
                .bounds(this.width / 2 + 5, y, buttonWidth, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, TITLE, this.width / 2, 34, 0xFF5555);
        if (this.body != null) {
            this.body.renderCentered(graphics, this.width / 2, 64);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 别让玩家按 Esc 就把报错关掉。 */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
