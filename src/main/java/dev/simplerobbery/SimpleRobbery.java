package dev.simplerobbery;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerTextures;
import java.net.URL;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** SimpleRobbery minigame.  No client mods, resource packs, WorldEdit, or Vault dependency. */
public final class SimpleRobbery extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, RobberyData> robberies = new LinkedHashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, ChatInput> inputs = new ConcurrentHashMap<>();
    private final Map<UUID, Crack> cracks = new HashMap<>();
    // Intruders are flagged ONLY after attempting to break a vault. This is intentionally
    // per-open-session state: after shutdown/reopen no visitors inherit old aggro.
    private final Map<String, Set<UUID>> intruders = new HashMap<>();
    private final DecimalFormat moneyFormat = new DecimalFormat("0.##");
    private File dataFile, discordFile;
    private YamlConfiguration menus;
    private RobberyStats stats;
    private static final Pattern OWN_PLACEHOLDERS=Pattern.compile("%simplerobbery_([a-z0-9_-]+)%|\\{(player_success|player_failed|player_attempts|player_money|player_rank|global_success|global_failed|global_attempts|global_money|active_robberies)\\}",Pattern.CASE_INSENSITIVE);
    private YamlConfiguration messages, discord;
    private NamespacedKey wandKey, guardKey, guardRoleKey, guardSessionKey;
    private final String sessionId=UUID.randomUUID().toString();
    private final Map<UUID, Location> guardHome = new HashMap<>();
    private final Map<String, List<Long>> guardRespawnQueue = new HashMap<>();
    private static final LinkedHashMap<Integer,EquipmentSlot> EQUIPMENT_SLOTS=new LinkedHashMap<>();
    static {
        EQUIPMENT_SLOTS.put(13, EquipmentSlot.HEAD);
        EQUIPMENT_SLOTS.put(22, EquipmentSlot.CHEST);
        EQUIPMENT_SLOTS.put(31, EquipmentSlot.LEGS);
        EQUIPMENT_SLOTS.put(40, EquipmentSlot.FEET);
        EQUIPMENT_SLOTS.put(20, EquipmentSlot.HAND);
        EQUIPMENT_SLOTS.put(24, EquipmentSlot.OFF_HAND);
    }
    private BukkitTask updateTask;
    private long lastGuardCheck;
    private final Map<UUID, UUID> guardVisuals = new HashMap<>(); // invisible hostile AI -> player-skin mannequin
    private final Map<UUID, UUID> guardControllers = new HashMap<>(); // mannequin -> hostile AI
    private static final List<String> GUARD_TYPES = List.of(
        "ZOMBIE","HUSK","DROWNED","SKELETON","STRAY","BOGGED",
        "WITHER_SKELETON","PILLAGER","VINDICATOR","WITCH","RAVAGER","PLAYER");

    private record Selection(String mode, String name, Location pos1, Location pos2) {
        Selection set(boolean first, Location value) {return new Selection(mode,name,first?value:pos1,first?pos2:value);}
        boolean complete() {return pos1!=null && pos2!=null;}
    }
    private record ChatInput(String id, String setting, int rewardSlot) {}
    private record Crack(String id, Location block, long started) {}

    private abstract static class Menu implements InventoryHolder {
        Inventory inv;
        final String robberyId;
        Menu(String robberyId) {this.robberyId=robberyId;}
        @Override public Inventory getInventory() {return inv;}
    }
    private static final class SettingsMenu extends Menu {SettingsMenu(String id){super(id);}}
    private static final class GuardTypeMenu extends Menu {GuardTypeMenu(String id){super(id);}}
    private static final class GuardEquipmentMenu extends Menu {GuardEquipmentMenu(String id){super(id);}}
    private static final class RewardsMenu extends Menu {
        final int page;
        RewardsMenu(String id,int page){super(id);this.page=page;}
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResourceIfAbsent("message.yml");
        saveResourceIfAbsent("discord.yml");
        saveResourceIfAbsent("menus.yml");
        reloadExternal();
        wandKey=new NamespacedKey(this,"selection_wand");
        guardKey=new NamespacedKey(this,"robbery_guard");
        guardRoleKey=new NamespacedKey(this,"robbery_guard_role");
        guardSessionKey=new NamespacedKey(this,"robbery_guard_session");
        dataFile = new File(getDataFolder(),"robberies.yml");
        readData();
        stats = new RobberyStats(new File(getDataFolder(),"stats.yml"),getLogger());
        if(!new File(getDataFolder(),"stats.yml").exists())stats.save();
        if(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {new RobberyPlaceholderExpansion(this).register(); getLogger().info("Registered PlaceholderAPI expansion: simplerobbery");}
            catch(LinkageError ex) {getLogger().warning("Could not register PlaceholderAPI: "+ex.getMessage());}
        }
        // Remove *all* old guard entities including mannequins / visuals. This scans every
        // loaded chunk, not only living mobs, and ChunkLoadEvent handles old unloaded chunks.
        cleanupTaggedGuards(null, true);
        Bukkit.getPluginManager().registerEvents(this,this);
        Objects.requireNonNull(getCommand("robbery")).setExecutor(this);
        Objects.requireNonNull(getCommand("robbery")).setTabCompleter(this);
        // 2-tick task: actionbar, holding validation and scheduled open/close periods.
        updateTask=Bukkit.getScheduler().runTaskTimer(this, this::pulse, 2L, 2L);
        getLogger().info("Loaded " + robberies.size() + " robberies. Discord webhook " +
                (discord.getBoolean("enabled") ? "enabled" : "disabled") + ".");
    }

    @Override public void onDisable() {
        if(updateTask!=null) updateTask.cancel();
        cracks.clear();
        intruders.clear();
        for(RobberyData r : robberies.values()) clearGuards(r);
        cleanupTaggedGuards(null,true);
        guardVisuals.clear();guardControllers.clear();guardHome.clear();
        saveData();
        if(stats!=null)stats.save();
    }

    private void saveResourceIfAbsent(String name) {
        if(!new File(getDataFolder(),name).exists()) saveResource(name,false);
    }
    private void reloadExternal() {
        reloadConfig();
        File messageFile=new File(getDataFolder(),"message.yml");
        messages=YamlConfiguration.loadConfiguration(messageFile);
        // Merge newly introduced labels into existing installations without overwriting custom text.
        try(InputStream in=getResource("message.yml")) {
            if(in!=null) {
                YamlConfiguration bundled=YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
                boolean changed=false;
                for(String key:bundled.getKeys(true)){
                    if(bundled.isConfigurationSection(key) || messages.contains(key,true))continue;
                    messages.set(key,bundled.get(key));changed=true;
                }
                if(changed)messages.save(messageFile);
            }
        }catch(IOException ex){getLogger().log(Level.WARNING,"Cannot upgrade message.yml",ex);}
        File menusFile=new File(getDataFolder(),"menus.yml");
        menus=YamlConfiguration.loadConfiguration(menusFile);
        try(InputStream in=getResource("menus.yml")) {
            if(in!=null) {
                YamlConfiguration bundled=YamlConfiguration.loadConfiguration(new InputStreamReader(in,StandardCharsets.UTF_8));
                boolean changed=false;
                for(String key:bundled.getKeys(true)) {
                    if(bundled.isConfigurationSection(key)||menus.contains(key,true))continue;
                    menus.set(key,bundled.get(key));changed=true;
                }
                if(changed)menus.save(menusFile);
            }
        }catch(IOException ex){getLogger().log(Level.WARNING,"Cannot upgrade menus.yml",ex);}
        discordFile=new File(getDataFolder(),"discord.yml");
        discord=YamlConfiguration.loadConfiguration(discordFile);
    }
    private void readData() {
        robberies.clear();
        if(!dataFile.exists()) return;
        YamlConfiguration yaml=YamlConfiguration.loadConfiguration(dataFile);
        var section=yaml.getConfigurationSection("robberies");
        if(section==null)return;
        for(String id: section.getKeys(false)) {
            var sub=section.getConfigurationSection(id);
            if(sub==null)continue;
            try {
                RobberyData r=new RobberyData(id,sub.getString("display-name",id));
                r.load(sub);
                robberies.put(id,r);
            } catch(Exception e) {getLogger().log(Level.WARNING,"Unable to read robbery "+id,e);}
        }
    }
    private void saveData() {
        if(dataFile==null)return;
        YamlConfiguration y=new YamlConfiguration();
        for(RobberyData r:robberies.values())r.save(y.createSection("robberies."+r.id));
        try {y.save(dataFile);}catch(IOException e){getLogger().log(Level.SEVERE,"Cannot save robberies.yml",e);}
    }

    // Accept legacy & codes plus both standard config hex notations: &#RRGGBB and <#RRGGBB>.
    private static final Pattern HEX_COLOR=Pattern.compile("(?i)&#([0-9a-f]{6})|<#([0-9a-f]{6})>");
    private String color(String text) {
        if(text==null)return "";
        Matcher matcher=HEX_COLOR.matcher(text);
        StringBuffer out=new StringBuffer();
        while(matcher.find()) {
            String hex=matcher.group(1)!=null?matcher.group(1):matcher.group(2);
            StringBuilder encoded=new StringBuilder("\u00a7x");
            for(char c:hex.toCharArray())encoded.append('\u00a7').append(Character.toLowerCase(c));
            matcher.appendReplacement(out,Matcher.quoteReplacement(encoded.toString()));
        }
        matcher.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&',out.toString());
    }
    private String fill(String s, Object... pairs) {
        for(int i=0;i+1<pairs.length;i+=2)s=s.replace("{"+pairs[i]+"}",String.valueOf(pairs[i+1]));
        return color(s);
    }
    private String msg(String path,Object... pairs){return fill(messages.getString(path,"&cMissing message: "+path),pairs);}
    private String prefix(){return msg("prefix");}
    private void tell(CommandSender c,String path,Object... pairs){
        String content=prefix()+msg(path,pairs);
        c.sendMessage(expandOwn(content,c instanceof Player p?p.getUniqueId():null));
    }
    private void announce(String path,Object... pairs){
        String text=prefix()+msg(path,pairs);
        Bukkit.broadcastMessage(expandOwn(text,null));
    }
    private String money(double value) {synchronized(moneyFormat){return moneyFormat.format(value);}}
    private String remaining(long timestamp){
        long seconds=Math.max(0, (timestamp-System.currentTimeMillis()+999)/1000);
        if(seconds>=3600)return (seconds/3600)+"h "+((seconds%3600)/60)+"m";
        if(seconds>=60)return (seconds/60)+"m "+(seconds%60)+"s";
        return seconds+"s";
    }
    private void sound(Player p, Sound sound,float pitch){p.playSound(p.getLocation(),sound,0.9f,pitch);}
    private void actionbar(Player p,String message){p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(message));}

    private String normalize(String s){return s.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9_-]+","_");}
    private RobberyData find(String value){
        if(value==null)return null;
        String id=normalize(value);
        RobberyData r=robberies.get(id);
        if(r!=null)return r;
        for(RobberyData candidate:robberies.values()) if(candidate.displayName.equalsIgnoreCase(value))return candidate;
        return null;
    }
    private boolean admin(CommandSender c){
        if(c.hasPermission("robbery.admin"))return true;
        tell(c,"errors.no-permission");return false;
    }
    private Player requirePlayer(CommandSender c){
        if(c instanceof Player p)return p;
        tell(c,"errors.players-only");return null;
    }
    private RobberyData getNamed(CommandSender c,String[] args){
        if(args.length<2){tell(c,"admin.help");return null;}
        RobberyData r=find(String.join(" ", Arrays.copyOfRange(args,1,args.length)));
        if(r==null)tell(c,"errors.robbery-not-found","name",String.join(" ",Arrays.copyOfRange(args,1,args.length)));
        return r;
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(args.length==0||args[0].equalsIgnoreCase("help")){
            int page=1;
            if(args.length>=2)try{page=Integer.parseInt(args[1]);}catch(NumberFormatException ignored){}
            List<String> entries=messages.getStringList("admin.help-entries");
            if(entries.isEmpty()){tell(sender,"admin.help");return true;}
            int per=Math.max(4,Math.min(5,messages.getInt("admin.help-page-size",5)));
            int pages=Math.max(1,(entries.size()+per-1)/per);page=Math.max(1,Math.min(page,pages));
            tell(sender,"admin.help-header","page",page,"pages",pages);
            for(int i=(page-1)*per;i<Math.min(page*per,entries.size());i++)
                sender.sendMessage(expandOwn(fill(entries.get(i)),sender instanceof Player p?p.getUniqueId():null));
            tell(sender,"admin.help-footer","page",page,"pages",pages);
            return true;
        }
        String cmd=args[0].toLowerCase(Locale.ROOT);
        if(cmd.equals("list")||cmd.equals("status")){
            if(robberies.isEmpty()){tell(sender,"admin.list-empty");return true;}
            for(RobberyData r:robberies.values()){
                String world=r.region==null?"unset":r.region.world;
                String coords=r.region==null?"unset":r.region.shortDesc();
                String opensIn=r.open?remaining(r.nextSwitchAt+r.closedMinutes*60_000L):remaining(r.nextSwitchAt);
                tell(sender,"admin.list-line","name",r.displayName,"state",r.open?"&aOPEN":"&cCLOSED",
                    "world",world,"coords",coords,"remaining",remaining(r.nextSwitchAt),"opens_in",opensIn);
            }
            return true;
        }
        if(cmd.equals("stats")){
            UUID target=sender instanceof Player p?p.getUniqueId():null;
            String targetName=sender.getName();
            if(args.length>=2){
                if(!sender.hasPermission("robbery.admin")){tell(sender,"errors.no-permission");return true;}
                RobberyStats.PlayerRecord selected=stats.findByName(String.join(" ",Arrays.copyOfRange(args,1,args.length)));
                if(selected==null){tell(sender,"admin.stats-not-found");return true;}
                target=selected.uuid;targetName=selected.lastName;
            }
            if(target==null){tell(sender,"errors.players-only");return true;}
            RobberyStats.PlayerRecord record=stats.find(target);
            tell(sender,"admin.stats-line","player",record==null?targetName:record.lastName,
                "success",record==null?0:record.successes,"failed",record==null?0:record.failures,
                "attempts",record==null?0:record.attempts,"earned",money(record==null?0:record.money),
                "rank",stats.rank(target,"success"));
            return true;
        }
        if(cmd.equals("top")||cmd.equals("leaderboard")){
            String type=args.length>=2?args[1].toLowerCase(Locale.ROOT):"success";
            if(!Set.of("success","failed","failures","attempts","money").contains(type)){
                tell(sender,"admin.top-usage");return true;
            }
            int page=1;if(args.length>=3)try{page=Integer.parseInt(args[2]);}catch(NumberFormatException ignored){}
            List<RobberyStats.PlayerRecord> rows=stats.top(type);
            int pages=Math.max(1,(rows.size()+4)/5);page=Math.max(1,Math.min(page,pages));
            tell(sender,"admin.top-header","type",type,"page",page,"pages",pages);
            for(int index=(page-1)*5;index<Math.min(rows.size(),page*5);index++){
                RobberyStats.PlayerRecord row=rows.get(index);
                String value=switch(type){
                    case "failed","failures"->Long.toString(row.failures);
                    case "attempts"->Long.toString(row.attempts);
                    case "money"->money(row.money);
                    default->Long.toString(row.successes);
                };
                tell(sender,"admin.top-line","rank",index+1,"player",row.lastName,"value",value);
            }
            return true;
        }
        if(!admin(sender))return true;
        switch(cmd){
            case "wand" -> {
                Player p=requirePlayer(sender);if(p==null)return true;
                giveWand(p);
                selections.putIfAbsent(p.getUniqueId(),new Selection("new",null,null,null));
                tell(p,"admin.wand-given");
            }
            case "create" -> {
                Player p=requirePlayer(sender);if(p==null)return true;
                if(args.length<2){tell(p,"admin.help");return true;}
                String name=String.join(" ",Arrays.copyOfRange(args,1,args.length)).trim();
                String id=normalize(name);
                if(id.isBlank()){tell(p,"admin.help");return true;}
                if(robberies.containsKey(id)){tell(p,"errors.exists");return true;}
                Selection selection=selections.get(p.getUniqueId());
                if(selection==null||!selection.complete()){tell(p,"errors.no-selection");return true;}
                RobberyData.Box box;
                try {box=new RobberyData.Box(selection.pos1,selection.pos2);}
                catch(IllegalArgumentException e){tell(p,"errors.different-worlds");return true;}
                if(box.volume()>getConfig().getLong("max-region-blocks",1000000)){
                    tell(p,"errors.limit");return true;
                }
                RobberyData r=new RobberyData(id,name);r.region=box;
                r.openMinutes=Math.max(1,getConfig().getInt("defaults.open-minutes",10));
                r.closedMinutes=Math.max(1,getConfig().getInt("defaults.closed-minutes",60));
                r.crackSeconds=Math.max(1,getConfig().getInt("defaults.crack-seconds",15));
                r.payout=getConfig().getDouble("defaults.payout",1000);
                r.guardsEnabled=getConfig().getBoolean("defaults.bodyguards-enabled",false);
                r.guardCount=getConfig().getInt("defaults.bodyguards-count",3);
                r.guardType=getConfig().getString("defaults.bodyguards-type","ZOMBIE").toUpperCase(Locale.ROOT);
                r.guardPlayerSkin=getConfig().getString("defaults.bodyguards-skin","Steve");
                r.requireEscape=getConfig().getBoolean("defaults.require-escape",false);
                r.guardHealth=getConfig().getDouble("defaults.bodyguards-health",30.0);
                r.guardSpeed=getConfig().getDouble("defaults.bodyguards-speed",0.28);
                r.guardDamage=getConfig().getDouble("defaults.bodyguards-damage",4.0);
                r.guardFollowRange=getConfig().getDouble("defaults.bodyguards-follow-range",18.0);
                r.guardRespawnSeconds=getConfig().getInt("defaults.bodyguards-respawn-seconds",30);
                r.nextSwitchAt=System.currentTimeMillis()+r.closedMinutes*60_000L;
                robberies.put(id,r);saveData();
                selections.remove(p.getUniqueId());
                tell(p,"admin.created","name",name);
                webhook("created",r,"",r.payout);
                openSettings(p,r);
            }
            case "edit", "gui" -> {
                Player p=requirePlayer(sender);if(p==null)return true;
                RobberyData r=getNamed(sender,args);if(r!=null)openSettings(p,r);
            }
            case "vault", "region" -> {
                Player p=requirePlayer(sender);if(p==null)return true;
                RobberyData r=getNamed(sender,args);if(r==null)return true;
                select(p,r,cmd);
            }
            case "start", "open", "stop", "close" -> {
                RobberyData r=getNamed(sender,args);if(r==null)return true;
                boolean toOpen=cmd.equals("start")||cmd.equals("open");
                if(toOpen && !r.ready()){tell(sender,"errors.invalid-vault");return true;}
                switchState(r,toOpen);
                tell(sender,toOpen?"admin.force-open":"admin.force-close","name",r.displayName);
            }
            case "delete" -> {
                RobberyData r=getNamed(sender,args);if(r==null)return true;
                clearGuards(r);intruders.remove(r.id);guardRespawnQueue.remove(r.id);
                robberies.remove(r.id);saveData();
                webhook("deleted",r,"",r.payout);
                tell(sender,"admin.deleted","name",r.displayName);
            }
            case "reload" -> {
                reloadExternal();tell(sender,"admin.reload");
            }
            case "cancel" -> {
                if(sender instanceof Player p){selections.remove(p.getUniqueId());inputs.remove(p.getUniqueId());}
                tell(sender,"admin.cancelled");
            }
            default -> tell(sender,"errors.unknown-command");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command c,String alias,String[] args){
        if(args.length==1){
            return Arrays.asList("help","list","status","stats","top","leaderboard","wand","create","edit","vault","region","start","stop","delete","reload","cancel")
                 .stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if(args.length==2 && args[0].equalsIgnoreCase("help"))return List.of("1","2","3");
        if(args.length==2 && (args[0].equalsIgnoreCase("top")||args[0].equalsIgnoreCase("leaderboard")))return List.of("success","failed","money","attempts");
        if(args.length==2)return robberies.keySet().stream().filter(s->s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    /** Exposed to the optional PlaceholderAPI expansion. Unknown placeholders return null. */
    public String placeholder(UUID uuid,String raw){
        if(stats==null||raw==null)return null;
        String key=raw.toLowerCase(Locale.ROOT);
        RobberyStats.PlayerRecord record=stats.find(uuid);
        long successes=record==null?0:record.successes, failures=record==null?0:record.failures;
        long attempts=record==null?0:record.attempts;
        switch(key){
            case "player_success", "player_successes", "player_completed"->{return Long.toString(successes);}
            case "player_failed", "player_failures"->{return Long.toString(failures);}
            case "player_attempts"->{return Long.toString(attempts);}
            case "player_money", "player_earned"->{return money(record==null?0:record.money);}
            case "player_rank"->{return Integer.toString(stats.rank(uuid,"success"));}
            case "player_success_rate"->{return money((successes+failures)==0?0:successes*100.0/(successes+failures));}
            case "player_failure_rate"->{return money((successes+failures)==0?0:failures*100.0/(successes+failures));}
            case "player_pending"->{return uuid!=null && robberies.values().stream().anyMatch(r->r.pendingClaims.containsKey(uuid))?"true":"false";}
            case "player_wanted"->{return uuid!=null && intruders.values().stream().anyMatch(players->players.contains(uuid))?"true":"false";}
            case "player_current_robbery"->{
                Player p=uuid==null?null:Bukkit.getPlayer(uuid);
                if(p==null)return "none";
                for(RobberyData r:robberies.values())if(r.region!=null && r.region.contains(p.getLocation()))return r.displayName;
                return "none";
            }
            case "global_success", "global_successes", "global_completed"->{return Long.toString(stats.successes());}
            case "global_failed", "global_failures"->{return Long.toString(stats.failures());}
            case "global_attempts"->{return Long.toString(stats.attempts());}
            case "global_money", "global_paid"->{return money(stats.money());}
            case "global_players"->{return Integer.toString(stats.uniquePlayers());}
            case "global_success_rate"->{return money((stats.successes()+stats.failures())==0?0:
                stats.successes()*100.0/(stats.successes()+stats.failures()));}
            case "global_wanted"->{return Long.toString(intruders.values().stream().flatMap(Set::stream).distinct().count());}
            case "active_robberies", "global_open"->{return Long.toString(robberies.values().stream().filter(r->r.open).count());}
            case "total_robberies", "global_robberies"->{return Integer.toString(robberies.size());}
        }
        if(key.startsWith("top_")){
            Matcher matcher=Pattern.compile("top_(success|failed|failures|attempts|money)_(\\d+)_(name|count|amount)").matcher(key);
            if(matcher.matches()){
                String field=matcher.group(1);int rank;
                try{rank=Integer.parseInt(matcher.group(2));}catch(NumberFormatException ex){return null;}
                if(rank<1||rank>100)return null;
                List<RobberyStats.PlayerRecord> leaderboard=stats.top(field);
                if(rank>leaderboard.size())return matcher.group(3).equals("name")?"---":"0";
                RobberyStats.PlayerRecord p=leaderboard.get(rank-1);
                return switch(matcher.group(3)){
                    case "name" -> p.lastName;
                    case "amount" -> money(p.money);
                    default -> Long.toString(field.equals("failed")||field.equals("failures")?p.failures:
                        field.equals("attempts")?p.attempts:field.equals("money")?(long)p.money:p.successes);
                };
            }
        }
        if(key.startsWith("robbery_")){
            for(RobberyData r:robberies.values()){
                String prefix="robbery_"+r.id+"_";
                if(!key.startsWith(prefix))continue;
                RobberyStats.RegionRecord stat=stats.region(r.id);
                return switch(key.substring(prefix.length())){
                    case "status"->r.open?"OPEN":"CLOSED";
                    case "time"->remaining(r.nextSwitchAt);
                    case "opens_in"->remaining(r.open?r.nextSwitchAt+r.closedMinutes*60_000L:r.nextSwitchAt);
                    case "world"->r.region==null?"unset":r.region.world;
                    case "coords"->r.region==null?"unset":r.region.shortDesc();
                    case "vault_coords"->r.vault==null?"unset":r.vault.shortDesc();
                    case "payout"->money(r.payout);
                    case "success"->Long.toString(stat.successes);
                    case "failed"->Long.toString(stat.failures);
                    case "attempts"->Long.toString(stat.attempts);
                    case "open_minutes"->Integer.toString(r.openMinutes);
                    case "closed_minutes"->Integer.toString(r.closedMinutes);
                    case "crack_seconds"->Integer.toString(r.crackSeconds);
                    case "guards_enabled"->Boolean.toString(r.guardsEnabled);
                    case "guard_count"->Integer.toString(r.guardCount);
                    case "guards_active"->Integer.toString(r.guards.size());
                    case "require_escape"->Boolean.toString(r.requireEscape);
                    default->null;
                };
            }
        }
        return null;
    }
    private String expandOwn(String text,UUID uuid){
        if(text==null)return "";
        Matcher matcher=OWN_PLACEHOLDERS.matcher(text);StringBuffer result=new StringBuffer();
        while(matcher.find()){
            String key=matcher.group(1)!=null?matcher.group(1):matcher.group(2);
            String resolved=placeholder(uuid,key);
            matcher.appendReplacement(result,Matcher.quoteReplacement(resolved==null?matcher.group():resolved));
        }
        matcher.appendTail(result);
        String own=result.toString();
        if(uuid!=null && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            Player p=Bukkit.getPlayer(uuid);
            if(p!=null)try{return RobberyPapiBridge.expand(p,own);}catch(LinkageError ignored){}
        }
        return own;
    }
    private void giveWand(Player p){
        ItemStack item=new ItemStack(Material.GOLDEN_AXE);
        ItemMeta meta=item.getItemMeta();
        meta.setDisplayName(color("&6&lRobbery Selection Wand"));
        meta.setLore(List.of(color("&7Left-click: first corner"),color("&7Right-click: second corner")));
        meta.getPersistentDataContainer().set(wandKey,PersistentDataType.BYTE,(byte)1);
        item.setItemMeta(meta);
        p.getInventory().addItem(item).values().forEach(rest->p.getWorld().dropItemNaturally(p.getLocation(),rest));
    }
    private boolean isWand(ItemStack item){return item!=null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(wandKey,PersistentDataType.BYTE);}
    private void select(Player p, RobberyData r,String mode){
        boolean hasWand=false;
        for(ItemStack slot : p.getInventory().getStorageContents()) if(isWand(slot)){hasWand=true;break;}
        if(!hasWand)giveWand(p);
        selections.put(p.getUniqueId(),new Selection(mode,r.id,null,null));
        tell(p,mode.equals("vault")?"admin.select-vault":"admin.select-region","name",r.displayName);
        sound(p,Sound.UI_BUTTON_CLICK,1.2f);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void wandUse(PlayerInteractEvent e){
        if(e.getHand()!=EquipmentSlot.HAND || !e.getPlayer().hasPermission("robbery.admin"))return;
        if(!isWand(e.getPlayer().getInventory().getItemInMainHand()))return;
        if(e.getClickedBlock()==null)return;
        boolean first=e.getAction()==Action.LEFT_CLICK_BLOCK;
        boolean second=e.getAction()==Action.RIGHT_CLICK_BLOCK;
        if(!first&&!second)return;
        e.setCancelled(true);
        Player p=e.getPlayer();
        Selection current=selections.getOrDefault(p.getUniqueId(),new Selection("new",null,null,null));
        Selection next=current.set(first,e.getClickedBlock().getLocation());
        selections.put(p.getUniqueId(),next);
        Location loc=e.getClickedBlock().getLocation();
        tell(p,first?"admin.pos1":"admin.pos2","x",loc.getBlockX(),"y",loc.getBlockY(),"z",loc.getBlockZ(),"world",loc.getWorld().getName());
        sound(p,Sound.UI_BUTTON_CLICK,first?0.9f:1.3f);
        if(!next.complete() || next.name==null)return;
        RobberyData r=robberies.get(next.name);
        if(r==null)return;
        RobberyData.Box box;
        try{box=new RobberyData.Box(next.pos1,next.pos2);}
        catch(IllegalArgumentException ex){tell(p,"errors.different-worlds");return;}
        if(next.mode.equals("vault")){
            if(box.volume()>getConfig().getLong("max-vault-blocks",500)){tell(p,"errors.limit");return;}
            if(r.region==null || !r.region.contains(box)){tell(p,"errors.not-in-region");return;}
            r.vault=box; tell(p,"admin.vault-saved","name",r.displayName);
        } else {
            if(box.volume()>getConfig().getLong("max-region-blocks",1000000)){tell(p,"errors.limit");return;}
            if(r.vault!=null&&!box.contains(r.vault)){tell(p,"errors.not-in-region");return;}
            r.region=box; tell(p,"admin.region-saved","name",r.displayName);
        }
        selections.remove(p.getUniqueId());saveData();
        openSettings(p,r);
    }

    private ItemStack icon(Material type,String title,List<String> lore){
        return icon(type,title,lore,null);
    }
    /**
     * Optional menu icon keys: head-owner (Java username) and head-texture
     * (Minecraft textures URL, raw texture hash, or Base64 Mojang texture JSON).
     * Does not make HTTP requests; the client downloads the standard skin texture.
     */
    private ItemStack icon(Material type,String title,List<String> lore,ConfigurationSection iconSettings){
        ItemStack item=new ItemStack(type);
        ItemMeta meta=item.getItemMeta();
        if(meta==null)return item;
        meta.setDisplayName(title);
        meta.setLore(lore);
        if(meta instanceof SkullMeta skull && iconSettings!=null){
            String texture=iconSettings.getString("head-texture","").trim();
            String owner=iconSettings.getString("head-owner","").trim();
            if(!texture.isEmpty()){
                try {
                    String url=resolveMinecraftHeadTexture(texture);
                    if(url!=null){
                        // Bukkit PlayerProfile API is supported by Paper 26.2.
                        org.bukkit.profile.PlayerProfile profile=Bukkit.createPlayerProfile(UUID.randomUUID());
                        PlayerTextures textures=profile.getTextures();
                        textures.setSkin(URI.create(url).toURL());
                        profile.setTextures(textures);
                        skull.setOwnerProfile(profile);
                    }
                }catch(IllegalArgumentException|IOException ex){
                    getLogger().warning("Invalid head-texture in menus.yml: "+ex.getMessage());
                }
            } else if(!owner.isEmpty()) {
                // Resolves the stored player texture from the server profile cache.
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            }
        }
        item.setItemMeta(meta);
        return item;
    }
    private String resolveMinecraftHeadTexture(String value){
        String trimmed=value.trim();
        if(trimmed.matches("(?i)[a-f0-9]{32,128}"))
            return "https://textures.minecraft.net/texture/"+trimmed;
        if(!trimmed.startsWith("https://")){
            String decoded;
            try{decoded=new String(Base64.getDecoder().decode(trimmed),StandardCharsets.UTF_8);}
            catch(IllegalArgumentException ex){return null;}
            Matcher match=Pattern.compile("https://textures\\.minecraft\\.net/texture/[a-zA-Z0-9]+",Pattern.CASE_INSENSITIVE).matcher(decoded);
            if(!match.find())return null;
            trimmed=match.group();
        }
        URI uri=URI.create(trimmed);
        if(!"https".equalsIgnoreCase(uri.getScheme()) || !"textures.minecraft.net".equalsIgnoreCase(uri.getHost()) ||
           !uri.getPath().matches("/texture/[a-zA-Z0-9]{32,128}"))return null;
        return uri.toString();
    }
    private ItemStack entry(Material mat,String key,Object... pairs){
        List<String> lore=new ArrayList<>();
        for(String line:messages.getStringList("gui."+key+".lore"))lore.add(fill(line,pairs));
        return icon(mat,msg("gui."+key+".name",pairs),lore);
    }
    private Material guiMaterial(String material,Material fallback){
        if(material==null || material.isBlank())return fallback;
        Material result=Material.matchMaterial(material.trim().toUpperCase(Locale.ROOT));
        return result==null||result.isAir()?fallback:result;
    }
    private int menuSize(String menu,int fallback){
        int size=menus.getInt(menu+".size",fallback);
        return size>=9&&size<=54&&size%9==0?size:fallback;
    }
    private String menuTitle(String menu,String fallback,UUID player,Object... pairs){
        return expandOwn(fill(menus.getString(menu+".title",fallback),pairs),player);
    }
    private void background(Inventory inv,String menu){
        if(!menus.getBoolean(menu+".background.enabled",true))return;
        Material mat=guiMaterial(menus.getString(menu+".background.material"),Material.BLACK_STAINED_GLASS_PANE);
        String name=color(menus.getString(menu+".background.name","&8"));
        List<String> lore=menus.getStringList(menu+".background.lore").stream().map(this::color).toList();
        ItemStack item=icon(mat,name,lore,menus.getConfigurationSection(menu+".background"));
        for(int i=0;i<inv.getSize();i++)inv.setItem(i,item);
    }
    private int guiSlot(String menu,String button,int fallback){
        return menus.getInt(menu+".buttons."+button+".slot",fallback);
    }
    private ItemStack guiButton(String menu,String button,Material fallback,String msgKey,UUID player,Object... pairs){
        String path=menu+".buttons."+button;
        Material mat=guiMaterial(menus.getString(path+".material"),fallback);
        String defaultName=msg("gui."+msgKey+".name",pairs);
        String name=expandOwn(fill(menus.getString(path+".name",defaultName),pairs),player);
        List<String> lines=menus.isList(path+".lore")?menus.getStringList(path+".lore"):messages.getStringList("gui."+msgKey+".lore");
        List<String> lore=new ArrayList<>();
        for(String line:lines)lore.add(expandOwn(fill(line,pairs),player));
        return icon(mat,name,lore,menus.getConfigurationSection(path));
    }
    private void place(Inventory inv,String menu,String button,Material fallback,String msgKey,UUID player,Object... pairs){
        int slot=guiSlot(menu,button,-1);
        if(slot>=0 && slot<inv.getSize())inv.setItem(slot,guiButton(menu,button,fallback,msgKey,player,pairs));
    }
    private String buttonAt(String menu,int slot){
        ConfigurationSection buttons=menus.getConfigurationSection(menu+".buttons");
        if(buttons==null)return null;
        for(String key:buttons.getKeys(false))if(guiSlot(menu,key,-1)==slot)return key;
        return null;
    }
    private int equipmentIndex(EquipmentSlot slot){
        return menus.getInt("equipment.slots."+slot.name().toLowerCase(Locale.ROOT),-1);
    }
    private EquipmentSlot equipmentAt(int index){
        for(EquipmentSlot slot:EQUIPMENT_SLOTS.values())if(equipmentIndex(slot)==index)return slot;
        return null;
    }
    private List<Integer> rewardSlots(){
        String raw=menus.getString("rewards.content-slots","0-44");
        LinkedHashSet<Integer> slots=new LinkedHashSet<>();
        for(String token:raw.split(",")){
            token=token.trim();try{
                if(token.contains("-")) {
                    String[] parts=token.split("-",2);int a=Integer.parseInt(parts[0].trim()),b=Integer.parseInt(parts[1].trim());
                    for(int i=Math.max(0,a);i<=Math.min(53,b);i++)slots.add(i);
                } else slots.add(Integer.parseInt(token));
            }catch(NumberFormatException ignored){}
        }
        int size=menuSize("rewards",54);
        slots.removeIf(n->n<0||n>=size||buttonAt("rewards",n)!=null);
        if(slots.isEmpty()) for(int i=0;i<Math.min(45,size);i++)if(buttonAt("rewards",i)==null)slots.add(i);
        return List.copyOf(slots);
    }
    private void openSettings(Player p,RobberyData r){
        SettingsMenu holder=new SettingsMenu(r.id);
        holder.inv=Bukkit.createInventory(holder,menuSize("settings",54),menuTitle("settings",msg("gui.main-title","name",r.displayName),p.getUniqueId(),"name",r.displayName));
        Inventory inv=holder.inv;background(inv,"settings");UUID uid=p.getUniqueId();
        place(inv,"settings","info",Material.MAP,"info",uid,"name",r.displayName,"state",r.state(),
            "region",r.region==null?"Unset":r.region.shortDesc(),"vault",r.vault==null?"Unset":r.vault.shortDesc(),
            "world",r.region==null?"Unset":r.region.world);
        place(inv,"settings","open-time",Material.CLOCK,"open-time",uid,"value",r.openMinutes);
        place(inv,"settings","close-time",Material.REDSTONE_TORCH,"close-time",uid,"value",r.closedMinutes);
        place(inv,"settings","crack-time",Material.TRIPWIRE_HOOK,"crack-time",uid,"value",r.crackSeconds);
        place(inv,"settings","payout",Material.GOLD_INGOT,"payout",uid,"value",money(r.payout));
        place(inv,"settings","guards",Material.COPPER_GOLEM_STATUE,"guards",uid,"value",r.guardsEnabled?"Enabled":"Disabled");
        place(inv,"settings","guard-count",Material.IRON_HELMET,"guard-count",uid,"value",r.guardCount);
        place(inv,"settings","vault",Material.IRON_BLOCK,"vault",uid);
        place(inv,"settings","rewards",Material.CHEST,"rewards",uid);
        place(inv,"settings","force-open",Material.LIME_DYE,"force-open",uid);
        place(inv,"settings","force-close",Material.RED_DYE,"force-close",uid);
        place(inv,"settings","region",Material.WOODEN_AXE,"region",uid);
        place(inv,"settings","guard-type",Material.ZOMBIE_SPAWN_EGG,"guard-type",uid,"value",r.guardType);
        place(inv,"settings","guard-skin",Material.PLAYER_HEAD,"guard-skin",uid,"value",r.guardPlayerSkin);
        place(inv,"settings","escape-required",Material.OAK_DOOR,"escape-required",uid,"value",r.requireEscape?"Enabled":"Disabled");
        place(inv,"settings","guard-equipment",Material.CHEST,"guard-equipment",uid);
        place(inv,"settings","guard-health",Material.RED_DYE,"guard-health",uid,"value",r.guardHealth);
        place(inv,"settings","guard-speed",Material.SUGAR,"guard-speed",uid,"value",r.guardSpeed);
        place(inv,"settings","guard-damage",Material.IRON_SWORD,"guard-damage",uid,"value",r.guardDamage);
        place(inv,"settings","guard-follow-range",Material.COMPASS,"guard-follow-range",uid,"value",r.guardFollowRange);
        place(inv,"settings","guard-respawn-seconds",Material.CLOCK,"guard-respawn-seconds",uid,"value",r.guardRespawnSeconds);
        p.openInventory(inv);
    }
    private static final Map<String,Material> GUARD_ICONS=Map.ofEntries(
        Map.entry("ZOMBIE",Material.ZOMBIE_SPAWN_EGG),Map.entry("HUSK",Material.HUSK_SPAWN_EGG),
        Map.entry("DROWNED",Material.DROWNED_SPAWN_EGG),Map.entry("SKELETON",Material.SKELETON_SPAWN_EGG),
        Map.entry("STRAY",Material.STRAY_SPAWN_EGG),Map.entry("BOGGED",Material.BOGGED_SPAWN_EGG),
        Map.entry("WITHER_SKELETON",Material.WITHER_SKELETON_SPAWN_EGG),
        Map.entry("PILLAGER",Material.PILLAGER_SPAWN_EGG),Map.entry("VINDICATOR",Material.VINDICATOR_SPAWN_EGG),
        Map.entry("WITCH",Material.WITCH_SPAWN_EGG),Map.entry("RAVAGER",Material.RAVAGER_SPAWN_EGG),
        Map.entry("PLAYER",Material.PLAYER_HEAD));
    private int guardChoiceSlot(String name){return menus.getInt("guard-types.choices."+name+".slot",-1);}
    private void openGuardTypes(Player p,RobberyData r){
        GuardTypeMenu holder=new GuardTypeMenu(r.id);
        holder.inv=Bukkit.createInventory(holder,menuSize("guard-types",45),menuTitle("guard-types",msg("gui.guard-type-title","name",r.displayName),p.getUniqueId(),"name",r.displayName));
        background(holder.inv,"guard-types");
        for(String type:GUARD_TYPES){
            int slot=guardChoiceSlot(type);if(slot<0||slot>=holder.inv.getSize())continue;
            String path="guard-types.choices."+type;
            Material mat=guiMaterial(menus.getString(path+".material"),GUARD_ICONS.get(type));
            String name=expandOwn(fill(menus.getString(path+".name","&e"+type),"type",type,"value",r.guardType),p.getUniqueId());
            List<String> lore=new ArrayList<>();
            for(String line:menus.getStringList(path+".lore"))lore.add(fill(line,"type",type,"value",r.guardType));
            lore.add(msg(type.equals(r.guardType)?"gui.guard-choice.selected":"gui.guard-choice.select"));
            holder.inv.setItem(slot,icon(mat,name,lore,menus.getConfigurationSection(path)));
        }
        place(holder.inv,"guard-types","back",Material.BARRIER,"back",p.getUniqueId());
        p.openInventory(holder.inv);
    }
    // Editable equipment: six real slots. Other slots are locked. When PLAYER is
    // selected, the visible mannequin wears this gear; the invisible AI wears nothing.
    private String equipmentLabel(EquipmentSlot slot){
        return switch(slot){
            case HEAD -> "Helmet";
            case CHEST -> "Chestplate";
            case LEGS -> "Leggings";
            case FEET -> "Boots";
            case HAND -> "Main Hand";
            case OFF_HAND -> "Off Hand";
            default -> slot.name();
        };
    }
    private boolean slotSupported(String guardType,EquipmentSlot slot){
        // Witches and ravagers do not render standard armor/held equipment.
        if(guardType.equals("WITCH")||guardType.equals("RAVAGER"))return false;
        // Raiders can show hand/head equipment, not standard armor reliably.
        if(Set.of("PILLAGER","VINDICATOR").contains(guardType))
            return slot==EquipmentSlot.HAND || slot==EquipmentSlot.OFF_HAND || slot==EquipmentSlot.HEAD;
        return EQUIPMENT_SLOTS.containsValue(slot);
    }
    private ItemStack defaultGuardItem(RobberyData r,EquipmentSlot slot){
        if(!slotSupported(r.guardType,slot))return null;
        if(slot==EquipmentSlot.HEAD)return new ItemStack(Material.CHAINMAIL_HELMET);
        if(slot==EquipmentSlot.CHEST)return new ItemStack(Material.CHAINMAIL_CHESTPLATE);
        if(slot==EquipmentSlot.LEGS)return new ItemStack(Material.CHAINMAIL_LEGGINGS);
        if(slot==EquipmentSlot.FEET)return new ItemStack(Material.CHAINMAIL_BOOTS);
        if(slot==EquipmentSlot.HAND)return new ItemStack(
            Set.of("SKELETON","STRAY","BOGGED","PILLAGER").contains(r.guardType) ? Material.BOW:Material.IRON_SWORD);
        return null;
    }
    private ItemStack configuredEquipment(RobberyData r,EquipmentSlot slot){
        if(!slotSupported(r.guardType,slot))return null;
        ItemStack item=r.guardEquipmentCustomized?r.guardEquipment.get(slot):defaultGuardItem(r,slot);
        return item==null?null:item.clone();
    }
    private void openGuardEquipment(Player p,RobberyData r){
        GuardEquipmentMenu holder=new GuardEquipmentMenu(r.id);
        holder.inv=Bukkit.createInventory(holder,menuSize("equipment",54),menuTitle("equipment",msg("gui.guard-equipment-title","name",r.displayName),p.getUniqueId(),"name",r.displayName));
        background(holder.inv,"equipment");
        for(EquipmentSlot equipmentSlot:EQUIPMENT_SLOTS.values()){
            int index=equipmentIndex(equipmentSlot);
            if(index<0||index>=holder.inv.getSize())continue;
            if(!slotSupported(r.guardType,equipmentSlot)){
                String title=fill(menus.getString("equipment.unsupported.name",msg("gui.equipment-unsupported","slot",equipmentLabel(equipmentSlot))),"slot",equipmentLabel(equipmentSlot));
                List<String> description=menus.getStringList("equipment.unsupported.lore").stream()
                    .map(line->fill(line,"slot",equipmentLabel(equipmentSlot))).toList();
                holder.inv.setItem(index,icon(guiMaterial(menus.getString("equipment.unsupported.material"),Material.BARRIER),
                    title,description,menus.getConfigurationSection("equipment.unsupported")));
                continue;
            }
            // The real equipment stack is shown, so edits use the item's exact NBT.
            // On first use this is chainmail armour plus the guard's weapon.
            holder.inv.setItem(index,configuredEquipment(r,equipmentSlot));
        }
        place(holder.inv,"equipment","back",Material.BARRIER,"back",p.getUniqueId());
        p.openInventory(holder.inv);
    }
    private void saveGuardEquipment(GuardEquipmentMenu holder){
        RobberyData r=robberies.get(holder.robberyId);if(r==null)return;
        r.guardEquipmentCustomized=true;r.guardEquipment.clear();
        for(EquipmentSlot slot:EQUIPMENT_SLOTS.values()){
            int index=equipmentIndex(slot);
            if(index<0||index>=holder.inv.getSize() || !slotSupported(r.guardType,slot))continue;
            ItemStack item=holder.inv.getItem(index);
            if(item!=null&&!item.getType().isAir())r.guardEquipment.put(slot,item.clone());
        }
        saveData();
        for(UUID id:r.guards){
            Entity entity=Bukkit.getEntity(id);
            if(entity instanceof LivingEntity living)applyGuardEquipment(r,living);
        }
    }
    private void equipSlot(LivingEntity entity,EquipmentSlot slot,ItemStack item){
        if(entity.getEquipment()==null || !entity.canUseEquipmentSlot(slot))return;
        try{
            entity.getEquipment().setItem(slot,item);
            if(entity instanceof Mob)entity.getEquipment().setDropChance(slot,0.0f);
        }catch(IllegalArgumentException|UnsupportedOperationException ignored){}
    }
    private void applyGuardEquipment(RobberyData r,LivingEntity controller){
        LivingEntity visual=controller;
        UUID modelId=guardVisuals.get(controller.getUniqueId());
        if(modelId!=null && Bukkit.getEntity(modelId) instanceof LivingEntity mannequin)visual=mannequin;
        if(controller.getEquipment()!=null)controller.getEquipment().clear();
        if(visual!=controller && visual.getEquipment()!=null)visual.getEquipment().clear();
        if(r.guardType.equals("PLAYER") && visual==controller)return;
        for(EquipmentSlot slot:EQUIPMENT_SLOTS.values())equipSlot(visual,slot,configuredEquipment(r,slot));
    }
    private void openRewards(Player p,RobberyData r,int page){
        int max=Math.max(1,getConfig().getInt("max-rewards-pages",10));
        if(page<0||page>=max)return;
        RewardsMenu holder=new RewardsMenu(r.id,page);
        holder.inv=Bukkit.createInventory(holder,menuSize("rewards",54),menuTitle("rewards",msg("gui.rewards-title","name",r.displayName,"page",page+1),p.getUniqueId(),"name",r.displayName,"page",page+1));
        background(holder.inv,"rewards");
        List<Integer> slots=rewardSlots();
        for(int i=0;i<slots.size();i++){
            RobberyData.Reward reward=r.rewards.get(page*slots.size()+i);
            holder.inv.setItem(slots.get(i),reward==null?null:reward.item.clone());
        }
        place(holder.inv,"rewards","previous",Material.ARROW,"previous",p.getUniqueId());
        place(holder.inv,"rewards","reward-info",Material.BOOK,"reward-info",p.getUniqueId());
        place(holder.inv,"rewards","back",Material.BARRIER,"back",p.getUniqueId());
        place(holder.inv,"rewards","next",Material.ARROW,"next",p.getUniqueId());
        p.openInventory(holder.inv);
        tell(p,"admin.rewards-help");
    }
    private void saveRewards(RewardsMenu holder){
        RobberyData r=robberies.get(holder.robberyId);if(r==null)return;
        List<Integer> slots=rewardSlots();
        for(int i=0;i<slots.size();i++){
            int index=holder.page*slots.size()+i;
            ItemStack stack=holder.inv.getItem(slots.get(i));
            if(stack==null||stack.getType().isAir())r.rewards.remove(index);
            else{
                RobberyData.Reward previous=r.rewards.get(index);
                r.rewards.put(index,new RobberyData.Reward(stack.clone(),previous==null?100:previous.chance));
            }
        }
        saveData();
    }
    private void prompt(Player p,RobberyData r,String setting,int rewardSlot){
        inputs.put(p.getUniqueId(),new ChatInput(r.id,setting,rewardSlot));
        p.closeInventory();
        tell(p,setting.equals("chance")?"admin.chance-prompt":setting.equals("guard-player-skin")?
                "admin.guard-skin-prompt":"admin.prompt","setting",setting);
        sound(p,Sound.UI_BUTTON_CLICK,1.0f);
    }
    @EventHandler public void inventoryClick(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Menu m))return;
        if(!(e.getWhoClicked() instanceof Player p))return;
        if(!p.hasPermission("robbery.admin")){e.setCancelled(true);return;}
        int slot=e.getRawSlot();RobberyData r=robberies.get(m.robberyId);
        if(r==null){e.setCancelled(true);return;}
        if(m instanceof SettingsMenu){
            e.setCancelled(true);
            if(slot<0||slot>=m.inv.getSize())return;
            String key=buttonAt("settings",slot);if(key==null)return;
            switch(key){
                case "open-time"->prompt(p,r,"open-minutes",-1);
                case "close-time"->prompt(p,r,"closed-minutes",-1);
                case "crack-time"->prompt(p,r,"crack-seconds",-1);
                case "payout"->prompt(p,r,"payout",-1);
                case "guards"->{r.guardsEnabled=!r.guardsEnabled;
                    if(!r.guardsEnabled){clearGuards(r);guardRespawnQueue.remove(r.id);}
                    saveData();openSettings(p,r);}
                case "guard-count"->prompt(p,r,"guards-count",-1);
                case "vault"->{p.closeInventory();select(p,r,"vault");}
                case "rewards"->openRewards(p,r,0);
                case "force-open"->{if(!r.ready()){tell(p,"errors.invalid-vault");return;}switchState(r,true);openSettings(p,r);}
                case "force-close"->{switchState(r,false);openSettings(p,r);}
                case "region"->{p.closeInventory();select(p,r,"region");}
                case "guard-type"->openGuardTypes(p,r);
                case "guard-skin"->prompt(p,r,"guard-player-skin",-1);
                case "escape-required"->{r.requireEscape=!r.requireEscape;saveData();openSettings(p,r);
                    tell(p,"admin.escape-updated","value",r.requireEscape?"enabled":"disabled");}
                case "guard-equipment"->openGuardEquipment(p,r);
                case "guard-health"->prompt(p,r,"guard-health",-1);
                case "guard-speed"->prompt(p,r,"guard-speed",-1);
                case "guard-damage"->prompt(p,r,"guard-damage",-1);
                case "guard-follow-range"->prompt(p,r,"guard-follow-range",-1);
                case "guard-respawn-seconds"->prompt(p,r,"guard-respawn-seconds",-1);
                default->{}
            }
        } else if(m instanceof GuardTypeMenu){
            e.setCancelled(true);
            if(slot==guiSlot("guard-types","back",40)){openSettings(p,r);return;}
            for(String selection:GUARD_TYPES){
                if(slot!=guardChoiceSlot(selection))continue;
                if(!r.guardType.equals(selection)){clearGuards(r);r.guardType=selection;saveData();}
                tell(p,"admin.guard-type-updated","value",selection);
                openSettings(p,r);return;
            }
        } else if(m instanceof GuardEquipmentMenu){
            if(slot==guiSlot("equipment","back",49)){e.setCancelled(true);openSettings(p,r);return;}
            if(slot<0){e.setCancelled(true);return;}
            if(slot>=m.inv.getSize()){
                if(e.isShiftClick()||e.getClick().isKeyboardClick())e.setCancelled(true);
                return;
            }
            EquipmentSlot equipmentSlot=equipmentAt(slot);
            if(equipmentSlot==null||!slotSupported(r.guardType,equipmentSlot)||e.isShiftClick())e.setCancelled(true);
        } else if(m instanceof RewardsMenu rm){
            if(slot<0)return;
            if(slot>=rm.inv.getSize()){
                if(e.isShiftClick()||e.getClick().isKeyboardClick())e.setCancelled(true);
                return;
            }
            String button=buttonAt("rewards",slot);
            if(button!=null){
                e.setCancelled(true);
                if(button.equals("previous") && rm.page>0)openRewards(p,r,rm.page-1);
                else if(button.equals("next") && rm.page+1<getConfig().getInt("max-rewards-pages",10))openRewards(p,r,rm.page+1);
                else if(button.equals("back"))openSettings(p,r);
                return;
            }
            List<Integer> content=rewardSlots();int idx=content.indexOf(slot);
            if(idx<0){e.setCancelled(true);return;}
            if(e.getClick().isRightClick()){
                ItemStack clicked=rm.inv.getItem(slot);
                if(clicked!=null&&!clicked.getType().isAir()){
                    e.setCancelled(true);saveRewards(rm);
                    prompt(p,r,"chance",rm.page*content.size()+idx);
                }
            }
        }
    }
    @EventHandler public void inventoryDrag(InventoryDragEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Menu holder))return;
        if(holder instanceof SettingsMenu || holder instanceof GuardTypeMenu){e.setCancelled(true);return;}
        if(holder instanceof GuardEquipmentMenu){
            RobberyData r=robberies.get(holder.robberyId);
            for(int slot:e.getRawSlots())if(slot<holder.inv.getSize() &&
                (r==null||equipmentAt(slot)==null||!slotSupported(r.guardType,equipmentAt(slot)))){
                e.setCancelled(true);return;
            }
            return;
        }
        if(holder instanceof RewardsMenu){
            List<Integer> allowed=rewardSlots();
            for(int slot:e.getRawSlots())if(slot<holder.inv.getSize() && !allowed.contains(slot)){
                e.setCancelled(true);return;
            }
        }
    }
    @EventHandler public void inventoryClose(InventoryCloseEvent e){
        if(e.getInventory().getHolder() instanceof RewardsMenu rm)saveRewards(rm);
        if(e.getInventory().getHolder() instanceof GuardEquipmentMenu gm)saveGuardEquipment(gm);
    }
    @EventHandler public void adminChat(AsyncChatEvent e){
        ChatInput input=inputs.remove(e.getPlayer().getUniqueId());
        if(input==null)return;
        e.setCancelled(true);
        String value=PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(this,()->applyChat(e.getPlayer(),input,value));
    }
    private void applyChat(Player p,ChatInput input,String value){
        if(value.equalsIgnoreCase("cancel")){tell(p,"admin.cancelled");return;}
        RobberyData r=robberies.get(input.id);if(r==null){tell(p,"errors.robbery-not-found","name",input.id);return;}
        if(input.setting.equals("guard-player-skin")){
            if(!value.matches("[A-Za-z0-9_]{3,16}")) {tell(p,"errors.invalid-skin-name");return;}
            r.guardPlayerSkin=value;
            clearGuards(r);
            saveData();
            tell(p,"admin.guard-skin-updated","value",value);
            openSettings(p,r);
            return;
        }
        double number;
        try {number=Double.parseDouble(value);}catch(NumberFormatException ex){tell(p,"errors.invalid-number");return;}
        boolean valid=Double.isFinite(number);
        switch(input.setting){
            case "open-minutes" -> {valid &= number>=1&&number<=1440 && number==Math.floor(number);if(valid)r.openMinutes=(int)number;}
            case "closed-minutes" -> {valid &= number>=1&&number<=10080 && number==Math.floor(number);if(valid)r.closedMinutes=(int)number;}
            case "crack-seconds" -> {valid &= number>=1&&number<=3600&&number==Math.floor(number);if(valid)r.crackSeconds=(int)number;}
            case "guards-count" -> {valid &= number>=0&&number<=50&&number==Math.floor(number);if(valid)r.guardCount=(int)number;}
            case "guard-health" -> {valid &= number>=1&&number<=2048;if(valid)r.guardHealth=number;}
            case "guard-speed" -> {valid &= number>=0.01&&number<=1;if(valid)r.guardSpeed=number;}
            case "guard-damage" -> {valid &= number>=0&&number<=200;if(valid)r.guardDamage=number;}
            case "guard-follow-range" -> {valid &= number>=2&&number<=128;if(valid)r.guardFollowRange=number;}
            case "guard-respawn-seconds" -> {valid &= number>=0&&number<=3600&&number==Math.floor(number);if(valid)r.guardRespawnSeconds=(int)number;}
            case "payout" -> {valid &= number>=0&&number<=100000000; if(valid)r.payout=number;}
            case "chance" -> {
                valid &= number>=0.01&&number<=100;
                RobberyData.Reward reward=r.rewards.get(input.rewardSlot);
                valid &= reward!=null;
                if(valid)reward.chance=number;
            }
            default -> valid=false;
        }
        if(!valid){tell(p,"errors.invalid-number");return;}
        saveData();
        if(input.setting.startsWith("guard-"))for(UUID id:r.guards){
            Entity entity=Bukkit.getEntity(id);
            if(entity instanceof LivingEntity living)configureGuardStats(living,r);
        }
        tell(p,input.setting.equals("chance")?"admin.reward-chance":"admin.saved","setting",input.setting,
                "value",value,"chance",value);
        sound(p,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,1.3f);
        if(input.setting.equals("chance"))openRewards(p,r,input.rewardSlot/Math.max(1,rewardSlots().size()));
        else openSettings(p,r);
    }

    // Vault cracking uses the vanilla continuous left-mouse block-damage start/abort events.
    // Safe blocks are never actually mined. The player must keep aiming and stay nearby.
    @EventHandler(priority=EventPriority.HIGHEST) public void damageBlock(BlockDamageEvent e){
        Player p=e.getPlayer();
        if(isWand(p.getInventory().getItemInMainHand()))return;
        RobberyData r=robberyAt(e.getBlock().getLocation());
        if(r==null)return;
        if(!p.hasPermission("robbery.use")){tell(p,"errors.no-permission");return;}
        if(!r.open){tell(p,"errors.locked");return;}
        if(r.region==null||!r.region.contains(p.getLocation())){tell(p,"errors.player-outside-region");return;}
        if(r.claimed.contains(p.getUniqueId()) || r.pendingClaims.containsKey(p.getUniqueId())){tell(p,"errors.already-robbed");return;}
        if(!allowedGameMode(p)){tell(p,"errors.creative");return;}
        if(cracks.containsKey(p.getUniqueId()))return;
        Location spot=e.getBlock().getLocation();
        cracks.put(p.getUniqueId(),new Crack(r.id,spot,System.currentTimeMillis()));
        stats.attempt(p.getUniqueId(),p.getName(),r.id);
        // The FIRST valid breach attempt triggers guards. Ordinary visitors remain safe.
        boolean firstAttempt=intruders.computeIfAbsent(r.id,k->new HashSet<>()).add(p.getUniqueId());
        if(r.guardsEnabled && r.guardCount>0) {
            refreshGuards(r); // spawn immediately, not at the next periodic guard check
            assignGuardTargets(r);
            if(firstAttempt)tell(p,"robbery.guard-alert","name",r.displayName);
        }
        tell(p,"robbery.crack-start","name",r.displayName);
        sound(p,Sound.BLOCK_CHAIN_PLACE,0.8f);
    }
    @EventHandler public void damageAbort(BlockDamageAbortEvent e){
        Crack current=cracks.get(e.getPlayer().getUniqueId());
        if(current!=null && sameBlock(current.block,e.getBlock().getLocation()))cancelCrack(e.getPlayer(),true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void preventVaultBreak(BlockBreakEvent e){
        if(robberyAt(e.getBlock().getLocation())!=null)e.setCancelled(true);
    }
    private boolean sameBlock(Location a,Location b){
        return a.getWorld()!=null&&b.getWorld()!=null&&a.getWorld().getUID().equals(b.getWorld().getUID())
                && a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ();
    }
    private RobberyData robberyAt(Location location){
        for(RobberyData r:robberies.values())if(r.vault!=null&&r.vault.contains(location))return r;
        return null;
    }
    private boolean allowedGameMode(Player p){
        return getConfig().getBoolean("allow-creative-robbery",false) || (p.getGameMode()!=GameMode.CREATIVE&&p.getGameMode()!=GameMode.SPECTATOR);
    }
    private void cancelCrack(Player p,boolean notify){
        Crack old=cracks.remove(p.getUniqueId());
        if(old!=null){
            if(stats!=null)stats.fail(p.getUniqueId(),p.getName(),old.id);
            if(getConfig().getBoolean("block-crack-effect",true))p.sendBlockDamage(old.block,0.0f);
            if(notify)tell(p,"robbery.crack-cancelled");
        }
    }
    private void pulse(){
        long now=System.currentTimeMillis();
        for(RobberyData r:robberies.values()){
            if(r.ready() && now>=r.nextSwitchAt){switchState(r,!r.open);}
        }
        Iterator<Map.Entry<UUID,Crack>> it=cracks.entrySet().iterator();
        while(it.hasNext()){
            var next=it.next();Player p=Bukkit.getPlayer(next.getKey());Crack crack=next.getValue();
            RobberyData r=robberies.get(crack.id);
            if(p==null || !p.isOnline() || r==null || !r.open || r.region==null || !r.region.contains(p.getLocation())
                    || r.claimed.contains(next.getKey()) || r.pendingClaims.containsKey(next.getKey()) || !allowedGameMode(p)){
                if(p!=null && p.isOnline())cancelVisual(p,crack.block);
                if(stats!=null)stats.fail(next.getKey(),p==null?"Unknown":p.getName(),crack.id);
                it.remove();continue;
            }
            double distance=getConfig().getDouble("max-vault-distance",5);
            Block target=p.getTargetBlockExact((int)Math.ceil(distance));
            if(target==null || crack.block.getBlock().getType().isAir() || !sameBlock(crack.block,target.getLocation()) || !p.getWorld().equals(crack.block.getWorld())
                    || p.getEyeLocation().distanceSquared(crack.block.clone().add(.5,.5,.5))>distance*distance){
                cancelVisual(p,crack.block);it.remove();stats.fail(p.getUniqueId(),p.getName(),crack.id);tell(p,"robbery.crack-cancelled");continue;
            }
            double progress=Math.min(1,(now-crack.started)/(r.crackSeconds*1000.0));
            int percent=(int)(progress*100);
            int width=Math.max(4,Math.min(40,getConfig().getInt("progress-bar-width",16)));
            int full=(int)(progress*width);
            String bar="&a"+"|".repeat(full)+"&8"+"|".repeat(width-full);
            actionbar(p,msg("robbery.crack-actionbar","percent",percent,"bar",color(bar)));
            if(getConfig().getBoolean("block-crack-effect",true))p.sendBlockDamage(crack.block,(float)Math.min(.99,progress));
            if(percent>=100){
                cancelVisual(p,crack.block);it.remove();complete(p,r);
            } else if(now%1200<110) sound(p,Sound.BLOCK_CHAIN_HIT,.7f);
        }
        trackPlayerSkins();
        if(Bukkit.getCurrentTick()%10==0)confineGuards();
        if(now-lastGuardCheck>=Math.max(20,getConfig().getLong("guards-refresh-ticks",100))*50L){
            lastGuardCheck=now;for(RobberyData r:robberies.values())refreshGuards(r);
        }
    }
    private void cancelVisual(Player p,Location location){
        if(getConfig().getBoolean("block-crack-effect",true))p.sendBlockDamage(location,0);
    }
    private void complete(Player p,RobberyData r){
        if(!r.open || r.claimed.contains(p.getUniqueId()) || r.pendingClaims.containsKey(p.getUniqueId()))return;
        List<ItemStack> selected=new ArrayList<>();
        for(RobberyData.Reward reward:r.rewards.values()) {
            if(ThreadLocalRandom.current().nextDouble()*100 < reward.chance)
                selected.add(reward.item.clone());
        }
        // Persist rolled loot and ownership before issuing anything; a restart cannot reroll or duplicate rewards.
        r.pendingClaims.put(p.getUniqueId(),new RobberyData.PendingClaim(r.payout,selected));
        r.claimed.add(p.getUniqueId());
        saveData();
        tell(p,"robbery.crack-success","name",r.displayName);
        sound(p,Sound.ENTITY_PLAYER_LEVELUP,1.15f);
        if(r.requireEscape && r.region!=null && r.region.contains(p.getLocation())){
            tell(p,"robbery.escape-pending","name",r.displayName);
            actionbar(p,msg("robbery.escape-actionbar","name",r.displayName));
        } else redeem(p,r);
    }

    /** Use the same reward snapshot for immediate and delayed claims. */
    private void redeem(Player p,RobberyData r){
        RobberyData.PendingClaim claim=r.pendingClaims.remove(p.getUniqueId());
        if(claim==null)return;
        saveData(); // At-most-once payout: persist redemption before commands / item delivery.
        stats.success(p.getUniqueId(),p.getName(),r.id,claim.amount);
        if(r.requireEscape) {
            tell(p,"robbery.escape-success","name",r.displayName);
            sound(p,Sound.ENTITY_PLAYER_LEVELUP,1.4f);
        }
        if(claim.amount>0){
            String command=getConfig().getString("economy-command","eco give {player} {amount}")
                    .replace("{player}",p.getName()).replace("{amount}",money(claim.amount)).replaceFirst("^/","");
            boolean result=false;
            try {result=getServer().dispatchCommand(getServer().getConsoleSender(),command);}
            catch(Exception ex){getLogger().log(Level.WARNING,"Economy command failed: "+command,ex);}
            if(result)tell(p,"robbery.money-received","amount",money(claim.amount));
            else {tell(p,"robbery.economy-command-failed");getLogger().warning("Console economy command not accepted for "+r.displayName);}
        }
        boolean overflow=false;
        for(ItemStack original:claim.items){
            ItemStack stack=original.clone();
            for(ItemStack remaining:p.getInventory().addItem(stack).values()){
                p.getWorld().dropItemNaturally(p.getLocation(),remaining);overflow=true;
            }
            String label=original.hasItemMeta()&&original.getItemMeta().hasDisplayName()?original.getItemMeta().getDisplayName():original.getType().name();
            tell(p,"robbery.reward-received","item",label,"chance","rolled");
        }
        if(overflow)tell(p,"robbery.inventory-full");
        p.sendTitle(expandOwn(msg("robbery.success-title","name",r.displayName),p.getUniqueId()),
                expandOwn(msg("robbery.success-subtitle","name",r.displayName,"amount",money(claim.amount)),p.getUniqueId()),
                getConfig().getInt("success-title.fade-in-ticks",10),
                getConfig().getInt("success-title.stay-ticks",65),
                getConfig().getInt("success-title.fade-out-ticks",20));
        webhook("completed",r,p.getName(),claim.amount);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void escapeRegion(PlayerMoveEvent e){
        Location to=e.getTo();
        if(to==null)return;
        if(e.getFrom().getBlockX()==to.getBlockX() && e.getFrom().getBlockY()==to.getBlockY() &&
            e.getFrom().getBlockZ()==to.getBlockZ() && e.getFrom().getWorld()==to.getWorld())return;
        for(RobberyData r:robberies.values()){
            if(r.pendingClaims.containsKey(e.getPlayer().getUniqueId()) && r.region!=null && !r.region.contains(to))
                redeem(e.getPlayer(),r);
            if(r.region!=null && !r.region.contains(to))forgetIntruder(r,e.getPlayer().getUniqueId());
        }
    }
    @EventHandler public void join(PlayerJoinEvent e){
        Bukkit.getScheduler().runTaskLater(this,()->{
            Player p=e.getPlayer();if(!p.isOnline())return;
            for(RobberyData r:robberies.values()) if(r.pendingClaims.containsKey(p.getUniqueId())){
                if(r.region!=null && r.region.contains(p.getLocation()))
                    tell(p,"robbery.escape-reminder","name",r.displayName);
                else redeem(p,r);
            }
        },10L);
    }
    @EventHandler public void quit(PlayerQuitEvent e){
        Crack abandoned=cracks.remove(e.getPlayer().getUniqueId());
        if(abandoned!=null && stats!=null)stats.fail(e.getPlayer().getUniqueId(),e.getPlayer().getName(),abandoned.id);
        inputs.remove(e.getPlayer().getUniqueId());selections.remove(e.getPlayer().getUniqueId());
        for(RobberyData r:robberies.values())forgetIntruder(r,e.getPlayer().getUniqueId());
    }

    @EventHandler(priority=EventPriority.HIGHEST) public void robberyKeepInventory(PlayerDeathEvent e){
        if(!getConfig().getBoolean("keep-inventory-during-robbery",true))return;
        Player player=e.getEntity();
        for(RobberyData r:robberies.values())forgetIntruder(r,player.getUniqueId());
        for(RobberyData r:robberies.values()){
            if(r.open && r.region!=null && r.region.contains(player.getLocation())){
                e.setKeepInventory(true);
                e.getDrops().clear();
                e.setKeepLevel(true);
                e.setDroppedExp(0);
                return;
            }
        }
    }

    private void switchState(RobberyData r,boolean open){
        if(open&&!r.ready())return;
        // Every opening/closing starts a fresh set of wanted players and removes old guards.
        clearGuards(r);guardRespawnQueue.remove(r.id);intruders.remove(r.id);
        for(var entry:cracks.entrySet())if(entry.getValue().id.equals(r.id)){
            Player p=Bukkit.getPlayer(entry.getKey());
            if(p!=null){cancelVisual(p,entry.getValue().block);tell(p,"robbery.closed-during-crack");}
            if(stats!=null)stats.fail(entry.getKey(),p==null?"Unknown":p.getName(),r.id);
        }
        cracks.entrySet().removeIf(e->e.getValue().id.equals(r.id));
        r.open=open;r.claimed.clear();
        r.nextSwitchAt=System.currentTimeMillis()+(open?r.openMinutes:r.closedMinutes)*60_000L;
        saveData();
        announce(open?"robbery.opened":"robbery.closed", "name",r.displayName,
                "minutes",open?r.openMinutes:r.closedMinutes,
                "escape",r.requireEscape?msg("robbery.escape-announcement"):msg("robbery.instant-announcement"));
        webhook(open?"open":"close",r,"",r.payout);
        if(open)for(Player p:Bukkit.getOnlinePlayers())sound(p,Sound.BLOCK_BELL_USE,1.15f);
    }

    private String guardTag(Entity entity){
        return entity.getPersistentDataContainer().get(guardKey,PersistentDataType.STRING);
    }
    private void markGuard(Entity entity,String id,String role){
        entity.getPersistentDataContainer().set(guardKey,PersistentDataType.STRING,id);
        entity.getPersistentDataContainer().set(guardRoleKey,PersistentDataType.STRING,role);
        entity.getPersistentDataContainer().set(guardSessionKey,PersistentDataType.STRING,sessionId);
    }
    // Handles all entity types and catches guards from before server restarts.
    private void cleanupTaggedGuards(String id,boolean all){
        for(World world:Bukkit.getWorlds())for(Entity e:new ArrayList<>(world.getEntities())){
            String tag=guardTag(e);
            if(tag!=null && (all || tag.equals(id))){
                guardVisuals.remove(e.getUniqueId());guardControllers.remove(e.getUniqueId());
                e.remove();
            }
        }
    }
    @EventHandler public void guardChunkLoad(ChunkLoadEvent event){
        // Old NPCs may have been unloaded at startup. Clean them when the chunk is loaded.
        Bukkit.getScheduler().runTask(this,()->{
            for(Entity e:event.getChunk().getEntities()){
                String id=guardTag(e);if(id==null)continue;
                RobberyData r=robberies.get(id);
                String session=e.getPersistentDataContainer().get(guardSessionKey,PersistentDataType.STRING);
                boolean known=(r!=null && (r.guards.contains(e.getUniqueId())||
                    guardControllers.containsKey(e.getUniqueId()) || guardVisuals.containsValue(e.getUniqueId())));
                if(!sessionId.equals(session)||!known||!r.open||!r.guardsEnabled)e.remove();
            }
        });
    }
    @EventHandler public void guardChunkUnload(ChunkUnloadEvent event){
        // Never leave active guards saved in an unloaded chunk as a duplicate next time.
        for(Entity e:event.getChunk().getEntities()){
            String id=guardTag(e);if(id==null)continue;
            RobberyData r=robberies.get(id);
            if(r!=null)r.guards.remove(e.getUniqueId());
            removeGuard(e.getUniqueId());
        }
    }
    private void clearGuards(RobberyData r){
        for(UUID id:new HashSet<>(r.guards)) removeGuard(id);
        r.guards.clear();
        cleanupTaggedGuards(r.id,false); // scan loaded world, not just RAM UUID sets
        guardVisuals.entrySet().removeIf(entry->Bukkit.getEntity(entry.getKey())==null);
        guardControllers.entrySet().removeIf(entry->Bukkit.getEntity(entry.getKey())==null);
    }
    private void removeGuard(UUID id){
        UUID controller=guardControllers.remove(id);
        if(controller!=null)id=controller;
        UUID visualId=guardVisuals.remove(id);
        if(visualId!=null){
            guardControllers.remove(visualId);
            Entity visual=Bukkit.getEntity(visualId);
            if(visual!=null)visual.remove();
        }
        guardHome.remove(id);
        Entity body=Bukkit.getEntity(id);
        if(body!=null)body.remove();
    }
    private void configureGuardStats(LivingEntity body,RobberyData r){
        configureAttribute(body,Attribute.MAX_HEALTH,r.guardHealth);
        configureAttribute(body,Attribute.MOVEMENT_SPEED,r.guardSpeed);
        configureAttribute(body,Attribute.ATTACK_DAMAGE,r.guardDamage);
        configureAttribute(body,Attribute.FOLLOW_RANGE,r.guardFollowRange);
        if(body.getHealth()>r.guardHealth)body.setHealth(r.guardHealth);
    }
    private void configureAttribute(LivingEntity body,Attribute attribute,double value){
        AttributeInstance instance=body.getAttribute(attribute);
        if(instance!=null)instance.setBaseValue(value);
    }
    /** Only players who have attempted to breach THIS robbery's vault can be targeted. */
    private boolean eligibleIntruder(RobberyData r,Player p) {
        return r.open && r.region!=null && p.isOnline() && !p.isDead()
            && allowedGameMode(p) && r.region.contains(p.getLocation())
            && intruders.getOrDefault(r.id,Collections.emptySet()).contains(p.getUniqueId());
    }
    private List<Player> currentIntruders(RobberyData r) {
        Set<UUID> wanted=intruders.get(r.id);
        if(wanted==null || wanted.isEmpty())return List.of();
        List<Player> available=new ArrayList<>();
        for(UUID id:new HashSet<>(wanted)) {
            Player p=Bukkit.getPlayer(id);
            if(p==null || !eligibleIntruder(r,p))wanted.remove(id);
            else available.add(p);
        }
        if(wanted.isEmpty())intruders.remove(r.id);
        return available;
    }
    private void forgetIntruder(RobberyData r,UUID player) {
        Set<UUID> wanted=intruders.get(r.id);
        if(wanted==null || !wanted.remove(player))return;
        if(wanted.isEmpty()) {
            intruders.remove(r.id);
            guardRespawnQueue.remove(r.id);
            clearGuards(r);
        }else assignGuardTargets(r);
    }
    private void assignGuardTargets(RobberyData r) {
        List<Player> available=currentIntruders(r);
        if(available.isEmpty())return;
        double maximumDistanceSquared=r.guardFollowRange*r.guardFollowRange;
        for(UUID id:new HashSet<>(r.guards)) {
            Entity e=Bukkit.getEntity(id);
            if(!(e instanceof Mob mob) || !e.isValid())continue;
            Player nearest=null;double closest=Double.MAX_VALUE;
            for(Player p:available) {
                if(!e.getWorld().equals(p.getWorld()))continue;
                double distance=e.getLocation().distanceSquared(p.getLocation());
                if(distance<closest && distance<=maximumDistanceSquared) {
                    closest=distance;nearest=p;
                }
            }
            if(mob.getTarget()!=nearest)mob.setTarget(nearest);
        }
    }
    /**
     * Vanilla zombies and skeletons ignite in daylight. PLAYER-type guards use an
     * invisible zombie as their combat controller, and that controller must not
     * catch fire just because the robbery is outdoors.
     *
     * Handle sunlight-like spontaneous ignition only: do NOT cancel combustion
     * caused by lava/fire blocks or another entity's fire attack.
     */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void preventGuardSunlightBurn(EntityCombustEvent event){
        if(!getConfig().getBoolean("guards-prevent-sunlight-burning",true))return;
        if(event instanceof EntityCombustByBlockEvent || event instanceof EntityCombustByEntityEvent)return;
        if(guardTag(event.getEntity())==null)return;
        event.setCancelled(true);
        event.getEntity().setFireTicks(0);
    }

    @EventHandler(ignoreCancelled=true) public void guardTarget(EntityTargetLivingEntityEvent event){
        String id=guardTag(event.getEntity());if(id==null)return;
        // Never allow ambient vanilla AI to choose a visitor or another mob as a victim.
        if(event.getTarget()==null)return;
        RobberyData r=robberies.get(id);
        if(r==null || !(event.getTarget() instanceof Player p) || !eligibleIntruder(r,p))
            event.setCancelled(true);
    }
    private void confineGuards(){
        for(RobberyData r:robberies.values()){
            if(r.region==null)continue;
            if(currentIntruders(r).isEmpty()) {
                if(!r.guards.isEmpty())clearGuards(r);
                guardRespawnQueue.remove(r.id);
                continue;
            }
            for(UUID id:new HashSet<>(r.guards)){
                Entity entity=Bukkit.getEntity(id);
                if(!(entity instanceof Mob mob)||!entity.isValid())continue;
                Location pos=entity.getLocation();
                if(!r.region.contains(pos)){
                    mob.setTarget(null);
                    Location safe=guardHome.get(id);
                    if(safe!=null && safe.getWorld()!=null && r.region.contains(safe))entity.teleport(safe);
                    else {r.guards.remove(id);removeGuard(id);}
                }else if(mob.getTarget() instanceof Player target && !eligibleIntruder(r,target))
                    mob.setTarget(null);
            }
            assignGuardTargets(r);
        }
    }
    private void refreshGuards(RobberyData r){
        if(!r.ready()||!r.open||!r.guardsEnabled||r.guardCount<=0
                || currentIntruders(r).isEmpty()){
            if(!r.guards.isEmpty())clearGuards(r);
            return;
        }
        for(UUID id:new HashSet<>(r.guards)){
            Entity e=Bukkit.getEntity(id);
            UUID modelId=guardVisuals.get(id);
            if(e==null || !e.isValid() || e.isDead() ||
              (modelId!=null && (Bukkit.getEntity(modelId)==null || !Bukkit.getEntity(modelId).isValid()))){
                removeGuard(id);r.guards.remove(id);
            }
        }
        while(r.guards.size()>r.guardCount){
            UUID id=r.guards.iterator().next();r.guards.remove(id);removeGuard(id);
        }
        World w=Bukkit.getWorld(r.region.world);if(w==null)return;
        // Spawning is triggered by the first cracking attempt; only wanted players count.
        if(currentIntruders(r).isEmpty())return;
        List<Long> respawn=guardRespawnQueue.computeIfAbsent(r.id,k->new ArrayList<>());
        long now=System.currentTimeMillis();respawn.removeIf(deadline->deadline<=now);
        int allowed=Math.max(0,r.guardCount-respawn.size());
        int attempts=0;
        while(r.guards.size()<allowed && attempts++<r.guardCount*4+4){
            Location spot=guardSpawn(r.region);if(spot==null)break;
            LivingEntity body;
            try{
                EntityType type=EntityType.valueOf(r.guardType.equals("PLAYER")?"ZOMBIE":r.guardType);
                Entity spawned=w.spawnEntity(spot,type);
                if(!(spawned instanceof LivingEntity living)){spawned.remove();continue;}
                body=living;
            }catch(IllegalArgumentException ex){
                getLogger().warning("Invalid guard type "+r.guardType+" in "+r.id+"; using Zombie");
                body=w.spawn(spot,Zombie.class);
            }
            if(body instanceof Zombie z){
                z.setAdult();
                // Covers PLAYER's invisible zombie controller and ordinary zombie guards.
                z.setShouldBurnInDay(false);
            }
            body.setCanPickupItems(false);
            if(body instanceof Mob mob)mob.setRemoveWhenFarAway(false);
            markGuard(body,r.id,"controller");
            // Clear any flame the entity acquired while it was spawning.
            body.setFireTicks(0);
            body.setCustomName(color("&c"+r.displayName+" Bodyguard"));
            body.setCustomNameVisible(!r.guardType.equals("PLAYER"));
            configureGuardStats(body,r);
            body.setHealth(r.guardHealth);
            if(r.guardType.equals("PLAYER"))attachPlayerSkin(r,body,spot);
            else applyGuardEquipment(r,body);
            r.guards.add(body.getUniqueId());
            guardHome.put(body.getUniqueId(),spot.clone());
        }
        assignGuardTargets(r);
    }
    private void attachPlayerSkin(RobberyData r,LivingEntity host,Location spawn){
        try{
            // All controller equipment must be removed; invisible mobs still display gear.
            if(host.getEquipment()!=null)host.getEquipment().clear();
            host.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,Integer.MAX_VALUE,0,true,false));
            host.setSilent(true);
            Mannequin model=(Mannequin)spawn.getWorld().spawnEntity(spawn,EntityType.valueOf("MANNEQUIN"));
            model.setImmovable(true);
            model.setGravity(false);
            model.setSilent(true);
            model.setCustomName(color("&c"+r.displayName+" Bodyguard"));
            model.setCustomNameVisible(true);
            markGuard(model,r.id,"visual");
            model.setProfile(ResolvableProfile.resolvableProfile().name(r.guardPlayerSkin).build());
            guardVisuals.put(host.getUniqueId(),model.getUniqueId());
            guardControllers.put(model.getUniqueId(),host.getUniqueId());
            applyGuardEquipment(r,host);
            PlayerProfile lookup=Bukkit.createProfile(r.guardPlayerSkin);
            lookup.update().thenAccept(profile->{
                if(!isEnabled())return;
                Bukkit.getScheduler().runTask(this,()->{
                    if(isEnabled() && model.isValid() && guardControllers.containsKey(model.getUniqueId()) &&
                      !profile.getTextures().isEmpty())
                        model.setProfile(ResolvableProfile.resolvableProfile(profile));
                });
            }).exceptionally(ex->{getLogger().warning("Could not fetch player skin "+r.guardPlayerSkin+": "+ex.getMessage());return null;});
        }catch(Exception ex){
            getLogger().log(Level.WARNING,"Unable to render player guard skin; showing fallback Zombie",ex);
            host.removePotionEffect(PotionEffectType.INVISIBILITY);
            host.setSilent(false);host.setCustomNameVisible(true);
            applyGuardEquipment(r,host);
        }
    }
    private void trackPlayerSkins(){
        for(var entry:new HashMap<>(guardVisuals).entrySet()){
            Entity host=Bukkit.getEntity(entry.getKey());
            Entity model=Bukkit.getEntity(entry.getValue());
            if(host==null||!host.isValid()||model==null||!model.isValid())continue;
            Location at=host.getLocation();
            if(at.getWorld()!=model.getWorld()||at.distanceSquared(model.getLocation())>0.001 ||
               Math.abs(at.getYaw()-model.getLocation().getYaw())>3)
                model.teleport(at);
        }
    }
    private Location guardSpawn(RobberyData.Box box){
        World w=Bukkit.getWorld(box.world);if(w==null)return null;
        Player anchor=null;
        // Anchor guard positions to the actual intruder, never a non-participating visitor.
        for(RobberyData r:robberies.values())if(r.region==box) {
            List<Player> wanted=currentIntruders(r);
            if(!wanted.isEmpty())anchor=wanted.get(0);
            break;
        }
        if(anchor==null)return null;
        for(int t=0;t<70;t++){
            int x=anchor.getLocation().getBlockX()+ThreadLocalRandom.current().nextInt(-12,13);
            int z=anchor.getLocation().getBlockZ()+ThreadLocalRandom.current().nextInt(-12,13);
            int y=anchor.getLocation().getBlockY()+ThreadLocalRandom.current().nextInt(-3,4);
            if(x<box.x1||x>box.x2||z<box.z1||z>box.z2||y<box.y1||y>box.y2)continue;
            if(!w.isChunkLoaded(x>>4,z>>4))continue;
            Block feet=w.getBlockAt(x,y,z);
            if(feet.isPassable() && w.getBlockAt(x,y+1,z).isPassable() && w.getBlockAt(x,y-1,z).getType().isSolid())
                return new Location(w,x+.5,y,z+.5);
        }
        return null;
    }
    @EventHandler public void guardDeath(EntityDeathEvent e){
        String id=guardTag(e.getEntity());if(id==null)return;
        e.getDrops().clear();e.setDroppedExp(0);
        UUID dead=e.getEntity().getUniqueId();
        String role=e.getEntity().getPersistentDataContainer().get(guardRoleKey,PersistentDataType.STRING);
        if("visual".equals(role)){
            UUID controller=guardControllers.get(dead);
            if(controller!=null) {
                removeGuard(controller);
                RobberyData r=robberies.get(id);if(r!=null)r.guards.remove(controller);
            }
            return;
        }
        removeGuard(dead);
        RobberyData r=robberies.get(id);
        if(r!=null){
            r.guards.remove(dead);
            if(r.open && r.guardsEnabled && !currentIntruders(r).isEmpty() && r.guardRespawnSeconds>0)
                guardRespawnQueue.computeIfAbsent(r.id,k->new ArrayList<>())
                    .add(System.currentTimeMillis()+1000L*r.guardRespawnSeconds);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void playerSkinDamage(EntityDamageByEntityEvent e){
        UUID hostId=guardControllers.get(e.getEntity().getUniqueId());
        if(hostId==null)return;
        e.setCancelled(true);
        Entity ai=Bukkit.getEntity(hostId);
        if(!(ai instanceof LivingEntity host) || host.isDead())return;
        Entity attacker=e.getDamager();
        if(attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter)attacker=shooter;
        if(attacker instanceof LivingEntity living)host.damage(e.getFinalDamage(),living);
        else host.damage(e.getFinalDamage());
    }

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void configuredGuardDamage(EntityDamageByEntityEvent event){
        Entity attacker=event.getDamager();
        if(attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter)
            attacker=shooter;
        String id=guardTag(attacker);
        if(id==null)return;
        RobberyData robbery=robberies.get(id);
        if(robbery==null || !(event.getEntity() instanceof Player player)
            || !eligibleIntruder(robbery,player)) {
            // Guards must not harm visitors or innocent mobs, even via stray arrows.
            event.setCancelled(true);
            return;
        }
        event.setDamage(robbery.guardDamage); // arrows and other ranged attacks
    }

    /** HTTPS-only Discord webhooks; all HTTP work happens off the main server thread. */
    private void webhook(String event,RobberyData r,String player,double amount){
        if(!discord.getBoolean("enabled",false)||!discord.getBoolean("events."+event,false))return;
        String url=discord.getString("url","").trim();
        if(!url.matches("https://(discord\\.com|discordapp\\.com)/api/webhooks/[0-9]+/[^\\s/]+")){
            getLogger().warning("discord.yml enabled but contains no valid Discord webhook URL.");return;
        }
        String template=discord.getString("messages."+event,event+": {name}");
        String content=template.replace("{name}",r.displayName).replace("{player}",player)
                .replace("{amount}",money(amount))
                .replace("{minutes}",String.valueOf(event.equals("open")?r.openMinutes:r.closedMinutes))
                .replace("{escape}",r.requireEscape?"Escape required to claim rewards":"Instant rewards");
        String json;
        String namePart="\"username\":\""+escape(discord.getString("username","SimpleRobbery"))+"\",";
        String avatar=discord.getString("avatar-url", "");
        String avatarPart=avatar.startsWith("https://") ? "\"avatar_url\":\""+escape(avatar)+"\"," : "";
        if(discord.getBoolean("embed.enabled",true)){
            int color=discord.getInt("embed.color-"+(event.equals("created")||event.equals("deleted")?"admin":event),3447003);
            json="{"+namePart+avatarPart
                +"\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"description\":\""+escape(content)+"\","
                +"\"color\":"+color+",\"footer\":{\"text\":\""+escape(discord.getString("embed.footer","SimpleRobbery"))+"\"}}]}";
        }else{
            json="{"+namePart+avatarPart+"\"content\":\""+escape(content)+"\",\"allowed_mentions\":{\"parse\":[]}}";
        }
        Bukkit.getScheduler().runTaskAsynchronously(this,()->{
            try{
                HttpURLConnection conn=(HttpURLConnection)URI.create(url).toURL().openConnection();
                conn.setRequestMethod("POST");conn.setConnectTimeout(6000);conn.setReadTimeout(6000);
                conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
                try(OutputStream stream=conn.getOutputStream()){stream.write(json.getBytes(StandardCharsets.UTF_8));}
                int code=conn.getResponseCode();
                if(code<200||code>=300)getLogger().warning("Discord webhook returned HTTP "+code);
                conn.disconnect();
            }catch(Exception ex){getLogger().warning("Discord webhook send failed (check discord.yml URL and connectivity): "+ex.getMessage());}
        });
    }
    private String escape(String input){
        StringBuilder b=new StringBuilder();
        for(char c:input.toCharArray()){
            switch(c){
                case '"'->b.append("\\\"");case '\\'->b.append("\\\\");
                case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");
                default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}
            }
        }
        return b.toString();
    }
}
