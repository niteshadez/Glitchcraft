package com.donismp.glitchcraft.modules;

import com.donismp.glitchcraft.GlitchCraft;
import com.donismp.glitchcraft.manager.TargetManager;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.SkinParts;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.SignSide;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.entity.Pose;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.block.sign.Side;

public final class StalkerModule implements Listener {

    private static final long STARE_DURATION_TICKS = 60L;
    private static final long STAGE_DELAY_TICKS = 300L;
    private static final long CHASE_DURATION_TICKS = 400L;
    private static final long FLEE_UPDATE_INTERVAL_TICKS = 5L;
    private static final double DETECTION_ANGLE_RADIANS = Math.toRadians(10.0);
    private static final double DETECTION_RANGE = 50.0;
    private static final double FLEE_SPEED = 1.8;
    private static final double AMBUSH_SPEED = 2.2;
    private static final int FINAL_AMBUSH_LOOP = 2;
    private static final double THEFT_DISTANCE = 2.5;

    private static final int[] STAGE_MIN_DISTANCE = {35, 20, 10, 10};
    private static final int[] STAGE_MAX_DISTANCE = {45, 25, 12, 12};

    private final GlitchCraft plugin;
    private final TargetManager targetManager;
    private final Map<UUID, Integer> stageByTarget = new HashMap<>();
    private final Map<UUID, Integer> chaseLoopCountByTarget = new HashMap<>();
    private final Map<UUID, UUID> copiedPlayerByTarget = new HashMap<>();
    private final Map<UUID, ForcedFrame> forcedFramesByTarget = new HashMap<>();
    private final Map<UUID, UUID> forcedSignViewersByTarget = new HashMap<>();
    private final Map<UUID, StalkerSession> sessionsByTarget = new HashMap<>();
    private final Map<UUID, StalkerSession> sessionsByEntity = new HashMap<>();
    private final Map<UUID, BukkitTask> respawnTasks = new HashMap<>();
    private final Map<UUID, UUID> pendingProfileRequests = new HashMap<>();
    private final Map<UUID, String> lastSpawnStatus = new HashMap<>();
    private final Map<UUID, Mannequin> debugClones = new HashMap<>();
    private final Map<UUID, BukkitTask> debugCloneCleanupTasks = new HashMap<>();
    private final Set<UUID> forcedAmbushTargets = new HashSet<>();

    public StalkerModule(GlitchCraft plugin, TargetManager targetManager) {
        this.plugin = plugin;
        this.targetManager = targetManager;
    }

    public List<Component> getDebugStatus(Player target) {
        UUID targetId = target.getUniqueId();
        StalkerSession session = sessionsByTarget.get(targetId);
        PlayerProfile profile = target.getPlayerProfile();
        List<Component> lines = new ArrayList<>();
        lines.add(statusLine("Stalker target", targetManager.isStalkerTarget(targetId) ? "enabled" : "disabled"));
        lines.add(statusLine("Target status", targetManager.isVanished(target)
                ? "vanished"
                : plugin.isExempt(target) ? "exempt" : "visible"));
        lines.add(statusLine("Stage / loop", stageByTarget.getOrDefault(targetId, 1)
                + " / " + chaseLoopCountByTarget.getOrDefault(targetId, 0)));
        lines.add(statusLine("Live profile textures", texturePropertyCount(profile)
                + " found; signed=" + signedTexturePropertyCount(profile)));
        lines.add(statusLine("Profile request", pendingProfileRequests.containsKey(targetId) ? "pending" : "idle"));
        lines.add(statusLine("Respawn timer", respawnTasks.containsKey(targetId) ? "scheduled" : "none"));
        if (session == null) {
            lines.add(statusLine("Entity", "not tracked"));
        } else {
            lines.add(statusLine("Entity", session.doppelganger.isValid() ? "valid" : "invalid"));
            lines.add(statusLine("Entity UUID", session.doppelganger.getUniqueId().toString()));
            lines.add(statusLine("Entity world", session.doppelganger.getWorld().getName()));
            lines.add(statusLine("Entity location", formatLocation(session.doppelganger.getLocation())));
        }
        lines.add(statusLine("Last spawn", lastSpawnStatus.getOrDefault(targetId, "no spawn attempt recorded")));
        return lines;
    }

    public boolean restartForDebug(Player target) {
        UUID targetId = target.getUniqueId();
        if (isIgnored(target)) {
            lastSpawnStatus.put(targetId, "Debug restart refused: target is vanished or exempt.");
            return false;
        }
        targetManager.addStalkerTarget(targetId);
        cancelRespawn(targetId);
        cancelProfileRequest(targetId);
        removeSession(targetId);
        lastSpawnStatus.put(targetId, "Debug restart requested.");
        spawnStage(target);
        return true;
    }

    public void disableTarget(UUID targetId) {
        targetManager.removeStalkerTarget(targetId);
        forcedAmbushTargets.remove(targetId);
        cancelRespawn(targetId);
        cancelProfileRequest(targetId);
        removeSession(targetId);
        stageByTarget.remove(targetId);
        chaseLoopCountByTarget.remove(targetId);
        copiedPlayerByTarget.remove(targetId);
        forcedFramesByTarget.remove(targetId);
        forcedSignViewersByTarget.remove(targetId);
    }

    public void enableProgressionTarget(Player target) {
        if (isIgnored(target)) {
            return;
        }
        UUID targetId = target.getUniqueId();
        targetManager.addStalkerTarget(targetId);
        stageByTarget.putIfAbsent(targetId, 1);
        if (!sessionsByTarget.containsKey(targetId)
                && !respawnTasks.containsKey(targetId)
                && !pendingProfileRequests.containsKey(targetId)) {
            long delayTicks = ThreadLocalRandom.current().nextLong(30L, 121L) * 20L;
            scheduleStageSpawn(targetId, delayTicks);
        }
    }

    public boolean forceAmbush(Player target) {
        if (isIgnored(target)) {
            return false;
        }
        UUID targetId = target.getUniqueId();
        targetManager.addStalkerTarget(targetId);
        cancelRespawn(targetId);
        cancelProfileRequest(targetId);
        removeSession(targetId);
        stageByTarget.put(targetId, 4);
        forcedAmbushTargets.add(targetId);
        lastSpawnStatus.put(targetId, "Manual doppelganger ambush requested.");
        spawnStage(target);
        return true;
    }

    public boolean forceAmbushWithFrame(
            Player target,
            UUID framePlayerId,
            String framePlayerName,
            PlayerProfile profile,
            Location frameLocation
    ) {
        return forceAmbushWithFrame(target, framePlayerId, framePlayerName, profile, frameLocation, null);
    }

    public boolean forceAmbushWithFrame(
            Player target,
            UUID framePlayerId,
            String framePlayerName,
            PlayerProfile profile,
            Location frameLocation,
            Player signViewer
    ) {
        if (isIgnored(target) || !hasSkinTextures(profile)) {
            return false;
        }
        UUID targetId = target.getUniqueId();
        targetManager.addStalkerTarget(targetId);
        cancelRespawn(targetId);
        cancelProfileRequest(targetId);
        removeSession(targetId);
        stageByTarget.put(targetId, 4);
        copiedPlayerByTarget.put(targetId, framePlayerId);
        forcedFramesByTarget.put(
                targetId,
                new ForcedFrame(framePlayerId, framePlayerName, profile.clone(), frameLocation.clone())
        );
        if (signViewer != null && signViewer.isOnline()) {
            forcedSignViewersByTarget.put(targetId, signViewer.getUniqueId());
        } else {
            forcedSignViewersByTarget.remove(targetId);
        }
        forcedAmbushTargets.add(targetId);
        lastSpawnStatus.put(targetId, "Manual doppelganger ambush requested with mock frame " + framePlayerName + ".");
        spawnStage(target);
        return true;
    }

