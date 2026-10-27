package fr.slotmachine;

import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SlotPlugin extends JavaPlugin {

    private Configs configs;
    private Msg msg;
    private EconomyHook economy;
    private ItemFactory items;
    private Effects effects;
    private Engine engine;
    private Rewards rewards;
    private Pools pools;
    private NamespacedKey fireworkKey;
    private List<Double> bets = List.of(10d);

    private final Map<UUID, SlotSession> sessions = new HashMap<>();
    private final Map<UUID, Integer> betMemory = new HashMap<>();

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        fireworkKey = new NamespacedKey(this, "slot_firework");
        configs = new Configs(this);
        msg = new Msg(this);
        economy = new EconomyHook(this);
        items = new ItemFactory(this);
        effects = new Effects(this);
        engine = new Engine(this);
        pools = new Pools(this);
        reload();

        SlotCommand cmd = new SlotCommand(this);
        PluginCommand pc = getCommand("roulette");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new SlotListener(this), this);

        long period = Math.max(5L, configs.config().getLong("general.autosave-seconds", 30)) * 20L;
        getServer().getScheduler().runTaskTimer(this, pools::saveIfDirty, period, period);

        if (getServer().getPluginManager().isPluginEnabled("Nexo")) {
            getLogger().info("Nexo détecté : icônes 'nexo:<id>' disponibles.");
        }
    }

    @Override
    public void onDisable() {
        for (SlotSession s : new ArrayList<>(sessions.values())) {
            try {
                s.shutdown();
            } catch (Exception e) {
                getLogger().warning("Erreur à la fermeture d'une session : " + e.getMessage());
            }
        }
        sessions.clear();
        if (pools != null) pools.save();
    }

    /** Recharge config.yml, messages.yml, rewards.yml et gui.yml. */
    public void reload() {
        configs.load();
        msg.reload();
        rewards = new Rewards(this, configs.rewards());
        engine.reload();
        items.clearCache();
        pools.sync(rewards);

        List<Double> list = new ArrayList<>();
        for (double b : configs.config().getDoubleList("bets")) if (b > 0) list.add(b);
        if (list.isEmpty()) list.add(10d);
        bets = List.copyOf(list);
    }

    /** Cagnottes communes : rafraîchit le menu de tous les joueurs qui ont la machine ouverte. */
    public void refreshAllPools() {
        for (SlotSession s : new ArrayList<>(sessions.values())) s.refreshLive();
    }

    public Configs configs() { return configs; }
    public Msg msg() { return msg; }
    public EconomyHook economy() { return economy; }
    public ItemFactory items() { return items; }
    public Effects effects() { return effects; }
    public Engine engine() { return engine; }
    public Rewards rewards() { return rewards; }
    public Pools pools() { return pools; }
    public NamespacedKey fireworkKey() { return fireworkKey; }
    public List<Double> bets() { return bets; }
    public Map<UUID, SlotSession> sessions() { return sessions; }
    public Map<UUID, Integer> betMemory() { return betMemory; }
}
