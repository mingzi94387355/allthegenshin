package com.chapple.allthegenshin;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 联机时的“再验一轮”：
 *
 * <ol>
 *   <li>客户端进服务器以后，把自己加载的模组列表发给服务端；</li>
 *   <li>服务端拿<b>自己</b>配置里的 {@code crash_mod} 列表比对；</li>
 *   <li>把命中的模组回给客户端；</li>
 *   <li>客户端收到以后走和本地检测一模一样的报错流程（报错界面 + 放歌 + 启动原神）。</li>
 * </ol>
 *
 * <p>客户端发之前先问一下 {@link SimpleChannel#isRemotePresent}，服务端没装这个模组就直接跳过，
 * 免得发一个对面不认识的包。</p>
 */
public final class ATGNetwork {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(allthegenshin.MOD_ID, "main"),
            () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);

    private ATGNetwork() {
    }

    /** 注册两个包（commonSetup 里调用）。 */
    public static void register() {
        int index = 0;
        CHANNEL.registerMessage(index++, ModListMessage.class,
                ModListMessage::encode, ModListMessage::decode, ModListMessage::handle);
        CHANNEL.registerMessage(index++, ValidationMessage.class,
                ValidationMessage::encode, ValidationMessage::decode, ValidationMessage::handle);
    }

    /** 客户端 -&gt; 服务端：我这边加载了这些模组。 */
    public record ModListMessage(List<String> mods) {

        static void encode(ModListMessage message, FriendlyByteBuf buffer) {
            buffer.writeCollection(message.mods(), FriendlyByteBuf::writeUtf);
        }

        static ModListMessage decode(FriendlyByteBuf buffer) {
            return new ModListMessage(buffer.readList(FriendlyByteBuf::readUtf));
        }

        static void handle(ModListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) {
                    return;
                }
                List<String> hits = validate(message.mods());
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), new ValidationMessage(hits));
            });
            context.setPacketHandled(true);
        }
    }

    /** 服务端 -&gt; 客户端：验证结果。 */
    public record ValidationMessage(List<String> hits) {

        static void encode(ValidationMessage message, FriendlyByteBuf buffer) {
            buffer.writeCollection(message.hits(), FriendlyByteBuf::writeUtf);
        }

        static ValidationMessage decode(FriendlyByteBuf buffer) {
            return new ValidationMessage(buffer.readList(FriendlyByteBuf::readUtf));
        }

        static void handle(ValidationMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> GenshinTakeover.handleServerValidation(message.hits()));
            context.setPacketHandled(true);
        }
    }

    /** 服务端这边真正干活的地方：拿服务端配置里的 crash_mod 跟客户端列表比对。 */
    private static List<String> validate(List<String> clientMods) {
        if (!ATGConfig.enabled()) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        for (String modId : ATGConfig.crashMods()) {
            if (clientMods.contains(modId)) {
                hits.add(modId);
            }
        }
        if (hits.isEmpty()) {
            LOGGER.info("[All the Genshin] 联机验证通过：客户端发来 {} 个模组，没有 crash_mod",
                    clientMods.size());
        } else {
            LOGGER.warn("[All the Genshin] 联机验证不通过，客户端装了: {}", hits);
        }
        return hits;
    }
}
