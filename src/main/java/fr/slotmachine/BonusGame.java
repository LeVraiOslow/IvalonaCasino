package fr.slotmachine;

import fr.slotmachine.Rewards.Tier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Menu bonus : des cartes face cachée (21 par défaut). Le joueur les retourne une à une ;
 * la première série de N cartes identiques (3 par défaut) remporte la cagnotte correspondante.
 *
 * Le palier gagnant est tiré AVANT (selon wheel-weight, donc les petits paliers sortent bien plus souvent)
 * et la suite des cartes est construite pour que ce palier soit le premier à atteindre N, quel que soit
 * l'ordre des clics. Le gain est déjà versé par SlotSession.settle() : fermer ce menu ne fait rien perdre.
 */
public final class BonusGame implements InventoryHolder {

    private final SlotPlugin plugin;
    private final SlotSession session;
    private final Player player;
    private final FileConfiguration gui;
    private final Inventory inv;
    private final int[] cardSlots;
    private final int[] infoSlots;
    private final Tier winner;
    private final List<Tier> eligible;
    private final int need;
    private final boolean fillerEnabled;

    private final List<String> sequence = new ArrayList<>();
    private final List<String> rest = new ArrayList<>();
    private final boolean[] revealed;
    private final Map<String, Integer> found = new LinkedHashMap<>();
    private final Map<String, List<Integer>> cardsOf = new HashMap<>();

    private int picks, idleSeconds;
    private boolean animating, ended, done;
    private BukkitTask current, idleTask;

    BonusGame(SlotPlugin plugin, SlotSession session, Player player, Tier winner, List<Tier> eligible) {
        this.plugin = plugin;
        this.session = session;
        this.player = player;
        this.winner = winner;
        this.eligible = eligible;
        this.gui = plugin.configs().gui();
        this.need = Math.max(2, plugin.rewards().matchRequired());

        int rows = Math.max(3, Math.min(6, gui.getInt("bonus-game.rows", 6)));
        this.inv = Bukkit.createInventory(this, rows * 9, plugin.msg().parse(str("bonus-game.title", "Bonus")));

        // Emplacements des cartes (21 par défaut)
        Set<Integer> slots = new java.util.LinkedHashSet<>();
        for (int s : gui.getIntegerList("bonus-game.cards")) if (s >= 0 && s < inv.getSize()) slots.add(s);
        if (slots.size() < need) {
            slots.clear();
            for (int r = 1; r <= 3; r++) for (int c = 1; c <= 7; c++) slots.add(r * 9 + c);
        }
        this.cardSlots = slots.stream().mapToInt(Integer::intValue).toArray();
        this.revealed = new boolean[cardSlots.length];

        List<Integer> info = new ArrayList<>();
        for (int s : gui.getIntegerList("bonus-game.info.slots")) {
            if (s >= 0 && s < inv.getSize() && !slots.contains(s)) info.add(s);
        }
        this.infoSlots = info.stream().mapToInt(Integer::intValue).toArray();
        this.fillerEnabled = gui.getBoolean("bonus-game.filler.enabled", false);

        buildSequence();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inv;
    }

    private String str(String path, String def) {
        String v = gui.getString(path); // getString(path) tient compte des valeurs par défaut du jar
        return v == null ? def : v;
    }

    // ------------------------------------------------------------------ tirage

    /**
     * Construit l'ordre des révélations : le palier gagnant atteint N en dernier de la séquence,
     * et aucun autre palier n'atteint N avant (au plus N-1 cartes de chaque).
     */
    private void buildSequence() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int n = cardSlots.length;

        List<String> decoys = new ArrayList<>();
        for (Tier t : eligible) {
            if (t.id().equals(winner.id())) continue;
            for (int i = 0; i < need - 1; i++) decoys.add(t.id());
        }
        Collections.shuffle(decoys, r);
        int maxDecoys = Math.max(0, Math.min(decoys.size(), n - need));
        int d = maxDecoys == 0 ? 0 : r.nextInt(maxDecoys + 1);

