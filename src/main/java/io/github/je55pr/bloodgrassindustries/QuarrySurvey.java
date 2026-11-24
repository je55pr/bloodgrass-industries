package io.github.je55pr.bloodgrassindustries;

import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = BloodgrassIndustries.ID)
public final class QuarrySurvey {
    private static final String RETURN = "BloodgrassQuarrySurveyReturn";

    private QuarrySurvey() {}

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Request.TYPE, Request.CODEC, (request, context) -> {
            if (!(context.player() instanceof ServerPlayer player) || !(player.containerMenu instanceof QuarryMenu menu)
                    || menu.containerId != request.containerId()) {
                return;
            }
            boolean started = false;
            if (request.start() && !menu.surveying && player.isCreative() && menu.stillValid(player) && menu.quarry() != null) {
                started = start(player, menu);
            } else if (!request.start() && menu.surveying) {
                menu.surveying = !restore(player) && player.isSpectator();
            }
            PacketDistributor.sendToPlayer(player, new State(menu.containerId, started || menu.surveying));
        });
        registrar.playToClient(State.TYPE, State.CODEC, (message, context) -> Client.receive(message));
    }

    private static boolean start(ServerPlayer player, QuarryMenu menu) {
        if (player.getPersistentData().contains(RETURN) && !restore(player)) {
            return false;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", player.level().dimension().location().toString());
        tag.putDouble("X", player.getX());
        tag.putDouble("Y", player.getY());
        tag.putDouble("Z", player.getZ());
        tag.putFloat("Yaw", player.getYRot());
        tag.putFloat("Pitch", player.getXRot());
        tag.putBoolean("Flying", player.getAbilities().flying);
        player.getPersistentData().put(RETURN, tag);
        player.setGameMode(GameType.SPECTATOR);
        if (!player.isSpectator()) {
            restore(player);
            player.displayClientMessage(Component.literal("Spectator flight is unavailable."), true);
            return false;
        }
        menu.surveying = true;
        player.teleportTo(player.serverLevel(), player.getX(), player.getY() + 10, player.getZ(), Set.of(), player.getYRot(), 42);
        return true;
    }

    static boolean restore(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(RETURN)) {
            return true;
        }
        if (player.hasDisconnected()) {
            return false;
        }
        if (!player.isCreative()) {
            player.setGameMode(GameType.CREATIVE);
            if (!player.isCreative()) {
                player.displayClientMessage(Component.literal("Could not restore Creative mode. Your return point is saved."), true);
                return false;
            }
        }
        CompoundTag tag = data.getCompound(RETURN);
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        var level = dimension == null ? null : player.serverLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level != null) {
            player.teleportTo(level, tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"), Set.of(),
                    tag.getFloat("Yaw"), tag.getFloat("Pitch"));
        }
        player.getAbilities().flying = tag.getBoolean("Flying");
        player.onUpdateAbilities();
        data.remove(RETURN);
        return true;
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            restore(player);
        }
    }

    record Request(int containerId, boolean start) implements CustomPacketPayload {
        private static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BloodgrassIndustries.ID, "quarry_survey"));
        private static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.ofMember(
                (payload, buffer) -> { buffer.writeVarInt(payload.containerId()); buffer.writeBoolean(payload.start()); },
                buffer -> new Request(buffer.readVarInt(), buffer.readBoolean()));

        @Override
        public Type<Request> type() { return TYPE; }
    }

    record State(int containerId, boolean surveying) implements CustomPacketPayload {
        private static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BloodgrassIndustries.ID, "quarry_survey_state"));
        private static final StreamCodec<FriendlyByteBuf, State> CODEC = StreamCodec.ofMember(
                (payload, buffer) -> { buffer.writeVarInt(payload.containerId()); buffer.writeBoolean(payload.surveying()); },
                buffer -> new State(buffer.readVarInt(), buffer.readBoolean()));

        @Override
        public Type<State> type() { return TYPE; }
    }

    private static final class Client {
        private static void receive(State message) {
            QuarryFlyMode.receive(message);
        }
    }
}
