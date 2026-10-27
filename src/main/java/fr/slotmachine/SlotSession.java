package fr.slotmachine;

import fr.slotmachine.Rewards.Symbol;
import fr.slotmachine.Rewards.Tier;
import fr.slotmachine.Rewards.Type;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Une machine à sous ouverte par un joueur : GUI + animations.
 * Règle d'or : le résultat est tiré et PAYÉ au moment où les rouleaux s'arrêtent (settle()),
 * tout le reste (clignotements, roue des cagnottes, messages) est purement cosmétique.
 * Fermer le menu en cours de tour ne fait donc jamais perdre un gain.
 */
public final class SlotSession implements InventoryHolder {

    private final SlotPlugin plugin;
    private final Player player;
    private final UUID uuid;
    private final FileConfiguration gui;
    private final Inventory inv;

    // layout
    private final int[][] reelSlots = new int[3][3];
    private final Map<String, int[]> poolSlots = new LinkedHashMap<>();
    private final List<int[]> betSlots = new ArrayList<>();
    private int[] spinSlots = {49}, infoSlots = {50}, paytableSlots = {51};
    private final Set<Integer> fillers = new HashSet<>();
    private boolean fillerEnabled, frameEnabled;

    private final Map<String, ItemStack> symbolItems = new HashMap<>();
    private final Symbol[][] shown = new Symbol[3][3];

    // état
    private int betIndex;
    private boolean busy, settled, closed;
    private boolean switching;          // vrai pendant le passage menu machine <-> menu bonus
    private BonusGame bonusGame;
    private double lastWin;
    private String highlight;
    private final Map<String, Double> frozen = new HashMap<>();
    private BukkitTask task, refreshTask;
    private Runnable pending;

    // tour en cours
    private Engine.Result result;
    private double curBet, curTotal;
    private boolean curFree;
    private Tier curTier;

