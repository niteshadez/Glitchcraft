package com.donismp.glitchcraft.gui;

import com.donismp.glitchcraft.GlitchCraft;
import java.util.ArrayList;
import java.util.List;
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

public final class StalkerDebugGUI implements Listener {

    public static void open(Player admin, Player target) {
        DebugMenu holder = new DebugMenu(target.getUniqueId());
        Inventory inventory = Bukkit.createInventory(
                holder,
                27,
                Component.text("Stalker Debug: " + target.getName(), NamedTextColor.DARK_RED)
        );
        holder.setInventory(inventory);
        refresh(inventory, target);
        admin.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof DebugMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player admin) || !admin.isOp()
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        Player target = Bukkit.getPlayer(menu.targetId());
        if (target == null) {
            admin.sendMessage(Component.text("The debug target is no longer online.", NamedTextColor.RED));
            admin.closeInventory();
            return;
        }

        switch (event.getRawSlot()) {
            case 11 -> {
                boolean restarted = GlitchCraft.getInstance().getStalkerModule().restartForDebug(target);
                admin.sendMessage(Component.text(
                        restarted
                                ? "Stalker restart requested for " + target.getName() + "."
                                : "Could not restart Stalker: the target is vanished or exempt.",
                        restarted ? NamedTextColor.YELLOW : NamedTextColor.RED
                ));
                open(admin, target);
            }
            case 15 -> {
                GlitchCraft.getInstance().getStalkerModule().spawnVisualTest(target, admin);
                admin.sendMessage(Component.text("Spawning a temporary skin test clone near you.",
                        NamedTextColor.YELLOW));
                open(admin, target);
            }
            case 22 -> open(admin, target);
            case 26 -> admin.closeInventory();
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof DebugMenu) {
            event.setCancelled(true);
        }
    }

    private static void refresh(Inventory inventory, Player target) {
        List<Component> details = GlitchCraft.getInstance().getStalkerModule().getDebugStatus(target);
        inventory.setItem(4, item(Material.PLAYER_HEAD, "Target: " + target.getName(), details));
        inventory.setItem(11, item(
                Material.REPEATER,
                "Restart Stalker",
                List.of(Component.text("Recreate the active Stalker session.", NamedTextColor.GRAY))
        ));
        inventory.setItem(15, item(
                Material.SPYGLASS,
                "Spawn Visual Skin Test",
                List.of(
                        Component.text("Spawns this target's skin beside you.", NamedTextColor.GRAY),
                        Component.text("Automatically removes it after 20 seconds.", NamedTextColor.GRAY)
                )
        ));
        inventory.setItem(22, item(
                Material.CLOCK,
                "Refresh Diagnostics",
                List.of(Component.text("Update profile, entity and task status.", NamedTextColor.GRAY))
        ));
        inventory.setItem(26, item(
                Material.BARRIER,
                "Close",
                List.of(Component.text("Close this menu.", NamedTextColor.GRAY))
        ));
    }

    private static ItemStack item(Material material, String name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = Objects.requireNonNull(stack.getItemMeta());
        meta.displayName(Component.text(name, NamedTextColor.WHITE));
        meta.lore(new ArrayList<>(lore));
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class DebugMenu implements InventoryHolder {

        private final UUID targetId;
        private Inventory inventory;

        private DebugMenu(UUID targetId) {
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
