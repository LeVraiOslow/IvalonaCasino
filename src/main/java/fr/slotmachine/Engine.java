package fr.slotmachine;

import fr.slotmachine.Rewards.Symbol;
import fr.slotmachine.Rewards.Type;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/** Moteur de jeu : tirage de la grille 3x3, évaluation des lignes, paires, triples, scatters et bonus. */
public final class Engine {

    public record Line(String id, int[] rows) {}

    public record LineWin(Line line, Symbol symbol, int matches, double mult) {}

    /**
     * @param cells indices (rangée*3 + colonne) des cases à faire clignoter
     */
    public record Result(Symbol[][] grid, List<LineWin> wins, double lineMult, int scatters,
                         int freeSpins, int bonusCount, boolean bonus, Set<Integer> cells, boolean anyTriple) {}

    private final SlotPlugin plugin;
    private volatile List<Line> lines = List.of(new Line("middle", new int[]{1, 1, 1}));

    Engine(SlotPlugin plugin) {
        this.plugin = plugin;
    }

    void reload() {
        FileConfiguration c = plugin.configs().config();
        ConfigurationSection defs = c.getConfigurationSection("paylines.lines");
        List<Line> list = new ArrayList<>();
        for (String id : c.getStringList("paylines.active")) {
            List<Integer> r = defs == null ? List.of() : defs.getIntegerList(id);
            if (r.size() != 3 || r.stream().anyMatch(v -> v < 0 || v > 2)) {
                plugin.getLogger().warning("paylines : ligne '" + id + "' invalide (3 valeurs entre 0 et 2 attendues).");
                continue;
            }
            list.add(new Line(id, new int[]{r.get(0), r.get(1), r.get(2)}));
        }
        if (list.isEmpty()) list.add(new Line("middle", new int[]{1, 1, 1}));
        lines = List.copyOf(list);
    }

    public List<Line> lines() {
        return lines;
    }

    /** Tire une grille [rangée][colonne]. */
    public Symbol[][] roll(Rewards rw) {
        RandomGenerator rnd = ThreadLocalRandom.current();
        Symbol[][] g = new Symbol[3][3];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) g[r][c] = rw.random(rnd);
        return g;
    }

    public Result evaluate(Symbol[][] g, List<Line> lines, Rewards rw) {
        List<LineWin> wins = new ArrayList<>();
        Set<Integer> cells = new HashSet<>();
        double sum = 0;
        boolean triple = false;

        for (Line l : lines) {
            Symbol base = null;
            boolean blocked = false;
            for (int c = 0; c < 3; c++) {
                Symbol s = g[l.rows()[c]][c];
                if (s.type() == Type.WILD) continue;
                if (s.type() == Type.SCATTER || s.type() == Type.BONUS) blocked = true;
                else base = s;
                break;
            }
            if (blocked) continue;
            if (base == null) base = g[l.rows()[0]][0]; // ligne 100 % wild

            int matches = 0;
            for (int c = 0; c < 3; c++) {
                Symbol s = g[l.rows()[c]][c];
                if (s == base || s.type() == Type.WILD) matches++;
                else break;
            }
            if (matches < 2) continue;
            double mult = matches == 3 ? base.triple() : base.pair();
            if (mult <= 0) continue;

            wins.add(new LineWin(l, base, matches, mult));
            sum += mult;
            if (matches == 3) triple = true;
            for (int c = 0; c < matches; c++) cells.add(l.rows()[c] * 3 + c);
        }

        int scatters = 0, bonusCount = 0;
        Set<Integer> scatterCells = new HashSet<>(), bonusCells = new HashSet<>();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                Type t = g[r][c].type();
                if (t == Type.SCATTER) { scatters++; scatterCells.add(r * 3 + c); }
                else if (t == Type.BONUS) { bonusCount++; bonusCells.add(r * 3 + c); }
            }
        }
        int freeSpins = rw.freeSpinsFor(scatters);
        if (freeSpins > 0) cells.addAll(scatterCells);
        boolean bonus = bonusCount >= rw.bonusRequired();
        if (bonus) cells.addAll(bonusCells);

        return new Result(g, wins, sum, scatters, freeSpins, bonusCount, bonus, cells, triple);
    }

    /**
     * Simulation (mise de 1 par ligne). Les cagnottes ne sont pas simulées :
     * leur RTP théorique est la somme des contributions.
     */
    public Map<String, Double> simulate(long n) {
        Rewards rw = plugin.rewards();
        List<Line> ls = lines;
        double lineWon = 0, freeWon = 0;
        long hits = 0, bonusHits = 0, fsTriggers = 0;

        for (long i = 0; i < n; i++) {
            Result res = evaluate(roll(rw), ls, rw);
            lineWon += res.lineMult();
            if (res.lineMult() > 0) hits++;
            if (res.bonus()) bonusHits++;
            int fs = res.freeSpins();
            if (fs > 0) fsTriggers++;
            int guard = 0;
            while (fs > 0 && guard++ < 10_000) {
                fs--;
                Result f = evaluate(roll(rw), ls, rw);
                freeWon += f.lineMult() * rw.freeMultiplier();
                if (f.bonus()) bonusHits++;
                if (f.freeSpins() > 0 && rw.retrigger()) fs += f.freeSpins();
            }
        }
        double cost = (double) n * ls.size();
        double pool = rw.tiers().values().stream().mapToDouble(Rewards.Tier::contribution).sum() / 100.0;

        Map<String, Double> out = new LinkedHashMap<>();
        out.put("line-rtp", lineWon / cost);
        out.put("free-rtp", freeWon / cost);
        out.put("pool-rtp", pool);
        out.put("total-rtp", lineWon / cost + freeWon / cost + pool);
        out.put("hit", (double) hits / n);
        out.put("bonus", (double) bonusHits / n);
        out.put("free-trigger", (double) fsTriggers / n);
        return out;
    }
}
