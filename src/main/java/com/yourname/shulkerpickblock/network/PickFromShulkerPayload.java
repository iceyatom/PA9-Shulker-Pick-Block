package com.yourname.shulkerpickblock.network;

import com.yourname.shulkerpickblock.ShulkerPickBlock;
import com.yourname.shulkerpickblock.config.SourceSelectionStrategy;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Client → server request: "pull {@code item} out of a shulker box in my inventory and put it in
 * hotbar slot {@code hotbarSlot}". Only sent when the server has this mod (the client checks with
 * {@code ClientPlayNetworking.canSend}); vanilla servers never see it.
 *
 * <p>The client's selection settings travel with the request so the server's re-scan picks exactly
 * the same box and internal slot as the client's prediction.
 *
 * <p>Before 1.20.5 there is no {@code CustomPacketPayload}/{@code StreamCodec}; Fabric API's
 * {@link FabricPacket} + {@link PacketType} are the equivalent. The wire format is the same as the
 * 1.20.5+ codec: item registry id (VarInt), hotbar slot (VarInt), scan-offhand flag, strategy ordinal.
 */
public record PickFromShulkerPayload(Item item, int hotbarSlot, boolean scanOffhand,
                                     SourceSelectionStrategy strategy) implements FabricPacket {

    public static final PacketType<PickFromShulkerPayload> TYPE = PacketType.create(
            new ResourceLocation(ShulkerPickBlock.MOD_ID, "pick_from_shulker"), PickFromShulkerPayload::read);

    private static PickFromShulkerPayload read(FriendlyByteBuf buf) {
        return new PickFromShulkerPayload(
                BuiltInRegistries.ITEM.byIdOrThrow(buf.readVarInt()),
                buf.readVarInt(),
                buf.readBoolean(),
                strategyFromOrdinal(buf.readVarInt()));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeId(BuiltInRegistries.ITEM, item);
        buf.writeVarInt(hotbarSlot);
        buf.writeBoolean(scanOffhand);
        buf.writeVarInt(strategy.ordinal());
    }

    private static SourceSelectionStrategy strategyFromOrdinal(int ordinal) {
        SourceSelectionStrategy[] values = SourceSelectionStrategy.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : SourceSelectionStrategy.DEFAULT;
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
