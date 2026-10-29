package com.donismp.glitchcraft.gui;

import com.donismp.glitchcraft.GlitchCraft;
import com.donismp.glitchcraft.manager.TargetManager;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

public final class ControlGUI implements Listener {

    private final TargetManager targetManager;

    public ControlGUI(TargetManager targetManager) {
        this.targetManager = targetManager;
    }

    public static void openGUI(Player admin, Player target) {
        TargetManager targetManager = GlitchCraft.getInstance().getTargetManager();
        ControlMenu holder = new ControlMenu(target.getUniqueId());
        Inventory inventory = Bukkit.createInventory(
                holder,
                27,
                Component.text("GlitchCraft Panel: " + target.getName())
        );
        holder.setInventory(inventory);
        inventory.setItem(11, createToggleItem(
                Material.REDSTONE_BLOCK,
                "Quantum Glitch",
                targetManager.isGlitchTarget(target.getUniqueId())
        ));
        inventory.setItem(13, createToggleItem(
                Material.CHEST,
                "Mimic / Gravity Mode",
                targetManager.isMimicTarget(target.getUniqueId())
        ));
        inventory.setItem(15, createToggleItem(
                Material.WITHER_SKELETON_SKULL,
                "Doppelgänger Stalker",
                targetManager.isStalkerTarget(target.getUniqueId())
        ));
        admin.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ControlMenu menu)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player admin) || !admin.isOp()) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        UUID targetId = menu.targetId();
        switch (event.getRawSlot()) {
            case 11 -> targetManager.toggleGlitchTarget(targetId);
            case 13 -> targetManager.toggleMimicTarget(targetId);
            case 15 -> targetManager.toggleStalkerTarget(targetId);
            default -> {
                return;
            }
        }

        refreshInventory(event.getView().getTopInventory(), targetId);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ControlMenu) {
            event.setCancelled(true);
        }
    }

    private void refreshInventory(Inventory inventory, UUID targetId) {
        inventory.setItem(11, createToggleItem(
                Material.REDSTONE_BLOCK,
                "Quantum Glitch",
                targetManager.isGlitchTarget(targetId)
        ));
        inventory.setItem(13, createToggleItem(
                Material.CHEST,
                "Mimic / Gravity Mode",
                targetManager.isMimicTarget(targetId)
        ));
        inventory.setItem(15, createToggleItem(
                Material.WITHER_SKELETON_SKULL,
                "Doppelgänger Stalker",
                targetManager.isStalkerTarget(targetId)
        ));
    }

    private static ItemStack createToggleItem(Material material, String name, boolean enabled) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        meta.displayName(Component.text(name, NamedTextColor.WHITE));
        meta.lore(java.util.List.of(Component.text(
                enabled ? "Enabled" : "Disabled",
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED
        )));
        item.setItemMeta(meta);
        return item;
    }

    private static final class ControlMenu implements InventoryHolder {

        private final UUID targetId;
        private Inventory inventory;

        private ControlMenu(UUID targetId) {
            this.targetId = targetId;
        }

        private UUID targetId() {
            return targetId;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Objects.requireNonNull(inventory, "Inventory has not been created.");
        }
    }
}
