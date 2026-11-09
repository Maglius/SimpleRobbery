package dev.simplerobbery;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/** Kept separate so SimpleRobbery loads without PlaceholderAPI installed. */
final class RobberyPapiBridge {
    private RobberyPapiBridge(){}
    static String expand(Player player,String message){return PlaceholderAPI.setPlaceholders(player,message);}
}
