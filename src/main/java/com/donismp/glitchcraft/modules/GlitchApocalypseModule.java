package com.donismp.glitchcraft.modules;

import com.donismp.glitchcraft.GlitchCraft;
import com.donismp.glitchcraft.manager.TargetManager;
import com.destroystokyo.paper.SkinParts;
import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

public final class GlitchApocalypseModule implements Listener {

    private static final long UPDATE_INTERVAL_TICKS = 5L;
    private static final long FAKE_BLOCK_LIFETIME_TICKS = 40L;
    private static final long CLIMAX_DURATION_TICKS = 100L;
    private static final int BLOCKS_PER_PLAYER = 10;
    private static final int BLOCK_RADIUS = 15;
    private static final Material[] DECAY_BLOCKS = {Material.BEDROCK, Material.FIRE, Material.AIR};

    private final GlitchCraft plugin;
    private final Map<FakeBlockKey, FakeBlockChange> fakeBlocks = new HashMap<>();
    private final Map<UUID, Mannequin> skyClones = new HashMap<>();
    private final Map<UUID, BukkitTask> profileRequests = new HashMap<>();
    private final Map<UUID, BukkitTask> climaxTasks = new HashMap<>();
    private final Map<UUID, ClimaxState> climaxStates = new HashMap<>();
    private final Set<UUID> activeTargets = new HashSet<>();
    private BukkitTask activeTask;

