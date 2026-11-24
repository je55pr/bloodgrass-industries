package io.github.je55pr.bloodgrassindustries;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

record QuarryMaterialPayload(int containerId, QuarryPlan.Settings settings, String message, CompoundTag report) implements CustomPacketPayload {
    private static final Type<QuarryMaterialPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BloodgrassIndustries.ID, "quarry_materials"));
    private static final StreamCodec<FriendlyByteBuf, QuarryMaterialPayload> CODEC = StreamCodec.ofMember(
            (payload, buffer) -> {
                buffer.writeVarInt(payload.containerId());
                QuarryPayload.writeSettings(buffer, payload.settings());
                buffer.writeUtf(payload.message());
                buffer.writeNbt(payload.report());
            },
            buffer -> new QuarryMaterialPayload(buffer.readVarInt(), QuarryPayload.readSettings(buffer), buffer.readUtf(), buffer.readNbt()));

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, CODEC, (payload, context) -> Client.receive(payload));
    }

    @Override
    public Type<QuarryMaterialPayload> type() {
        return TYPE;
    }

    private static final class Client {
        private static void receive(QuarryMaterialPayload payload) {
            var player = Minecraft.getInstance().player;
            if (player != null && player.containerMenu instanceof QuarryMenu menu && menu.containerId == payload.containerId()) {
                menu.materialStatus = payload.message();
                menu.materialStatusSettings = payload.settings();
                if (payload.report() != null) {
                    menu.materialSettings = payload.settings();
                    menu.materialReport = QuarryMaterials.Report.load(payload.report());
                } else {
                    menu.forgetReport(payload.settings());
                }
            }
        }
    }
}
