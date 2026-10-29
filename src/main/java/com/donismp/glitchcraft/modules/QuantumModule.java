package com.donismp.glitchcraft.modules;

import com.donismp.glitchcraft.GlitchCraft;
import com.donismp.glitchcraft.manager.TargetManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class QuantumModule implements Listener {

    private static final Material[] GLITCH_BLOCKS = {
            Material.BEDROCK,
            Material.STRUCTURE_VOID,
            Material.MAGMA_BLOCK,
            Material.NETHERRACK
    };

    private final GlitchCraft plugin;
    private final TargetManager targetManager;
    private final Map<UUID, ActiveGlitch> activeGlitches = new HashMap<>();

    public QuantumModule(GlitchCraft plugin, TargetManager targetManager) {
        this.plugin = plugin;
        this.targetManager = targetManager;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!targetManager.isGlitchTarget(playerId)
                || targetManager.isVanished(player)
                || plugin.isExempt(player)) {
            restore(playerId);
            return;
        }
        if (activeGlitches.containsKey(playerId)
                || ThreadLocalRandom.current().nextInt(8) != 0) {
            return;
        }

        triggerGlitch(player);
    }

    public boolean triggerGlitch(Player player) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline() || plugin.isExempt(player)) {
            return false;
        }
        restore(playerId);
        List<BlockSnapshot> snapshots = captureRegion(player);
        for (BlockSnapshot snapshot : snapshots) {
            player.sendBlockChange(snapshot.location(), GLITCH_BLOCKS[
                    ThreadLocalRandom.current().nextInt(GLITCH_BLOCKS.length)
            ].createBlockData());
        }

        Location soundLocation = player.getLocation();
        player.playSound(soundLocation, Sound.BLOCK_NOTE_BLOCK_BIT, 1.0F, 2.0F);
        player.playSound(soundLocation, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.0F);

        ActiveGlitch effect = new ActiveGlitch(playerId, snapshots);
        effect.task = new BukkitRunnable() {
            @Override
            public void run() {
                restore(effect);
            }
        }.runTaskLater(plugin, 20L);
        activeGlitches.put(playerId, effect);
        return true;
    }

    public void restoreAll() {
        for (UUID playerId : activeGlitches.keySet().toArray(UUID[]::new)) {
            restore(playerId);
        }
    }

    public void disableTarget(UUID playerId) {
        targetManager.removeGlitchTarget(playerId);
        restore(playerId);
    }

    private List<BlockSnapshot> captureRegion(Player player) {
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().setY(0);
        if (forward.lengthSquared() == 0) {
            forward = new Vector(0, 0, 1);
        } else {
            forward.normalize();
        }
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        Location center = eye.clone().add(forward.clone().multiply(2));
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        int forwardX = (int) Math.round(forward.getX());
        int forwardZ = (int) Math.round(forward.getZ());
        int rightX = (int) Math.round(right.getX());
        int rightZ = (int) Math.round(right.getZ());
        List<BlockSnapshot> snapshots = new ArrayList<>(18);

        for (int depth = 0; depth < 2; depth++) {
            for (int width = -1; width <= 1; width++) {
                for (int height = -1; height <= 1; height++) {
                    Block block = player.getWorld().getBlockAt(
                            centerX + forwardX * depth + rightX * width,
                            centerY + height,
                            centerZ + forwardZ * depth + rightZ * width
                    );
                    snapshots.add(new BlockSnapshot(
                            block.getLocation(),
                            block.getBlockData().clone()
                    ));
                }
            }
        }
        return snapshots;
    }

    private void restore(UUID playerId) {
        ActiveGlitch effect = activeGlitches.get(playerId);
        if (effect != null) {
            restore(effect);
        }
    }

    private void restore(ActiveGlitch effect) {
        if (activeGlitches.get(effect.playerId) != effect) {
            return;
        }
        activeGlitches.remove(effect.playerId);
        if (effect.task != null) {
            effect.task.cancel();
        }

        Player player = plugin.getServer().getPlayer(effect.playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        for (BlockSnapshot snapshot : effect.snapshots) {
            if (player.getWorld().equals(snapshot.location().getWorld())
                    && snapshot.location().getChunk().isLoaded()) {
                player.sendBlockChange(snapshot.location(), snapshot.data());
            }
        }
    }

    private record BlockSnapshot(Location location, BlockData data) {
    }

    private static final class ActiveGlitch {

        private final UUID playerId;
        private final List<BlockSnapshot> snapshots;
        private BukkitTask task;

        private ActiveGlitch(UUID playerId, List<BlockSnapshot> snapshots) {
            this.playerId = playerId;
            this.snapshots = snapshots;
        }
    }
}