    public GlitchApocalypseModule(GlitchCraft plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return activeTask != null && !activeTask.isCancelled();
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!plugin.isExempt(player)) {
                activeTargets.add(player.getUniqueId());
            }
        }
        ensureActiveTask();
    }

    public void startApocalypseSequence() {
        start();
    }

    public void startFor(Player player) {
        if (plugin.isExempt(player)) {
            return;
        }
        activeTargets.add(player.getUniqueId());
        ensureActiveTask();
    }

    public void stopFor(UUID playerId) {
        activeTargets.remove(playerId);
        removeSkyClone(playerId);
        BukkitTask request = profileRequests.remove(playerId);
        if (request != null) {
            request.cancel();
        }
        for (FakeBlockKey key : fakeBlocks.keySet().toArray(FakeBlockKey[]::new)) {
            if (key.playerId.equals(playerId)) {
                restoreFakeBlock(key);
            }
        }
        cancelClimaxTask(playerId);
        restoreClimaxState(playerId, true);
        if (activeTargets.isEmpty() && isActive()) {
            activeTask.cancel();
            activeTask = null;
        }
    }

    private void ensureActiveTask() {
        if (isActive()) {
            return;
        }
        activeTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::runActivePhase,
                1L,
                UPDATE_INTERVAL_TICKS
        );
        plugin.getLogger().info("Glitch Apocalypse active phase started.");
    }

    public void triggerClimax() {
        start();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!plugin.isExempt(player)) {
                beginClimax(player);
            }
        }
    }

    public void triggerClimax(Player player) {
        if (plugin.isExempt(player)) {
            return;
        }
        startFor(player);
        beginClimax(player);
    }

    public void stop() {
        if (activeTask != null) {
            activeTask.cancel();
            activeTask = null;
        }
        for (UUID playerId : skyClones.keySet().toArray(UUID[]::new)) {
            removeSkyClone(playerId);
        }
        activeTargets.clear();
        for (UUID playerId : profileRequests.keySet().toArray(UUID[]::new)) {
            BukkitTask task = profileRequests.remove(playerId);
            if (task != null) {
                task.cancel();
            }
        }
        for (UUID playerId : climaxTasks.keySet().toArray(UUID[]::new)) {
            BukkitTask task = climaxTasks.remove(playerId);
            if (task != null) {
                task.cancel();
            }
        }
        for (UUID playerId : climaxStates.keySet().toArray(UUID[]::new)) {
            restoreClimaxState(playerId, true);
        }
        for (FakeBlockKey key : fakeBlocks.keySet().toArray(FakeBlockKey[]::new)) {
            restoreFakeBlock(key);
        }
        plugin.getLogger().info("Glitch Apocalypse stopped; temporary effects were restored.");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        activeTargets.remove(playerId);
        removeSkyClone(playerId);
        BukkitTask request = profileRequests.remove(playerId);
        if (request != null) {
            request.cancel();
        }
        BukkitTask climaxTask = climaxTasks.remove(playerId);
        if (climaxTask != null) {
            climaxTask.cancel();
        }
        restoreClimaxState(playerId, true);
        fakeBlocks.keySet().stream()
                .filter(key -> key.playerId.equals(playerId))
                .toList()
                .forEach(this::discardFakeBlock);
    }

    private void runActivePhase() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isExempt(player) || !activeTargets.contains(player.getUniqueId())) {
                removeSkyClone(player.getUniqueId());
                restoreFakeBlocksFor(player.getUniqueId());
                continue;
            }
            decayAround(player);
            updateSkyClone(player);
        }
    }

    private void decayAround(Player player) {
        Location center = player.getLocation();
        World world = center.getWorld();
        int selected = 0;
        int attempts = 0;
        while (selected < BLOCKS_PER_PLAYER && attempts < BLOCKS_PER_PLAYER * 8) {
            attempts++;
            int xOffset = ThreadLocalRandom.current().nextInt(-BLOCK_RADIUS, BLOCK_RADIUS + 1);
            int yOffset = ThreadLocalRandom.current().nextInt(-BLOCK_RADIUS, BLOCK_RADIUS + 1);
            int zOffset = ThreadLocalRandom.current().nextInt(-BLOCK_RADIUS, BLOCK_RADIUS + 1);
            if (xOffset * xOffset + yOffset * yOffset + zOffset * zOffset > BLOCK_RADIUS * BLOCK_RADIUS) {
                continue;
            }
            int x = center.getBlockX() + xOffset;
            int y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, center.getBlockY() + yOffset));
            int z = center.getBlockZ() + zOffset;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            selected++;

            Block block = world.getBlockAt(x, y, z);
            Location location = block.getLocation();
            Material fakeType = DECAY_BLOCKS[ThreadLocalRandom.current().nextInt(DECAY_BLOCKS.length)];
            changeFakeBlock(player, location, fakeType.createBlockData());
            player.spawnParticle(Particle.EXPLOSION_EMITTER, location.clone().add(0.5, 0.5, 0.5), 1);
            player.spawnParticle(
                    Particle.BLOCK_CRUMBLE,
                    location.clone().add(0.5, 0.5, 0.5),
                    8,
                    0.35,
                    0.35,
                    0.35,
                    0.05,
                    block.getBlockData()
            );
            player.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 0.35F, 0.8F);
        }
    }

    private void changeFakeBlock(Player player, Location location, org.bukkit.block.data.BlockData data) {
        changeFakeBlock(player, location, data, FAKE_BLOCK_LIFETIME_TICKS);
    }

    private void changeFakeBlock(
            Player player,
            Location location,
            org.bukkit.block.data.BlockData data,
            long lifetimeTicks
    ) {
        FakeBlockKey key = FakeBlockKey.of(player.getUniqueId(), location);
        FakeBlockChange previous = fakeBlocks.get(key);
        if (previous != null) {
            previous.expiryTask.cancel();
        }

        if (plugin.isExempt(player)) {
            player.sendBlockChange(location, location.getBlock().getBlockData());
            fakeBlocks.remove(key);
            return;
        }

        player.sendBlockChange(location, data);
        BukkitTask expiryTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> restoreFakeBlock(key),
                lifetimeTicks
        );
        fakeBlocks.put(key, new FakeBlockChange(expiryTask));
    }

    private void restoreFakeBlock(FakeBlockKey key) {
        FakeBlockChange change = fakeBlocks.remove(key);
        if (change == null) {
            return;
        }
        change.expiryTask.cancel();
        Player player = Bukkit.getPlayer(key.playerId);
        World world = Bukkit.getWorld(key.worldId);
        if (player != null && player.isOnline() && world != null && player.getWorld().equals(world)) {
            Location location = key.toLocation(world);
            player.sendBlockChange(location, location.getBlock().getBlockData());
        }
    }

    private void restoreFakeBlocksFor(UUID playerId) {
        fakeBlocks.keySet().stream()
                .filter(key -> key.playerId.equals(playerId))
                .toList()
                .forEach(this::restoreFakeBlock);
    }

    private void discardFakeBlock(FakeBlockKey key) {
        FakeBlockChange change = fakeBlocks.remove(key);
        if (change != null) {
            change.expiryTask.cancel();
        }
    }

    private void updateSkyClone(Player target) {
        UUID targetId = target.getUniqueId();
        Mannequin clone = skyClones.get(targetId);
        if (clone != null && clone.isValid()) {
            clone.teleport(target.getLocation().clone().add(0.0, 8.0, 0.0));
            target.spawnParticle(
                    Particle.DRAGON_BREATH,
                    clone.getLocation().add(0.0, 1.0, 0.0),
                    8,
                    0.45,
                    0.35,
                    0.45,
                    0.01
            );
            return;
        }
        if (profileRequests.containsKey(targetId)) {
            return;
        }

        List<? extends Player> choices = Bukkit.getOnlinePlayers().stream()
                .filter(candidate -> !candidate.getUniqueId().equals(targetId))
                .filter(candidate -> !plugin.isExempt(candidate))
                .toList();
        if (choices.isEmpty()) {
            return;
        }
        Player framePlayer = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
        PlayerProfile profile = framePlayer.getPlayerProfile();
        UUID framePlayerId = framePlayer.getUniqueId();
        BukkitTask requestMarker = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player currentTarget = Bukkit.getPlayer(targetId);
            Player currentFramePlayer = Bukkit.getPlayer(framePlayerId);
            if (!isActive() || currentTarget == null || currentFramePlayer == null
                    || plugin.isExempt(currentTarget) || plugin.isExempt(currentFramePlayer)) {
                profileRequests.remove(targetId);
                return;
            }
            try {
                profile.update().whenComplete((resolved, error) -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    plugin.getServer().getScheduler().runTask(
                            plugin,
                            () -> spawnSkyClone(targetId, framePlayerId, resolved, error)
                    );
                });
            } catch (RuntimeException exception) {
                profileRequests.remove(targetId);
                plugin.getLogger().warning("Could not request apocalypse clone skin: " + exception.getMessage());
            }
        }, 1L);
        profileRequests.put(targetId, requestMarker);
    }

    private void spawnSkyClone(
            UUID targetId,
            UUID framePlayerId,
            PlayerProfile profile,
            Throwable error
    ) {
        profileRequests.remove(targetId);
        Player target = Bukkit.getPlayer(targetId);
        Player framePlayer = Bukkit.getPlayer(framePlayerId);
        if (!isActive() || target == null || !target.isOnline() || plugin.isExempt(target)
                || framePlayer == null || plugin.isExempt(framePlayer)
                || error != null || profile == null || !hasSkinTextures(profile)) {
            if (error != null) {
                plugin.getLogger().warning("Could not resolve apocalypse clone skin: " + error.getMessage());
            }
            return;
        }

        Location cloneLocation = target.getLocation().clone().add(0.0, 8.0, 0.0);
        try {
            Mannequin clone = cloneLocation.getWorld().spawn(cloneLocation, Mannequin.class, mannequin -> {
                mannequin.setProfile(ResolvableProfile.resolvableProfile(profile.clone()));
                mannequin.setSkinParts(SkinParts.allParts());
                mannequin.setSilent(true);
                mannequin.setCollidable(false);
                mannequin.setInvulnerable(true);
                mannequin.setImmovable(true);
                mannequin.setGravity(false);
                mannequin.setPersistent(false);
                mannequin.setCustomNameVisible(false);
                mannequin.getEquipment().setArmorContents(cloneArmor(framePlayer));
            });
            skyClones.put(targetId, clone);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not spawn apocalypse sky clone: " + exception.getMessage());
        }
    }

    private void beginClimax(Player player) {
        if (plugin.isExempt(player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        cancelClimaxTask(playerId);
        restoreClimaxState(playerId, true);
        ClimaxState state = new ClimaxState(
                player.getLocation().clone(),
                player.getWalkSpeed(),
                player.isInvulnerable(),
                player.hasGravity()
        );
        climaxStates.put(playerId, state);

        Location original = player.getLocation();
        player.playSound(original, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0F, 1.8F);
        player.playSound(original, Sound.BLOCK_ANVIL_DESTROY, 1.0F, 1.5F);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 255, false, false));
        player.setWalkSpeed(0.0F);
        player.setInvulnerable(true);
        player.setGravity(false);
        player.showTitle(Title.title(
                Component.text("FATAL WORLD CORRUPTION", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text("Deleting region files: region/r.0.0.mca...", NamedTextColor.RED)
        ));

        BukkitTask climaxTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            climaxTasks.remove(playerId);
            if (!climaxStates.containsKey(playerId)) {
                return;
            }
            Player current = Bukkit.getPlayer(playerId);
            if (current == null || !current.isOnline() || plugin.isExempt(current)) {
                restoreClimaxState(playerId, false);
                return;
            }
            current.playSound(current.getLocation(), Sound.UI_BUTTON_CLICK, 1.0F, 0.5F);
            teleportToVoidBox(current);
            current.sendMessage(Component.text(
                    "[Server] Connection forcibly closed: World save file missing or corrupted.",
                    NamedTextColor.RED
            ));
            BukkitTask restoreTask = Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> {
                        climaxTasks.remove(playerId);
                        restoreClimaxState(playerId, true);
                    },
                    CLIMAX_DURATION_TICKS
            );
            climaxTasks.put(playerId, restoreTask);
        }, 100L);
        climaxTasks.put(playerId, climaxTask);
    }

    private void teleportToVoidBox(Player player) {
        World world = player.getWorld();
        int voidY = Math.max(world.getMinHeight() + 2, -60);
        Location voidLocation = new Location(
                world,
                player.getLocation().getBlockX() + 0.5,
                voidY,
                player.getLocation().getBlockZ() + 0.5,
                player.getLocation().getYaw(),
                player.getLocation().getPitch()
        );
        player.teleport(voidLocation);
        sendVoidBox(player, voidLocation);
    }

    private void sendVoidBox(Player player, Location center) {
        World world = center.getWorld();
        Set<FakeBlockKey> keys = new HashSet<>();
        int blockY = center.getBlockY();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = -1; y <= 2; y++) {
                    boolean boundary = x == -2 || x == 2 || z == -2 || z == 2 || y == -1 || y == 2;
                    if (!boundary) {
                        continue;
                    }
                    Location location = new Location(
                            world,
                            center.getBlockX() + x,
                            blockY + y,
                            center.getBlockZ() + z
                    );
                    FakeBlockKey key = FakeBlockKey.of(player.getUniqueId(), location);
                    keys.add(key);
                    changeFakeBlock(
                            player,
                            location,
                            Material.BEDROCK.createBlockData(),
                            CLIMAX_DURATION_TICKS + 20L
                    );
                }
            }
        }
        climaxStates.computeIfPresent(player.getUniqueId(), (id, state) -> state.withVoidBlocks(keys));
    }

    private void restoreClimaxState(UUID playerId, boolean returnToOriginalLocation) {
        ClimaxState state = climaxStates.remove(playerId);
        if (state == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        for (FakeBlockKey key : state.voidBlocks) {
            restoreFakeBlock(key);
        }
        if (player == null || !player.isOnline()) {
            return;
        }
        if (returnToOriginalLocation) {
            player.teleport(state.originalLocation);
        }
        player.setWalkSpeed(state.walkSpeed);
        player.setInvulnerable(state.invulnerable);
        player.setGravity(state.gravity);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    private void cancelClimaxTask(UUID playerId) {
        BukkitTask task = climaxTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void removeSkyClone(UUID targetId) {
        Mannequin clone = skyClones.remove(targetId);
        if (clone != null && clone.isValid()) {
            clone.remove();
        }
    }

    private boolean hasSkinTextures(PlayerProfile profile) {
        return profile.getProperties().stream().anyMatch(property ->
                property.getName().equals("textures") && !property.getValue().isBlank()
        );
    }

    private ItemStack[] cloneArmor(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        ItemStack[] copy = new ItemStack[armor.length];
        for (int index = 0; index < armor.length; index++) {
            copy[index] = armor[index] == null ? null : armor[index].clone();
        }
        return copy;
    }

    private record FakeBlockKey(UUID playerId, UUID worldId, int x, int y, int z) {

        private static FakeBlockKey of(UUID playerId, Location location) {
            return new FakeBlockKey(
                    playerId,
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }

        private Location toLocation(World world) {
            return new Location(world, x, y, z);
        }
    }

    private record FakeBlockChange(BukkitTask expiryTask) {
    }

    private record ClimaxState(
            Location originalLocation,
            float walkSpeed,
            boolean invulnerable,
            boolean gravity,
            Set<FakeBlockKey> voidBlocks
    ) {

        private ClimaxState(Location originalLocation, float walkSpeed, boolean invulnerable, boolean gravity) {
            this(originalLocation, walkSpeed, invulnerable, gravity, Set.of());
        }

        private ClimaxState withVoidBlocks(Set<FakeBlockKey> blocks) {
            return new ClimaxState(originalLocation, walkSpeed, invulnerable, gravity, Set.copyOf(blocks));
        }
    }
}
