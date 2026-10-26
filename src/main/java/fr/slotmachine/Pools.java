package fr.slotmachine;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Cagnottes progressives + free spins en attente, persistés dans data.yml. */
public final class Pools {

    public static final class FreeSpins {
        public int count;
        public double bet;
        public double won;
    }

    private final SlotPlugin plugin;
    private final File file;
    private final Map<String, Double> amounts = new LinkedHashMap<>();
    private final Map<UUID, FreeSpins> free = new HashMap<>();
    private boolean dirty;

    Pools(SlotPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection p = y.getConfigurationSection("pools");
        if (p != null) for (String k : p.getKeys(false)) amounts.put(k, p.getDouble(k));
        ConfigurationSection f = y.getConfigurationSection("free-spins");
        if (f != null) {
            for (String k : f.getKeys(false)) {
                try {
                    FreeSpins fs = new FreeSpins();
                    fs.count = f.getInt(k + ".count");
                    fs.bet = f.getDouble(k + ".bet");
                    fs.won = f.getDouble(k + ".won");
                    if (fs.count > 0) free.put(UUID.fromString(k), fs);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    /** Ajoute les nouveaux paliers (au montant de départ) après un reload. */
    void sync(Rewards rw) {
        for (Rewards.Tier t : rw.tiers().values()) amounts.putIfAbsent(t.id(), t.seed());
        dirty = true;
    }

    public double get(String tier) { return amounts.getOrDefault(tier, 0d); }

    public void add(String tier, double value) {
        amounts.merge(tier, value, Double::sum);
        dirty = true;
    }

    public void set(String tier, double value) {
        amounts.put(tier, Math.max(0, value));
        dirty = true;
    }

    public void reset(String tier, double seed) {
        amounts.put(tier, seed);
        dirty = true;
    }

    public FreeSpins freeOf(UUID id) { return free.get(id); }

    public FreeSpins createFree(UUID id) {
        FreeSpins fs = new FreeSpins();
        free.put(id, fs);
        dirty = true;
        return fs;
    }

    public void clearFree(UUID id) {
        free.remove(id);
        dirty = true;
    }

    public void markDirty() { dirty = true; }

    void saveIfDirty() {
        if (dirty) save();
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Double> e : amounts.entrySet()) y.set("pools." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, FreeSpins> e : free.entrySet()) {
            String base = "free-spins." + e.getKey();
            y.set(base + ".count", e.getValue().count);
            y.set(base + ".bet", e.getValue().bet);
            y.set(base + ".won", e.getValue().won);
        }
        String data = y.saveToString();
        dirty = false;
        Runnable write = () -> {
            try {
                Files.writeString(file.toPath(), data);
            } catch (IOException ex) {
                plugin.getLogger().severe("Impossible d'écrire data.yml : " + ex.getMessage());
            }
        };
        if (plugin.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        else write.run();
    }
}
