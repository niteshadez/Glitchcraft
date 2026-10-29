package com.donismp.glitchcraft.commands;

import com.donismp.glitchcraft.GlitchCraft;
import com.donismp.glitchcraft.managers.ProgressionManager;
import com.donismp.glitchcraft.managers.ProgressionManager.MockPlayer;
import com.destroystokyo.paper.profile.PlayerProfile;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class DebugCommand {

    private static final String MOCK_NAME_PATTERN = "[A-Za-z0-9_]{1,16}";

    private final GlitchCraft plugin;

    public DebugCommand(GlitchCraft plugin) {
        this.plugin = plugin;
    }

    public boolean handle(Player executor, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> handleStart(executor, label, args);
            case "stop" -> handleStop(executor, label, args);
            case "restart" -> handleRestart(executor, label, args);
            case "mock", "mockplayer" -> handleMock(executor, label, args);
            case "test" -> handleTest(executor, label, args);
            default -> false;
        };
    }

    private boolean handleStop(Player executor, String label, String[] args) {
        if (args.length != 1) {
            executor.sendMessage(Component.text("Usage: /" + label + " stop", NamedTextColor.RED));
            return true;
        }
        if (!executor.hasPermission("glitchcraft.admin")) {
            executor.sendMessage(Component.text("You do not have permission to stop GlitchCraft.", NamedTextColor.RED));
            return true;
        }
        plugin.getProgressionManager().stopGlitchCraft();
        executor.sendMessage(Component.text(
                "[GlitchCraft] Plugin stopped! All background loops halted and effects cleared.",
                NamedTextColor.RED
        ));
        return true;
    }

    private boolean handleRestart(Player executor, String label, String[] args) {
        if (args.length != 1) {
            executor.sendMessage(Component.text("Usage: /" + label + " restart", NamedTextColor.RED));
            return true;
        }
        if (!executor.hasPermission("glitchcraft.admin")) {
            executor.sendMessage(Component.text("You do not have permission to restart GlitchCraft.", NamedTextColor.RED));
            return true;
        }
        plugin.getProgressionManager().restartProgression();
        executor.sendMessage(Component.text(
                "[GlitchCraft] Plugin completely restarted! Progression reset with fresh 1-2 hour entry delays.",
                NamedTextColor.GREEN
        ));
        return true;
    }

    private boolean handleStart(Player executor, String label, String[] args) {
        if (args.length != 1) {
            executor.sendMessage(Component.text("Usage: /" + label + " start", NamedTextColor.RED));
            return true;
        }
        plugin.getProgressionManager().startMasterProgression();
        executor.sendActionBar(Component.text(
                "[GlitchCraft] Master progression engine started with randomized 1-2 hour entry delays.",
                NamedTextColor.GRAY
        ));
        return true;
    }

    private boolean handleMock(Player executor, String label, String[] args) {
        ProgressionManager progression = plugin.getProgressionManager();
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            if (progression.getMockPlayerNames().isEmpty()) {
                executor.sendMessage(Component.text("No mock players are registered.", NamedTextColor.YELLOW));
                return true;
            }
            executor.sendMessage(Component.text("Registered mock players:", NamedTextColor.GOLD));
            progression.getMockPlayerNames().stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(name -> executor.sendMessage(Component.text(
                            "- " + name + " (Stage " + progression.getMockPlayerStage(name) + ")",
                            NamedTextColor.GRAY
                    )));
            return true;
        }
        if (args.length != 3 || (!args[1].equalsIgnoreCase("add")
                && !args[1].equalsIgnoreCase("remove"))) {
            executor.sendMessage(Component.text(
                    "Usage: /" + label + " mock <add|remove|list> [name]",
                    NamedTextColor.RED
            ));
            return true;
        }
        String name = args[2];
        if (!name.matches(MOCK_NAME_PATTERN)) {
            executor.sendMessage(Component.text(
                    "Mock names must be 1-16 characters using letters, numbers, or underscores.",
                    NamedTextColor.RED
            ));
            return true;
        }
        if (args[1].equalsIgnoreCase("remove")) {
            boolean removed = progression.removeMockPlayer(name);
            executor.sendMessage(Component.text(
                    removed ? "Removed mock player " + name + "." : "No mock player named " + name + " exists.",
                    removed ? NamedTextColor.GREEN : NamedTextColor.RED
            ));
            return true;
        }
        if (plugin.isExemptName(name)) {
            executor.sendMessage(Component.text("That name is exempt from GlitchCraft.", NamedTextColor.RED));
            return true;
        }
        if (progression.isMockPlayer(name) || org.bukkit.Bukkit.getPlayerExact(name) != null) {
            executor.sendMessage(Component.text("That player name is already tracked or online.", NamedTextColor.RED));
            return true;
        }

        PlayerProfile profile = org.bukkit.Bukkit.createProfile(name);
        Location mockLocation = executor.getLocation().clone();
        try {
            profile.update().whenComplete((resolved, failure) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!executor.isOnline()) {
                            return;
                        }
                        if (failure != null || resolved == null || !resolved.hasTextures()) {
                            executor.sendMessage(Component.text(
                                    "Could not load a skin profile for " + name + ". "
                                            + "Verify the name and try again.",
                                    NamedTextColor.RED
                            ));
                            if (failure != null) {
                                plugin.getLogger().warning(
                                        "Could not resolve mock skin for " + name + ": " + failure.getMessage()
                                );
                            }
                            return;
                        }
                        try {
                            progression.addMockPlayer(name, resolved, mockLocation);
                            executor.sendMessage(Component.text(
                                    "Registered mock player " + name
                                            + " with a Mojang skin and randomized progression timer.",
                                    NamedTextColor.GREEN
                            ));
                        } catch (IllegalArgumentException exception) {
                            executor.sendMessage(Component.text(exception.getMessage(), NamedTextColor.RED));
                        }
                    })
            );
        } catch (RuntimeException exception) {
            executor.sendMessage(Component.text(
                    "Could not start skin lookup for " + name + ": " + exception.getMessage(),
                    NamedTextColor.RED
            ));
        }
        return true;
    }

    private boolean handleTest(Player executor, String label, String[] args) {
        if (args.length == 4 && args[1].equalsIgnoreCase("doppelganger")) {
            return handleDoppelgangerTest(executor, label, args[2], args[3]);
        }
        if (args.length != 2 || !args[1].equalsIgnoreCase("countdown")) {
            executor.sendMessage(Component.text(
                    "Usage: /" + label + " test <countdown|doppelganger <targetRealPlayer> <frameMockPlayer>>",
                    NamedTextColor.RED
            ));
            return true;
        }
        boolean started = plugin.getProgressionManager().beginApocalypseCountdownTest();
        if (started) {
            Component status = Component.text(
                    "[GlitchCraft Debug] All players set to Stage 4! "
                            + "5-minute apocalypse countdown started.",
                    NamedTextColor.GOLD
            );
            executor.sendActionBar(status);
        } else {
            executor.sendMessage(Component.text(
                    "Cannot test the countdown because there are no non-exempt real or mock players.",
                    NamedTextColor.RED
            ));
        }
        return true;
    }

    private boolean handleDoppelgangerTest(
            Player executor,
            String label,
            String targetName,
            String frameName
    ) {
        ProgressionManager progression = plugin.getProgressionManager();
        Player target = org.bukkit.Bukkit.getPlayerExact(targetName);
        MockPlayer frame = progression.getMockPlayer(frameName);
        if (target == null) {
            executor.sendMessage(Component.text("Target must be a real online player.", NamedTextColor.RED));
            return true;
        }
        if (plugin.isExempt(target)) {
            executor.sendMessage(Component.text("Exempt players cannot be Stalker targets.", NamedTextColor.RED));
            return true;
        }
        if (frame == null) {
            executor.sendMessage(Component.text("No mock player named " + frameName + " exists.", NamedTextColor.RED));
            return true;
        }
        var framePlayerId = progression.getMockPlayerId(frameName);
        if (framePlayerId == null) {
            executor.sendMessage(Component.text("Mock frame profile is no longer registered.", NamedTextColor.RED));
            return true;
        }
        var frameProfile = frame.profile();
        Location frameBase = executor.getLocation().clone();
        boolean started = plugin.getStalkerModule().forceAmbushWithFrame(
                target,
                framePlayerId,
                frame.name(),
                frameProfile,
                frameBase,
                executor
        );
        executor.sendMessage(Component.text(
                started
                        ? "Started a doppelganger heist against " + target.getName()
                                + " framed as " + frame.name() + "."
                        : "Could not start the mock-frame doppelganger test.",
                started ? NamedTextColor.GREEN : NamedTextColor.RED
        ));
        return true;
    }

}
