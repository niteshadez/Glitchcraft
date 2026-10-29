package com.donismp.glitchcraft.managers;

import com.donismp.glitchcraft.GlitchCraft;
import com.donismp.glitchcraft.manager.TargetManager;
import com.donismp.glitchcraft.modules.GlitchApocalypseModule;
import com.donismp.glitchcraft.modules.BlockGlitchModule;
import com.donismp.glitchcraft.modules.InvertedAnomalyModule;
import com.donismp.glitchcraft.modules.MimicModule;
import com.donismp.glitchcraft.modules.QuantumModule;
import com.donismp.glitchcraft.modules.StalkerModule;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.destroystokyo.paper.profile.PlayerProfile;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

public final class ProgressionManager implements Listener {

    private static final long CHECK_INTERVAL_TICKS = 20L * 20L;
    private static final int MIN_STAGE_DELAY_SECONDS = 5 * 60;
    private static final int MAX_STAGE_DELAY_SECONDS = 12 * 60;

    private final GlitchCraft plugin;
    private final TargetManager targetManager;
    private final QuantumModule quantumModule;
    private final BlockGlitchModule blockGlitchModule;
    private final MimicModule mimicModule;
    private final StalkerModule stalkerModule;
    private final InvertedAnomalyModule invertedAnomalyModule;
    private final GlitchApocalypseModule apocalypseModule;
    private final Map<UUID, Integer> playerStages = new HashMap<>();
    private final Map<UUID, Long> nextStageCheck = new HashMap<>();
    private final Map<UUID, Long> nextEscalationTimestamp = new HashMap<>();
    private final Map<UUID, String> playerNames = new HashMap<>();
    private final Map<UUID, MockPlayer> mockPlayers = new HashMap<>();
    private final Map<String, UUID> mockPlayerIdsByName = new HashMap<>();
    private final Set<String> mockPlayerNames = new LinkedHashSet<>();
    private final Map<String, Integer> mockPlayerStages = new LinkedHashMap<>();
    private BukkitTask progressionTask;
    private boolean apocalypseCountdownActive = false;
    private BukkitTask apocalypseTask = null;
    private boolean masterProgressionStarted;
    private int maxGlobalStage;
    private final File playerDataFile;

    public ProgressionManager(
            GlitchCraft plugin,
            TargetManager targetManager,
            QuantumModule quantumModule,
            BlockGlitchModule blockGlitchModule,
            MimicModule mimicModule,
            StalkerModule stalkerModule,
            InvertedAnomalyModule invertedAnomalyModule,
            GlitchApocalypseModule apocalypseModule
    ) {
        this.plugin = plugin;
        this.targetManager = targetManager;
        this.quantumModule = quantumModule;
        this.blockGlitchModule = blockGlitchModule;
        this.mimicModule = mimicModule;
        this.stalkerModule = stalkerModule;
        this.invertedAnomalyModule = invertedAnomalyModule;
        this.apocalypseModule = apocalypseModule;
        this.maxGlobalStage = clampStage(plugin.getConfig().getInt("progression.max-global-stage", 5));
        this.playerDataFile = new File(new File(plugin.getDataFolder(), "data"), "players.yml");
        this.masterProgressionStarted = plugin.getConfig().getBoolean("auto-progression.master-started", false);
        loadPlayerData();
    }

