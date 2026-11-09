package dev.simplerobbery;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;

import java.util.*;
import java.util.Base64;

/** Persistent robbery settings. All player-created rewards are stored as ItemStack objects. */
public final class RobberyData {
    public final String id;
    public String displayName;
    public Box region;
    public Box vault;
    public long nextSwitchAt;
    public boolean open;
    public int openMinutes;
    public int closedMinutes;
    public int crackSeconds;
    public double payout;
    public boolean guardsEnabled;
    public int guardCount;
    public String guardType = "ZOMBIE";
    public String guardPlayerSkin = "Steve";
    public boolean requireEscape = false;
    public double guardHealth = 30.0;
    public double guardSpeed = 0.28;
    public double guardDamage = 4.0;
    public double guardFollowRange = 18.0;
    public int guardRespawnSeconds = 30;
    // An explicit empty equipment layout is valid. Null means legacy default gear.
    public boolean guardEquipmentCustomized = false;
    public final EnumMap<EquipmentSlot,ItemStack> guardEquipment = new EnumMap<>(EquipmentSlot.class);
    public final Map<UUID, PendingClaim> pendingClaims = new HashMap<>();
    public final Map<Integer, Reward> rewards = new TreeMap<>();
    public final Set<UUID> claimed = new HashSet<>();
    public final Set<UUID> guards = new HashSet<>();

