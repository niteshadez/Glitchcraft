package com.donismp.glitchcraft.modules;

import com.donismp.glitchcraft.GlitchCraft;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

public final class BlockGlitchModule implements Listener {

    private static final long UPDATE_INTERVAL_TICKS = 20L;
    private static final int RAY_TRACE_DISTANCE = 8;
    private static final double MIN_BLOCK_DISTANCE = 3.0;
    private static final long MIN_FAKE_BLOCK_LIFETIME_TICKS = 60L;
    private static final long MAX_FAKE_BLOCK_LIFETIME_TICKS = 120L;
    private static final long MIN_NEXT_GLITCH_DELAY_TICKS = 15L * 20L;
    private static final long MAX_NEXT_GLITCH_DELAY_TICKS = 45L * 20L;
    private static final double GHOST_DROP_CHANCE = 0.10;

    private static final Material[] NATURAL_BLOCKS = {
            Material.DIRT,
            Material.GRASS_BLOCK,
            Material.DIRT_PATH,
            Material.GRAVEL,
            Material.SAND,
            Material.RED_SAND,
            Material.STONE,
            Material.COBBLESTONE,
            Material.MOSSY_COBBLESTONE,
            Material.COAL_ORE,
            Material.IRON_ORE,
            Material.COPPER_ORE,
            Material.GOLD_ORE,
            Material.REDSTONE_ORE,
            Material.EMERALD_ORE,
            Material.LAPIS_ORE,
            Material.DIAMOND_ORE,
            Material.DEEPSLATE_COAL_ORE,
            Material.DEEPSLATE_IRON_ORE,
            Material.DEEPSLATE_COPPER_ORE,
            Material.DEEPSLATE_GOLD_ORE,
            Material.DEEPSLATE_REDSTONE_ORE,
            Material.DEEPSLATE_EMERALD_ORE,
            Material.DEEPSLATE_LAPIS_ORE,
            Material.DEEPSLATE_DIAMOND_ORE,
            Material.NETHER_GOLD_ORE,
            Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS,
            Material.OAK_LOG,
            Material.STRIPPED_OAK_LOG,
            Material.OAK_WOOD,
            Material.STRIPPED_OAK_WOOD,
            Material.SPRUCE_LOG,
            Material.STRIPPED_SPRUCE_LOG,
            Material.SPRUCE_WOOD,
            Material.STRIPPED_SPRUCE_WOOD,
            Material.BIRCH_LOG,
            Material.STRIPPED_BIRCH_LOG,
            Material.BIRCH_WOOD,
            Material.STRIPPED_BIRCH_WOOD,
            Material.JUNGLE_LOG,
            Material.STRIPPED_JUNGLE_LOG,
            Material.JUNGLE_WOOD,
            Material.STRIPPED_JUNGLE_WOOD,
            Material.ACACIA_LOG,
            Material.STRIPPED_ACACIA_LOG,
            Material.ACACIA_WOOD,
            Material.STRIPPED_ACACIA_WOOD,
            Material.DARK_OAK_LOG,
            Material.STRIPPED_DARK_OAK_LOG,
            Material.DARK_OAK_WOOD,
            Material.STRIPPED_DARK_OAK_WOOD,
            Material.MANGROVE_LOG,
            Material.STRIPPED_MANGROVE_LOG,
            Material.MANGROVE_WOOD,
            Material.STRIPPED_MANGROVE_WOOD,
            Material.CHERRY_LOG,
            Material.STRIPPED_CHERRY_LOG,
            Material.CHERRY_WOOD,
            Material.STRIPPED_CHERRY_WOOD,
            Material.PALE_OAK_LOG,
            Material.STRIPPED_PALE_OAK_LOG,
            Material.PALE_OAK_WOOD,
            Material.STRIPPED_PALE_OAK_WOOD,
            Material.CRIMSON_STEM,
            Material.STRIPPED_CRIMSON_STEM,
            Material.CRIMSON_HYPHAE,
            Material.STRIPPED_CRIMSON_HYPHAE,
            Material.WARPED_STEM,
            Material.STRIPPED_WARPED_STEM,
            Material.WARPED_HYPHAE,
            Material.STRIPPED_WARPED_HYPHAE,
            Material.BAMBOO_BLOCK,
            Material.STRIPPED_BAMBOO_BLOCK,
            Material.OAK_LEAVES,
            Material.SPRUCE_LEAVES,
            Material.BIRCH_LEAVES,
            Material.JUNGLE_LEAVES,
            Material.ACACIA_LEAVES,
            Material.DARK_OAK_LEAVES,
            Material.MANGROVE_LEAVES,
            Material.CHERRY_LEAVES,
            Material.PALE_OAK_LEAVES,
            Material.AZALEA_LEAVES,
            Material.FLOWERING_AZALEA_LEAVES
    };

