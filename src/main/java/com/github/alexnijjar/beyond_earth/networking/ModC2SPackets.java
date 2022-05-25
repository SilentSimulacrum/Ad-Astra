package com.github.alexnijjar.beyond_earth.networking;

import com.github.alexnijjar.beyond_earth.BeyondEarth;
import com.github.alexnijjar.beyond_earth.entities.vehicles.LanderEntity;
import com.github.alexnijjar.beyond_earth.entities.vehicles.RocketEntity;
import com.github.alexnijjar.beyond_earth.registry.ModRecipes;
import com.github.alexnijjar.beyond_earth.util.ModIdentifier;
import com.github.alexnijjar.beyond_earth.util.ModUtils;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity.RemovalReason;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;

public class ModC2SPackets {

    public static final Identifier TELEPORT_TO_PLANET_PACKET_ID = new ModIdentifier("teleport_to_planet_packet");
    public static final Identifier DELETE_SPACE_STATION_ITEMS_PACKET_ID = new ModIdentifier("delete_space_station_item_packet");
    public static final Identifier CREATE_SPACE_STATION_PACKET_ID = new ModIdentifier("create_space_station_packet");

    public static final Identifier LAUNCH_ROCKET_PACKET_ID = new ModIdentifier("launch_rocket_packet");

    public static final Identifier START_LANDER_BOOSTERS = new ModIdentifier("start_lander_boosters_packet");
    public static final Identifier END_LANDER_BOOSTERS = new ModIdentifier("end_lander_boosters_packet");

    public static void register() {

        // Send planets packet.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, minecraftServer) -> {
            try {
                sender.sendPacket(ModS2CPackets.DATAPACK_PLANETS_PACKET_ID, createPlanetsDatapackBuf());
            } catch (Exception e) {
                BeyondEarth.LOGGER.error("Failed to send datapack values to client: " + e);
                e.printStackTrace();
            }
        });

        // Teleport to a planet.
        ServerPlayNetworking.registerGlobalReceiver(TELEPORT_TO_PLANET_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            RegistryKey<World> targetDimension = RegistryKey.of(Registry.WORLD_KEY, buf.readIdentifier());

            // Teleport has to be called on the server thread.
            server.execute(new Runnable() {

                @Override
                public void run() {
                    // Delete the rocket.
                    if (player.getVehicle() instanceof RocketEntity rocket) {
                        rocket.remove(RemovalReason.DISCARDED);
                    }
                    ModUtils.teleportToWorld(targetDimension, player, true);
                }
            });
        });

        // Delete Space Station items.
        ServerPlayNetworking.registerGlobalReceiver(DELETE_SPACE_STATION_ITEMS_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            PlayerInventory inventory = player.getInventory();

            ModRecipes.SPACE_STATION_RECIPE.getRecipes(player.world).forEach(recipe -> {
                for (int i = 0; i < recipe.getInputs().length; i++) {
                    inventory.remove(recipe.getInputs()[i]::test, recipe.getStackCounts().get(i), inventory);
                }
            });
        });

        // Spawn the Space Station in the world.
        ServerPlayNetworking.registerGlobalReceiver(CREATE_SPACE_STATION_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            BlockPos spaceStationLocation = new BlockPos(player.getX() - 15.5f, 100, player.getZ() - 15.5f);
            ServerWorld world = server.getWorld(RegistryKey.of(Registry.WORLD_KEY, buf.readIdentifier()));
            // Create the Space Station from the nbt file.
            world.getStructureManager().getStructureOrBlank(new ModIdentifier("space_station")).place(world, spaceStationLocation, spaceStationLocation, new StructurePlacementData(), world.random, 2);
        });

        // Space was pressed while the player was inside of a rocket.
        ServerPlayNetworking.registerGlobalReceiver(LAUNCH_ROCKET_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            if (player.getVehicle() instanceof RocketEntity rocket) {
                if (!rocket.isFlying()) {
                    rocket.initiateLaunchSequenceFromServer();
                }

                // Tell all clients to start rendering the rocket launch.
                int id = buf.readInt();
                for (ServerPlayerEntity serverPlayer : server.getPlayerManager().getPlayerList()) {
                    PacketByteBuf buffer = PacketByteBufs.create();
                    buffer.writeInt(id);
                    serverPlayer.networkHandler.sendPacket(responseSender.createPacket(ModS2CPackets.START_ROCKET_PACKET_ID, buffer));
                }
            }
        });

        // Player pressed space to start boosting the lander.
        ServerPlayNetworking.registerGlobalReceiver(START_LANDER_BOOSTERS, (server, player, handler, buf, responseSender) -> {
            if (player.getVehicle() instanceof LanderEntity lander) {
                lander.isPressingSpace = true;
            }
        });

        // Player let go of space to end boosting the lander.
        ServerPlayNetworking.registerGlobalReceiver(END_LANDER_BOOSTERS, (server, player, handler, buf, responseSender) -> {
            if (player.getVehicle() instanceof LanderEntity lander) {
                lander.isPressingSpace = false;
            }
        });

    }

    private static PacketByteBuf createPlanetsDatapackBuf() {
        PacketByteBuf mainBuf = PacketByteBufs.create();
        mainBuf.writeCollection(BeyondEarth.planets, (buf, planet) -> {
            buf.writeString(planet.name());
            buf.writeIdentifier(planet.galaxy());
            buf.writeIdentifier(planet.solarSystem());
            buf.writeIdentifier(planet.dimension().getValue());
            buf.writeIdentifier(planet.orbitDimension().getValue());
            buf.writeIdentifier(planet.parentDimension() == null ? new Identifier("empty") : planet.parentDimension().getValue());
            buf.writeInt(planet.rocketTier());
            buf.writeFloat(planet.gravity());
            buf.writeInt(planet.daysInYear());
            buf.writeFloat(planet.temperature());
            buf.writeBoolean(planet.hasOxygen());
            buf.writeInt(planet.atmosphereStart());
            buf.writeEnumConstant(planet.buttonColour());
        });
        return mainBuf;
    }
}