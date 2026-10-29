package com.donismp.glitchcraft.modules;

import com.donismp.glitchcraft.GlitchCraft;
import com.donismp.glitchcraft.manager.TargetManager;
import io.papermc.paper.block.TileStateInventoryHolder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class MimicModule implements Listener {

    private static final long MIMIC_LIFETIME_TICKS = 200L;
    private static final long UPDATE_INTERVAL_TICKS = 5L;
    private static final double ESCAPE_DISTANCE = 10.0;
    private static final double PATHFINDER_SPEED = 1.0;

    private final GlitchCraft plugin;
    private final TargetManager targetManager;
    private final Map<UUID, MimicSession> sessionsByZombie = new HashMap<>();
    private final Map<UUID, MimicSession> sessionsByPlayer = new HashMap<>();
    private final Map<UUID, Long> mimicCooldowns = new HashMap<>();
    private final Map<UUID, Long> lastChestInteractTick = new HashMap<>();

    public MimicModule(GlitchCraft plugin, TargetManager targetManager) {
        this.plugin = plugin;
        this.targetManager = targetManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!isEligible(player)) {
            return;
        }

        Block chest = event.getClickedBlock();
        if (chest == null
                || (chest.getType() != Material.CHEST && chest.getType() != Material.TRAPPED_CHEST)) {
            return;
        }
        lastChestInteractTick.put(playerId, (long) plugin.getServer().getCurrentTick());
        if (tryTriggerMimic(player, chest)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !isEligible(player)) {
            return;
        }
        Location inventoryLocation = event.getInventory().getLocation();
        if (inventoryLocation == null) {
            return;
        }
        Block chest = inventoryLocation.getBlock();
        if (chest.getType() != Material.CHEST && chest.getType() != Material.TRAPPED_CHEST) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (lastChestInteractTick.getOrDefault(playerId, Long.MIN_VALUE)
                == plugin.getServer().getCurrentTick()) {
            return;
        }
        if (tryTriggerMimic(player, chest)) {
            event.setCancelled(true);
        }
    }

    private boolean tryTriggerMimic(Player player, Block chest) {
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < mimicCooldowns.getOrDefault(playerId, 0L)
                || ThreadLocalRandom.current().nextDouble() >= 0.06
                || !isEligible(player)
                || (chest.getType() != Material.CHEST && chest.getType() != Material.TRAPPED_CHEST)) {
            return false;
        }

        cleanup(sessionsByPlayer.get(playerId), true);

        Location chestLocation = chest.getLocation();
        Material originalMaterial = chest.getType();
        BlockData originalBlockData = chest.getBlockData().clone();
        BlockState originalBlockState = chest.getState();
        if (!(originalBlockState instanceof Chest chestState)) {
            plugin.getLogger().warning("Could not snapshot chest tile state at " + chestLocation);
            return false;
        }
        Inventory snapshotInventory = chestState.getSnapshotInventory();
        ItemStack[] originalContents = cloneContents(snapshotInventory.getContents());
        Chunk chestChunk = chest.getChunk();
        boolean addedChunkTicket = chestChunk.addPluginChunkTicket(plugin);
        chestState.getBlockInventory().clear();
        chest.setType(Material.AIR, false);
        player.playSound(chestLocation, Sound.BLOCK_WOOD_BREAK, 1.0F, 0.8F);

        Zombie zombie;
        try {
            zombie = chest.getWorld().spawn(chestLocation.clone().add(0.5, 0.1, 0.5), Zombie.class, mimic -> {
                mimic.setInvisible(true);
                mimic.setCanPickupItems(false);
                mimic.setRemoveWhenFarAway(false);
                mimic.setPersistent(false);
                mimic.setSilent(true);
                mimic.setCanBreakDoors(false);
                mimic.addPotionEffect(new PotionEffect(
                        PotionEffectType.SPEED,
                        PotionEffect.INFINITE_DURATION,
                        2,
                        true,
                        false
                ));
                mimic.addPotionEffect(new PotionEffect(
                        PotionEffectType.JUMP_BOOST,
                        PotionEffect.INFINITE_DURATION,
                        3,
                        true,
                        false
                ));
                mimic.getEquipment().setHelmet(new ItemStack(Material.CHEST));
                mimic.getEquipment().setHelmetDropChance(0.0F);
            });
        } catch (RuntimeException exception) {
            boolean restored = restoreChest(
                    chest,
                    originalMaterial,
                    originalBlockData,
                    originalBlockState,
                    originalContents
            );
            if (addedChunkTicket && restored) {
                chestChunk.removePluginChunkTicket(plugin);
            }
            throw exception;
        }

        mimicCooldowns.put(playerId, now + 90_000L);
        MimicSession session = new MimicSession(
                playerId,
                zombie,
                chest,
                chestLocation,
                chestChunk,
                addedChunkTicket,
                originalMaterial,
                originalBlockData,
                originalBlockState,
                originalContents
        );
        sessionsByZombie.put(zombie.getUniqueId(), session);
        sessionsByPlayer.put(playerId, session);

        session.updateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Player target = plugin.getServer().getPlayer(session.playerId);
            if (target == null
                    || !target.isOnline()
                    || !targetManager.isMimicTarget(session.playerId)
                    || targetManager.isVanished(target)
                    || plugin.isExempt(target)
                    || !session.zombie.isValid()) {
                cleanup(session, true);
                return;
            }

            moveAwayFrom(session.zombie, target);
        }, UPDATE_INTERVAL_TICKS, UPDATE_INTERVAL_TICKS);

        session.expiryTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> cleanup(session, true),
                MIMIC_LIFETIME_TICKS
        );
        return true;
    }

    @EventHandler
    public void onZombieDeath(EntityDeathEvent event) {
        MimicSession session = sessionsByZombie.get(event.getEntity().getUniqueId());
        if (session == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        cleanup(session, true);
    }

    public void cleanupAll() {
        for (MimicSession session : sessionsByZombie.values().toArray(MimicSession[]::new)) {
            cleanup(session, true);
        }
        mimicCooldowns.clear();
        lastChestInteractTick.clear();
    }

    public void disableTarget(UUID playerId) {
        targetManager.removeMimicTarget(playerId);
        cleanup(sessionsByPlayer.get(playerId), true);
        mimicCooldowns.remove(playerId);
        lastChestInteractTick.remove(playerId);
    }

    private boolean isEligible(Player player) {
        return targetManager.isMimicTarget(player.getUniqueId())
                && !targetManager.isVanished(player)
                && !plugin.isExempt(player)
                && plugin.getProgressionManager().getPlayerStage(player) >= 2;
    }

    private void moveAwayFrom(Zombie zombie, Player player) {
        Vector away = zombie.getLocation().toVector().subtract(player.getLocation().toVector());
        away.setY(0);
        if (away.lengthSquared() == 0) {
            away = player.getLocation().getDirection().setY(0).multiply(-1);
        }
        if (away.lengthSquared() == 0) {
            away = new Vector(0, 0, 1);
        }
        Location destination = zombie.getLocation().clone().add(away.normalize().multiply(ESCAPE_DISTANCE));
        zombie.getPathfinder().moveTo(destination, PATHFINDER_SPEED);
    }

    private void cleanup(MimicSession session, boolean restoreChest) {
        if (session == null || session.cleanedUp) {
            return;
        }
        session.cleanedUp = true;
        sessionsByZombie.remove(session.zombie.getUniqueId(), session);
        sessionsByPlayer.remove(session.playerId, session);
        if (session.updateTask != null) {
            session.updateTask.cancel();
        }
        if (session.expiryTask != null) {
            session.expiryTask.cancel();
        }
        if (session.zombie.isValid()) {
            session.zombie.remove();
        }

        if (restoreChest
                && restoreChest(
                        session.chest,
                        session.originalMaterial,
                        session.originalBlockData,
                        session.originalBlockState,
                        session.originalContents
                )) {
            if (session.addedChunkTicket) {
                session.chestChunk.removePluginChunkTicket(plugin);
            }
            Player player = plugin.getServer().getPlayer(session.playerId);
            if (player != null && player.isOnline()
                    && player.getWorld().equals(session.chestLocation.getWorld())) {
                player.sendBlockChange(session.chestLocation, session.chest.getBlockData());
                player.playSound(session.chestLocation, Sound.BLOCK_CHEST_CLOSE, 1.0F, 0.8F);
            }
        }
    }

    private boolean restoreChest(
            Block block,
            Material originalMaterial,
            BlockData originalBlockData,
            BlockState originalBlockState,
            ItemStack[] originalContents
    ) {
        block.setType(originalMaterial, false);
        block.setBlockData(originalBlockData, false);

        if (!(originalBlockState instanceof TileStateInventoryHolder tileState)) {
            plugin.getLogger().warning("Saved chest state has no inventory at " + block.getLocation());
            return false;
        }
        tileState.getSnapshotInventory().setContents(cloneContents(originalContents));
        if (!tileState.update(true, false)) {
            plugin.getLogger().warning("Could not restore chest state at " + block.getLocation());
            return false;
        }
        return true;
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    private static final class MimicSession {

        private final UUID playerId;
        private final Zombie zombie;
        private final Block chest;
        private final Location chestLocation;
        private final Chunk chestChunk;
        private final boolean addedChunkTicket;
        private final Material originalMaterial;
        private final BlockData originalBlockData;
        private final BlockState originalBlockState;
        private final ItemStack[] originalContents;
        private BukkitTask updateTask;
        private BukkitTask expiryTask;
        private boolean cleanedUp;

        private MimicSession(
                UUID playerId,
                Zombie zombie,
                Block chest,
                Location chestLocation,
                Chunk chestChunk,
                boolean addedChunkTicket,
                Material originalMaterial,
                BlockData originalBlockData,
                BlockState originalBlockState,
                ItemStack[] originalContents
        ) {
            this.playerId = playerId;
            this.zombie = zombie;
            this.chest = chest;
            this.chestLocation = chestLocation;
            this.chestChunk = chestChunk;
            this.addedChunkTicket = addedChunkTicket;
            this.originalMaterial = originalMaterial;
            this.originalBlockData = originalBlockData;
            this.originalBlockState = originalBlockState;
            this.originalContents = originalContents;
        }
    }
}