    public void clearMockFrame(UUID mockPlayerId) {
        for (Map.Entry<UUID, ForcedFrame> entry : Map.copyOf(forcedFramesByTarget).entrySet()) {
            if (!entry.getValue().playerId.equals(mockPlayerId)) {
                continue;
            }
            UUID targetId = entry.getKey();
            forcedFramesByTarget.remove(targetId);
            forcedSignViewersByTarget.remove(targetId);
            forcedAmbushTargets.remove(targetId);
            cancelRespawn(targetId);
            cancelProfileRequest(targetId);
            removeSession(targetId);
            copiedPlayerByTarget.remove(targetId, mockPlayerId);
            lastSpawnStatus.put(targetId, "Mock frame player was removed.");
        }
    }

    public void spawnVisualTest(Player copiedPlayer, Player viewer) {
        if (plugin.isExempt(copiedPlayer) || plugin.isExempt(viewer)) {
            viewer.sendMessage(Component.text(
                    "Skin test is unavailable for exempt players.",
                    NamedTextColor.RED
            ));
            return;
        }
        PlayerProfile profile = copiedPlayer.getPlayerProfile();
        ItemStack[] copiedArmor = cloneItems(copiedPlayer.getInventory().getArmorContents());
        UUID copiedId = copiedPlayer.getUniqueId();
        UUID viewerId = viewer.getUniqueId();
        String copiedName = copiedPlayer.getName();
        try {
            profile.update().whenComplete((resolvedProfile, error) -> {
                if (!plugin.isEnabled()) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player currentViewer = Bukkit.getPlayer(viewerId);
                    if (currentViewer == null || !currentViewer.isOnline()) {
                        return;
                    }
                    if (plugin.isExempt(currentViewer) || plugin.isExempt(copiedPlayer)) {
                        currentViewer.sendMessage(Component.text(
                                "Skin test is unavailable for exempt players.",
                                NamedTextColor.RED
                        ));
                        return;
                    }
                    if (error != null || resolvedProfile == null || !hasSkinTextures(resolvedProfile)) {
                        String message = error == null
                                ? "Profile has no skin texture property."
                                : error.getMessage();
                        currentViewer.sendMessage(Component.text(
                                "Skin test failed for " + copiedName + ": " + message,
                                NamedTextColor.RED
                        ));
                        lastSpawnStatus.put(copiedId, "Visual test profile failed: " + message);
                        return;
                    }

                    Location testLocation = findDebugSpawnLocation(currentViewer);
                    if (testLocation == null) {
                        currentViewer.sendMessage(Component.text(
                                "Skin test failed: no safe nearby spawn location.",
                                NamedTextColor.RED
                        ));
                        lastSpawnStatus.put(copiedId, "Visual test failed: no safe nearby location.");
                        return;
                    }

                    Mannequin mannequin;
                    try {
                        mannequin = spawnPlayerClone(copiedName, testLocation, resolvedProfile, copiedArmor);
                    } catch (RuntimeException exception) {
                        currentViewer.sendMessage(Component.text(
                                "Skin test failed: " + exception.getMessage(),
                                NamedTextColor.RED
                        ));
                        lastSpawnStatus.put(copiedId, "Visual test entity failed: " + exception.getMessage());
                        return;
                    }

                    UUID mannequinId = mannequin.getUniqueId();
                    debugClones.put(mannequinId, mannequin);
                    BukkitTask cleanupTask = plugin.getServer().getScheduler().runTaskLater(
                            plugin,
                            () -> removeDebugClone(mannequinId),
                            20L * 20L
                    );
                    debugCloneCleanupTasks.put(mannequinId, cleanupTask);
                    String result = "Visual test spawned " + mannequinId + " at " + formatLocation(testLocation)
                            + " with " + texturePropertyCount(resolvedProfile) + " texture property.";
                    lastSpawnStatus.put(copiedId, result);
                    currentViewer.sendMessage(Component.text(result, NamedTextColor.GREEN));
                });
            });
        } catch (RuntimeException exception) {
            lastSpawnStatus.put(copiedId, "Visual test profile request failed: " + exception.getMessage());
            viewer.sendMessage(Component.text(
                    "Skin test failed: " + exception.getMessage(),
                    NamedTextColor.RED
            ));
        }
    }

    private Location findDebugSpawnLocation(Player viewer) {
        Location origin = viewer.getLocation();
        Vector forward = origin.getDirection().setY(0);
        if (forward.lengthSquared() == 0.0) {
            forward = new Vector(0, 0, 1);
        } else {
            forward.normalize();
        }
        for (double distance : new double[]{2.5, 3.5, 4.5}) {
            for (double angle : new double[]{0.0, Math.PI / 3.0, -Math.PI / 3.0, Math.PI / 2.0, -Math.PI / 2.0}) {
                Vector offset = forward.clone().rotateAroundY(angle).multiply(distance);
                int x = origin.getBlockX() + (int) Math.round(offset.getX());
                int z = origin.getBlockZ() + (int) Math.round(offset.getZ());
                Location candidate = findSafeGroundAt(viewer.getWorld(), x, z, origin.getBlockY());
                if (candidate != null) {
                    candidate.setDirection(origin.toVector().subtract(candidate.toVector()));
                    return candidate;
                }
            }
        }

        World world = viewer.getWorld();
        int originX = origin.getBlockX();
        int originZ = origin.getBlockZ();
        for (int radius = 0; radius <= 8; radius++) {
            for (int xOffset = -radius; xOffset <= radius; xOffset++) {
                for (int zOffset = -radius; zOffset <= radius; zOffset++) {
                    if (radius > 0 && Math.max(Math.abs(xOffset), Math.abs(zOffset)) != radius) {
                        continue;
                    }
                    Location candidate = findSafeGroundAt(
                            world,
                            originX + xOffset,
                            originZ + zOffset,
                            origin.getBlockY()
                    );
                    if (candidate != null) {
                        candidate.setDirection(origin.toVector().subtract(candidate.toVector()));
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private Location findSafeGroundAt(World world, int x, int z, int originY) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }

        int lowestY = Math.max(world.getMinHeight() + 1, originY - 12);
        int highestY = Math.min(world.getMaxHeight() - 2, originY + 8);
        for (int y = highestY; y >= lowestY; y--) {
            Location candidate = new Location(world, x + 0.5, y, z + 0.5);
            if (isSafeSpawnLocation(candidate)) {
                return candidate;
            }
        }

        int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        if (surfaceY >= world.getMinHeight() + 1 && surfaceY < world.getMaxHeight() - 1) {
            Location surface = new Location(world, x + 0.5, surfaceY, z + 0.5);
            if (isSafeSpawnLocation(surface)) {
                return surface;
            }
        }
        return null;
    }

    private void removeDebugClone(UUID cloneId) {
        BukkitTask cleanupTask = debugCloneCleanupTasks.remove(cloneId);
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        Mannequin mannequin = debugClones.remove(cloneId);
        if (mannequin != null && mannequin.isValid()) {
            mannequin.remove();
        }
    }

    private Component statusLine(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    private boolean isIgnored(Player player) {
        return targetManager.isVanished(player) || plugin.isExempt(player);
    }

    private String formatLocation(Location location) {
        return location.getWorld().getName() + " "
                + location.getBlockX() + ", "
                + location.getBlockY() + ", "
                + location.getBlockZ();
    }

    private long texturePropertyCount(PlayerProfile profile) {
        return profile.getProperties().stream()
                .filter(property -> property.getName().equals("textures") && !property.getValue().isBlank())
                .count();
    }

    private long signedTexturePropertyCount(PlayerProfile profile) {
        return profile.getProperties().stream()
                .filter(property -> property.getName().equals("textures")
                        && !property.getValue().isBlank()
                        && property.getSignature() != null
                        && !property.getSignature().isBlank())
                .count();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player target = event.getPlayer();
        UUID targetId = target.getUniqueId();

        if (!targetManager.isStalkerTarget(targetId)) {
            removeSession(targetId);
            cancelRespawn(targetId);
            cancelProfileRequest(targetId);
            stageByTarget.remove(targetId);
            chaseLoopCountByTarget.remove(targetId);
            copiedPlayerByTarget.remove(targetId);
            forcedFramesByTarget.remove(targetId);
            forcedSignViewersByTarget.remove(targetId);
            return;
        }
        if (isIgnored(target)) {
            removeSession(targetId);
            cancelRespawn(targetId);
            cancelProfileRequest(targetId);
            stageByTarget.remove(targetId);
            chaseLoopCountByTarget.remove(targetId);
            copiedPlayerByTarget.remove(targetId);
            forcedFramesByTarget.remove(targetId);
            forcedSignViewersByTarget.remove(targetId);
            return;
        }
        if (!sessionsByTarget.containsKey(targetId)
                && !respawnTasks.containsKey(targetId)
                && !pendingProfileRequests.containsKey(targetId)) {
            spawnStage(target);
        }
    }

    @EventHandler
    public void onDoppelgangerHit(EntityDamageByEntityEvent event) {
        StalkerSession attackingSession = sessionsByEntity.get(event.getDamager().getUniqueId());
        if (attackingSession != null && event.getEntity().getUniqueId().equals(attackingSession.targetId)) {
            event.setCancelled(true);
            return;
        }

        StalkerSession session = sessionsByEntity.get(event.getEntity().getUniqueId());
        if (session == null
                || session.stage != 4
                || (session.state != StalkerState.CHASING_AWAY
                && session.state != StalkerState.AMBUSHING)
                || getAttackingPlayer(event.getDamager()) == null) {
            return;
        }
        Player target = plugin.getServer().getPlayer(session.targetId);
        if (target == null || isIgnored(target)) {
            event.setCancelled(true);
            removeSession(session.targetId);
            return;
        }
        event.setCancelled(true);
        if (session.state == StalkerState.AMBUSHING) {
            stealInventory(session);
        } else {
            finishAwayChase(session);
        }
    }

    @EventHandler
    public void onDoppelgangerDeath(EntityDeathEvent event) {
        StalkerSession session = sessionsByEntity.get(event.getEntity().getUniqueId());
        if (session == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        Player target = plugin.getServer().getPlayer(session.targetId);
        if (target == null || isIgnored(target)) {
            removeSession(session.targetId);
            return;
        }
        if (session.stage == 4) {
            if (session.state == StalkerState.AMBUSHING) {
                resetAfterAmbush(session);
            } else if (session.state == StalkerState.CHASING_AWAY) {
                finishAwayChase(session);
            } else {
                resetAfterStageFour(session);
            }
        } else {
            advanceStage(session);
        }
    }

    public void removeAllStalkers() {
        for (UUID targetId : sessionsByTarget.keySet().toArray(UUID[]::new)) {
            removeSession(targetId);
        }
        for (UUID targetId : respawnTasks.keySet().toArray(UUID[]::new)) {
            cancelRespawn(targetId);
        }
        for (UUID cloneId : debugClones.keySet().toArray(UUID[]::new)) {
            removeDebugClone(cloneId);
        }
        pendingProfileRequests.clear();
        lastSpawnStatus.clear();
        stageByTarget.clear();
        chaseLoopCountByTarget.clear();
        copiedPlayerByTarget.clear();
        forcedFramesByTarget.clear();
        forcedSignViewersByTarget.clear();
        forcedAmbushTargets.clear();
    }

    private void spawnStage(Player target) {
        UUID targetId = target.getUniqueId();
        if (isIgnored(target) || !targetManager.isStalkerTarget(targetId)) {
            return;
        }
        int stage = stageByTarget.getOrDefault(targetId, 1);
        ForcedFrame forcedFrame = forcedFramesByTarget.get(targetId);
        UUID previousCopiedPlayerId = copiedPlayerByTarget.get(targetId);
        Player copiedPlayer = null;
        PlayerProfile profile;
        ItemStack[] copiedArmor;
        UUID copiedPlayerId;
        String copiedPlayerName;
        Location copiedPlayerLocation;
        if (forcedFrame != null) {
            profile = forcedFrame.profile.clone();
            copiedArmor = new ItemStack[0];
            copiedPlayerId = forcedFrame.playerId;
            copiedPlayerName = forcedFrame.name;
            copiedPlayerLocation = forcedFrame.location.clone();
        } else {
            copiedPlayer = previousCopiedPlayerId == null
                    ? null
                    : Bukkit.getPlayer(previousCopiedPlayerId);
            if (copiedPlayer == null
                    || !copiedPlayer.isOnline()
                    || copiedPlayer.getUniqueId().equals(targetId)
                    || plugin.isExempt(copiedPlayer)) {
                copiedPlayer = choosePlayerToCopy(target, previousCopiedPlayerId);
                if (copiedPlayer == null) {
                    lastSpawnStatus.put(targetId, "No other online, non-exempt frame player is available.");
                    scheduleStageSpawn(targetId, STAGE_DELAY_TICKS);
                    return;
                }
                copiedPlayerByTarget.put(targetId, copiedPlayer.getUniqueId());
            }
            profile = copiedPlayer.getPlayerProfile();
            copiedArmor = cloneItems(copiedPlayer.getInventory().getArmorContents());
            copiedPlayerId = copiedPlayer.getUniqueId();
            copiedPlayerName = copiedPlayer.getName();
            Location respawnLocation = copiedPlayer.getRespawnLocation();
            copiedPlayerLocation = respawnLocation != null
                    ? respawnLocation.clone()
                    : copiedPlayer.getLocation().clone();
        }
        UUID requestId = UUID.randomUUID();
        pendingProfileRequests.put(targetId, requestId);
        lastSpawnStatus.put(targetId, "Resolving skin profile for " + copiedPlayerName + ".");

        try {
            CompletableFuture<PlayerProfile> profileFuture = forcedFrame == null
                    ? profile.update()
                    : CompletableFuture.completedFuture(profile);
            profileFuture.whenComplete((resolvedProfile, error) -> {
                if (!plugin.isEnabled()) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!requestId.equals(pendingProfileRequests.get(targetId))) {
                        return;
                    }
                    pendingProfileRequests.remove(targetId);

                    Player currentTarget = plugin.getServer().getPlayer(targetId);
                    if (currentTarget == null
                            || !currentTarget.isOnline()
                            || !targetManager.isStalkerTarget(targetId)
                            || isIgnored(currentTarget)
                            || sessionsByTarget.containsKey(targetId)) {
                        return;
                    }
                    Player currentFramePlayer = forcedFrame == null
                            ? Bukkit.getPlayer(copiedPlayerId)
                            : null;
                    if ((forcedFrame != null && forcedFramesByTarget.get(targetId) != forcedFrame)
                            || (forcedFrame == null && (currentFramePlayer == null
                            || !currentFramePlayer.isOnline()
                            || currentFramePlayer.getUniqueId().equals(targetId)
                            || plugin.isExempt(currentFramePlayer)))) {
                        copiedPlayerByTarget.remove(targetId, copiedPlayerId);
                        lastSpawnStatus.put(targetId, "Selected frame player went offline or became exempt.");
                        scheduleStageSpawn(targetId, 20L);
                        return;
                    }
                    if (error != null || resolvedProfile == null || !hasSkinTextures(resolvedProfile)) {
                        String failure = error == null
                                ? "Resolved profile has no skin texture property."
                                : "Profile lookup failed: " + error.getMessage();
                        lastSpawnStatus.put(targetId, failure);
                        plugin.getLogger().warning(
                                "Could not resolve skin textures for " + copiedPlayerName + ": " + failure
                        );
                        scheduleStageSpawn(targetId, 20L);
                        return;
                    }

                    DoppelgangerSpawn spawned = spawnDoppelganger(
                            currentTarget,
                            stage,
                            resolvedProfile,
                            copiedPlayerId,
                            copiedPlayerName,
                            copiedPlayerLocation,
                            copiedArmor
                    );
                    if (spawned == null) {
                        scheduleStageSpawn(targetId, 20L);
                        return;
                    }

                    StalkerSession session = new StalkerSession(targetId, stage, spawned);
                    sessionsByTarget.put(targetId, session);
                    sessionsByEntity.put(spawned.entity.getUniqueId(), session);
                    if (forcedAmbushTargets.remove(targetId)) {
                        startFinalAmbush(session, currentTarget);
                    }
                    lastSpawnStatus.put(
                            targetId,
                            "Spawned visible Paper Mannequin " + spawned.entity.getUniqueId()
                                    + " at " + formatLocation(spawned.entity.getLocation())
                                    + " with " + texturePropertyCount(resolvedProfile) + " texture property."
                    );
                    plugin.getLogger().info(
                            "Stalker spawned mannequin " + spawned.entity.getUniqueId()
                                    + " for " + currentTarget.getName()
                                    + " using skin profile " + copiedPlayerName
                                    + " at " + formatLocation(spawned.entity.getLocation())
                    );
                    session.stateTask = plugin.getServer().getScheduler().runTaskTimer(
                            plugin,
                            () -> updateSession(session),
                            1L,
                            1L
                    );
                });
            });
        } catch (RuntimeException exception) {
            pendingProfileRequests.remove(targetId, requestId);
            plugin.getLogger().warning(
                    "Could not resolve the copied skin profile for " + copiedPlayerName + ": "
                            + exception.getMessage()
            );
            lastSpawnStatus.put(targetId, "Profile request failed: " + exception.getMessage());
            scheduleStageSpawn(targetId, 20L);
        }
    }

    private DoppelgangerSpawn spawnDoppelganger(
            Player target,
            int stage,
            PlayerProfile copiedProfile,
            UUID copiedPlayerId,
            String copiedPlayerName,
            Location copiedPlayerLocation,
            ItemStack[] copiedArmor
    ) {
        Location spawnLocation = findGroundLocation(target, stage);
        if (spawnLocation == null) {
            spawnLocation = findDebugSpawnLocation(target);
            if (spawnLocation != null) {
                lastSpawnStatus.put(
                        target.getUniqueId(),
                        "Stage " + stage + " location unavailable; using nearby safe fallback."
                );
            }
        }
        if (spawnLocation == null) {
            lastSpawnStatus.put(
                    target.getUniqueId(),
                    "No safe spawn location found for stage " + stage
                            + " in loaded chunks near " + formatLocation(target.getLocation()) + "."
            );
            return null;
        }

        try {
            Mannequin doppelganger = spawnPlayerClone(
                    copiedPlayerName,
                    spawnLocation,
                    copiedProfile,
                    copiedArmor
            );
            return new DoppelgangerSpawn(
                    doppelganger,
                    copiedPlayerId,
                    copiedPlayerName,
                    copiedPlayerLocation
            );
        } catch (RuntimeException exception) {
            plugin.getLogger().warning(
                    "Could not spawn the doppelgänger for " + target.getName() + ": " + exception.getMessage()
            );
            lastSpawnStatus.put(target.getUniqueId(), "Mannequin creation failed: " + exception.getMessage());
            return null;
        }
    }

    private Player choosePlayerToCopy(Player target, UUID excludedPlayerId) {
        List<? extends Player> alternatives = Bukkit.getOnlinePlayers().stream()
                .filter(player -> !player.getUniqueId().equals(target.getUniqueId()))
                .filter(player -> !player.getUniqueId().equals(excludedPlayerId))
                .filter(player -> !plugin.isExempt(player))
                .toList();
        if (!alternatives.isEmpty()) {
            return alternatives.get(ThreadLocalRandom.current().nextInt(alternatives.size()));
        }
        return null;
    }

    private Mannequin spawnPlayerClone(
            String copiedPlayerName,
            Location location,
            PlayerProfile copiedProfile,
            ItemStack[] copiedArmor
    ) {
        if (!isSafeSpawnLocation(location)) {
            throw new IllegalArgumentException("Invalid doppelgänger spawn location");
        }
        PlayerProfile profile = copiedProfile.clone();
        if (!hasSkinTextures(profile)) {
            throw new IllegalArgumentException(
                    "Copied player profile has no skin textures: " + copiedPlayerName
            );
        }
        return location.getWorld().spawn(location, Mannequin.class, mannequin -> {
            mannequin.setProfile(ResolvableProfile.resolvableProfile(profile));
            mannequin.setSkinParts(SkinParts.allParts());
            mannequin.setPose(Pose.STANDING, false);
            mannequin.setSilent(true);
            mannequin.setCollidable(false);
            mannequin.setInvulnerable(true);
            mannequin.setImmovable(true);
            mannequin.setPersistent(true);
            mannequin.customName(Component.text(copiedPlayerName));
            mannequin.setCustomNameVisible(false);
            copyArmor(copiedArmor, mannequin.getEquipment());
        });
    }

    private boolean hasSkinTextures(PlayerProfile profile) {
        return profile.getProperties().stream().anyMatch(property ->
                property.getName().equals("textures") && !property.getValue().isBlank()
        );
    }

    private void copyArmor(ItemStack[] armorContents, EntityEquipment destinationEquipment) {
        destinationEquipment.setArmorContents(cloneItems(armorContents));
    }

    private boolean isSafeSpawnLocation(Location location) {
        World world = location.getWorld();
        if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);
        return location.getY() > world.getMinHeight()
                && !floor.isPassable()
                && feet.isPassable()
                && head.isPassable()
                && floor.getType() != Material.WATER
                && floor.getType() != Material.LAVA;
    }

    private ItemStack cloneItem(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private Location findGroundLocation(Player target, int stage) {
        World world = target.getWorld();
        Location targetLocation = target.getLocation();
        Vector forward = targetLocation.getDirection().setY(0);
        if (forward.lengthSquared() == 0) {
            forward = new Vector(0, 0, 1);
        } else {
            forward.normalize();
        }

        int minDistance = STAGE_MIN_DISTANCE[stage - 1];
        int maxDistance = STAGE_MAX_DISTANCE[stage - 1];
        for (int attempt = 0; attempt < 64; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(-Math.PI / 3.0, Math.PI / 3.0);
            double distance = ThreadLocalRandom.current().nextDouble(minDistance, maxDistance);
            Vector offset = forward.clone().rotateAroundY(angle).multiply(distance);
            int x = targetLocation.getBlockX() + (int) Math.round(offset.getX());
            int z = targetLocation.getBlockZ() + (int) Math.round(offset.getZ());
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }

            int targetY = targetLocation.getBlockY();
            for (int y = targetY + 8; y >= targetY - 12; y--) {
                Block floor = world.getBlockAt(x, y - 1, z);
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                if (!floor.isPassable()
                        && feet.isPassable()
                        && head.isPassable()
                        && floor.getType() != Material.WATER
                        && floor.getType() != Material.LAVA) {
                    return new Location(world, x + 0.5, y, z + 0.5, 0.0F, 0.0F);
                }
            }
        }
        return null;
    }

    private void updateSession(StalkerSession session) {
        Player target = plugin.getServer().getPlayer(session.targetId);
        if (target == null
                || !target.isOnline()
                || !targetManager.isStalkerTarget(session.targetId)
                || isIgnored(target)
                || !session.doppelganger.isValid()
                || !session.doppelganger.getWorld().equals(target.getWorld())) {
            removeSession(session.targetId);
            return;
        }
        Player framePlayer = Bukkit.getPlayer(session.copiedPlayerId);
        if (framePlayer != null && plugin.isExempt(framePlayer)) {
            copiedPlayerByTarget.remove(session.targetId, session.copiedPlayerId);
            removeSession(session.targetId);
            return;
        }

        if (session.state == StalkerState.LURK) {
            if (plugin.getServer().getCurrentTick() % 2 == 0) {
                faceTarget(session.doppelganger, target);
            }
            if (!session.isStaring && isLookingAt(target, session.doppelganger)) {
                startStareDown(session);
            }
        }
    }

    private void startStareDown(StalkerSession session) {
        if (session.isStaring || session.state != StalkerState.LURK) {
            return;
        }
        session.isStaring = true;
        session.stareTask = new BukkitRunnable() {
            private long elapsedTicks;

            @Override
            public void run() {
                Player target = plugin.getServer().getPlayer(session.targetId);
                if (target == null
                        || !target.isOnline()
                        || !targetManager.isStalkerTarget(session.targetId)
                        || isIgnored(target)
                        || !session.doppelganger.isValid()
                        || !session.doppelganger.getWorld().equals(target.getWorld())) {
                    session.isStaring = false;
                    cancel();
                    removeSession(session.targetId);
                    return;
                }

                faceTarget(session.doppelganger, target);
                elapsedTicks++;
                if (elapsedTicks >= STARE_DURATION_TICKS) {
                    session.isStaring = false;
                    session.stareTask = null;
                    cancel();
                    finishStareDown(session, target);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void finishStareDown(StalkerSession session, Player target) {
        if (session.stage < 4) {
            Location entityLocation = session.doppelganger.getLocation().add(0.0, 1.0, 0.0);
            target.spawnParticle(
                    Particle.CAMPFIRE_COSY_SMOKE,
                    entityLocation,
                    18,
                    0.25,
                    0.35,
                    0.25,
                    0.01
            );
            advanceStage(session);
            return;
        }

        if (chaseLoopCountByTarget.getOrDefault(session.targetId, 0) >= FINAL_AMBUSH_LOOP) {
            startFinalAmbush(session, target);
            return;
        }

        session.state = StalkerState.CHASING_AWAY;
        moveAway(session.doppelganger, target);
        session.doppelganger.setInvulnerable(false);
        session.doppelganger.setImmovable(false);
        session.chaseTask = new BukkitRunnable() {
            private long elapsedTicks;

            @Override
            public void run() {
                Player currentTarget = plugin.getServer().getPlayer(session.targetId);
                if (currentTarget == null
                        || !currentTarget.isOnline()
                        || !targetManager.isStalkerTarget(session.targetId)
                        || isIgnored(currentTarget)
                        || !session.doppelganger.isValid()
                        || !session.doppelganger.getWorld().equals(currentTarget.getWorld())) {
                    cancel();
                    removeSession(session.targetId);
                    return;
                }

                if (session.state != StalkerState.CHASING_AWAY) {
                    cancel();
                    return;
                }
                moveAway(session.doppelganger, currentTarget);
                elapsedTicks += FLEE_UPDATE_INTERVAL_TICKS;
                if (elapsedTicks >= CHASE_DURATION_TICKS) {
                    session.chaseTask = null;
                    cancel();
                    finishAwayChase(session);
                }
            }
        }.runTaskTimer(plugin, FLEE_UPDATE_INTERVAL_TICKS, FLEE_UPDATE_INTERVAL_TICKS);
    }

    private void faceTarget(Mannequin doppelganger, Player target) {
        Location entityLocation = doppelganger.getLocation();
        Vector direction = target.getEyeLocation().toVector().subtract(entityLocation.toVector());
        entityLocation.setDirection(direction);
        doppelganger.setRotation(entityLocation.getYaw(), entityLocation.getPitch());
    }

    private boolean isLookingAt(Player player, Mannequin doppelganger) {
        Location eyeLocation = player.getEyeLocation();
        Location entityCenter = doppelganger.getLocation().add(0.0, 1.0, 0.0);
        Vector toEntity = entityCenter.toVector().subtract(eyeLocation.toVector());
        double distance = toEntity.length();
        if (distance == 0.0 || distance > DETECTION_RANGE) {
            return false;
        }
        if (eyeLocation.getDirection().angle(toEntity.normalize()) > DETECTION_ANGLE_RADIANS) {
            return false;
        }

        RayTraceResult hit = player.getWorld().rayTraceEntities(
                eyeLocation,
                eyeLocation.getDirection(),
                Math.min(DETECTION_RANGE, distance + 1.0),
                0.4,
                entity -> entity.getUniqueId().equals(doppelganger.getUniqueId())
        );
        return hit != null && doppelganger.equals(hit.getHitEntity());
    }

    private void advanceStage(StalkerSession session) {
        int nextStage = Math.min(session.stage + 1, 4);
        removeSession(session.targetId);
        stageByTarget.put(session.targetId, nextStage);
        scheduleStageSpawn(session.targetId, STAGE_DELAY_TICKS);
    }

    private void moveAway(Mannequin doppelganger, Player target) {
        Vector away = doppelganger.getLocation().toVector().subtract(target.getLocation().toVector());
        away.setY(0);
        if (away.lengthSquared() == 0) {
            away = target.getLocation().getDirection().setY(0).multiply(-1);
        }
        if (away.lengthSquared() == 0) {
            away = new Vector(0, 0, 1);
        }
        Vector direction = away.normalize();
        Location facing = doppelganger.getLocation();
        facing.setDirection(direction);
        doppelganger.setRotation(facing.getYaw(), facing.getPitch());
        doppelganger.setVelocity(direction.multiply(FLEE_SPEED * 0.25));
    }

    private void startFinalAmbush(StalkerSession session, Player target) {
        session.state = StalkerState.AMBUSHING;
        session.doppelganger.setInvulnerable(false);
        session.doppelganger.setImmovable(false);
        session.chaseTask = new BukkitRunnable() {
            private long ticks;

            @Override
            public void run() {
                Player currentTarget = plugin.getServer().getPlayer(session.targetId);
                if (currentTarget == null
                        || !currentTarget.isOnline()
                        || !targetManager.isStalkerTarget(session.targetId)
                        || isIgnored(currentTarget)
                        || !session.doppelganger.isValid()
                        || !session.doppelganger.getWorld().equals(currentTarget.getWorld())) {
                    cancel();
                    removeSession(session.targetId);
                    return;
                }

                if (session.doppelganger.getLocation().distanceSquared(currentTarget.getLocation())
                        < THEFT_DISTANCE * THEFT_DISTANCE) {
                    session.chaseTask = null;
                    cancel();
                    stealInventory(session);
                    return;
                }

                if (ticks % FLEE_UPDATE_INTERVAL_TICKS == 0L) {
                    moveTowards(session.doppelganger, currentTarget);
                }
                ticks++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void moveTowards(Mannequin doppelganger, Player target) {
        Vector toward = target.getLocation().toVector().subtract(doppelganger.getLocation().toVector());
        toward.setY(0);
        if (toward.lengthSquared() == 0) {
            toward = target.getLocation().getDirection().setY(0);
        }
        if (toward.lengthSquared() > 0) {
            Vector direction = toward.normalize();
            Location facing = doppelganger.getLocation();
            facing.setDirection(direction);
            doppelganger.setRotation(facing.getYaw(), facing.getPitch());
            doppelganger.setVelocity(direction.multiply(AMBUSH_SPEED * 0.25));
        }
    }

    private void stealInventory(StalkerSession session) {
        if (session.state != StalkerState.AMBUSHING) {
            return;
        }
        Player target = plugin.getServer().getPlayer(session.targetId);
        Player copiedPlayer = Bukkit.getPlayer(session.copiedPlayerId);
        if (target == null || !target.isOnline() || isIgnored(target)
                || (copiedPlayer != null && plugin.isExempt(copiedPlayer))) {
            if (copiedPlayer != null && plugin.isExempt(copiedPlayer)) {
                copiedPlayerByTarget.remove(session.targetId, session.copiedPlayerId);
            }
            removeSession(session.targetId);
            return;
        }
        session.state = StalkerState.VANISHING;
        session.doppelganger.remove();
        removeSession(session.targetId);
        if (target != null && target.isOnline()) {
            PlayerInventory inventory = target.getInventory();
            List<ItemStack> stolenItems = new ArrayList<>();
            collectItems(stolenItems, inventory.getStorageContents());
            collectItems(stolenItems, inventory.getArmorContents());
            collectItem(stolenItems, inventory.getItemInOffHand());

            Location copiedPlayerLocation = copiedPlayer == null
                    ? session.copiedPlayerLocation
                    : getFrameBaseLocation(copiedPlayer, session.copiedPlayerLocation);
            UUID signViewerId = forcedSignViewersByTarget.remove(target.getUniqueId());
            Player signViewer = signViewerId == null ? copiedPlayer : Bukkit.getPlayer(signViewerId);
            if (signViewer != null && !signViewer.isOnline()) {
                signViewer = null;
            }
            boolean stored = storeFrameLoot(copiedPlayerLocation, stolenItems, signViewer);
            if (!stored) {
                plugin.getLogger().warning(
                        "Could not safely store stolen inventory for " + target.getName()
                                + "; inventory was left unchanged."
                );
                resetAfterAmbush(session);
                return;
            }
            forcedFramesByTarget.remove(target.getUniqueId());

            if (copiedPlayer != null) {
                copiedPlayer.sendMessage(Component.text(
                        "Check your base... a gift has arrived.",
                        NamedTextColor.GRAY,
                        TextDecoration.ITALIC
                ));
            }

            inventory.clear();
            inventory.setArmorContents(null);
            inventory.setExtraContents(null);
            inventory.setItemInOffHand(null);

            Location targetLocation = target.getLocation();
            target.playSound(targetLocation, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0F, 1.0F);
            target.showTitle(Title.title(
                    Component.text("You have been robbed...", NamedTextColor.RED),
                    Component.empty()
            ));
            target.spawnParticle(
                    Particle.CAMPFIRE_COSY_SMOKE,
                    targetLocation.clone().add(0.0, 1.0, 0.0),
                    80,
                    1.0,
                    1.0,
                    1.0,
                    0.05
            );
            target.spawnParticle(
                    Particle.FLASH,
                    targetLocation.clone().add(0.0, 1.0, 0.0),
                    1,
                    Color.WHITE
            );

            if (!chooseNextCopiedPlayer(target.getUniqueId(), session.copiedPlayerId, target.getUniqueId())) {
                UUID targetId = target.getUniqueId();
                targetManager.removeStalkerTarget(targetId);
                stageByTarget.remove(targetId);
                chaseLoopCountByTarget.remove(targetId);
                copiedPlayerByTarget.remove(targetId);
                forcedFramesByTarget.remove(targetId);
                forcedSignViewersByTarget.remove(targetId);
                lastSpawnStatus.put(targetId, "Stalker stopped after the robbery: no other online player is available.");
                return;
            }
        }
        resetAfterAmbush(session);
    }

    private ItemStack[] cloneItems(ItemStack[] contents) {
        ItemStack[] clones = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            clones[index] = cloneItem(contents[index]);
        }
        return clones;
    }

    private boolean storeInPhysicalChests(
            Location around,
            List<ItemStack> items,
            boolean addGiftSign
    ) {
        if (items.isEmpty()) {
            if (!addGiftSign) {
                return true;
            }
            List<Block> existingChests = findExistingChestBlocks(around);
            Block signChest = existingChests.isEmpty() ? null : existingChests.get(0);
            if (signChest == null) {
                signChest = findChestLocation(around, List.of());
                if (signChest == null) {
                    return false;
                }
                signChest.setType(Material.CHEST, false);
            }
            return placeGiftSign(signChest, null);
        }
        List<ItemStack> remaining = cloneItemsAsList(items);
        List<Block> existingChests = findExistingChestBlocks(around);
        List<Location> reservedLocations = new ArrayList<>();
        for (Block existingChest : existingChests) {
            reservedLocations.add(existingChest.getLocation());
        }
        Map<Block, ItemStack[]> originalContents = new HashMap<>();
        List<Block> createdChests = new ArrayList<>();
        Block firstReceivingChest = null;

        for (Block chestBlock : existingChests) {
            if (!(chestBlock.getState() instanceof Chest chestState)) {
                continue;
            }
            Inventory chestInventory = chestState.getBlockInventory();
            if (chestInventory.firstEmpty() == -1) {
                continue;
            }
            originalContents.put(chestBlock, cloneItems(chestInventory.getContents()));
            long amountBefore = itemAmount(remaining);
            List<ItemStack> leftovers = addItems(chestInventory, remaining);
            if (itemAmount(leftovers) < amountBefore && firstReceivingChest == null) {
                firstReceivingChest = chestBlock;
            }
            remaining = leftovers;
            if (remaining.isEmpty()) {
                break;
            }
        }

        while (!remaining.isEmpty()) {
            Block chestBlock = findChestLocation(around, reservedLocations);
            if (chestBlock == null) {
                rollbackChestStorage(originalContents, createdChests);
                return false;
            }
            chestBlock.setType(Material.CHEST, false);
            createdChests.add(chestBlock);
            reservedLocations.add(chestBlock.getLocation());
            if (!(chestBlock.getState() instanceof Chest chestState)) {
                plugin.getLogger().warning("Failed to create fallback theft chest at " + chestBlock.getLocation());
                rollbackChestStorage(originalContents, createdChests);
                return false;
            }
            if (firstReceivingChest == null) {
                firstReceivingChest = chestBlock;
            }
            remaining = addItems(chestState.getBlockInventory(), remaining);
        }

        if (addGiftSign && firstReceivingChest != null && !placeGiftSign(firstReceivingChest, null)) {
            rollbackChestStorage(originalContents, createdChests);
            return false;
        }
        return true;
    }

    private boolean storeFrameLoot(Location around, List<ItemStack> items, Player framePlayer) {
        World world = around.getWorld();
        if (world == null) {
            return false;
        }
        world.getChunkAt(around.getBlockX() >> 4, around.getBlockZ() >> 4).load();
        Block chestBlock = findChestLocation(around, List.of());
        if (chestBlock == null) {
            return false;
        }
        chestBlock.setType(Material.CHEST, false);
        if (!(chestBlock.getBlockData() instanceof org.bukkit.block.data.type.Chest chestData)) {
            chestBlock.setType(Material.AIR, false);
            return false;
        }
        chestData.setFacing(BlockFace.NORTH);
        chestData.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
        chestBlock.setBlockData(chestData, false);

        List<ItemStack> overflow = cloneItemsAsList(items);
        Block secondChest = findAdjacentChestBlock(chestBlock);
        if (secondChest != null) {
            secondChest.setType(Material.CHEST, false);
            if (secondChest.getBlockData() instanceof org.bukkit.block.data.type.Chest secondChestData) {
                org.bukkit.block.data.type.Chest firstChestData =
                        (org.bukkit.block.data.type.Chest) chestBlock.getBlockData();
                firstChestData.setType(org.bukkit.block.data.type.Chest.Type.LEFT);
                secondChestData.setFacing(BlockFace.NORTH);
                secondChestData.setType(org.bukkit.block.data.type.Chest.Type.RIGHT);
                chestBlock.setBlockData(firstChestData, false);
                secondChest.setBlockData(secondChestData, false);
            } else {
                secondChest.setType(Material.AIR, false);
                secondChest = null;
            }
        }

        if (chestBlock.getState() instanceof Chest chestState) {
            Inventory receivingInventory = chestState.getInventory();
            overflow = addItems(receivingInventory, overflow);
        } else {
            plugin.getLogger().warning("Created frame loot chest could not be read at " + chestBlock.getLocation());
            return false;
        }

        if (!overflow.isEmpty()) {
            List<Location> reservedLocations = new ArrayList<>();
            reservedLocations.add(chestBlock.getLocation());
            if (secondChest != null) {
                reservedLocations.add(secondChest.getLocation());
            }
            while (!overflow.isEmpty()) {
                Block overflowChest = findChestLocation(around, reservedLocations);
                if (overflowChest == null) {
                    plugin.getLogger().severe(
                            "Unable to store all stolen items for frame loot at " + chestBlock.getLocation()
                    );
                    return false;
                }
                overflowChest.setType(Material.CHEST, false);
                if (!(overflowChest.getState() instanceof Chest overflowChestState)) {
                    overflowChest.setType(Material.AIR, false);
                    plugin.getLogger().warning(
                            "Could not create overflow storage chest at " + overflowChest.getLocation()
                    );
                    return false;
                }
                reservedLocations.add(overflowChest.getLocation());
                overflow = addItems(overflowChestState.getBlockInventory(), overflow);
            }
        }
        if (!placeGiftSign(chestBlock, framePlayer)) {
            plugin.getLogger().warning("Could not place the private gift sign at " + chestBlock.getLocation());
        }
        return true;
    }

    private Block findAdjacentChestBlock(Block chestBlock) {
        World world = chestBlock.getWorld();
        for (BlockFace face : List.of(BlockFace.EAST, BlockFace.WEST)) {
            Block candidate = chestBlock.getRelative(face);
            Block floor = candidate.getRelative(BlockFace.DOWN);
            Block above = candidate.getRelative(BlockFace.UP);
            if (world.isChunkLoaded(candidate.getX() >> 4, candidate.getZ() >> 4)
                    && floor.getType().isSolid()
                    && candidate.isEmpty()
                    && above.isPassable()) {
                return candidate;
            }
        }
        return null;
    }

    private List<Block> findExistingChestBlocks(Location around) {
        List<Block> chests = new ArrayList<>();
        World world = around.getWorld();
        if (world == null) {
            return chests;
        }

        int baseX = around.getBlockX();
        int baseY = around.getBlockY();
        int baseZ = around.getBlockZ();
        Set<String> visited = new HashSet<>();
        for (int radius = 0; radius <= 8; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                        continue;
                    }
                    for (int y = baseY - 1; y <= baseY + 1; y++) {
                        int blockX = baseX + x;
                        int blockZ = baseZ + z;
                        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                            continue;
                        }
                        Block block = world.getBlockAt(blockX, y, blockZ);
                        String key = blockX + ":" + y + ":" + blockZ;
                        if (visited.add(key) && block.getType() == Material.CHEST) {
                            chests.add(block);
                        }
                    }
                }
            }
        }
        return chests;
    }

    private List<ItemStack> addItems(Inventory inventory, List<ItemStack> items) {
        if (items.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(inventory.addItem(
                cloneItemsAsList(items).toArray(ItemStack[]::new)
        ).values());
    }

    private void rollbackChestStorage(Map<Block, ItemStack[]> originalContents, List<Block> createdChests) {
        for (Map.Entry<Block, ItemStack[]> entry : originalContents.entrySet()) {
            if (entry.getKey().getType() == Material.CHEST
                    && entry.getKey().getState() instanceof Chest chestState) {
                chestState.getBlockInventory().setContents(entry.getValue());
            }
        }
        for (Block createdChest : createdChests) {
            if (createdChest.getType() == Material.CHEST) {
                createdChest.setType(Material.AIR, false);
            }
        }
    }

    private boolean placeGiftSign(Block chestBlock, Player framePlayer) {
        Block signBlock = chestBlock.getRelative(0, 1, 0);
        if (signBlock.isEmpty()) {
            signBlock.setType(Material.OAK_SIGN, false);
        } else {
            signBlock = placeWallGiftSign(chestBlock);
            if (signBlock == null) {
                plugin.getLogger().warning("No room to place the gift sign at " + chestBlock.getLocation());
                return false;
            }
        }

        if (!(signBlock.getState() instanceof Sign sign)) {
            plugin.getLogger().warning("Could not create gift sign above chest at " + chestBlock.getLocation());
            signBlock.setType(Material.AIR, false);
            return false;
        }

        var frontSide = sign.getSide(Side.FRONT);
        for (int line = 0; line < 4; line++) {
            frontSide.line(line, Component.empty());
        }
        var backSide = sign.getSide(Side.BACK);
        for (int line = 0; line < 4; line++) {
            backSide.line(line, Component.empty());
        }
        if (!sign.update(true)) {
            plugin.getLogger().warning("Could not save blank gift sign at " + chestBlock.getLocation());
            signBlock.setType(Material.AIR, false);
            return false;
        }

        if (framePlayer == null || !framePlayer.isOnline()) {
            return true;
        }
        if (!(signBlock.getState() instanceof Sign privateSign)) {
            return false;
        }
        Component note = Component.text(
                "Enjoy the gift...",
                NamedTextColor.DARK_RED,
                TextDecoration.ITALIC
        );
        setGiftNote(privateSign.getSide(Side.FRONT), note);
        setGiftNote(privateSign.getSide(Side.BACK), note);
        Location signLocation = signBlock.getLocation();
        Block clientSignBlock = signBlock;
        framePlayer.sendBlockChange(signLocation, clientSignBlock.getBlockData());
        UUID framePlayerId = framePlayer.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player recipient = Bukkit.getPlayer(framePlayerId);
            if (recipient == null
                    || !recipient.isOnline()
                    || !recipient.getWorld().equals(signLocation.getWorld())
                    || !recipient.getWorld().isChunkLoaded(
                            signLocation.getBlockX() >> 4,
                            signLocation.getBlockZ() >> 4
                    )
                    || !(clientSignBlock.getState() instanceof Sign signState)) {
                return;
            }
            Component clientNote = Component.text(
                    "Enjoy the gift...",
                    NamedTextColor.DARK_RED,
                    TextDecoration.ITALIC
            );
            setGiftNote(signState.getSide(Side.FRONT), clientNote);
            setGiftNote(signState.getSide(Side.BACK), clientNote);
            recipient.sendBlockUpdate(signLocation, signState);
            plugin.getLogger().info(
                    "Sent private sign block update to " + recipient.getName() + ": Enjoy the gift..."
            );
        }, 1L);
        return true;
    }

    private void setGiftNote(SignSide side, Component note) {
        side.line(0, Component.empty());
        side.line(1, note);
        side.line(2, Component.empty());
        side.line(3, Component.empty());
    }

    private Block placeWallGiftSign(Block chestBlock) {
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block candidate = chestBlock.getRelative(face);
            if (!candidate.isEmpty()) {
                continue;
            }
            candidate.setType(Material.OAK_WALL_SIGN, false);
            if (candidate.getBlockData() instanceof WallSign wallSign) {
                wallSign.setFacing(face);
                candidate.setBlockData(wallSign, false);
                return candidate;
            }
            candidate.setType(Material.AIR, false);
        }
        return null;
    }

    private long itemAmount(List<ItemStack> items) {
        long amount = 0;
        for (ItemStack item : items) {
            amount += item.getAmount();
        }
        return amount;
    }

    private List<ItemStack> cloneItemsAsList(List<ItemStack> items) {
        List<ItemStack> clones = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            clones.add(item.clone());
        }
        return clones;
    }

    private Block findChestLocation(Location around, List<Location> reservedLocations) {
        World world = around.getWorld();
        if (world == null) {
            return null;
        }
        int baseX = around.getBlockX();
        int baseY = around.getBlockY();
        int baseZ = around.getBlockZ();
        for (int radius = 1; radius <= 8; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                        continue;
                    }
                    int blockX = baseX + x;
                    int blockZ = baseZ + z;
                    if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                        continue;
                    }
                    boolean nearReservedLocation = reservedLocations.stream().anyMatch(location ->
                            Math.abs(location.getBlockX() - blockX) <= 1
                                    && Math.abs(location.getBlockZ() - blockZ) <= 1
                    );
                    if (nearReservedLocation) {
                        continue;
                    }
                    int highestY = Math.min(world.getMaxHeight() - 2, baseY + 4);
                    int lowestY = Math.max(world.getMinHeight() + 1, baseY - 16);
                    for (int y = highestY; y >= lowestY; y--) {
                        Block floor = world.getBlockAt(blockX, y - 1, blockZ);
                        Block candidate = world.getBlockAt(blockX, y, blockZ);
                        Block above = world.getBlockAt(blockX, y + 1, blockZ);
                        if (floor.getType().isSolid()
                                && candidate.isEmpty()
                                && above.isPassable()) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    private Location getFrameBaseLocation(Player framePlayer, Location fallback) {
        Location respawnLocation = framePlayer.getRespawnLocation();
        if (isLoadedLocation(respawnLocation)) {
            return respawnLocation.clone();
        }
        Location currentLocation = framePlayer.getLocation();
        if (isLoadedLocation(currentLocation)) {
            return currentLocation;
        }
        return fallback.clone();
    }

    private boolean isLoadedLocation(Location location) {
        World world = location == null ? null : location.getWorld();
        return world != null
                && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private void collectItems(List<ItemStack> collected, ItemStack[] contents) {
        for (ItemStack item : contents) {
            collectItem(collected, item);
        }
    }

    private void collectItem(List<ItemStack> collected, ItemStack item) {
        if (item != null && !item.getType().isAir()) {
            collected.add(item.clone());
        }
    }

    private void finishAwayChase(StalkerSession session) {
        if (session.state == StalkerState.VANISHING) {
            return;
        }
        session.state = StalkerState.VANISHING;
        Player target = plugin.getServer().getPlayer(session.targetId);
        if (target != null && target.isOnline() && session.doppelganger.isValid()) {
            session.doppelganger.setVelocity(new Vector(0, 0, 0));
            Location facingLocation = session.doppelganger.getLocation();
            facingLocation.setDirection(target.getEyeLocation().toVector()
                    .subtract(facingLocation.toVector()));
            session.doppelganger.teleport(facingLocation);
            Location particleLocation = session.doppelganger.getLocation().add(0.0, 1.0, 0.0);
            target.spawnParticle(
                    Particle.TRIAL_SPAWNER_DETECTION_OMINOUS,
                    particleLocation,
                    24,
                    0.45,
                    0.45,
                    0.45,
                    0.0
            );
            target.spawnParticle(
                    Particle.CAMPFIRE_COSY_SMOKE,
                    particleLocation,
                    28,
                    0.35,
                    0.5,
                    0.35,
                    0.02
            );
        }
        chaseLoopCountByTarget.merge(session.targetId, 1, Integer::sum);
        resetAfterStageFour(session);
    }

    private void resetAfterAmbush(StalkerSession session) {
        UUID targetId = session.targetId;
        removeSession(targetId);
        stageByTarget.put(targetId, 1);
        chaseLoopCountByTarget.put(targetId, 0);
        scheduleStageSpawn(targetId, STAGE_DELAY_TICKS);
    }

    private void resetAfterStageFour(StalkerSession session) {
        UUID targetId = session.targetId;
        removeSession(targetId);
        stageByTarget.put(targetId, 1);
        scheduleStageSpawn(targetId, STAGE_DELAY_TICKS);
    }

    private boolean chooseNextCopiedPlayer(
            UUID targetId,
            UUID previousCopiedPlayerId,
            UUID robbedPlayerId
    ) {
        List<? extends Player> alternatives = Bukkit.getOnlinePlayers().stream()
                .filter(player -> !player.getUniqueId().equals(previousCopiedPlayerId))
                .filter(player -> !player.getUniqueId().equals(robbedPlayerId))
                .filter(player -> !plugin.isExempt(player))
                .toList();
        if (alternatives.isEmpty()) {
            return false;
        }
        Player nextCopiedPlayer = alternatives.get(ThreadLocalRandom.current().nextInt(alternatives.size()));
        copiedPlayerByTarget.put(targetId, nextCopiedPlayer.getUniqueId());
        return true;
    }

    private void scheduleStageSpawn(UUID targetId, long delayTicks) {
        cancelRespawn(targetId);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            respawnTasks.remove(targetId);
            Player target = plugin.getServer().getPlayer(targetId);
            if (target != null
                    && target.isOnline()
                    && targetManager.isStalkerTarget(targetId)
                    && !isIgnored(target)) {
                spawnStage(target);
            }
        }, delayTicks);
        respawnTasks.put(targetId, task);
    }

    private void cancelRespawn(UUID targetId) {
        BukkitTask task = respawnTasks.remove(targetId);
        if (task != null) {
            task.cancel();
        }
    }

    private void cancelProfileRequest(UUID targetId) {
        pendingProfileRequests.remove(targetId);
    }

    private Player getAttackingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private void removeSession(UUID targetId) {
        StalkerSession session = sessionsByTarget.remove(targetId);
        if (session == null) {
            return;
        }
        sessionsByEntity.remove(session.doppelganger.getUniqueId(), session);
        if (session.stateTask != null) {
            session.stateTask.cancel();
        }
        if (session.stareTask != null) {
            session.stareTask.cancel();
        }
        if (session.chaseTask != null) {
            session.chaseTask.cancel();
        }
        if (session.doppelganger.isValid()) {
            session.doppelganger.remove();
        }
    }

    private enum StalkerState {
        LURK,
        CHASING_AWAY,
        AMBUSHING,
        VANISHING
    }

    private record ForcedFrame(
            UUID playerId,
            String name,
            PlayerProfile profile,
            Location location
    ) {
        private ForcedFrame {
            profile = profile.clone();
            location = location.clone();
        }
    }

    private static final class DoppelgangerSpawn {

        private final Mannequin entity;
        private final UUID copiedPlayerId;
        private final String copiedPlayerName;
        private final Location copiedPlayerLocation;

        private DoppelgangerSpawn(
                Mannequin entity,
                UUID copiedPlayerId,
                String copiedPlayerName,
                Location copiedPlayerLocation
        ) {
            this.entity = entity;
            this.copiedPlayerId = copiedPlayerId;
            this.copiedPlayerName = copiedPlayerName;
            this.copiedPlayerLocation = copiedPlayerLocation;
        }
    }

    private static final class StalkerSession {

        private final UUID targetId;
        private final int stage;
        private final Mannequin doppelganger;
        private final UUID copiedPlayerId;
        private final String copiedPlayerName;
        private final Location copiedPlayerLocation;
        private StalkerState state = StalkerState.LURK;
        private boolean isStaring;
        private BukkitTask stateTask;
        private BukkitTask stareTask;
        private BukkitTask chaseTask;

        private StalkerSession(UUID targetId, int stage, DoppelgangerSpawn spawned) {
            this.targetId = targetId;
            this.stage = stage;
            this.doppelganger = spawned.entity;
            this.copiedPlayerId = spawned.copiedPlayerId;
            this.copiedPlayerName = spawned.copiedPlayerName;
            this.copiedPlayerLocation = spawned.copiedPlayerLocation.clone();
        }
    }
}
