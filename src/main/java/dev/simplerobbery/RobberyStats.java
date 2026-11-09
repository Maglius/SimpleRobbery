package dev.simplerobbery;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.util.*;
import java.util.logging.Logger;

/** Persisted counters. Success means paid claim, failure means an interrupted vault attempt. */
final class RobberyStats {
    static final class PlayerRecord {
        final UUID uuid;
        String lastName = "Unknown";
        long attempts, successes, failures;
        double money;
        PlayerRecord(UUID id){this.uuid=id;}
    }
    static final class RegionRecord {
        long attempts, successes, failures;
        double money;
    }
    private final File file;
    private final Logger logger;
    private final Map<UUID, PlayerRecord> players=new HashMap<>();
    private final Map<String, RegionRecord> regions=new HashMap<>();
    private long allAttempts,allSuccesses,allFailures;
    private double allMoney;
    RobberyStats(File file,Logger logger){this.file=file;this.logger=logger;load();}
    PlayerRecord player(UUID id){return players.computeIfAbsent(id,PlayerRecord::new);}
    PlayerRecord find(UUID id){return id==null?null:players.get(id);}
    PlayerRecord findByName(String name){
        for(PlayerRecord p:players.values())if(p.lastName.equalsIgnoreCase(name))return p;
        return null;
    }
    RegionRecord region(String id){return regions.computeIfAbsent(id,unused->new RegionRecord());}
    long attempts(){return allAttempts;}
    long successes(){return allSuccesses;}
    long failures(){return allFailures;}
    double money(){return allMoney;}
    int uniquePlayers(){return players.size();}
    Collection<PlayerRecord> players(){return Collections.unmodifiableCollection(players.values());}
    void attempt(UUID id,String playerName,String region){
        PlayerRecord p=player(id);p.lastName=playerName;p.attempts++;region(region).attempts++;allAttempts++;save();
    }
    void fail(UUID id,String playerName,String region){
        PlayerRecord p=player(id);p.lastName=playerName;p.failures++;region(region).failures++;allFailures++;save();
    }
    void success(UUID id,String playerName,String region,double amount){
        PlayerRecord p=player(id);p.lastName=playerName;p.successes++;p.money+=amount;
        RegionRecord r=region(region);r.successes++;r.money+=amount;
        allSuccesses++;allMoney+=amount;save();
    }
    List<PlayerRecord> top(String type){
        List<PlayerRecord> list=new ArrayList<>(players.values());
        Comparator<PlayerRecord> cmp=switch(type){
            case "fail", "failed", "failures" -> Comparator.comparingLong((PlayerRecord p)->p.failures);
            case "money", "earned" -> Comparator.comparingDouble((PlayerRecord p)->p.money);
            case "attempts" -> Comparator.comparingLong((PlayerRecord p)->p.attempts);
            default -> Comparator.comparingLong((PlayerRecord p)->p.successes);
        };
        list.sort(cmp.reversed().thenComparing(p->p.lastName,String.CASE_INSENSITIVE_ORDER));
        return list;
    }
    int rank(UUID uuid,String type){
        if(uuid==null||!players.containsKey(uuid))return 0;
        List<PlayerRecord> list=top(type);
        for(int i=0;i<list.size();i++)if(list.get(i).uuid.equals(uuid))return i+1;
        return 0;
    }
    void load(){
        if(!file.isFile())return;
        YamlConfiguration y=YamlConfiguration.loadConfiguration(file);
        allAttempts=y.getLong("global.attempts");allSuccesses=y.getLong("global.successes");
        allFailures=y.getLong("global.failures");allMoney=y.getDouble("global.money");
        ConfigurationSection ps=y.getConfigurationSection("players");
        if(ps!=null)for(String key:ps.getKeys(false)){
            try{UUID uuid=UUID.fromString(key);PlayerRecord p=player(uuid);
                p.lastName=ps.getString(key+".name","Unknown");p.attempts=ps.getLong(key+".attempts");
                p.successes=ps.getLong(key+".successes");p.failures=ps.getLong(key+".failures");
                p.money=ps.getDouble(key+".money");
            }catch(IllegalArgumentException ignored){}
        }
        ConfigurationSection rs=y.getConfigurationSection("regions");
        if(rs!=null)for(String id:rs.getKeys(false)){
            RegionRecord r=region(id);r.attempts=rs.getLong(id+".attempts");
            r.successes=rs.getLong(id+".successes");r.failures=rs.getLong(id+".failures");
            r.money=rs.getDouble(id+".money");
        }
    }
    void save(){
        YamlConfiguration y=new YamlConfiguration();
        y.set("global.attempts",allAttempts);y.set("global.successes",allSuccesses);
        y.set("global.failures",allFailures);y.set("global.money",allMoney);
        for(PlayerRecord p:players.values()){
            String base="players."+p.uuid+".";y.set(base+"name",p.lastName);y.set(base+"attempts",p.attempts);
            y.set(base+"successes",p.successes);y.set(base+"failures",p.failures);y.set(base+"money",p.money);
        }
        for(Map.Entry<String,RegionRecord> e:regions.entrySet()){
            String base="regions."+e.getKey()+".";RegionRecord r=e.getValue();
            y.set(base+"attempts",r.attempts);y.set(base+"successes",r.successes);
            y.set(base+"failures",r.failures);y.set(base+"money",r.money);
        }
        try{y.save(file);}catch(IOException ex){logger.warning("Unable to save stats.yml: "+ex.getMessage());}
    }
}
