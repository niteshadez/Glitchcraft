package com.donismp.glitchcraft.modules;

import com.donismp.glitchcraft.GlitchCraft;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public final class InvertedAnomalyModule implements Listener {

    private static final long RUBBERBAND_INTERVAL_TICKS = 80L;
    private static final long GRAVITY_INTERVAL_TICKS = 400L;
    private static final double RUBBERBAND_DISTANCE = 3.0;

    private final GlitchCraft plugin;
    private final Map<UUID, Long> nextRubberbandTick = new HashMap<>();
    private final Map<UUID, Long> lastGravityDistortionTick = new HashMap<>();
    private final Map<UUID, ChunkPosition> lastChunkByPlayer = new HashMap<>();
    private final Set<UUID> anomalyTargets = new HashSet<>();

    public InvertedAnomalyModule(GlitchCraft plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (plugin.isExempt(player) || !anomalyTargets.contains(player.getUniqueId())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        if (player.isSprinting()) {
            if (tryRubberband(event, player, from, to)) {
                return;
            }
        }
        applyGravityDistortionOnChunkEntry(player, to);
    }

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (plugin.isExempt(player) || !anomalyTargets.contains(player.getUniqueId())) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= 0.30) {
            return;
        }

        event.setCancelled(true);
        UUID playerId = player.getUniqueId();
        int hijackedSlot = (event.getPreviousSlot() + 4) % 9;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player onlinePlayer = plugin.getServer().getPlayer(playerId);
            if (onlinePlayer == null || !onlinePlayer.isOnline() || plugin.isExempt(onlinePlayer)) {
                return;
            }
            onlinePlayer.getInventory().setHeldItemSlot(hijackedSlot);
            onlinePlayer.playSound(
                    onlinePlayer.getLocation(),
                    Sound.BLOCK_NOTE_BLOCK_BASS,
                    1.0F,
                    0.5F
            );
        });
    }

    @EventHandler
    public void onAsyncPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (plugin.isExempt(player) || !anomalyTargets.contains(player.getUniqueId())) {
            return;
        }

        String originalText = PlainTextComponentSerializer.plainText().serialize(event.message());
        event.message(Component.text(
                originalText,
                NamedTextColor.DARK_RED,
                TextDecoration.OBFUSCATED
        ));

        UUID playerId = player.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player onlinePlayer = plugin.getServer().getPlayer(playerId);
            if (onlinePlayer == null
                    || !onlinePlayer.isOnline()
                    || plugin.isExempt(onlinePlayer)
                    || !anomalyTargets.contains(playerId)) {
                return;
            }
            onlinePlayer.sendActionBar(Component.text(
                    "[Server Error] Chat pipeline desynchronized.",
                    NamedTextColor.RED
            ));
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        nextRubberbandTick.remove(playerId);
        lastGravityDistortionTick.remove(playerId);
        lastChunkByPlayer.remove(playerId);
    }

    public void clearPlayerState() {
        nextRubberbandTick.clear();
        lastGravityDistortionTick.clear();
        lastChunkByPlayer.clear();
        anomalyTargets.clear();
    }

    public void enableTarget(Player player) {
        if (plugin.isExempt(player)) {
            return;
        }
        anomalyTargets.add(player.getUniqueId());
    }

    public void triggerEvent(Player player) {
        if (plugin.isExempt(player)) {
            return;
        }
        enableTarget(player);
        Location location = player.getLocation();
        player.playSound(location, Sound.BLOCK_PORTAL_TRIGGER, 0.4F, 0.7F);
        player.spawnParticle(
                Particle.PORTAL,
                location.clone().add(0.0, 1.0, 0.0),
                40,
                0.45,
                0.6,
                0.45,
                0.15
        );
        player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 40, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0, false, false));
    }

    public void disableTarget(UUID playerId) {
        anomalyTargets.remove(playerId);
        nextRubberbandTick.remove(playerId);
        lastGravityDistortionTick.remove(playerId);
        lastChunkByPlayer.remove(playerId);
    }

    private boolean tryRubberband(PlayerMoveEvent event, Player player, Location from, Location to) {
        Vector movement = to.toVector().subtract(from.toVector());
        movement.setY(0);
        if (movement.lengthSquared() == 0.0) {
            return false;
        }

        long currentTick = plugin.getServer().getCurrentTick();
        long nextAttempt = nextRubberbandTick.getOrDefault(player.getUniqueId(), 0L);
        if (currentTick < nextAttempt) {
            return false;
        }
        nextRubberbandTick.put(player.getUniqueId(), currentTick + RUBBERBAND_INTERVAL_TICKS);
        if (ThreadLocalRandom.current().nextDouble() >= 0.15) {
            return false;
        }

        Location currentLocation = player.getLocation();
        Vector backward = movement.normalize().multiply(-RUBBERBAND_DISTANCE);
        Location destination = currentLocation.clone().add(backward);
        destination.setYaw(currentLocation.getYaw());
        destination.setPitch(currentLocation.getPitch());

        event.setCancelled(true);
        player.playSound(currentLocation, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.0F);
        player.spawnParticle(Particle.PORTAL, currentLocation.clone().add(0.0, 1.0, 0.0),
                24, 0.35, 0.5, 0.35, 0.1);
        player.teleport(destination);
        return true;
    }

    private void applyGravityDistortionOnChunkEntry(Player player, Location location) {
        ChunkPosition currentChunk = new ChunkPosition(
                location.getWorld().getUID(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4
        );
        UUID playerId = player.getUniqueId();
        ChunkPosition previousChunk = lastChunkByPlayer.put(playerId, currentChunk);
        if (previousChunk == null || previousChunk.equals(currentChunk)) {
            return;
        }

        long currentTick = plugin.getServer().getCurrentTick();
        long lastApplied = lastGravityDistortionTick.getOrDefault(playerId, Long.MIN_VALUE);
        if (lastApplied != Long.MIN_VALUE && currentTick - lastApplied < GRAVITY_INTERVAL_TICKS) {
            return;
        }
        lastGravityDistortionTick.put(playerId, currentTick);
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.LEVITATION,
                40,
                1,
                false,
                false
        ));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOW_FALLING,
                60,
                0,
                false,
                false
        ));
        player.playSound(location, Sound.BLOCK_PORTAL_TRIGGER, 0.2F, 1.0F);
    }

    private record ChunkPosition(UUID worldId, int x, int z) {
    }
}
