package com.donismp.glitchcraft.manager;

import java.util.HashSet;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class TargetManager {

    private final HashSet<UUID> glitchTargets = new HashSet<>();
    private final HashSet<UUID> mimicTargets = new HashSet<>();
    private final HashSet<UUID> stalkerTargets = new HashSet<>();

    public void addGlitchTarget(@NotNull UUID playerId) {
        glitchTargets.add(playerId);
    }

    public boolean removeGlitchTarget(@NotNull UUID playerId) {
        return glitchTargets.remove(playerId);
    }

    public boolean toggleGlitchTarget(@NotNull UUID playerId) {
        if (glitchTargets.remove(playerId)) {
            return false;
        }
        return glitchTargets.add(playerId);
    }

    public boolean isGlitchTarget(@NotNull UUID playerId) {
        return glitchTargets.contains(playerId);
    }

    public void addMimicTarget(@NotNull UUID playerId) {
        mimicTargets.add(playerId);
    }

    public boolean removeMimicTarget(@NotNull UUID playerId) {
        return mimicTargets.remove(playerId);
    }

    public boolean toggleMimicTarget(@NotNull UUID playerId) {
        if (mimicTargets.remove(playerId)) {
            return false;
        }
        return mimicTargets.add(playerId);
    }

    public boolean isMimicTarget(@NotNull UUID playerId) {
        return mimicTargets.contains(playerId);
    }

    public void addStalkerTarget(@NotNull UUID playerId) {
        stalkerTargets.add(playerId);
    }

    public boolean removeStalkerTarget(@NotNull UUID playerId) {
        return stalkerTargets.remove(playerId);
    }

    public boolean toggleStalkerTarget(@NotNull UUID playerId) {
        if (stalkerTargets.remove(playerId)) {
            return false;
        }
        return stalkerTargets.add(playerId);
    }

    public boolean isStalkerTarget(@NotNull UUID playerId) {
        return stalkerTargets.contains(playerId);
    }

    public void clearTargets() {
        glitchTargets.clear();
        mimicTargets.clear();
        stalkerTargets.clear();
    }

    public boolean isVanished(@NotNull Player player) {
        return player.isInvisible() || Bukkit.getOnlinePlayers().stream()
                .filter(viewer -> !viewer.getUniqueId().equals(player.getUniqueId()))
                .anyMatch(viewer -> !viewer.canSee(player));
    }
}
