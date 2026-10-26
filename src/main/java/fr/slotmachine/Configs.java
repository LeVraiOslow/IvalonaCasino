package fr.slotmachine;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Charge les 4 fichiers de configuration, séparés par rôle. */
public final class Configs {

    private final SlotPlugin plugin;
    private YamlConfiguration config, messages, rewards, gui;

    Configs(SlotPlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        config = read("config.yml");
        messages = read("messages.yml");
        rewards = read("rewards.yml");
        gui = read("gui.yml");
    }

    private YamlConfiguration read(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) plugin.saveResource(name, false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) {
                y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible de lire les valeurs par défaut de " + name);
        }
        return y;
    }

    public YamlConfiguration config() { return config; }
    public YamlConfiguration messages() { return messages; }
    public YamlConfiguration rewards() { return rewards; }
    public YamlConfiguration gui() { return gui; }
}
