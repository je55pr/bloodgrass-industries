package io.github.je55pr.bloodgrassindustries;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

record QuarryPayload(int containerId, Action action, QuarryPlan.Settings settings, QuarryMaterials.Finish finish) implements CustomPacketPayload {
    enum Action { SAVE, SURVEY, EXCAVATE }

    private static final Type<QuarryPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BloodgrassIndustries.ID, "quarry"));
    private static final StreamCodec<FriendlyByteBuf, QuarryPayload> CODEC = StreamCodec.ofMember(QuarryPayload::write, QuarryPayload::read);

    static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("4");
        registrar.playToServer(TYPE, CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof QuarryMenu menu
                    && menu.containerId == payload.containerId() && !menu.surveying && menu.stillValid(player)) {
                QuarryBlockEntity quarry = menu.quarry();
                if (quarry != null) {
                    quarry.apply(player, menu.containerId, payload.settings(), payload.finish(), payload.action());
                }
            }
        });
        QuarrySurvey.register(registrar);
        QuarryMaterialPayload.register(registrar);
    }

    static QuarryPlan.Settings readSettings(FriendlyByteBuf buffer) {
        int y = buffer.readVarInt();
        int width = buffer.readVarInt();
        int drop = buffer.readVarInt();
        int steps = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > QuarryPlan.MAX_CORNERS) {
            throw new DecoderException("Invalid quarry corner count");
        }
        List<QuarryPlan.Corner> corners = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            corners.add(new QuarryPlan.Corner(buffer.readVarInt(), buffer.readVarInt()));
        }
        return new QuarryPlan.Settings(y, width, drop, steps, corners);
    }

    static void writeSettings(FriendlyByteBuf buffer, QuarryPlan.Settings settings) {
        buffer.writeVarInt(settings.baseY());
        buffer.writeVarInt(settings.width());
        buffer.writeVarInt(settings.drop());
        buffer.writeVarInt(settings.steps());
        buffer.writeVarInt(settings.corners().size());
        for (QuarryPlan.Corner corner : settings.corners()) {
            buffer.writeVarInt(corner.x());
            buffer.writeVarInt(corner.z());
        }
    }

    private static QuarryPayload read(FriendlyByteBuf buffer) {
        int id = buffer.readVarInt();
        Action action = buffer.readEnum(Action.class);
        QuarryPlan.Settings settings = readSettings(buffer);
        var finish = buffer.readNbt();
        if (finish == null) {
            throw new DecoderException("Missing quarry finish data");
        }
        return new QuarryPayload(id, action, settings, QuarryMaterials.Finish.load(finish));
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeEnum(action);
        writeSettings(buffer, settings);
        buffer.writeNbt(finish.save());
    }

    @Override
    public Type<QuarryPayload> type() {
        return TYPE;
    }
}
