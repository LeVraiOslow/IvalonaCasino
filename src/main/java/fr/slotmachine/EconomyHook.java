package fr.slotmachine;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Accès à l'économie via Vault (Essentials et autres s'y enregistrent). */
public final class EconomyHook {

    private final SlotPlugin plugin;
    private Economy eco;

    EconomyHook(SlotPlugin plugin) {
        this.plugin = plugin;
    }

    private Economy get() {
        if (eco == null) {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) eco = rsp.getProvider();
        }
        return eco;
    }

    public boolean ready() {
        return get() != null;
    }

    public double balance(OfflinePlayer p) {
        Economy e = get();
        return e == null ? 0 : e.getBalance(p);
    }

    public boolean has(OfflinePlayer p, double amount) {
        Economy e = get();
        return e != null && e.has(p, amount);
    }

    public boolean withdraw(OfflinePlayer p, double amount) {
        Economy e = get();
        if (e == null) return false;
        EconomyResponse r = e.withdrawPlayer(p, amount);
        return r.transactionSuccess();
    }

    public boolean deposit(OfflinePlayer p, double amount) {
        Economy e = get();
        if (e == null) return false;
        EconomyResponse r = e.depositPlayer(p, amount);
        if (!r.transactionSuccess()) {
            plugin.getLogger().severe("Dépôt échoué pour " + p.getName() + " (" + amount + ") : " + r.errorMessage);
        }
        return r.transactionSuccess();
    }

    public String format(double value) {
        FileConfiguration c = plugin.configs().config();
        Locale loc = Locale.forLanguageTag(c.getString("economy.locale", "en-US"));
        DecimalFormat df = new DecimalFormat(c.getString("economy.format", "#,##0.##"), DecimalFormatSymbols.getInstance(loc));
        return c.getString("economy.display", "<amount> $").replace("<amount>", df.format(value));
    }
}
