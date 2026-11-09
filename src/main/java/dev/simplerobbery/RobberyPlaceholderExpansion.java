package dev.simplerobbery;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** Embedded PlaceholderAPI expansion. No separate /papi ecloud download needed. */
public final class RobberyPlaceholderExpansion extends PlaceholderExpansion {
    private final SimpleRobbery plugin;
    public RobberyPlaceholderExpansion(SimpleRobbery plugin){this.plugin=plugin;}
    @Override public @NotNull String getIdentifier(){return "simplerobbery";}
    @Override public @NotNull String getAuthor(){return "SimpleRobbery";}
    @Override public @NotNull String getVersion(){return plugin.getDescription().getVersion();}
    @Override public boolean persist(){return true;}
    @Override public String onRequest(OfflinePlayer player,@NotNull String identifier){
        return plugin.placeholder(player==null?null:player.getUniqueId(),identifier);
    }
}
