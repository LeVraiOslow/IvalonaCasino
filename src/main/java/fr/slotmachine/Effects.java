package fr.slotmachine;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Sons, particules et feux d'artifice, entièrement pilotés par config.yml. */
public final class Effects {

    private final SlotPlugin plugin;

    Effects(SlotPlugin plugin) {
        this.plugin = plugin;
    }

    /** Joue un son nommé de la section "sounds" de config.yml. */
    public void named(Player p, String name) {
        named(p, name, 1f);
    }

    public void named(Player p, String name, float pitchMul) {
        sound(p, plugin.configs().config().getString("sounds." + name), pitchMul);
    }

    /** Format : "clé;volume;pitch;délai". */
    public void sound(Player p, String spec, float pitchMul) {
        if (spec == null || spec.isBlank()) return;
        String[] a = spec.split(";");
        String key = a[0].trim();
        float vol = a.length > 1 ? parseFloat(a[1], 1f) : 1f;
        float pitch = (a.length > 2 ? parseFloat(a[2], 1f) : 1f) * pitchMul;
        long delay = a.length > 3 ? (long) parseFloat(a[3], 0f) : 0L;
        Runnable r = () -> {
            if (p.isOnline()) p.playSound(p.getLocation(), key, SoundCategory.MASTER, vol, pitch);
        };
        if (delay <= 0 || !plugin.isEnabled()) r.run();
        else Bukkit.getScheduler().runTaskLater(plugin, r, delay);
    }

    /** Joue les effets d'une catégorie (lose, pair, triple, big, free-spins, mini, major, giant, jackpot). */
    public void play(Player p, String category) {
        ConfigurationSection s = plugin.configs().config().getConfigurationSection("effects." + category);
        if (s == null) return;

        for (String line : s.getStringList("sounds")) sound(p, line, 1f);

        ConfigurationSection ps = s.getConfigurationSection("particles");
        if (ps != null) {
            try {
                NamespacedKey key = NamespacedKey.fromString(ps.getString("type", "happy_villager").toLowerCase(Locale.ROOT));
                Particle particle = key == null ? null : Registry.PARTICLE_TYPE.get(key);
                if (particle != null) {
                    p.getWorld().spawnParticle(particle, p.getLocation().add(0, 1.2, 0), ps.getInt("count", 10),
                            0.6, 0.6, 0.6, ps.getDouble("speed", 0.05));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Particule invalide pour effects." + category + " : " + e.getMessage());
            }
        }

        int fireworks = s.getInt("fireworks", 0);
        for (int i = 0; i < fireworks; i++) {
            long delay = i * 8L;
            if (delay == 0 || !plugin.isEnabled()) launch(p);
            else Bukkit.getScheduler().runTaskLater(plugin, () -> launch(p), delay);
        }
    }

    private void launch(Player p) {
        if (!p.isOnline()) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location loc = p.getLocation().add(rnd.nextDouble(-2, 2), 2.5, rnd.nextDouble(-2, 2));
        List<Color> colors = colors();
        Color c1 = colors.get(rnd.nextInt(colors.size()));
        Color c2 = colors.get(rnd.nextInt(colors.size()));
        Firework fw = p.getWorld().spawn(loc, Firework.class, f -> {
            FireworkMeta m = f.getFireworkMeta();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(c1).withFade(c2).flicker(true).trail(true).build());
            f.setFireworkMeta(m);
            // Marqué pour annuler les dégâts d'explosion (voir SlotListener)
            f.getPersistentDataContainer().set(plugin.fireworkKey(), PersistentDataType.BYTE, (byte) 1);
        });
        fw.detonate();
    }

    private List<Color> colors() {
        List<Color> out = new ArrayList<>();
        for (String hex : plugin.configs().config().getStringList("effects.firework-colors")) {
            try {
                out.add(Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16)));
            } catch (NumberFormatException ignored) {
            }
        }
        if (out.isEmpty()) out.add(Color.YELLOW);
        return out;
    }

    private static float parseFloat(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
