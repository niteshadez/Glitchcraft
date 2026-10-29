package com.donismp.glitchcraft;

import com.donismp.glitchcraft.commands.DebugCommand;
import com.donismp.glitchcraft.gui.ControlGUI;
import com.donismp.glitchcraft.gui.GlitchAdminGUI;
import com.donismp.glitchcraft.gui.StalkerDebugGUI;
import com.donismp.glitchcraft.manager.TargetManager;
import com.donismp.glitchcraft.managers.ProgressionManager;
import com.donismp.glitchcraft.modules.GlitchApocalypseModule;
import com.donismp.glitchcraft.modules.BlockGlitchModule;
import com.donismp.glitchcraft.modules.InvertedAnomalyModule;
import com.donismp.glitchcraft.modules.MimicModule;
import com.donismp.glitchcraft.modules.QuantumModule;
import com.donismp.glitchcraft.modules.StalkerModule;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class GlitchCraft extends JavaPlugin implements CommandExecutor {

    private static GlitchCraft instance;

    private Set<String> exemptPlayers = Set.of();
    private TargetManager targetManager;
    private QuantumModule quantumModule;
    private BlockGlitchModule blockGlitchModule;
    private MimicModule mimicModule;
    private InvertedAnomalyModule invertedAnomalyModule;
    private GlitchApocalypseModule glitchApocalypseModule;
    private StalkerModule stalkerModule;
    private ProgressionManager progressionManager;

    public static GlitchCraft getInstance() {
        if (instance == null) {
            throw new IllegalStateException("GlitchCraft has not been enabled.");
        }
        return instance;
    }

    public TargetManager getTargetManager() {
        return Objects.requireNonNull(targetManager, "TargetManager has not been initialized.");
    }

    public StalkerModule getStalkerModule() {
        return Objects.requireNonNull(stalkerModule, "StalkerModule has not been initialized.");
    }

    public ProgressionManager getProgressionManager() {
        return Objects.requireNonNull(progressionManager, "ProgressionManager has not been initialized.");
    }

    public boolean isExempt(@NotNull Player player) {
        String playerId = player.getUniqueId().toString();
        return isExemptName(player.getName()) || exemptPlayers.contains(playerId.toLowerCase(Locale.ROOT));
    }

    public boolean isExemptName(@NotNull String playerName) {
        return exemptPlayers.contains(playerName.toLowerCase(Locale.ROOT));
    }

    private void loadExemptPlayers() {
        Set<String> configured = new HashSet<>();
        getConfig().getStringList("exempt-players").stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(entry -> entry.toLowerCase(Locale.ROOT))
                .forEach(configured::add);
        exemptPlayers = Set.copyOf(configured);
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadExemptPlayers();
        targetManager = new TargetManager();

        quantumModule = new QuantumModule(this, targetManager);
        blockGlitchModule = new BlockGlitchModule(this);
        mimicModule = new MimicModule(this, targetManager);
        invertedAnomalyModule = new InvertedAnomalyModule(this);
        glitchApocalypseModule = new GlitchApocalypseModule(this);
        stalkerModule = new StalkerModule(this, targetManager);
        ControlGUI controlGUI = new ControlGUI(targetManager);
        progressionManager = new ProgressionManager(
                this,
                targetManager,
                quantumModule,
                blockGlitchModule,
                mimicModule,
                stalkerModule,
                invertedAnomalyModule,
                glitchApocalypseModule
        );

        Bukkit.getPluginManager().registerEvents(quantumModule, this);
        Bukkit.getPluginManager().registerEvents(blockGlitchModule, this);
        blockGlitchModule.start();
        Bukkit.getPluginManager().registerEvents(mimicModule, this);
        Bukkit.getPluginManager().registerEvents(invertedAnomalyModule, this);
        Bukkit.getPluginManager().registerEvents(glitchApocalypseModule, this);
        Bukkit.getPluginManager().registerEvents(stalkerModule, this);
        Bukkit.getPluginManager().registerEvents(controlGUI, this);
        Bukkit.getPluginManager().registerEvents(new GlitchAdminGUI(), this);
        Bukkit.getPluginManager().registerEvents(new StalkerDebugGUI(), this);
        Bukkit.getPluginManager().registerEvents(progressionManager, this);
        progressionManager.start();

        Objects.requireNonNull(getCommand("glitchcraft"), "glitchcraft command is missing from plugin.yml")
                .setExecutor(this);
    }

    @Override
    public void onDisable() {
        if (quantumModule != null) {
            quantumModule.restoreAll();
        }
        if (blockGlitchModule != null) {
            blockGlitchModule.stop();
        }
        if (mimicModule != null) {
            mimicModule.cleanupAll();
        }
        if (stalkerModule != null) {
            stalkerModule.removeAllStalkers();
        }
        if (invertedAnomalyModule != null) {
            invertedAnomalyModule.clearPlayerState();
        }
        if (glitchApocalypseModule != null) {
            glitchApocalypseModule.stop();
        }
        if (progressionManager != null) {
            progressionManager.stop();
        }
        Bukkit.getScheduler().cancelTasks(this);
        instance = null;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player admin) || !admin.hasPermission("glitchcraft.admin")) {
            sender.sendMessage(net.kyori.adventure.text.Component.text(
                    "You do not have permission to use this command."
            ));
            return true;
        }

        if (new DebugCommand(this).handle(admin, label, args)) {
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("gui")) {
            if (args.length != 1) {
                admin.sendMessage(net.kyori.adventure.text.Component.text("Usage: /" + label + " gui"));
                return true;
            }
            GlitchAdminGUI.open(admin);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("setstage")) {
            if (args.length != 3) {
                admin.sendMessage(net.kyori.adventure.text.Component.text(
                        "Usage: /" + label + " setstage <player> <1-5>"
                ));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            Integer stage = parseStage(args[2]);
            if (target == null) {
                admin.sendMessage(net.kyori.adventure.text.Component.text("That player is not online."));
            } else if (stage == null || stage < 1 || stage > 5) {
                admin.sendMessage(net.kyori.adventure.text.Component.text("Stage must be a number from 1 to 5."));
            } else if (isExempt(target)) {
                admin.sendMessage(net.kyori.adventure.text.Component.text(
                        target.getName() + " is exempt from GlitchCraft."
                ));
            } else {
                progressionManager.setPlayerStage(target, stage);
                admin.sendMessage(net.kyori.adventure.text.Component.text(
                        "Set " + target.getName() + " to Stage "
                                + progressionManager.getPlayerStage(target.getUniqueId()) + "."
                ));
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("setglobal")) {
            if (args.length != 2) {
                admin.sendMessage(net.kyori.adventure.text.Component.text("Usage: /" + label + " setglobal <1-5>"));
                return true;
            }
            Integer stage = parseStage(args[1]);
            if (stage == null || stage < 1 || stage > 5) {
                admin.sendMessage(net.kyori.adventure.text.Component.text(
                        "Global stage cap must be a number from 1 to 5."
                ));
            } else {
                progressionManager.setMaxGlobalStage(stage);
                admin.sendMessage(net.kyori.adventure.text.Component.text("Global stage cap set to Stage " + stage + "."));
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("toggleauto")) {
            if (args.length != 1) {
                admin.sendMessage(net.kyori.adventure.text.Component.text("Usage: /" + label + " toggleauto"));
                return true;
            }
            boolean enabled = progressionManager.toggleAutoProgression();
            admin.sendMessage(net.kyori.adventure.text.Component.text(
                    "Automatic progression is now " + (enabled ? "ON." : "OFF.")
            ));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("trigger")) {
            if (args.length != 3) {
                admin.sendMessage(net.kyori.adventure.text.Component.text(
                        "Usage: /" + label + " trigger <player> <doppelganger|inverted|apocalypse|quantum>"
                ));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                admin.sendMessage(net.kyori.adventure.text.Component.text("That player is not online."));
            } else {
                boolean triggered = progressionManager.triggerPlayerEvent(target, args[2]);
                admin.sendMessage(net.kyori.adventure.text.Component.text(
                        triggered
                                ? "Triggered " + args[2] + " for " + target.getName() + "."
                                : isExempt(target)
                                        ? target.getName() + " is exempt from GlitchCraft."
                                        : "Unknown module or event could not be started: " + args[2] + "."
                ));
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("apocalypse")) {
            if (args.length != 2) {
                admin.sendMessage(net.kyori.adventure.text.Component.text(
                        "Usage: /" + label + " apocalypse <start|trigger|stop>"
                ));
                return true;
            }
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "start" -> {
                    glitchApocalypseModule.start();
                    admin.sendMessage(net.kyori.adventure.text.Component.text(
                            "Glitch Apocalypse active phase started."
                    ));
                }
                case "trigger" -> {
                    progressionManager.triggerApocalypseNow();
                    admin.sendMessage(net.kyori.adventure.text.Component.text(
                            "Glitch Apocalypse climax triggered."
                    ));
                }
                case "stop" -> {
                    glitchApocalypseModule.stop();
                    admin.sendMessage(net.kyori.adventure.text.Component.text(
                            "Glitch Apocalypse stopped; temporary effects were restored."
                    ));
                }
                default -> admin.sendMessage(net.kyori.adventure.text.Component.text(
                        "Usage: /" + label + " apocalypse <start|trigger|stop>"
                ));
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("progression")) {
            if (args.length != 3 || !args[1].equalsIgnoreCase("cap")) {
                admin.sendMessage(net.kyori.adventure.text.Component.text(
                        "Usage: /" + label + " progression cap <0-5>"
                ));
                return true;
            }
            try {
                int cap = Integer.parseInt(args[2]);
                progressionManager.setMaxGlobalStage(cap);
                admin.sendMessage(net.kyori.adventure.text.Component.text(
                        "Global progression cap set to Stage " + progressionManager.getMaxGlobalStage() + "."
                ));
            } catch (IllegalArgumentException exception) {
                admin.sendMessage(net.kyori.adventure.text.Component.text(
                        "Stage cap must be a number from 0 to 5."
                ));
            }
            return true;
        }

        boolean debug = args.length > 0 && args[0].equalsIgnoreCase("debug");
        int targetArgument = debug ? 1 : 0;
        if (args.length > targetArgument + 1) {
            admin.sendMessage(net.kyori.adventure.text.Component.text(
                    debug ? "Usage: /" + label + " debug [player]" : "Usage: /" + label + " [player]"
            ));
            return true;
        }
        Player target = admin;
        if (args.length > targetArgument) {
            target = Bukkit.getPlayerExact(args[targetArgument]);
            if (target == null) {
                admin.sendMessage(net.kyori.adventure.text.Component.text("That player is not online."));
                return true;
            }
        }

        if (debug) {
            StalkerDebugGUI.open(admin, target);
        } else {
            ControlGUI.openGUI(admin, target);
        }
        return true;
    }

    private Integer parseStage(String argument) {
        try {
            return Integer.valueOf(argument);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

}