    public void start() {
        if (progressionTask != null && !progressionTask.isCancelled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerNames.put(player.getUniqueId(), player.getName());
            scheduleNextCheckIfAbsent(player);
            if (!plugin.isExempt(player)) {
                applyStage(player, getPlayerStage(player.getUniqueId()), false);
            }
        }
        progressionTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::checkProgression,
                CHECK_INTERVAL_TICKS,
                CHECK_INTERVAL_TICKS
        );
    }

    public void stop() {
        if (progressionTask != null) {
            progressionTask.cancel();
            progressionTask = null;
        }
        cancelApocalypseCountdown(false);
        savePlayerData();
    }

    public void stopGlitchCraft() {
        plugin.getConfig().set("auto-progression.enabled", false);
        plugin.saveConfig();

        if (progressionTask != null) {
            progressionTask.cancel();
            progressionTask = null;
        }
        cancelApocalypseCountdown(true);

        apocalypseModule.stop();
        stalkerModule.removeAllStalkers();
        invertedAnomalyModule.clearPlayerState();
        blockGlitchModule.stop();
        quantumModule.restoreAll();
        mimicModule.cleanupAll();
        targetManager.clearTargets();

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS);
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION);
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING);
            player.setWalkSpeed(0.2F);
        }
        savePlayerData();
    }

    public void startEngine() {
        startMasterProgression();
    }

    public void restartEngine() {
        restartProgression();
    }

    public void stopEngine() {
        stopGlitchCraft();
    }

    public int getPlayerStage(UUID playerId) {
        return playerStages.getOrDefault(playerId, 0);
    }

    public int getPlayerStage(Player player) {
        return getPlayerStage(player.getUniqueId());
    }

    public UUID addMockPlayer(String name, PlayerProfile skinProfile, org.bukkit.Location frameLocation) {
        String normalizedName = name.toLowerCase(java.util.Locale.ROOT);
        if (mockPlayerIdsByName.containsKey(normalizedName)) {
            throw new IllegalArgumentException("A mock player with that name already exists.");
        }
        if (Bukkit.getPlayerExact(name) != null) {
            throw new IllegalArgumentException("A real online player already has that name.");
        }
        UUID playerId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        if (mockPlayers.containsKey(playerId)) {
            throw new IllegalArgumentException("A mock player with that name already exists.");
        }
        PlayerProfile profile = Bukkit.createProfile(playerId, name);
        profile.setProperties(skinProfile.getProperties());
        if (!profile.hasTextures()) {
            throw new IllegalArgumentException("The selected skin profile does not contain texture data.");
        }
        mockPlayers.put(playerId, new MockPlayer(name, profile, frameLocation.clone()));
        mockPlayerIdsByName.put(normalizedName, playerId);
        mockPlayerNames.add(name);
        mockPlayerStages.put(name, 0);
        playerStages.put(playerId, 0);
        playerNames.put(playerId, name);
        setNextStageCheck(playerId, masterProgressionStarted ? initialEntryDelayTick() : nextCheckTick());
        savePlayerData();
        return playerId;
    }

    public boolean removeMockPlayer(String name) {
        UUID playerId = mockPlayerIdsByName.remove(name.toLowerCase(java.util.Locale.ROOT));
        if (playerId == null) {
            return false;
        }
        MockPlayer removed = mockPlayers.remove(playerId);
        playerStages.remove(playerId);
        nextStageCheck.remove(playerId);
        nextEscalationTimestamp.remove(playerId);
        playerNames.remove(playerId);
        String canonicalName = removed == null ? name : removed.name();
        mockPlayerNames.remove(canonicalName);
        mockPlayerStages.remove(canonicalName);
        stalkerModule.clearMockFrame(playerId);
        if (!checkAllNonExemptAtLeastStage4()) {
            abortApocalypseCountdown();
        }
        savePlayerData();
        return true;
    }

    public MockPlayer getMockPlayer(String name) {
        UUID playerId = mockPlayerIdsByName.get(name.toLowerCase(java.util.Locale.ROOT));
        return playerId == null ? null : mockPlayers.get(playerId);
    }

    public Map<UUID, MockPlayer> getMockPlayers() {
        return Map.copyOf(mockPlayers);
    }

    public List<String> getAllTrackedPlayers() {
        List<String> tracked = new ArrayList<>();
        Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .forEach(tracked::add);
        tracked.addAll(mockPlayerNames);
        return List.copyOf(tracked);
    }

    public Set<String> getMockPlayerNames() {
        return Set.copyOf(mockPlayerNames);
    }

    public int getMockPlayerStage(String name) {
        String canonicalName = findMockName(name);
        return canonicalName == null ? 0 : mockPlayerStages.getOrDefault(canonicalName, 0);
    }

    public boolean setMockPlayerStage(String name, int stage) {
        if (stage < 0 || stage > 5) {
            throw new IllegalArgumentException("Mock player stage must be between 0 and 5.");
        }
        String canonicalName = findMockName(name);
        if (canonicalName == null) {
            return false;
        }
        int cappedStage = stage == 5 ? 5 : Math.min(stage, maxGlobalStage);
        mockPlayerStages.put(canonicalName, cappedStage);
        UUID playerId = mockPlayerIdsByName.get(canonicalName.toLowerCase(java.util.Locale.ROOT));
        if (playerId != null) {
            playerStages.put(playerId, cappedStage);
            setNextStageCheck(playerId, nextCheckTick());
        }
        if (cappedStage < 4) {
            abortApocalypseCountdown();
        } else if (stage == 5) {
            startApocalypseNow();
        }
        savePlayerData();
        return true;
    }

    public boolean isMockPlayer(String name) {
        return findMockName(name) != null;
    }

    public UUID getMockPlayerId(String name) {
        UUID id = mockPlayerIdsByName.get(name.toLowerCase(java.util.Locale.ROOT));
        return id;
    }

    public boolean beginApocalypseCountdownTest() {
        boolean hasParticipant = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isExempt(player)) {
                continue;
            }
            hasParticipant = true;
            playerStages.put(player.getUniqueId(), 4);
            playerNames.put(player.getUniqueId(), player.getName());
            applyStage(player, 4);
            setNextStageCheck(player.getUniqueId(), nextCheckTick());
        }
        for (Map.Entry<UUID, MockPlayer> entry : mockPlayers.entrySet()) {
            hasParticipant = true;
            playerStages.put(entry.getKey(), 4);
            mockPlayerStages.put(entry.getValue().name(), 4);
            setNextStageCheck(entry.getKey(), nextCheckTick());
        }
        if (!hasParticipant) {
            return false;
        }
        if (!checkAllNonExemptAtLeastStage4()) {
            return false;
        }
        cancelApocalypseCountdown(false);
        startApocalypseCountdown();
        plugin.getLogger().info(
                "[GlitchCraft Debug] All players set to Stage 4! "
                        + "5-minute apocalypse countdown started."
        );
        savePlayerData();
        return true;
    }

    public Long getNextStageCheck(UUID playerId) {
        return nextStageCheck.get(playerId);
    }

    public int getMaxGlobalStage() {
        return maxGlobalStage;
    }

    public boolean isAutoProgressionEnabled() {
        return plugin.getConfig().getBoolean("auto-progression.enabled", true);
    }

    public boolean toggleAutoProgression() {
        boolean enabled = !isAutoProgressionEnabled();
        plugin.getConfig().set("auto-progression.enabled", enabled);
        plugin.saveConfig();
        return enabled;
    }

    public void startMasterProgression() {
        start();
        plugin.getConfig().set("auto-progression.enabled", true);
        plugin.getConfig().set("auto-progression.master-started", true);
        plugin.saveConfig();
        masterProgressionStarted = true;
        cancelApocalypseCountdown(false);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isExempt(player)) {
                continue;
            }
            UUID playerId = player.getUniqueId();
            playerStages.put(playerId, 0);
            playerNames.put(playerId, player.getName());
            applyStage(player, 0, false);
            setNextStageCheck(playerId, initialEntryDelayTick());
        }
        for (Map.Entry<UUID, MockPlayer> entry : mockPlayers.entrySet()) {
            UUID playerId = entry.getKey();
            playerStages.put(playerId, 0);
            mockPlayerStages.put(entry.getValue().name(), 0);
            setNextStageCheck(playerId, initialEntryDelayTick());
        }
        savePlayerData();

        plugin.getLogger().info(
                "[GlitchCraft] Master progression engine started with randomized 1-2 hour entry delays."
        );
    }

    public void restartProgression() {
        cancelApocalypseCountdown(false);

        apocalypseModule.stop();
        stalkerModule.removeAllStalkers();
        mimicModule.cleanupAll();
        quantumModule.restoreAll();
        invertedAnomalyModule.clearPlayerState();
        for (UUID playerId : List.copyOf(playerStages.keySet())) {
            blockGlitchModule.disableTarget(playerId);
            quantumModule.disableTarget(playerId);
            mimicModule.disableTarget(playerId);
            stalkerModule.disableTarget(playerId);
            invertedAnomalyModule.disableTarget(playerId);
            apocalypseModule.stopFor(playerId);
        }
        targetManager.clearTargets();

        playerStages.clear();
        nextStageCheck.clear();
        nextEscalationTimestamp.clear();
        playerNames.clear();
        mockPlayerStages.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isExempt(player)) {
                continue;
            }
            UUID playerId = player.getUniqueId();
            playerNames.put(playerId, player.getName());
            playerStages.put(playerId, 0);
            applyStage(player, 0, false);
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS);
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION);
        }

        for (Map.Entry<UUID, MockPlayer> entry : mockPlayers.entrySet()) {
            UUID playerId = entry.getKey();
            MockPlayer mockPlayer = entry.getValue();
            playerStages.put(playerId, 0);
            playerNames.put(playerId, mockPlayer.name());
            mockPlayerStages.put(mockPlayer.name(), 0);
        }

        plugin.getConfig().set("auto-progression.enabled", true);
        plugin.getConfig().set("auto-progression.master-started", true);
        plugin.saveConfig();
        masterProgressionStarted = true;

        for (UUID playerId : playerStages.keySet()) {
            setNextStageCheck(playerId, initialEntryDelayTick());
        }

        start();
        savePlayerData();
    }

    public String getTimeUntilNextStage(UUID playerId) {
        if (getPlayerStage(playerId) >= maxGlobalStage) {
            return "Global cap reached";
        }
        Long nextTick = nextStageCheck.get(playerId);
        if (nextTick == null) {
            return "Not scheduled";
        }
        long remainingTicks = Math.max(0L, nextTick - plugin.getServer().getCurrentTick());
        long remainingSeconds = (remainingTicks + 19L) / 20L;
        return String.format("%d:%02d", remainingSeconds / 60L, remainingSeconds % 60L);
    }

    public String getActiveEffects(Player player) {
        int stage = getPlayerStage(player.getUniqueId());
        return getActiveEffects(stage);
    }

    public String getActiveEffects(int stage) {
        if (stage <= 0) {
            return "None";
        }
        StringBuilder effects = new StringBuilder();
        appendEffect(effects, stage >= 1, "Block Glitch");
        appendEffect(effects, stage >= 2, "Mimics");
        appendEffect(effects, stage >= 3, "Doppelganger");
        appendEffect(effects, stage >= 4, "Inverted Anomaly");
        appendEffect(effects, stage >= 5, "Apocalypse");
        return effects.toString();
    }

    public void setMaxGlobalStage(int stage) {
        if (stage < 0 || stage > 5) {
            throw new IllegalArgumentException("Global stage cap must be between 0 and 5.");
        }
        maxGlobalStage = stage;
        plugin.getConfig().set("progression.max-global-stage", stage);
        plugin.saveConfig();

        for (Map.Entry<UUID, Integer> entry : Map.copyOf(playerStages).entrySet()) {
            if (entry.getValue() > stage) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    setPlayerStage(player, stage);
                } else {
                    playerStages.put(entry.getKey(), stage);
                    MockPlayer mockPlayer = mockPlayers.get(entry.getKey());
                    if (mockPlayer != null) {
                        mockPlayerStages.put(mockPlayer.name(), stage);
                    }
                }
            }
        }
        savePlayerData();
    }

    public int advancePlayerStage(Player player) {
        if (!player.isOnline() || plugin.isExempt(player)) {
            return getPlayerStage(player.getUniqueId());
        }
        UUID playerId = player.getUniqueId();
        int currentStage = getPlayerStage(playerId);
        if (currentStage >= maxGlobalStage) {
            return currentStage;
        }

        int newStage = currentStage + 1;
        playerStages.put(playerId, newStage);
        playerNames.put(playerId, player.getName());
        applyStage(player, newStage);
        setNextStageCheck(playerId, nextCheckTick());
        String message = "[GlitchCraft] " + player.getName() + " escalated to Stage " + newStage + ".";
        plugin.getLogger().info(message);
        savePlayerData();
        return newStage;
    }

    public void setPlayerStage(Player player, int stage) {
        if (stage < 0 || stage > 5) {
            throw new IllegalArgumentException("Player stage must be between 0 and 5.");
        }
        if (plugin.isExempt(player)) {
            return;
        }
        int cappedStage = stage == 5 ? 5 : Math.min(stage, maxGlobalStage);
        playerStages.put(player.getUniqueId(), cappedStage);
        playerNames.put(player.getUniqueId(), player.getName());
        applyStage(player, cappedStage);
        setNextStageCheck(player.getUniqueId(), nextCheckTick());
        if (cappedStage < 4) {
            abortApocalypseCountdown();
        } else if (stage == 5) {
            startApocalypseNow();
        }
        savePlayerData();
    }

    public boolean triggerPlayerEvent(Player player) {
        if (!player.isOnline() || plugin.isExempt(player)) {
            return false;
        }
        if (getPlayerStage(player.getUniqueId()) >= 4) {
            invertedAnomalyModule.triggerEvent(player);
            return true;
        }
        if (getPlayerStage(player.getUniqueId()) == 1) {
            return blockGlitchModule.triggerGlitch(player);
        }
        return stalkerModule.forceAmbush(player);
    }

    public boolean triggerPlayerEvent(Player player, String module) {
        if (!player.isOnline() || plugin.isExempt(player)) {
            return false;
        }
        return switch (module.toLowerCase(java.util.Locale.ROOT)) {
            case "block", "blockglitch", "ghostblock" ->
                    blockGlitchModule.triggerGlitch(player);
            case "doppelganger", "stalker" -> stalkerModule.forceAmbush(player);
            case "inverted", "anomaly" -> {
                invertedAnomalyModule.triggerEvent(player);
                yield true;
            }
            case "apocalypse" -> {
                apocalypseModule.triggerClimax(player);
                yield true;
            }
            case "quantum" -> quantumModule.triggerGlitch(player);
            case "glitch" -> blockGlitchModule.triggerGlitch(player);
            default -> false;
        };
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        boolean returning = playerStages.containsKey(playerId);
        playerNames.put(playerId, player.getName());
        if (plugin.isExempt(player)) {
            return;
        }
        if (returning) {
            restoreNextStageCheck(playerId);
            if (!nextStageCheck.containsKey(playerId)) {
                setNextStageCheck(playerId, nextCheckTick());
            }
        } else {
            playerStages.put(playerId, 0);
            setNextStageCheck(
                    playerId,
                    masterProgressionStarted ? initialEntryDelayTick() : nextCheckTick()
            );
        }
        int stage = getPlayerStage(playerId);
        if (stage > 0 && !plugin.isExempt(player)) {
            applyStage(player, stage);
        }
        if (!plugin.isExempt(player) && stage < 4) {
            abortApocalypseCountdown();
        }
        savePlayerData(playerId);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        playerNames.put(playerId, event.getPlayer().getName());
        updateSavedTimestampFromTick(playerId);
        savePlayerData(playerId);
        nextStageCheck.remove(playerId);
    }

    private void checkProgression() {
        if (isAutoProgressionEnabled()) {
            long currentTick = plugin.getServer().getCurrentTick();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (plugin.isExempt(player)) {
                    continue;
                }
                UUID playerId = player.getUniqueId();
                scheduleNextCheckIfAbsent(player);
                if (currentTick >= nextStageCheck.getOrDefault(playerId, Long.MAX_VALUE)) {
                    advancePlayerStage(player);
                }
            }
            for (Map.Entry<UUID, MockPlayer> entry : mockPlayers.entrySet()) {
                if (Bukkit.getPlayer(entry.getKey()) != null) {
                    continue;
                }
                UUID playerId = entry.getKey();
                if (currentTick < nextStageCheck.getOrDefault(playerId, Long.MAX_VALUE)) {
                    continue;
                }
                int currentStage = getPlayerStage(playerId);
                if (currentStage < maxGlobalStage) {
                    int nextStage = currentStage + 1;
                    playerStages.put(playerId, nextStage);
                    mockPlayerStages.put(entry.getValue().name(), nextStage);
                    setNextStageCheck(playerId, nextCheckTick());
                    plugin.getLogger().info(
                            "[GlitchCraft] " + entry.getValue().name()
                                    + " escalated to Stage " + nextStage + "."
                    );
                } else {
                    setNextStageCheck(playerId, nextCheckTick());
                }
            }
            savePlayerData();
        }
        if (checkAllNonExemptAtLeastStage4()) {
            startApocalypseCountdown();
        } else if (apocalypseCountdownActive) {
            abortApocalypseCountdown();
        }
    }

    public boolean checkAllNonExemptAtLeastStage4() {
        boolean foundNonExemptPlayer = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isExempt(player)) {
                continue;
            }
            foundNonExemptPlayer = true;
            if (getPlayerStage(player.getUniqueId()) < 4) {
                return false;
            }
        }
        for (UUID playerId : mockPlayers.keySet()) {
            if (Bukkit.getPlayer(playerId) != null) {
                continue;
            }
            foundNonExemptPlayer = true;
            if (getPlayerStage(playerId) < 4) {
                return false;
            }
        }
        return foundNonExemptPlayer;
    }

    public void triggerApocalypseNow() {
        startApocalypseNow();
    }

    public void triggerApocalypseClimax() {
        apocalypseModule.triggerClimax();
    }

    private void startApocalypseCountdown() {
        if (apocalypseCountdownActive) {
            return;
        }
        apocalypseCountdownActive = true;
        plugin.getLogger().info(
                "[GlitchCraft] ALL non-exempt players have reached Stage 4! "
                        + "Starting 5-minute apocalypse countdown..."
        );
        apocalypseTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!checkAllNonExemptAtLeastStage4()) {
                abortApocalypseCountdown();
                return;
            }
            apocalypseTask = null;
            apocalypseCountdownActive = false;
            triggerApocalypseClimax();
            advanceAllNonExemptPlayersToStage5();
            apocalypseModule.startApocalypseSequence();
        }, 300L * 20L);
    }

    private void abortApocalypseCountdown() {
        if (!apocalypseCountdownActive) {
            return;
        }
        cancelApocalypseCountdown(true);
    }

    private void cancelApocalypseCountdown(boolean logAbort) {
        if (apocalypseTask != null) {
            apocalypseTask.cancel();
            apocalypseTask = null;
        }
        boolean wasActive = apocalypseCountdownActive;
        apocalypseCountdownActive = false;
        if (logAbort && wasActive) {
            plugin.getLogger().info(
                    "[GlitchCraft] Apocalypse countdown aborted: "
                            + "Not all non-exempt players are at Stage 4."
            );
        }
    }

    private void startApocalypseNow() {
        cancelApocalypseCountdown(false);
        advanceAllNonExemptPlayersToStage5();
        triggerApocalypseClimax();
        apocalypseModule.startApocalypseSequence();
    }

    private void advanceAllNonExemptPlayersToStage5() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isExempt(player)) {
                continue;
            }
            UUID playerId = player.getUniqueId();
            playerStages.put(playerId, 5);
            playerNames.put(playerId, player.getName());
            applyStage(player, 5);
            setNextStageCheck(playerId, nextCheckTick());
        }
        for (UUID playerId : mockPlayers.keySet()) {
            playerStages.put(playerId, 5);
            MockPlayer mockPlayer = mockPlayers.get(playerId);
            if (mockPlayer != null) {
                mockPlayerStages.put(mockPlayer.name(), 5);
            }
            setNextStageCheck(playerId, nextCheckTick());
        }
        savePlayerData();
    }

    public record MockPlayer(String name, PlayerProfile profile, org.bukkit.Location frameLocation) {
        public MockPlayer {
            profile = profile.clone();
            frameLocation = frameLocation.clone();
        }

        @Override
        public PlayerProfile profile() {
            return profile.clone();
        }

        @Override
        public org.bukkit.Location frameLocation() {
            return frameLocation.clone();
        }
    }

    private String findMockName(String name) {
        return mockPlayerNames.stream()
                .filter(mockName -> mockName.equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private void scheduleNextCheckIfAbsent(Player player) {
        if (!plugin.isExempt(player)) {
            UUID playerId = player.getUniqueId();
            playerNames.put(playerId, player.getName());
            if (!nextStageCheck.containsKey(playerId)) {
                restoreNextStageCheck(playerId);
            }
            if (!nextStageCheck.containsKey(playerId)) {
                setNextStageCheck(
                        playerId,
                        masterProgressionStarted && getPlayerStage(playerId) == 0
                                ? initialEntryDelayTick()
                                : nextCheckTick()
                );
            }
        }
    }

    public void loadPlayerData() {
        if (!playerDataFile.isFile()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(playerDataFile);
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return;
        }

        for (String idText : players.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(idText);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid player UUID in players.yml: " + idText);
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(idText);
            if (section == null) {
                continue;
            }
            String name = section.getString("name", idText);
            int stage = clampStage(section.getInt("stage", 0));
            long timestamp = section.getLong("next-escalation-timestamp", 0L);
            playerNames.put(playerId, name);
            playerStages.put(playerId, stage);
            if (timestamp <= 0L) {
                timestamp = System.currentTimeMillis() + nextCheckTickDelayMillis();
            }
            nextEscalationTimestamp.put(playerId, timestamp);
            restoreNextStageCheck(playerId);

            if (!section.getBoolean("mock", false)) {
                continue;
            }
            PlayerProfile profile = Bukkit.createProfile(playerId, name);
            List<ProfileProperty> properties = new ArrayList<>();
            for (Map<?, ?> propertyData : section.getMapList("profile-properties")) {
                Object propertyName = propertyData.get("name");
                Object value = propertyData.get("value");
                Object signature = propertyData.get("signature");
                if (propertyName instanceof String propertyKey && value instanceof String propertyValue) {
                    properties.add(signature instanceof String propertySignature
                            ? new ProfileProperty(propertyKey, propertyValue, propertySignature)
                            : new ProfileProperty(propertyKey, propertyValue));
                }
            }
            profile.setProperties(properties);
            Location frameLocation = loadMockLocation(section);
            MockPlayer mockPlayer = new MockPlayer(name, profile, frameLocation);
            mockPlayers.put(playerId, mockPlayer);
            mockPlayerIdsByName.put(name.toLowerCase(java.util.Locale.ROOT), playerId);
            mockPlayerNames.add(name);
            mockPlayerStages.put(name, stage);
        }
    }

    public void savePlayerData() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> entry : playerStages.entrySet()) {
            UUID playerId = entry.getKey();
            Player onlinePlayer = Bukkit.getPlayer(playerId);
            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                playerNames.put(playerId, onlinePlayer.getName());
            }
            updateSavedTimestampFromTick(playerId);
            String base = "players." + playerId;
            data.set(base + ".name", playerNames.getOrDefault(playerId, playerId.toString()));
            data.set(base + ".stage", entry.getValue());
            data.set(base + ".next-escalation-timestamp",
                    nextEscalationTimestamp.getOrDefault(playerId, System.currentTimeMillis()));

            MockPlayer mockPlayer = mockPlayers.get(playerId);
            if (mockPlayer == null) {
                data.set(base + ".mock", false);
                continue;
            }
            data.set(base + ".mock", true);
            data.set(base + ".frame-base.world", mockPlayer.frameLocation().getWorld() == null
                    ? ""
                    : mockPlayer.frameLocation().getWorld().getName());
            data.set(base + ".frame-base.x", mockPlayer.frameLocation().getX());
            data.set(base + ".frame-base.y", mockPlayer.frameLocation().getY());
            data.set(base + ".frame-base.z", mockPlayer.frameLocation().getZ());
            data.set(base + ".frame-base.yaw", mockPlayer.frameLocation().getYaw());
            data.set(base + ".frame-base.pitch", mockPlayer.frameLocation().getPitch());
            List<Map<String, Object>> properties = new ArrayList<>();
            for (ProfileProperty property : mockPlayer.profile().getProperties()) {
                Map<String, Object> propertyData = new HashMap<>();
                propertyData.put("name", property.getName());
                propertyData.put("value", property.getValue());
                if (property.getSignature() != null) {
                    propertyData.put("signature", property.getSignature());
                }
                properties.add(propertyData);
            }
            data.set(base + ".profile-properties", properties);
        }
        try {
            File parent = playerDataFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Could not create player data directory: " + parent);
            }
            data.save(playerDataFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save player progression data: " + exception.getMessage());
        }
    }

    public void savePlayerData(UUID playerId) {
        updateSavedTimestampFromTick(playerId);
        savePlayerData();
    }

    private Location loadMockLocation(ConfigurationSection section) {
        ConfigurationSection locationSection = section.getConfigurationSection("frame-base");
        World world = locationSection == null
                ? null
                : Bukkit.getWorld(locationSection.getString("world", ""));
        if (world == null) {
            world = Bukkit.getWorlds().stream().findFirst().orElseThrow(
                    () -> new IllegalStateException("Cannot restore mock players without a loaded world.")
            );
        }
        if (locationSection == null) {
            return world.getSpawnLocation();
        }
        return new Location(
                world,
                locationSection.getDouble("x"),
                locationSection.getDouble("y"),
                locationSection.getDouble("z"),
                (float) locationSection.getDouble("yaw"),
                (float) locationSection.getDouble("pitch")
        );
    }

    private void setNextStageCheck(UUID playerId, long nextTick) {
        nextStageCheck.put(playerId, nextTick);
        long remainingTicks = Math.max(0L, nextTick - plugin.getServer().getCurrentTick());
        nextEscalationTimestamp.put(playerId, System.currentTimeMillis() + remainingTicks * 50L);
    }

    private void restoreNextStageCheck(UUID playerId) {
        Long timestamp = nextEscalationTimestamp.get(playerId);
        if (timestamp == null) {
            return;
        }
        long remainingMillis = Math.max(0L, timestamp - System.currentTimeMillis());
        long remainingTicks = (remainingMillis + 49L) / 50L;
        nextStageCheck.put(playerId, plugin.getServer().getCurrentTick() + remainingTicks);
    }

    private void updateSavedTimestampFromTick(UUID playerId) {
        Long nextTick = nextStageCheck.get(playerId);
        if (nextTick == null) {
            return;
        }
        long remainingTicks = Math.max(0L, nextTick - plugin.getServer().getCurrentTick());
        nextEscalationTimestamp.put(playerId, System.currentTimeMillis() + remainingTicks * 50L);
    }

    private long nextCheckTickDelayMillis() {
        int minimumSeconds = Math.max(
                60,
                plugin.getConfig().getInt("progression.min-stage-delay-seconds", MIN_STAGE_DELAY_SECONDS)
        );
        int maximumSeconds = Math.max(
                minimumSeconds,
                plugin.getConfig().getInt("progression.max-stage-delay-seconds", MAX_STAGE_DELAY_SECONDS)
        );
        long seconds = ThreadLocalRandom.current().nextLong((long) minimumSeconds, (long) maximumSeconds + 1L);
        return seconds * 1000L;
    }

    private long nextCheckTick() {
        int minimumSeconds = Math.max(
                60,
                plugin.getConfig().getInt("progression.min-stage-delay-seconds", MIN_STAGE_DELAY_SECONDS)
        );
        int maximumSeconds = Math.max(
                minimumSeconds,
                plugin.getConfig().getInt("progression.max-stage-delay-seconds", MAX_STAGE_DELAY_SECONDS)
        );
        long seconds = ThreadLocalRandom.current().nextLong((long) minimumSeconds, (long) maximumSeconds + 1L);
        return plugin.getServer().getCurrentTick() + seconds * 20L;
    }

    private long initialEntryDelayTick() {
        long delayTicks = ThreadLocalRandom.current().nextLong(60L, 121L) * 60L * 20L;
        return plugin.getServer().getCurrentTick() + delayTicks;
    }

    private void applyStage(Player player, int stage) {
        applyStage(player, stage, true);
    }

    private void applyStage(Player player, int stage, boolean notifyPlayer) {
        UUID playerId = player.getUniqueId();
        if (plugin.isExempt(player)) {
            return;
        }

        quantumModule.disableTarget(playerId);
        blockGlitchModule.disableTarget(playerId);
        mimicModule.disableTarget(playerId);
        stalkerModule.disableTarget(playerId);
        invertedAnomalyModule.disableTarget(playerId);
        apocalypseModule.stopFor(playerId);

        if (stage >= 1) {
            blockGlitchModule.enableTarget(player);
        }
        if (stage >= 2) {
            targetManager.addMimicTarget(playerId);
        }
        if (stage >= 3) {
            stalkerModule.enableProgressionTarget(player);
        }
        if (stage >= 4) {
            invertedAnomalyModule.enableTarget(player);
        }
        if (stage >= 5) {
            apocalypseModule.startFor(player);
        }
        if (notifyPlayer) {
            player.sendMessage(Component.text("[GlitchCraft] Your world corruption stage is now " + stage + "."));
        }
    }

    private int clampStage(int stage) {
        return Math.max(0, Math.min(5, stage));
    }

    private void appendEffect(StringBuilder effects, boolean enabled, String effect) {
        if (!enabled) {
            return;
        }
        if (!effects.isEmpty()) {
            effects.append(", ");
        }
        effects.append(effect);
    }
}
