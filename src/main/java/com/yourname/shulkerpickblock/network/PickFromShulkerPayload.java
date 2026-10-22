package com.yourname.shulkerpickblock.network;

import com.yourname.shulkerpickblock.ShulkerPickBlock;
import com.yourname.shulkerpickblock.config.SourceSelectionStrategy;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Client → server request: "pull {@code item} out of a shulker box in my inventory and put it in
 * hotbar slot {@code hotbarSlot}". Only sent when the server has this mod (the client checks with
 * {@code ClientPlayNetworking.canSend}); vanilla servers never see it.
 *
 * <p>The client's selection settings travel with the request so the server's re-scan picks exactly
 * the same box and internal slot as the client's prediction.
 */
public record PickFromShulkerPayload(Item item, int hotbarSlot, boolean scanOffhand,
                                     SourceSelectionStrategy strategy) implements CustomPacketPayload {

    public static final Type<PickFromShulkerPayload> TYPE = new Type<>(
            new ResourceLocation(ShulkerPickBlock.MOD_ID, "pick_from_shulker"));

    private static final StreamCodec<RegistryFriendlyByteBuf, Item> ITEM_CODEC =
            ByteBufCodecs.registry(Registries.ITEM);

    public static final StreamCodec<RegistryFriendlyByteBuf, PickFromShulkerPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                ITEM_CODEC.encode(buf, payload.item());
                buf.writeVarInt(payload.hotbarSlot());
                buf.writeBoolean(payload.scanOffhand());
                buf.writeVarInt(payload.strategy().ordinal());
            },
            buf -> new PickFromShulkerPayload(
                    ITEM_CODEC.decode(buf),
                    buf.readVarInt(),
                    buf.readBoolean(),
                    strategyFromOrdinal(buf.readVarInt())));

    private static SourceSelectionStrategy strategyFromOrdinal(int ordinal) {
        SourceSelectionStrategy[] values = SourceSelectionStrategy.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : SourceSelectionStrategy.DEFAULT;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
