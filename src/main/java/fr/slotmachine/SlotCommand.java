package fr.slotmachine;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /roulette : ouvre la machine. Sous-commandes : reload, pools, setpool, simulate. */
public final class SlotCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "slotmachine.admin";
    private static final String USE = "slotmachine.use";
    private final SlotPlugin plugin;

    SlotCommand(SlotPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        Msg msg = plugin.msg();
        if (args.length == 0) {
            open(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return true; }
                try {
                    plugin.reload();
                    msg.send(sender, "reloaded");
                } catch (Exception e) {
                    msg.send(sender, "reload-error", Placeholder.unparsed("error", String.valueOf(e.getMessage())));
                    plugin.getLogger().severe("Erreur de rechargement : " + e);
                }
            }
            case "pools" -> {
                if (!sender.hasPermission(USE)) { msg.send(sender, "no-permission"); return true; }
                msg.send(sender, "pools-header");
                for (Rewards.Tier t : plugin.rewards().tiers().values()) {
                    msg.send(sender, "pools-line", tierTag(t.id()),
                            Placeholder.unparsed("amount", plugin.economy().format(plugin.pools().get(t.id()))));
                }
            }
            case "resetpools" -> {
                if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return true; }
                for (Rewards.Tier t : plugin.rewards().tiers().values()) plugin.pools().reset(t.id(), t.seed());
                plugin.refreshAllPools();
                msg.send(sender, "resetpools-ok");
            }
            case "checkitems" -> {
                if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return true; }
                checkItems(sender);
            }
            case "setpool" -> {
                if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return true; }
                if (args.length < 3) { msg.send(sender, "setpool-usage"); return true; }
                String id = args[1].toLowerCase(Locale.ROOT);
                if (!plugin.rewards().tiers().containsKey(id)) {
                    msg.send(sender, "setpool-unknown", Placeholder.unparsed("tier", id));
                    return true;
                }
                double amount;
                try {
                    amount = Double.parseDouble(args[2]);
                } catch (NumberFormatException e) {
                    msg.send(sender, "setpool-usage");
                    return true;
                }
                plugin.pools().set(id, amount);
                msg.send(sender, "setpool-ok", tierTag(id),
                        Placeholder.unparsed("amount", plugin.economy().format(Math.max(0, amount))));
            }
            case "simulate" -> {
                if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return true; }
                long n;
                try {
                    n = Long.parseLong(args.length > 1 ? args[1] : "");
                } catch (NumberFormatException e) {
                    msg.send(sender, "simulate-usage");
                    return true;
                }
                n = Math.max(1000, Math.min(5_000_000, n));
                final long spins = n;
                msg.send(sender, "simulate-start", Placeholder.unparsed("spins", String.valueOf(spins)));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    Map<String, Double> r = plugin.engine().simulate(spins);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        TagResolver res = TagResolver.resolver(
                                Placeholder.unparsed("spins", String.valueOf(spins)),
                                Placeholder.unparsed("line-rtp", pct(r.get("line-rtp"))),
                                Placeholder.unparsed("free-rtp", pct(r.get("free-rtp"))),
                                Placeholder.unparsed("pool-rtp", pct(r.get("pool-rtp"))),
                                Placeholder.unparsed("total-rtp", pct(r.get("total-rtp"))),
                                Placeholder.unparsed("hit", pct(r.get("hit"))),
                                Placeholder.unparsed("bonus", pct(r.get("bonus"))),
                                Placeholder.unparsed("free-trigger", pct(r.get("free-trigger"))));
                        for (String line : msg.list("simulate-result")) msg.sendRaw(sender, line, res);
                    });
                });
            }
            default -> msg.send(sender, "unknown-subcommand");
        }
        return true;
    }

    /** Vérifie chaque "item:" de gui.yml (Material valide ou item Nexo existant). */
    private void checkItems(CommandSender sender) {
        Msg msg = plugin.msg();
        org.bukkit.configuration.file.FileConfiguration gui = plugin.configs().gui();
        Map<String, String> specs = new java.util.LinkedHashMap<>();
        for (String k : gui.getKeys(true)) {
            if (k.endsWith(".item") && gui.isString(k)) specs.put(k, gui.getString(k));
            else if (k.startsWith("buttons.bet-icons.") && gui.isString(k)) specs.put(k, gui.getString(k));
        }
        int i = 0;
        for (String sp : gui.getStringList("win-frame.materials")) specs.put("win-frame.materials[" + (i++) + "]", sp);
        int ok = 0;
        for (Map.Entry<String, String> e : specs.entrySet()) {
            if (e.getValue() != null && plugin.items().exists(e.getValue())) ok++;
            else msg.send(sender, "checkitems-missing", Placeholder.unparsed("path", e.getKey()),
                    Placeholder.unparsed("spec", String.valueOf(e.getValue())));
        }
        msg.send(sender, "checkitems-result", Placeholder.unparsed("ok", String.valueOf(ok)),
                Placeholder.unparsed("total", String.valueOf(specs.size())));
    }

    private void open(CommandSender sender) {
        Msg msg = plugin.msg();
        if (!(sender instanceof Player p)) {
            msg.send(sender, "players-only");
            return;
        }
        if (!p.hasPermission(USE)) {
            msg.send(p, "no-permission");
            return;
        }
        if (plugin.configs().config().getStringList("general.disabled-worlds").contains(p.getWorld().getName())) {
            msg.send(p, "world-disabled");
            return;
        }
        if (!plugin.economy().ready()) {
            msg.send(p, "economy-missing");
            return;
        }
        new SlotSession(plugin, p).open();
    }

    private TagResolver tierTag(String id) {
        String name = plugin.configs().messages().getString("tiers." + id, id.toUpperCase(Locale.ROOT));
        return Placeholder.parsed("tier", name);
    }

    private static String pct(Double v) {
        return String.format(Locale.ROOT, "%.2f%%", (v == null ? 0 : v) * 100);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("pools");
            if (sender.hasPermission(ADMIN)) out.addAll(List.of("reload", "setpool", "resetpools", "checkitems", "simulate"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("setpool") && sender.hasPermission(ADMIN)) {
            out.addAll(plugin.rewards().tiers().keySet());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("simulate") && sender.hasPermission(ADMIN)) {
            out.addAll(List.of("100000", "1000000"));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
