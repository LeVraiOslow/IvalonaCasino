package fr.slotmachine;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/** Modèle de rewards.yml : symboles, free spins, bonus et cagnottes. */
public final class Rewards {

    public enum Type { NORMAL, WILD, SCATTER, BONUS }

    public record Symbol(String id, Type type, int weight, double pair, double triple) {}

    public record Tier(String id, double seed, double contribution, int wheelWeight, double minBet) {}

    private final List<Symbol> symbols = new ArrayList<>();
    private final Map<String, Symbol> byId = new LinkedHashMap<>();
    private final Map<String, Tier> tiers = new LinkedHashMap<>();
    private final TreeMap<Integer, Integer> freeSpinAwards = new TreeMap<>();
    private int totalWeight;
    private final int bonusRequired;
    private final boolean retrigger;
    private final double freeMultiplier;
    private final boolean autoPlay;
    private final int autoDelay;
    private final double bigWinMultiplier;

    Rewards(SlotPlugin plugin, FileConfiguration cfg) {
        ConfigurationSection sec = cfg.getConfigurationSection("symbols");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null) continue;
                Type type;
                try {
                    type = Type.valueOf(s.getString("type", "NORMAL").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Type de symbole invalide pour '" + id + "', NORMAL utilisé.");
                    type = Type.NORMAL;
                }
                int weight = Math.max(0, s.getInt("weight", 1));
                Symbol sym = new Symbol(id, type, weight, s.getDouble("pair", 0), s.getDouble("triple", 0));
                symbols.add(sym);
                byId.put(id, sym);
                totalWeight += weight;
            }
        }
        if (symbols.isEmpty() || totalWeight <= 0) {
            throw new IllegalStateException("rewards.yml : aucun symbole valide (poids total = 0).");
        }

        ConfigurationSection jp = cfg.getConfigurationSection("jackpots");
        if (jp != null) {
            for (String id : jp.getKeys(false)) {
                ConfigurationSection t = jp.getConfigurationSection(id);
                if (t == null) continue;
                tiers.put(id, new Tier(id, t.getDouble("seed", 0), t.getDouble("contribution", 0),
                        Math.max(0, t.getInt("wheel-weight", 1)), t.getDouble("min-bet", 0)));
            }
        }

        ConfigurationSection aw = cfg.getConfigurationSection("free-spins.awards");
        if (aw != null) {
            for (String k : aw.getKeys(false)) {
                try {
                    freeSpinAwards.put(Integer.parseInt(k), aw.getInt(k));
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("free-spins.awards : clé invalide '" + k + "'");
                }
            }
        }
        retrigger = cfg.getBoolean("free-spins.retrigger", true);
        freeMultiplier = cfg.getDouble("free-spins.win-multiplier", 1.0);
        autoPlay = cfg.getBoolean("free-spins.auto-play", true);
        autoDelay = Math.max(1, cfg.getInt("free-spins.auto-delay-ticks", 40));
        bonusRequired = Math.max(1, cfg.getInt("bonus.required", 3));
        bigWinMultiplier = cfg.getDouble("big-win-multiplier", 20);
    }

    public Symbol random(RandomGenerator rnd) {
        int r = rnd.nextInt(totalWeight);
        for (Symbol s : symbols) {
            r -= s.weight();
            if (r < 0) return s;
        }
        return symbols.get(symbols.size() - 1);
    }

    /** Nombre de free spins pour un nombre de scatters (0 si palier non atteint). */
    public int freeSpinsFor(int scatters) {
        Map.Entry<Integer, Integer> e = freeSpinAwards.floorEntry(scatters);
        return e == null ? 0 : e.getValue();
    }

    public Symbol symbol(String id) { return byId.get(id); }
    public List<Symbol> symbols() { return symbols; }
    public Map<String, Tier> tiers() { return tiers; }
    public int bonusRequired() { return bonusRequired; }
    public boolean retrigger() { return retrigger; }
    public double freeMultiplier() { return freeMultiplier; }
    public boolean autoPlay() { return autoPlay; }
    public int autoDelay() { return autoDelay; }
    public double bigWinMultiplier() { return bigWinMultiplier; }
}
