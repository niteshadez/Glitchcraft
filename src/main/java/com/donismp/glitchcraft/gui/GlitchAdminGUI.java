package com.donismp.glitchcraft.gui;

import com.donismp.glitchcraft.GlitchCraft;
import com.donismp.glitchcraft.managers.ProgressionManager;
import com.donismp.glitchcraft.managers.ProgressionManager.MockPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

public final class GlitchAdminGUI implements Listener {

    private static final int PAGE_SIZE = 36;
    private static final int[] PLAYER_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44
    };

    public GlitchAdminGUI() {
    }

    public static void open(Player admin) {
        open(admin, 0);
    }

    private static void open(Player admin, int page) {
        AdminMenu holder = new AdminMenu(page);
        Inventory inventory = Bukkit.createInventory(
                holder,
                54,
                Component.text("GlitchCraft Control Panel", NamedTextColor.DARK_RED)
        );
        holder.setInventory(inventory);
        refresh(inventory, page);
        admin.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof AdminMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player admin)
                || !admin.hasPermission("glitchcraft.admin")
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        GlitchCraft plugin = GlitchCraft.getInstance();
        ProgressionManager progression = plugin.getProgressionManager();
        int slot = event.getRawSlot();
        if (slot >= 48 && slot <= 50) {
            if (event.getClick() != ClickType.LEFT) {
                return;
            }
            switch (slot) {
                case 48 -> {
                    progression.startEngine();
                    admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 1.0F, 1.5F);
                }
                case 49 -> {
                    progression.restartEngine();
                    admin.playSound(admin.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0F, 1.0F);
                }
                case 50 -> {
                    progression.stopEngine();
                    admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 1.0F, 0.5F);
                }
                default -> {
                    return;
                }
            }
            refresh(event.getView().getTopInventory(), menu.page);
            return;
        }
        if (slot == 0) {
            progression.toggleAutoProgression();
            refresh(event.getView().getTopInventory(), menu.page);
            return;
        }
        if (slot == 4) {
            int currentCap = progression.getMaxGlobalStage();
            int nextCap = event.getClick() == ClickType.RIGHT
                    ? Math.max(1, currentCap - 1)
                    : Math.min(5, currentCap + 1);
            progression.setMaxGlobalStage(nextCap);
            refresh(event.getView().getTopInventory(), menu.page);
            return;
        }
        if (slot == 45) {
            open(admin, Math.max(0, menu.page - 1));
            return;
        }
        if (slot == 53) {
            int maximumPage = maximumPage();
            open(admin, Math.min(maximumPage, menu.page + 1));
            return;
        }

        int playerIndex = indexForSlot(slot);
        if (playerIndex < 0) {
            return;
        }
        int selectedIndex = playerIndex;
        if (selectedIndex >= menu.entries.size()) {
            return;
        }
        GuiPlayerEntry selected = menu.entries.get(selectedIndex);
        Player realPlayer = selected.mock ? null : Bukkit.getPlayerExact(selected.name);
        if (!selected.mock && (realPlayer == null || plugin.isExempt(realPlayer))) {
            refresh(event.getView().getTopInventory(), menu.page);
            return;
        }
        int stage = selected.mock
                ? progression.getMockPlayerStage(selected.name)
                : progression.getPlayerStage(realPlayer);
        if (event.isShiftClick()) {
            boolean triggered;
            if (selected.mock) {
                MockPlayer mock = progression.getMockPlayer(selected.name);
                triggered = mock != null && plugin.getStalkerModule().forceAmbushWithFrame(
                        admin,
                        progression.getMockPlayerId(selected.name),
                        mock.name(),
                        mock.profile(),
                        admin.getLocation()
                );
            } else {
                triggered = progression.triggerPlayerEvent(realPlayer);
            }
            admin.sendMessage(Component.text(
                    triggered
                            ? "Triggered an event for " + selected.name + "."
                            : "Could not trigger an event for " + selected.name + ".",
                    triggered ? NamedTextColor.GREEN : NamedTextColor.RED
            ));
        } else if (event.getClick() == ClickType.RIGHT) {
            if (selected.mock) {
                progression.setMockPlayerStage(selected.name, Math.max(0, stage - 1));
            } else {
                progression.setPlayerStage(realPlayer, Math.max(0, stage - 1));
            }
        } else if (event.getClick() == ClickType.LEFT) {
            if (selected.mock) {
                progression.setMockPlayerStage(selected.name, Math.min(5, stage + 1));
            } else {
                progression.setPlayerStage(realPlayer, Math.min(5, stage + 1));
            }
        }
        refresh(event.getView().getTopInventory(), menu.page);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof AdminMenu) {
            event.setCancelled(true);
        }
    }

    private static void refresh(Inventory inventory, int page) {
        GlitchCraft plugin = GlitchCraft.getInstance();
        ProgressionManager progression = plugin.getProgressionManager();
        inventory.clear();

        boolean autoEnabled = progression.isAutoProgressionEnabled();
        inventory.setItem(0, item(
                autoEnabled ? Material.GREEN_WOOL : Material.RED_WOOL,
                "Auto-Progression: " + (autoEnabled ? "ON" : "OFF"),
                List.of(Component.text("Click to toggle automatic stage progression.", NamedTextColor.GRAY))
        ));
        inventory.setItem(4, item(
                Material.BEACON,
                "Global Stage Cap: " + progression.getMaxGlobalStage(),
                List.of(
                        Component.text("Left-click to increase the cap.", NamedTextColor.GRAY),
                        Component.text("Right-click to decrease the cap.", NamedTextColor.GRAY)
                )
        ));
        inventory.setItem(48, masterAction(
                Material.GREEN_WOOL,
                "Start Progression Engine",
                NamedTextColor.GREEN,
                List.of(
                        Component.text("Status: " + (autoEnabled ? "RUNNING" : "STOPPED"), NamedTextColor.GRAY),
                        Component.text(
                                "Click to start automated progression with 1-2 hour entry delays.",
                                NamedTextColor.YELLOW
                        )
                )
        ));
        inventory.setItem(49, masterAction(
                Material.YELLOW_WOOL,
                "Restart Engine & Wipe Data",
                NamedTextColor.GOLD,
                List.of(
                        Component.text("Wipes all saved player stages in players.yml.", NamedTextColor.RED),
                        Component.text(
                                "Resets all players to Stage 0 with fresh 1-2 hour entry delays.",
                                NamedTextColor.YELLOW
                        )
                )
        ));
        inventory.setItem(50, masterAction(
                Material.RED_WOOL,
                "Stop Engine & Clear Effects",
                NamedTextColor.RED,
                List.of(
                        Component.text(
                                "Halts all background loops, cancels apocalypse countdowns,",
                                NamedTextColor.GRAY
                        ),
                        Component.text(
                                "despawns doppelgängers, and restores walk speeds.",
                                NamedTextColor.GRAY
                        )
                )
        ));

        List<GuiPlayerEntry> players = eligiblePlayers();
        if (!(inventory.getHolder(false) instanceof AdminMenu menu)) {
            return;
        }
        menu.entries.clear();
        int start = page * PAGE_SIZE;
        for (int index = 0; index < PAGE_SIZE && start + index < players.size(); index++) {
            GuiPlayerEntry entry = players.get(start + index);
            inventory.setItem(PLAYER_SLOTS[index], playerHead(entry, progression));
            menu.entries.add(entry);
        }
        inventory.setItem(45, item(
                Material.ARROW,
                "Previous Page",
                List.of(Component.text("Page " + (page + 1), NamedTextColor.GRAY))
        ));
        inventory.setItem(53, item(
                Material.ARROW,
                "Next Page",
                List.of(Component.text("Page " + (page + 1) + " of " + maximumPage() + ".", NamedTextColor.GRAY))
        ));
    }

    private static ItemStack playerHead(GuiPlayerEntry entry, ProgressionManager progression) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (!(head.getItemMeta() instanceof SkullMeta meta)) {
            throw new IllegalStateException("Player head item does not have SkullMeta.");
        }
        int stage;
        String nextStage;
        String effects;
        if (entry.mock) {
            MockPlayer mock = progression.getMockPlayer(entry.name);
            if (mock != null) {
                meta.setPlayerProfile(mock.profile());
            }
            stage = progression.getMockPlayerStage(entry.name);
            UUID mockId = progression.getMockPlayerId(entry.name);
            nextStage = mockId == null ? "Not scheduled" : progression.getTimeUntilNextStage(mockId);
            effects = progression.getActiveEffects(stage);
        } else {
            Player player = Bukkit.getPlayerExact(entry.name);
            if (player != null) {
                meta.setPlayerProfile(player.getPlayerProfile());
                stage = progression.getPlayerStage(player);
                nextStage = progression.getTimeUntilNextStage(player.getUniqueId());
                effects = progression.getActiveEffects(player);
            } else {
                stage = 0;
                nextStage = "Not online";
                effects = "None";
            }
        }
        meta.displayName(Component.text(entry.name, NamedTextColor.WHITE));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Stage: " + stage + (entry.mock ? " (Mock)" : ""), NamedTextColor.YELLOW));
        lore.add(Component.text("Next escalation: " + nextStage, NamedTextColor.GRAY));
        lore.add(Component.text("Active effects: " + effects, NamedTextColor.GRAY));
        lore.add(Component.text("Left-click: advance stage", NamedTextColor.GREEN));
        lore.add(Component.text("Right-click: regress stage", NamedTextColor.RED));
        lore.add(Component.text("Shift-click: trigger a stage event", NamedTextColor.DARK_RED));
        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private static ItemStack item(Material material, String name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = Objects.requireNonNull(stack.getItemMeta());
        meta.displayName(Component.text(name, NamedTextColor.WHITE));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack masterAction(
            Material material,
            String name,
            NamedTextColor color,
            List<Component> lore
    ) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = Objects.requireNonNull(stack.getItemMeta());
        meta.displayName(Component.text(name, color, TextDecoration.BOLD));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static List<GuiPlayerEntry> eligiblePlayers() {
        GlitchCraft plugin = GlitchCraft.getInstance();
        List<GuiPlayerEntry> entries = new ArrayList<>();
        Bukkit.getOnlinePlayers().stream()
                .filter(player -> !plugin.isExempt(player))
                .map(player -> new GuiPlayerEntry(player.getName(), false))
                .forEach(entries::add);
        plugin.getProgressionManager().getMockPlayerNames().stream()
                .map(name -> new GuiPlayerEntry(name, true))
                .forEach(entries::add);
        entries.sort(Comparator.comparing(GuiPlayerEntry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private static int maximumPage() {
        return Math.max(0, (eligiblePlayers().size() - 1) / PAGE_SIZE);
    }

    private static int indexForSlot(int slot) {
        for (int index = 0; index < PLAYER_SLOTS.length; index++) {
            if (PLAYER_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    private static final class AdminMenu implements InventoryHolder {

        private final int page;
        private final List<GuiPlayerEntry> entries = new ArrayList<>();
        private Inventory inventory;

        private AdminMenu(int page) {
            this.page = page;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Objects.requireNonNull(inventory, "Inventory has not been created.");
        }
    }

    private record GuiPlayerEntry(String name, boolean mock) {
    }
}