        List<String> prefix = new ArrayList<>(decoys.subList(0, d));
        for (int i = 0; i < need - 1; i++) prefix.add(winner.id());
        Collections.shuffle(prefix, r);
        sequence.addAll(prefix);
        sequence.add(winner.id());

        // Cartes restantes : simple décor révélé à la fin (poids : rewards.yml -> bonus.card-weights)
        while (sequence.size() + rest.size() < n) rest.add(randomRest(r));
    }

    private String randomRest(ThreadLocalRandom r) {
        Map<String, Integer> w = plugin.rewards().cardWeights();
        int sum = 0;
        for (Tier t : eligible) sum += Math.max(1, w.getOrDefault(t.id(), 1));
        int x = r.nextInt(sum);
        for (Tier t : eligible) {
            x -= Math.max(1, w.getOrDefault(t.id(), 1));
            if (x < 0) return t.id();
        }
        return winner.id();
    }

    // ------------------------------------------------------------------ affichage

    private String tierName(String id) {
        String n = plugin.configs().messages().getString("tiers." + id);
        return plugin.msg().color(n == null ? id.toUpperCase(java.util.Locale.ROOT) : n);
    }

    private TagResolver tags(String tier) {
        double amount = tier == null ? 0 : session.poolAmount(tier);
        Tier t = tier == null ? null : plugin.rewards().tiers().get(tier);
        return TagResolver.resolver(
                Placeholder.parsed("tier", tier == null ? "" : tierName(tier)),
                Placeholder.unparsed("amount", plugin.economy().format(amount)),
                Placeholder.unparsed("min-bet", plugin.economy().format(t == null ? 0 : t.minBet())),
                Placeholder.unparsed("found", String.valueOf(tier == null ? 0 : found.getOrDefault(tier, 0))),
                Placeholder.unparsed("need", String.valueOf(need)));
    }

    private ItemStack hiddenItem() {
        return plugin.items().build(gui.getConfigurationSection("bonus-game.card-hidden"), Material.ENDER_CHEST, tags(null));
    }

    private ItemStack flipItem() {
        return plugin.items().build(gui.getConfigurationSection("bonus-game.card-flip"), Material.PAPER, tags(null));
    }

    private ItemStack revealedItem(String tier, boolean glow) {
        ConfigurationSection s = gui.getConfigurationSection("bonus-game.revealed." + tier);
        if (s == null) s = gui.getConfigurationSection("pools." + tier);
        return ItemFactory.glow(plugin.items().build(s, Material.GOLD_INGOT, tags(tier)), glow);
    }

    private void renderInfo() {
        if (infoSlots.length == 0) return;
        ItemStack it = plugin.items().build(gui.getConfigurationSection("bonus-game.info"), Material.PAPER, tags(null));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            String line = str("bonus-game.tier-line", "<tier> <gray>: <yellow><found>/<need>");
            for (Tier t : eligible) lore.add(plugin.msg().item(line, tags(t.id())));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        for (int s : infoSlots) inv.setItem(s, it.clone());
    }

    private void renderAll() {
        if (fillerEnabled) {
            ItemStack f = plugin.items().build(gui.getConfigurationSection("bonus-game.filler"), Material.BLACK_STAINED_GLASS_PANE);
            Set<Integer> used = new HashSet<>();
            for (int s : cardSlots) used.add(s);
            for (int s : infoSlots) used.add(s);
            for (int i = 0; i < inv.getSize(); i++) if (!used.contains(i)) inv.setItem(i, f.clone());
        }
        ItemStack hidden = hiddenItem();
        for (int s : cardSlots) inv.setItem(s, hidden.clone());
        renderInfo();
    }

    // ------------------------------------------------------------------ cycle de vie

    void open() {
        renderAll();
        player.openInventory(inv);
        plugin.effects().named(player, "bonus-open");

        int seconds = plugin.configs().config().getInt("animation.bonus-game.auto-pick-seconds", 20);
        if (seconds > 0) {
            idleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (done || ended || animating) return;
                if (++idleSeconds >= seconds) autoPick();
            }, 20L, 20L);
        }
    }

    private void autoPick() {
        List<Integer> hidden = new ArrayList<>();
        for (int i = 0; i < revealed.length; i++) if (!revealed[i]) hidden.add(i);
        if (!hidden.isEmpty()) reveal(hidden.get(ThreadLocalRandom.current().nextInt(hidden.size())));
    }

    void click(int slot) {
        if (done || ended || animating) return;
        for (int i = 0; i < cardSlots.length; i++) {
            if (cardSlots[i] == slot && !revealed[i]) {
                reveal(i);
                return;
            }
        }
    }

    private void reveal(int idx) {
        FileConfiguration cfg = plugin.configs().config();
        revealed[idx] = true;
        animating = true;
        idleSeconds = 0;

        String tier = picks < sequence.size() ? sequence.get(picks) : winner.id();
        picks++;
        int count = found.merge(tier, 1, Integer::sum);
        cardsOf.computeIfAbsent(tier, k -> new ArrayList<>()).add(idx);
        boolean win = count >= need;
        int slot = cardSlots[idx];

        plugin.effects().named(player, "bonus-flip", 0.9f + 0.05f * picks);
        inv.setItem(slot, flipItem());

        int flipTicks = Math.max(1, cfg.getInt("animation.bonus-game.flip-ticks", 6));
        current = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (done) return;
            inv.setItem(slot, revealedItem(tier, false));
            renderInfo();
            if (win) {
                endGame(tier);
            } else {
                if (count == need - 1) plugin.effects().named(player, "bonus-match");
                animating = false;
            }
        }, flipTicks);
    }

    private void endGame(String tier) {
        FileConfiguration cfg = plugin.configs().config();
        ended = true;
        plugin.effects().named(player, "bonus-win");
        for (int i : cardsOf.getOrDefault(tier, List.of())) inv.setItem(cardSlots[i], revealedItem(tier, true));

        int hold = Math.max(1, cfg.getInt("animation.bonus-game.win-hold-ticks", 25));
        current = Bukkit.getScheduler().runTaskLater(plugin, this::revealRest, hold);
    }

    /** Révèle les cartes restantes une à une, puis retourne à la machine. */
    private void revealRest() {
        if (done) return;
        FileConfiguration cfg = plugin.configs().config();
        List<Integer> hidden = new ArrayList<>();
        for (int i = 0; i < revealed.length; i++) if (!revealed[i]) hidden.add(i);
        int period = Math.max(1, cfg.getInt("animation.bonus-game.rest-reveal-period-ticks", 2));
        int endHold = Math.max(1, cfg.getInt("animation.bonus-game.end-hold-ticks", 50));

        current = new BukkitRunnable() {
            int k = 0;

            @Override
            public void run() {
                if (done) {
                    cancel();
                    return;
                }
                if (k >= hidden.size()) {
                    cancel();
                    current = Bukkit.getScheduler().runTaskLater(plugin, BonusGame.this::complete, endHold);
                    return;
                }
                String t = k < rest.size() ? rest.get(k) : winner.id();
                inv.setItem(cardSlots[hidden.get(k)], revealedItem(t, false));
                k++;
            }
        }.runTaskTimer(plugin, 0L, period);
    }

    /** Fin normale : retour à la machine à sous. */
    private void complete() {
        if (done) return;
        done = true;
        cancelTasks();
        session.bonusFinished();
    }

    /** Le joueur ferme le menu bonus (Échap) ou se déconnecte : le gain est déjà versé, on termine la session. */
    void onClose() {
        if (done) return;
        done = true;
        cancelTasks();
        session.onClose();
    }

    /** Appelé par la session (arrêt du serveur, fermeture...). */
    void cancel() {
        done = true;
        cancelTasks();
    }

    private void cancelTasks() {
        if (current != null) current.cancel();
        if (idleTask != null) idleTask.cancel();
    }
}