    public RobberyData(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public boolean ready() { return region != null && vault != null && region.world.equals(vault.world) && region.contains(vault); }
    public String state() { return open ? "OPEN" : "CLOSED"; }

    public void load(ConfigurationSection s) {
        displayName = s.getString("display-name", id);
        region = Box.load(s.getConfigurationSection("region"));
        vault = Box.load(s.getConfigurationSection("vault"));
        openMinutes = Math.max(1, s.getInt("open-minutes", 10));
        closedMinutes = Math.max(1, s.getInt("closed-minutes", 60));
        crackSeconds = Math.max(1, s.getInt("crack-seconds", 15));
        payout = Math.max(0, s.getDouble("payout", 0));
        guardsEnabled = s.getBoolean("guards-enabled", false);
        guardCount = Math.max(0, s.getInt("guards-count", 3));
        guardType = s.getString("guard-type", "ZOMBIE").toUpperCase(Locale.ROOT);
        guardPlayerSkin = s.getString("guard-player-skin", "Steve");
        requireEscape = s.getBoolean("require-escape", false);
        guardHealth = Math.max(1, Math.min(2048, s.getDouble("guard-health",30.0)));
        guardSpeed = Math.max(0.01, Math.min(1.0,s.getDouble("guard-speed",0.28)));
        guardDamage = Math.max(0, Math.min(200, s.getDouble("guard-damage",4.0)));
        guardFollowRange = Math.max(2, Math.min(128,s.getDouble("guard-follow-range",18.0)));
        guardRespawnSeconds = Math.max(0, Math.min(3600,s.getInt("guard-respawn-seconds",30)));
        guardEquipmentCustomized = s.getBoolean("guard-equipment-customized",false);
        guardEquipment.clear();
        ConfigurationSection es=s.getConfigurationSection("guard-equipment");
        if(es!=null) for(String key:es.getKeys(false)) {
            try {
                EquipmentSlot slot=EquipmentSlot.valueOf(key);
                String encoded=es.getString(key,"");
                if(encoded!=null&&!encoded.isBlank()) {
                    ItemStack item=ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
                    if(item!=null && !item.getType().isAir())guardEquipment.put(slot,item);
                }
            } catch(Exception ignored) {} // Continue loading even if an old item has become invalid.
        }
        open = s.getBoolean("open", false);
        nextSwitchAt = s.getLong("next-switch-at", System.currentTimeMillis());
        for (String key : s.getStringList("claimed")) {
            try { claimed.add(UUID.fromString(key)); } catch (IllegalArgumentException ignored) {}
        }
        ConfigurationSection ps = s.getConfigurationSection("pending-claims");
        if(ps!=null) for(String raw : ps.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(raw);
                ConfigurationSection entry=ps.getConfigurationSection(raw);
                if(entry==null)continue;
                List<ItemStack> items=new ArrayList<>();
                for(String encoded:entry.getStringList("items-nbt")) {
                    try { items.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded))); }
                    catch(RuntimeException ignored) {}
                }
                pendingClaims.put(playerId,new PendingClaim(entry.getDouble("amount",0),items));
            } catch(IllegalArgumentException ignored) {}
        }
        ConfigurationSection rs = s.getConfigurationSection("rewards");
        if (rs != null) for (String k : rs.getKeys(false)) {
            try {
                int idx = Integer.parseInt(k);
                ItemStack item = null;
                String encoded = rs.getString(k + ".item-nbt", "");
                if (!encoded.isEmpty()) {
                    try { item = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded)); }
                    catch (RuntimeException ex) { item = rs.getItemStack(k + ".item"); }
                } else item = rs.getItemStack(k + ".item"); // legacy migration
                double chance = rs.getDouble(k + ".chance", 100);
                if (idx >= 0 && idx < 450 && item != null && !item.getType().isAir())
                    rewards.put(idx, new Reward(item.clone(), Math.max(0.01, Math.min(100, chance))));
            } catch (NumberFormatException ignored) {}
        }
    }

    public void save(ConfigurationSection s) {
        s.set("display-name", displayName);
        s.set("region", null);
        s.set("vault", null);
        if (region != null) region.save(s.createSection("region"));
        if (vault != null) vault.save(s.createSection("vault"));
        s.set("open-minutes", openMinutes);
        s.set("closed-minutes", closedMinutes);
        s.set("crack-seconds", crackSeconds);
        s.set("payout", payout);
        s.set("guards-enabled", guardsEnabled);
        s.set("guards-count", guardCount);
        s.set("guard-type",guardType);
        s.set("guard-player-skin",guardPlayerSkin);
        s.set("require-escape",requireEscape);
        s.set("guard-health",guardHealth);
        s.set("guard-speed",guardSpeed);
        s.set("guard-damage",guardDamage);
        s.set("guard-follow-range",guardFollowRange);
        s.set("guard-respawn-seconds",guardRespawnSeconds);
        s.set("guard-equipment-customized",guardEquipmentCustomized);
        s.set("guard-equipment",null);
        ConfigurationSection es=s.createSection("guard-equipment");
        guardEquipment.forEach((slot,item)->{
            if(item!=null && !item.getType().isAir())
                es.set(slot.name(),Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        });
        s.set("open", open);
        s.set("next-switch-at", nextSwitchAt);
        s.set("claimed", claimed.stream().map(UUID::toString).toList());
        s.set("pending-claims",null);
        ConfigurationSection pc=s.createSection("pending-claims");
        pendingClaims.forEach((uuid,claim)->{
            ConfigurationSection entry=pc.createSection(uuid.toString());
            entry.set("amount",claim.amount);
            entry.set("items-nbt",claim.items.stream().map(i->Base64.getEncoder().encodeToString(i.serializeAsBytes())).toList());
        });
        s.set("rewards", null);
        ConfigurationSection rs = s.createSection("rewards");
        rewards.forEach((slot, reward) -> {
            // Use raw NBT bytes rather than ItemMeta YAML, keeping custom components / NBT.
            rs.set(slot + ".item-nbt", Base64.getEncoder().encodeToString(reward.item.serializeAsBytes()));
            rs.set(slot + ".chance", reward.chance);
        });
    }

    /** A reward roll stored before collection, so quitting/restarting cannot reroll or duplicate it. */
    public static final class PendingClaim {
        public final double amount;
        public final List<ItemStack> items;
        public PendingClaim(double amount, List<ItemStack> items) {
            this.amount = amount;
            this.items = new ArrayList<>();
            for(ItemStack item:items) this.items.add(item.clone());
        }
    }

    public static final class Reward {
        public ItemStack item;
        public double chance;
        public Reward(ItemStack item, double chance) { this.item = item; this.chance = chance; }
    }

    /** World-bound inclusive cuboid, using block coordinates and no world loading. */
    public static final class Box {
        public final String world;
        public final int x1, y1, z1, x2, y2, z2;
        public Box(Location a, Location b) {
            if (a.getWorld() == null || b.getWorld() == null || !a.getWorld().getUID().equals(b.getWorld().getUID()))
                throw new IllegalArgumentException("Selection corners must be in the same world");
            this.world = a.getWorld().getName();
            x1 = Math.min(a.getBlockX(), b.getBlockX()); x2 = Math.max(a.getBlockX(), b.getBlockX());
            y1 = Math.min(a.getBlockY(), b.getBlockY()); y2 = Math.max(a.getBlockY(), b.getBlockY());
            z1 = Math.min(a.getBlockZ(), b.getBlockZ()); z2 = Math.max(a.getBlockZ(), b.getBlockZ());
        }
        public Box(String world,int x1,int y1,int z1,int x2,int y2,int z2) {
            this.world=world;this.x1=x1;this.y1=y1;this.z1=z1;this.x2=x2;this.y2=y2;this.z2=z2;
        }
        public boolean contains(Location p) {
            return p.getWorld() != null && p.getWorld().getName().equals(world)
                && p.getBlockX() >= x1 && p.getBlockX() <= x2
                && p.getBlockY() >= y1 && p.getBlockY() <= y2
                && p.getBlockZ() >= z1 && p.getBlockZ() <= z2;
        }
        public boolean contains(Box b) {
            return world.equals(b.world) && b.x1 >= x1 && b.x2 <= x2 && b.y1 >= y1 && b.y2 <= y2 && b.z1 >= z1 && b.z2 <= z2;
        }
        public long volume() { return ((long)x2 - x1 + 1)*((long)y2 - y1 + 1)*((long)z2 - z1 + 1); }
        public Location center() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, (x1+x2)/2.0+.5, (y1+y2)/2.0+.5, (z1+z2)/2.0+.5);
        }
        public String shortDesc() { return x1+","+y1+","+z1+" → "+x2+","+y2+","+z2; }
        public void save(ConfigurationSection s) {
            s.set("world",world);s.set("x1",x1);s.set("y1",y1);s.set("z1",z1);
            s.set("x2",x2);s.set("y2",y2);s.set("z2",z2);
        }
        public static Box load(ConfigurationSection s) {
            if (s == null || s.getString("world", "").isBlank()) return null;
            return new Box(s.getString("world"),s.getInt("x1"),s.getInt("y1"),s.getInt("z1"),
                s.getInt("x2"),s.getInt("y2"),s.getInt("z2"));
        }
    }
}