    static {
        for (Material material : NATURAL_BLOCKS) {
            if (!material.isBlock() || !material.isSolid()) {
                throw new IllegalStateException(
                        "Block glitch replacement must be a solid vanilla block: " + material
                );
            }
        }
    }

    private final GlitchCraft plugin;
    private final Set<UUID> stageOneTargets = new HashSet<>();
    private final Map<UUID, Long> nextGlitchTick = new HashMap<>();
    private final Map<UUID, ActiveFakeBlock> activeFakeBlocks = new HashMap<>();
    private BukkitTask updateTask;

    public BlockGlitchModule(GlitchCraft plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (updateTask != null && !updateTask.isCancelled()) {
            return;
        }
        updateTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tick,
                UPDATE_INTERVAL_TICKS,
                UPDATE_INTERVAL_TICKS
        );
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (UUID playerId : stageOneTargets.toArray(UUID[]::new)) {
            disableTarget(playerId);
        }
    }

    public void enableTarget(Player player) {
        if (plugin.isExempt(player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        stageOneTargets.add(playerId);
        nextGlitchTick.computeIfAbsent(playerId, ignored -> nextRandomGlitchTick());
    }

    public boolean triggerGlitch(Player player) {
        if (!isStageOneEligible(player)) {
            return false;
        }
        enableTarget(player);
        nextGlitchTick.put(player.getUniqueId(), nextRandomGlitchTick());
        return createFakeBlock(player);
    }

    public void disableTarget(UUID playerId) {
        stageOneTargets.remove(playerId);
        nextGlitchTick.remove(playerId);
        restoreFakeBlock(playerId);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isIgnored(player)) {
            restoreFakeBlock(player.getUniqueId());
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked != null) {
            restoreFakeBlockAt(player, clicked);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        restoreFakeBlockAt(player, event.getBlock());
        if (!isStageOneEligible(player) || !stageOneTargets.contains(playerId)) {
            return;
        }

        Material brokenType = event.getBlock().getType();
        if (!isNaturalOreOrLog(brokenType)
                || ThreadLocalRandom.current().nextDouble() >= GHOST_DROP_CHANCE) {
            return;
        }

        Location effectLocation = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        player.playSound(effectLocation, Sound.BLOCK_STONE_BREAK, 0.8F, 0.75F);
        player.spawnParticle(
                Particle.ITEM,
                effectLocation,
                1,
                0.15,
                0.2,
                0.15,
                0.0,
                new ItemStack(Material.COBBLESTONE)
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        disableTarget(playerId);
    }

    private void tick() {
        long currentTick = plugin.getServer().getCurrentTick();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (plugin.isExempt(player)) {
                disableTarget(playerId);
                continue;
            }
            if (!stageOneTargets.contains(playerId)) {
                continue;
            }
            if (plugin.getProgressionManager().getPlayerStage(playerId) != 1) {
                disableTarget(playerId);
                continue;
            }
            if (plugin.getTargetManager().isVanished(player)) {
                restoreFakeBlock(playerId);
                continue;
            }

            ActiveFakeBlock active = activeFakeBlocks.get(playerId);
            if (active != null && (active.worldId == null
                    || !player.getWorld().getUID().equals(active.worldId))) {
                restoreFakeBlock(playerId);
            }
            if (currentTick >= nextGlitchTick.getOrDefault(playerId, Long.MAX_VALUE)) {
                nextGlitchTick.put(playerId, nextRandomGlitchTick());
                createFakeBlock(player);
            }
        }
    }

    private boolean createFakeBlock(Player player) {
        if (!isStageOneEligible(player) || !stageOneTargets.contains(player.getUniqueId())) {
            return false;
        }
        Block targetBlock = findBlockInSight(player);
        if (targetBlock == null) {
            return false;
        }
        restoreFakeBlock(player.getUniqueId());

        Location location = targetBlock.getLocation();
        BlockData fakeData = NATURAL_BLOCKS[
                ThreadLocalRandom.current().nextInt(NATURAL_BLOCKS.length)
        ].createBlockData();
        player.sendBlockChange(location, fakeData);
        long lifetime = ThreadLocalRandom.current().nextLong(
                MIN_FAKE_BLOCK_LIFETIME_TICKS,
                MAX_FAKE_BLOCK_LIFETIME_TICKS + 1L
        );
        BukkitTask expiryTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> restoreFakeBlock(player.getUniqueId()),
                lifetime
        );
        activeFakeBlocks.put(
                player.getUniqueId(),
                new ActiveFakeBlock(
                        location.getWorld().getUID(),
                        location.getBlockX(),
                        location.getBlockY(),
                        location.getBlockZ(),
                        expiryTask
                )
        );
        return true;
    }

    private Block findBlockInSight(Player player) {
        var result = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                RAY_TRACE_DISTANCE,
                FluidCollisionMode.NEVER,
                false
        );
        if (result == null || result.getHitBlock() == null || result.getHitPosition() == null) {
            return null;
        }
        double distanceSquared = result.getHitPosition().distanceSquared(player.getEyeLocation().toVector());
        if (distanceSquared < MIN_BLOCK_DISTANCE * MIN_BLOCK_DISTANCE) {
            return null;
        }

        Block block = result.getHitBlock();
        if (!block.getType().isBlock() || block.isEmpty() || block.isLiquid()) {
            return null;
        }
        return block;
    }

    private boolean isNaturalOreOrLog(Material material) {
        return switch (material) {
            case COAL_ORE, DEEPSLATE_COAL_ORE,
                    IRON_ORE, DEEPSLATE_IRON_ORE,
                    COPPER_ORE, DEEPSLATE_COPPER_ORE,
                    GOLD_ORE, DEEPSLATE_GOLD_ORE,
                    REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE,
                    EMERALD_ORE, DEEPSLATE_EMERALD_ORE,
                    LAPIS_ORE, DEEPSLATE_LAPIS_ORE,
                    DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE,
                    NETHER_GOLD_ORE, NETHER_QUARTZ_ORE,
                    OAK_LOG, SPRUCE_LOG, BIRCH_LOG, JUNGLE_LOG,
                    ACACIA_LOG, DARK_OAK_LOG, MANGROVE_LOG, CHERRY_LOG, PALE_OAK_LOG,
                    CRIMSON_STEM, WARPED_STEM -> true;
            default -> false;
        };
    }

    private long nextRandomGlitchTick() {
        long delay = ThreadLocalRandom.current().nextLong(
                MIN_NEXT_GLITCH_DELAY_TICKS,
                MAX_NEXT_GLITCH_DELAY_TICKS + 1L
        );
        return plugin.getServer().getCurrentTick() + delay;
    }

    private boolean isIgnored(Player player) {
        return plugin.isExempt(player) || plugin.getTargetManager().isVanished(player);
    }

    private boolean isStageOneEligible(Player player) {
        return !plugin.isExempt(player)
                && plugin.getProgressionManager().getPlayerStage(player.getUniqueId()) == 1
                && !plugin.getTargetManager().isVanished(player);
    }

    private void restoreFakeBlockAt(Player player, Block block) {
        ActiveFakeBlock active = activeFakeBlocks.get(player.getUniqueId());
        if (active == null
                || !active.worldId.equals(block.getWorld().getUID())
                || active.x != block.getX()
                || active.y != block.getY()
                || active.z != block.getZ()) {
            return;
        }
        restoreFakeBlock(player.getUniqueId());
    }

    private void restoreFakeBlock(UUID playerId) {
        ActiveFakeBlock active = activeFakeBlocks.remove(playerId);
        if (active == null) {
            return;
        }
        active.expiryTask.cancel();
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        World world = Bukkit.getWorld(active.worldId);
        if (world == null || !player.getWorld().equals(world)) {
            return;
        }
        Location location = new Location(world, active.x, active.y, active.z);
        player.sendBlockChange(location, location.getBlock().getBlockData());
    }

    private record ActiveFakeBlock(
            UUID worldId,
            int x,
            int y,
            int z,
            BukkitTask expiryTask
    ) {
    }
}