    public SlotSession(SlotPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.uuid = player.getUniqueId();
        this.gui = plugin.configs().gui();

        int rows = Math.max(1, Math.min(6, gui.getInt("rows", 6)));
        this.inv = Bukkit.createInventory(this, rows * 9, plugin.msg().parse(gui.getString("title", "Slots")));
        loadLayout();

        int max = plugin.bets().size() - 1;
        this.betIndex = Math.max(0, Math.min(max, plugin.betMemory().getOrDefault(uuid, 0)));

        Rewards rw = plugin.rewards();
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) shown[r][c] = rw.random(ThreadLocalRandom.current());
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inv;
    }

    // ------------------------------------------------------------------ layout

    private boolean ok(int slot) {
        return slot >= 0 && slot < inv.getSize();
    }

    /** Lit "slots: [..]" (plusieurs emplacements pour un même objet) ou "slot: n". */
    private int[] slotsOf(String path, int... def) {
        ConfigurationSection sec = gui.getConfigurationSection(path);
        List<Integer> out = new ArrayList<>();
        if (sec != null) {
            if (sec.isList("slots")) {
                for (int v : sec.getIntegerList("slots")) if (ok(v)) out.add(v);
            } else {
                int v = sec.getInt("slot", -1);
                if (ok(v)) out.add(v);
            }
        }
        if (out.isEmpty()) for (int d : def) if (ok(d)) out.add(d);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean contains(int[] arr, int v) {
        for (int a : arr) if (a == v) return true;
        return false;
    }

    /** Pose le même item sur tous les slots donnés (null = slot laissé vide). */
    private void put(int[] slots, ItemStack item) {
        for (int s : slots) inv.setItem(s, item == null ? null : item.clone());
    }

    /** Appelé quand une cagnotte change à cause d'un autre joueur. */
    void refreshLive() {
        if (closed || busy) return;
        renderPools();
    }

    private void loadLayout() {
        Set<Integer> used = new HashSet<>();
        for (int r = 0; r < 3; r++) {
            List<Integer> row = gui.getIntegerList("reels.row-" + (r + 1));
            for (int c = 0; c < 3; c++) {
                int slot = row.size() > c ? row.get(c) : 12 + r * 9 + c;
                reelSlots[r][c] = ok(slot) ? slot : 0;
                used.add(reelSlots[r][c]);
            }
        }
        ConfigurationSection ps = gui.getConfigurationSection("pools");
        if (ps != null) {
            for (String id : ps.getKeys(false)) {
                int[] sl = slotsOf("pools." + id);
                if (sl.length > 0 && plugin.rewards().tiers().containsKey(id)) {
                    poolSlots.put(id, sl);
                    for (int x : sl) used.add(x);
                }
            }
        }
        // Chaque entrée de buttons.bet.slots = un slot (45) OU plusieurs slots ([45, 46])
        for (Object o : gui.getList("buttons.bet.slots", List.of())) {
            List<Integer> g = new ArrayList<>();
            if (o instanceof Number num) {
                if (ok(num.intValue())) g.add(num.intValue());
            } else if (o instanceof List<?> l) {
                for (Object x : l) if (x instanceof Number num2 && ok(num2.intValue())) g.add(num2.intValue());
            }
            if (!g.isEmpty()) {
                int[] arr = g.stream().mapToInt(Integer::intValue).toArray();
                betSlots.add(arr);
                for (int x : arr) used.add(x);
            }
        }
        if (betSlots.size() < plugin.bets().size()) {
            plugin.getLogger().warning("gui.yml : " + plugin.bets().size() + " mises configurées mais seulement "
                    + betSlots.size() + " emplacements dans buttons.bet.slots.");
        }

        spinSlots = slotsOf("buttons.spin", 49);
        infoSlots = slotsOf("buttons.info", 50);
        paytableSlots = slotsOf("buttons.paytable", 51);
        for (int x : spinSlots) used.add(x);
        for (int x : infoSlots) used.add(x);
        for (int x : paytableSlots) used.add(x);

        fillerEnabled = gui.getBoolean("filler.enabled", false);
        frameEnabled = gui.getBoolean("win-frame.enabled", false);
        for (int i = 0; i < inv.getSize(); i++) if (!used.contains(i)) fillers.add(i);
    }

    // ------------------------------------------------------------------ helpers

    private String fmt(double v) {
        return plugin.economy().format(v);
    }

    private double currentBet() {
        Pools.FreeSpins fs = plugin.pools().freeOf(uuid);
        if (fs != null && fs.count > 0) return fs.bet;
        return plugin.bets().get(Math.min(betIndex, plugin.bets().size() - 1));
    }

    private TagResolver tagsFor(double bet) {
        int lines = plugin.engine().lines().size();
        Pools.FreeSpins fs = plugin.pools().freeOf(uuid);
        return TagResolver.resolver(
                Placeholder.unparsed("bet", fmt(bet)),
                Placeholder.unparsed("total", fmt(bet * lines)),
                Placeholder.unparsed("lines", String.valueOf(lines)),
                Placeholder.unparsed("balance", fmt(plugin.economy().balance(player))),
                Placeholder.unparsed("last-win", fmt(lastWin)),
                Placeholder.unparsed("free-spins", String.valueOf(fs == null ? 0 : fs.count)),
                Placeholder.unparsed("free-win", fmt(fs == null ? 0 : fs.won)));
    }

    private static String key(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------ rendering

    private ItemStack symbolItem(Symbol s, boolean glow) {
        ItemStack base = symbolItems.computeIfAbsent(s.id(),
                id -> plugin.items().build(gui.getConfigurationSection("symbols." + id), Material.BARRIER));
        return ItemFactory.glow(base, glow);
    }

    private void renderCell(int r, int c, boolean glow) {
        inv.setItem(reelSlots[r][c], symbolItem(shown[r][c], glow));
    }

    /** null si les cases vides doivent rester vides (filler.enabled: false). */
    private ItemStack filler() {
        if (!fillerEnabled) return null;
        return plugin.items().build(gui.getConfigurationSection("filler"), Material.BLACK_STAINED_GLASS_PANE);
    }

    private void renderAll() {
        ItemStack f = filler();
        for (int slot : fillers) inv.setItem(slot, f);
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) renderCell(r, c, false);
        renderPools();
        renderBets();
        renderSpin();
        renderInfo();
        renderPaytable();
    }

    private void renderPools() {
        for (Map.Entry<String, int[]> e : poolSlots.entrySet()) {
            String id = e.getKey();
            Tier tier = plugin.rewards().tiers().get(id);
            double amount = frozen.containsKey(id) ? frozen.get(id) : plugin.pools().get(id);
            ItemStack it = plugin.items().build(gui.getConfigurationSection("pools." + id), Material.GOLD_INGOT,
                    Placeholder.unparsed("amount", fmt(amount)),
                    Placeholder.unparsed("min-bet", fmt(tier == null ? 0 : tier.minBet())));
            put(e.getValue(), ItemFactory.glow(it, id.equals(highlight)));
        }
    }

    private void renderBets() {
        List<Double> bets = plugin.bets();
        for (int i = 0; i < betSlots.size(); i++) {
            if (i >= bets.size()) {
                put(betSlots.get(i), filler());
                continue;
            }
            double b = bets.get(i);
            ConfigurationSection s = gui.getConfigurationSection(i == betIndex ? "buttons.bet-selected" : "buttons.bet");
            String icon = gui.getString("buttons.bet-icons." + key(b));
            put(betSlots.get(i), plugin.items().build(s, icon, Material.GOLD_NUGGET, tagsFor(b)));
        }
    }

    private void renderSpin() {
        Pools.FreeSpins fs = plugin.pools().freeOf(uuid);
        boolean free = fs != null && fs.count > 0;
        String path = busy ? "buttons.spin-busy" : (free ? "buttons.spin-free" : "buttons.spin");
        put(spinSlots, plugin.items().build(gui.getConfigurationSection(path), Material.LIME_CONCRETE,
                tagsFor(currentBet())));
    }

    private void renderInfo() {
        put(infoSlots, plugin.items().build(gui.getConfigurationSection("buttons.info"), Material.PAPER,
                tagsFor(currentBet())));
    }

    /** Nom de balise valide (minuscules, chiffres, _ et -). */
    private static String tagName(String id) {
        return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    /**
     * Placeholders de la table des gains, utilisables dans buttons.paytable.lore (gui.yml) :
     *   <ID-pair> <ID-triple> <ID-name>         pour chaque symbole de rewards.yml (ex: <cherry-pair>)
     *   <tier-ID> <minbet-ID> <pool-ID>         pour chaque cagnotte (nom, mise minimale, montant actuel)
     *   <free-spins-N>                          free spins pour N scatters (rewards.yml -> free-spins.awards)
     *   <free-multiplier> <bonus-required> <match-required> <lines>
     */
    private TagResolver paytableTags() {
        Rewards rw = plugin.rewards();
        Msg msg = plugin.msg();
        FileConfiguration m = plugin.configs().messages();
        String none = msg.raw("paytable.none");
        String multFmt = msg.raw("paytable.mult");
        TagResolver.Builder b = TagResolver.builder();
        b.resolver(tagsFor(currentBet()));
        for (Symbol s : rw.symbols()) {
            String id = tagName(s.id());
            b.resolver(Placeholder.unparsed(id + "-pair", s.pair() > 0 ? multFmt.replace("<value>", trim(s.pair())) : none));
            b.resolver(Placeholder.unparsed(id + "-triple", multFmt.replace("<value>", trim(s.triple()))));
            b.resolver(Placeholder.parsed(id + "-name", msg.color(gui.getString("symbols." + s.id() + ".name", s.id()))));
        }
        for (Tier t : rw.tiers().values()) {
            String id = tagName(t.id());
            String n = m.getString("tiers." + t.id());
            b.resolver(Placeholder.parsed("tier-" + id, msg.color(n == null ? t.id().toUpperCase(Locale.ROOT) : n)));
            b.resolver(Placeholder.unparsed("minbet-" + id, fmt(t.minBet())));
            b.resolver(Placeholder.unparsed("pool-" + id, fmt(poolAmount(t.id()))));
        }
        for (Map.Entry<Integer, Integer> e : rw.freeSpinAwards().entrySet()) {
            b.resolver(Placeholder.unparsed("free-spins-" + e.getKey(), String.valueOf(e.getValue())));
        }
        b.resolver(Placeholder.unparsed("free-multiplier", trim(rw.freeMultiplier())));
        b.resolver(Placeholder.unparsed("bonus-required", String.valueOf(rw.bonusRequired())));
        b.resolver(Placeholder.unparsed("match-required", String.valueOf(rw.matchRequired())));
        return b.build();
    }

    private void renderPaytable() {
        ConfigurationSection sec = gui.getConfigurationSection("buttons.paytable");
        ItemStack it = plugin.items().build(sec, Material.BOOK, paytableTags());

        // Lore vide dans gui.yml -> ancien mode : lignes générées depuis messages.yml (paytable.*)
        boolean custom = sec != null && !sec.getStringList("lore").isEmpty();
        if (!custom) {
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                Msg msg = plugin.msg();
                String none = msg.raw("paytable.none");
                String multFmt = msg.raw("paytable.mult");
                List<Component> lore = new ArrayList<>();
                for (String h : msg.list("paytable.header")) lore.add(msg.item(h));
                for (Symbol s : plugin.rewards().symbols()) {
                    String name = gui.getString("symbols." + s.id() + ".name", s.id());
                    String lineKey = switch (s.type()) {
                        case NORMAL -> "paytable.line";
                        case WILD -> "paytable.line-wild";
                        case SCATTER -> "paytable.line-scatter";
                        case BONUS -> "paytable.line-bonus";
                    };
                    lore.add(msg.item(msg.raw(lineKey),
                            Placeholder.parsed("symbol", msg.color(name)),
                            Placeholder.unparsed("pair", s.pair() > 0 ? multFmt.replace("<value>", trim(s.pair())) : none),
                            Placeholder.unparsed("triple", multFmt.replace("<value>", trim(s.triple()))),
                            Placeholder.unparsed("required", String.valueOf(plugin.rewards().bonusRequired()))));
                }
                for (String f : msg.list("paytable.footer")) lore.add(msg.item(f));
                meta.lore(lore);
                it.setItemMeta(meta);
            }
        }
        put(paytableSlots, it);
    }

    // ------------------------------------------------------------------ cycle de vie

    public void open() {
        plugin.sessions().put(uuid, this);
        renderAll();
        player.openInventory(inv);
        plugin.effects().named(player, "open");

        int refresh = Math.max(10, gui.getInt("refresh-ticks", 40));
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (closed || busy) return;
            renderPools();
            renderInfo();
        }, refresh, refresh);

        // Free spins en attente d'une session précédente : on reprend automatiquement
        Pools.FreeSpins fs = plugin.pools().freeOf(uuid);
        if (fs != null && fs.count > 0 && plugin.rewards().autoPlay()) {
            task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!closed) spin();
            }, plugin.rewards().autoDelay());
        }
    }

    public void onClose() {
        if (switching || closed) return;
        closed = true;
        if (bonusGame != null) {
            bonusGame.cancel();
            bonusGame = null;
        }
        if (task != null) task.cancel();
        if (refreshTask != null) refreshTask.cancel();
        if (busy) {
            settle();
            busy = false;
            Runnable p = pending;
            pending = null;
            if (p != null) p.run();
        }
        frozen.clear();
        plugin.sessions().remove(uuid, this);
    }

    /** Appelé à l'arrêt du serveur. */
    public void shutdown() {
        onClose();
        player.closeInventory();
    }

    // ------------------------------------------------------------------ clics

    public void click(int slot) {
        if (closed) return;
        if (contains(spinSlots, slot)) {
            spin();
            return;
        }
        for (int i = 0; i < betSlots.size() && i < plugin.bets().size(); i++) {
            if (!contains(betSlots.get(i), slot)) continue;
            Pools.FreeSpins fs = plugin.pools().freeOf(uuid);
            if (busy || (fs != null && fs.count > 0)) return;
            betIndex = i;
            plugin.betMemory().put(uuid, i);
            plugin.effects().named(player, "click");
            renderBets();
            renderSpin();
            renderInfo();
            return;
        }
    }

    // ------------------------------------------------------------------ lancer un tour

    public void spin() {
        if (busy || closed) return;
        Rewards rw = plugin.rewards();
        Engine eng = plugin.engine();
        EconomyHook eco = plugin.economy();
        Msg msg = plugin.msg();

        Pools.FreeSpins fs = plugin.pools().freeOf(uuid);
        boolean free = fs != null && fs.count > 0;
        double bet = free ? fs.bet : plugin.bets().get(Math.min(betIndex, plugin.bets().size() - 1));
        double total = bet * eng.lines().size();

        if (!free) {
            if (!eco.ready()) {
                msg.send(player, "economy-missing");
                return;
            }
            if (!eco.has(player, total)) {
                msg.send(player, "not-enough-money",
                        Placeholder.unparsed("total", fmt(total)),
                        Placeholder.unparsed("balance", fmt(eco.balance(player))));
                plugin.effects().named(player, "no-money");
                return;
            }
            if (!eco.withdraw(player, total)) {
                msg.send(player, "transaction-failed");
                return;
            }
            for (Tier t : rw.tiers().values()) plugin.pools().add(t.id(), total * t.contribution() / 100.0);
            plugin.refreshAllPools();
        } else {
            fs.count--;
            plugin.pools().markDirty();
        }

        curBet = bet;
        curTotal = total;
        curFree = free;
        busy = true;
        settled = false;
        pending = null;
        highlight = null;
        frozen.clear();

        result = eng.evaluate(eng.roll(rw), eng.lines(), rw);
        curTier = result.bonus() ? pickTier(total) : null;

        plugin.effects().named(player, "spin-start");
        renderSpin();
        renderInfo();
        renderPools();
        startReels();
    }

    private Tier pickTier(double total) {
        List<Tier> eligible = new ArrayList<>();
        int sum = 0;
        for (Tier t : plugin.rewards().tiers().values()) {
            if (t.wheelWeight() > 0 && total >= t.minBet()) {
                eligible.add(t);
                sum += t.wheelWeight();
            }
        }
        if (eligible.isEmpty()) return plugin.rewards().tiers().values().iterator().next();
        int r = ThreadLocalRandom.current().nextInt(sum);
        for (Tier t : eligible) {
            r -= t.wheelWeight();
            if (r < 0) return t;
        }
        return eligible.get(0);
    }

    // ------------------------------------------------------------------ animation des rouleaux

    private void startReels() {
        FileConfiguration cfg = plugin.configs().config();
        Rewards rw = plugin.rewards();
        Symbol[][] grid = result.grid();

        final int period = Math.max(1, cfg.getInt("animation.shift-period", 2));
        int first = cfg.getInt("animation.first-reel-ticks", 30);
        int stagger = cfg.getInt("animation.reel-stagger-ticks", 12);
        int extra = cfg.getInt("animation.tease.extra-ticks", 30);

        // Suspense : 2 symboles spéciaux déjà sortis sur les 2 premiers rouleaux
        boolean tease = false;
        if (cfg.getBoolean("animation.tease.enabled", true)) {
            Set<Type> types = new HashSet<>();
            for (String s : cfg.getStringList("animation.tease.symbol-types")) {
                try {
                    types.add(Type.valueOf(s.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            int count = 0;
            for (int c = 0; c < 2; c++) for (int r = 0; r < 3; r++) if (types.contains(grid[r][c].type())) count++;
            tease = count >= cfg.getInt("animation.tease.min-symbols", 2);
        }
        final boolean teaseFinal = tease;

        final int[] total = new int[3];
        final int[] done = new int[3];
        for (int c = 0; c < 3; c++) {
            int stop = first + c * stagger + (tease && c == 2 ? extra : 0);
            total[c] = Math.max(3, stop / period);
        }

        task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (closed) {
                    cancel();
                    return;
                }
                t++;
                if (t % period != 0) return;
                boolean tick = false, allDone = true;
                for (int c = 0; c < 3; c++) {
                    if (done[c] >= total[c]) continue;
                    int k = done[c]++;
                    int left = total[c] - k;
                    // Les 3 derniers décalages posent la colonne finale (bas, milieu, haut)
                    Symbol s = left <= 3 ? grid[left - 1][c] : rw.random(ThreadLocalRandom.current());
                    shown[2][c] = shown[1][c];
                    shown[1][c] = shown[0][c];
                    shown[0][c] = s;
                    for (int r = 0; r < 3; r++) renderCell(r, c, false);
                    tick = true;
                    if (done[c] == total[c]) reelStopped(c, teaseFinal, grid);
                    else allDone = false;
                }
                if (tick) plugin.effects().named(player, "reel-tick");
                if (allDone) {
                    cancel();
                    onReelsStopped();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void reelStopped(int c, boolean tease, Symbol[][] grid) {
        plugin.effects().named(player, "reel-stop", 1f + 0.12f * c);
        for (int r = 0; r < 3; r++) {
            Type t = grid[r][c].type();
            if (t == Type.BONUS || t == Type.SCATTER) {
                plugin.effects().named(player, "special-land");
                break;
            }
        }
        if (tease && c == 1) plugin.effects().named(player, "anticipation");
    }

    private void onReelsStopped() {
        settle();
        renderInfo();
        renderPools();
        if (!result.cells().isEmpty()) flash();
        else finish();
    }

    // ------------------------------------------------------------------ paiement (une seule fois)

    private void settle() {
        if (settled) return;
        settled = true;
        Rewards rw = plugin.rewards();
        Pools pl = plugin.pools();

        double line = curBet * result.lineMult() * (curFree ? rw.freeMultiplier() : 1.0);
        double prize = 0;
        if (curTier != null) {
            prize = pl.get(curTier.id());
            frozen.put(curTier.id(), prize); // on affiche le montant gagné pendant la roue
            pl.reset(curTier.id(), curTier.seed());
            plugin.refreshAllPools();
        }
        double pay = line + prize;
        if (pay > 0 && !plugin.economy().deposit(player, pay)) {
            plugin.msg().send(player, "deposit-failed", Placeholder.unparsed("win", fmt(pay)));
        }
        lastWin = pay;

        // Free spins
        int awarded = 0;
        Pools.FreeSpins fs = pl.freeOf(uuid);
        if (result.freeSpins() > 0 && (!curFree || rw.retrigger())) {
            awarded = result.freeSpins();
            if (fs == null) {
                fs = pl.createFree(uuid);
                fs.bet = curBet;
                fs.won = 0;
            }
            fs.count += awarded;
        }
        boolean ended = false;
        double seriesWon = 0;
        if (curFree && fs != null) {
            fs.won += pay;
            if (fs.count <= 0) {
                ended = true;
                seriesWon = fs.won;
                pl.clearFree(uuid);
            }
        }
        pl.markDirty();

        // Catégorie du résultat
        String cat;
        if (curTier != null) cat = curTier.id();
        else if (awarded > 0) cat = "free-spins";
        else if (pay > 0 && pay >= curTotal * rw.bigWinMultiplier()) cat = "big";
        else if (result.anyTriple()) cat = "triple";
        else if (pay > 0) cat = "pair";
        else cat = "lose";

        final String category = cat;
        final double fPay = pay, fPrize = prize, fSeries = seriesWon;
        final int fAwarded = awarded;
        final boolean fEnded = ended;
        final Tier fTier = curTier;
        final double fBet = curTotal, fMult = result.lineMult() * (curFree ? rw.freeMultiplier() : 1.0);
        final boolean fFree = curFree;
        pending = () -> announce(category, fPay, fPrize, fAwarded, fEnded, fSeries, fTier, fBet, fMult, fFree);
    }

    private void announce(String cat, double pay, double prize, int awarded, boolean ended, double seriesWon,
                          Tier tier, double totalBet, double mult, boolean wasFree) {
        if (!player.isOnline()) return;
        Msg msg = plugin.msg();
        FileConfiguration m = plugin.configs().messages();
        Pools.FreeSpins fs = plugin.pools().freeOf(uuid);

        String tierName = tier == null ? "" : m.getString("tiers." + tier.id(), tier.id().toUpperCase(Locale.ROOT));
        TagResolver r = TagResolver.resolver(
                Placeholder.unparsed("win", fmt(pay)),
                Placeholder.unparsed("bet", fmt(totalBet)),
                Placeholder.unparsed("mult", trim(mult)),
                Placeholder.unparsed("spins", String.valueOf(awarded)),
                Placeholder.unparsed("prize", fmt(prize)),
                Placeholder.parsed("tier", msg.color(tierName)),
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("free-left", String.valueOf(fs == null ? 0 : fs.count)),
                Placeholder.unparsed("series-win", fmt(seriesWon)));

        String key = (wasFree && cat.equals("lose")) ? "results.free-spin-lose" : "results." + cat;
        msg.send(player, key, r);

        ConfigurationSection t = m.getConfigurationSection("titles." + cat);
        if (t != null) {
            FileConfiguration cfg = plugin.configs().config();
            Title.Times times = Title.Times.times(
                    Duration.ofMillis(cfg.getInt("animation.title-times.fade-in", 10) * 50L),
                    Duration.ofMillis(cfg.getInt("animation.title-times.stay", 60) * 50L),
                    Duration.ofMillis(cfg.getInt("animation.title-times.fade-out", 20) * 50L));
            player.showTitle(Title.title(msg.parse(t.getString("title", ""), r), msg.parse(t.getString("subtitle", ""), r), times));
        }

        String broadcast = m.getString("broadcast." + cat, "");
        if (!broadcast.isBlank()) Bukkit.broadcast(msg.parse(broadcast, r));

        plugin.effects().play(player, cat);

        if (ended) msg.send(player, "results.free-spins-end", r);
    }

    // ------------------------------------------------------------------ animation de gain

    private void flash() {
        FileConfiguration cfg = plugin.configs().config();
        final int duration = Math.max(1, cfg.getInt("animation.win-flash.duration-ticks", 50));
        final int period = Math.max(1, cfg.getInt("animation.win-flash.period-ticks", 5));
        final List<ItemStack> frames = new ArrayList<>();
        if (frameEnabled) {
            List<String> specs = gui.getStringList("win-frame.materials");
            if (specs.isEmpty()) specs = List.of("LIME_STAINED_GLASS_PANE");
            for (String sp : specs) frames.add(plugin.items().simple(sp));
        }

        task = new BukkitRunnable() {
            int elapsed = 0, step = 0;
            boolean on = false;

            @Override
            public void run() {
                if (closed) {
                    cancel();
                    return;
                }
                if (elapsed >= duration) {
                    cancel();
                    if (!frames.isEmpty()) {
                        ItemStack f = filler();
                        for (int slot : fillers) inv.setItem(slot, f);
                    }
                    for (int cell : result.cells()) renderCell(cell / 3, cell % 3, false);
                    if (curTier != null) startBonus();
                    else finish();
                    return;
                }
                on = !on;
                for (int cell : result.cells()) renderCell(cell / 3, cell % 3, on);
                if (!frames.isEmpty()) {
                    ItemStack frame = frames.get(step++ % frames.size());
                    for (int slot : fillers) inv.setItem(slot, frame);
                }
                elapsed += period;
            }
        }.runTaskTimer(plugin, 0L, period);
    }

    // ------------------------------------------------------------------ bonus : menu de cartes

    /** Montant affiché d'une cagnotte (le gain en cours est figé pendant le bonus). */
    double poolAmount(String id) {
        return frozen.containsKey(id) ? frozen.get(id) : plugin.pools().get(id);
    }

    private List<Tier> eligibleTiers() {
        List<Tier> out = new ArrayList<>();
        for (Tier t : plugin.rewards().tiers().values()) {
            if (t.wheelWeight() > 0 && curTotal >= t.minBet()) out.add(t);
        }
        if (curTier != null && out.stream().noneMatch(t -> t.id().equals(curTier.id()))) out.add(curTier);
        return out;
    }

    private void startBonus() {
        if (!plugin.rewards().bonusCards()) {
            wheel();
            return;
        }
        try {
            bonusGame = new BonusGame(plugin, this, player, curTier, eligibleTiers());
            plugin.msg().send(player, "results.bonus-start",
                    Placeholder.unparsed("need", String.valueOf(plugin.rewards().matchRequired())));
            switching = true; // ouvrir un autre inventaire ferme celui-ci : ne pas terminer la session
            try {
                bonusGame.open();
            } finally {
                switching = false;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Menu bonus impossible, roue utilisée à la place : " + e);
            bonusGame = null;
            wheel();
        }
    }

    /** Appelé par BonusGame à la fin de la partie : retour sur la machine classique. */
    void bonusFinished() {
        bonusGame = null;
        if (closed) return;
        highlight = curTier == null ? null : curTier.id();
        switching = true;
        try {
            player.openInventory(inv);
        } finally {
            switching = false;
        }
        renderPools();
        finish();
    }

    // ------------------------------------------------------------------ roue des cagnottes

    private void wheel() {
        FileConfiguration cfg = plugin.configs().config();
        List<String> ids = new ArrayList<>(poolSlots.keySet());
        int n = ids.size();
        int target = ids.indexOf(curTier.id());
        if (n == 0 || target < 0) {
            finish();
            return;
        }
        final int steps = Math.max(n, cfg.getInt("animation.wheel.steps", 28));
        final int d0 = Math.max(1, cfg.getInt("animation.wheel.start-delay", 2));
        final int d1 = Math.max(1, cfg.getInt("animation.wheel.end-delay", 9));
        final int hold = Math.max(1, cfg.getInt("animation.wheel.hold-ticks", 40));
        // On démarre de façon à atterrir exactement sur le palier tiré
        final int[] idx = {Math.floorMod(target - (steps - 1), n)};
        final int[] i = {0};
        final Runnable[] step = new Runnable[1];

        plugin.effects().named(player, "wheel-start");
        step[0] = () -> {
            if (closed) return;
            highlight = ids.get(idx[0]);
            renderPools();
            plugin.effects().named(player, "wheel-tick");
            if (i[0] >= steps - 1) {
                plugin.effects().named(player, "wheel-stop");
                task = Bukkit.getScheduler().runTaskLater(plugin, this::finish, hold);
                return;
            }
            int delay = d0 + Math.round((d1 - d0) * (float) i[0] / (steps - 1));
            i[0]++;
            idx[0] = (idx[0] + 1) % n;
            task = Bukkit.getScheduler().runTaskLater(plugin, step[0], Math.max(1, delay));
        };
        step[0].run();
    }

    // ------------------------------------------------------------------ fin de tour

    private void finish() {
        if (!busy) return;
        busy = false;
        frozen.clear();
        Runnable p = pending;
        pending = null;
        if (p != null) p.run();
        if (closed) return;

        renderSpin();
        renderInfo();
        renderPools();

        Pools.FreeSpins fs = plugin.pools().freeOf(uuid);
        if (fs != null && fs.count > 0 && plugin.rewards().autoPlay()) {
            task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!closed) spin();
            }, plugin.rewards().autoDelay());
        }
    }
}
