package fr.slotmachine;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Construit les icônes à partir de gui.yml.
 * "item" accepte un Material ou "nexo:<id>" (Nexo est appelé par réflexion : aucune dépendance de compilation).
 */
public final class ItemFactory {

    private final SlotPlugin plugin;
    private final Map<String, ItemStack> cache = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    ItemFactory(SlotPlugin plugin) {
        this.plugin = plugin;
    }

    void clearCache() {
        cache.clear();
        warned.clear();
    }

    /** Item de base (sans nom/lore) pour une spec Material ou nexo:id. */
    public ItemStack base(String spec, Material fallback) {
        ItemStack cached = cache.get(spec);
        if (cached != null) return cached.clone();

        ItemStack built = null;
        if (spec.regionMatches(true, 0, "nexo:", 0, 5)) {
            built = nexo(spec.substring(5).trim());
            if (built == null) {
                warnOnce(spec, "Item Nexo introuvable (ou Nexo pas encore chargé) : " + spec + " -> remplacé par " + fallback);
                return new ItemStack(fallback);
            }
        } else {
            Material m = Material.matchMaterial(spec);
            if (m == null || !m.isItem() || m.isAir()) {
                warnOnce(spec, "Material invalide : " + spec + " -> remplacé par " + fallback);
                return new ItemStack(fallback);
            }
            built = new ItemStack(m);
        }
        cache.put(spec, built);
        return built.clone();
    }

    private ItemStack nexo(String id) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            warnOnce("nonexo", "Un item 'nexo:...' est configuré mais le plugin Nexo n'est pas actif.");
            return null;
        }
        try {
            Class<?> api = Class.forName("com.nexomc.nexo.api.NexoItems");
            Method m = api.getMethod("itemFromId", String.class);
            Object target = Modifier.isStatic(m.getModifiers()) ? null : api.getField("INSTANCE").get(null);
            Object builder = m.invoke(target, id);
            if (builder == null) return null;
            for (String name : new String[]{"build", "getFinalItemStack", "getReferenceStack"}) {
                try {
                    Object o = builder.getClass().getMethod(name).invoke(builder);
                    if (o instanceof ItemStack is) return is;
                } catch (NoSuchMethodException ignored) {
                }
            }
            warnOnce("nobuild", "API Nexo : impossible de construire l'item '" + id + "' (méthode build introuvable).");
            return null;
        } catch (Throwable t) {
            warnOnce("err:" + id, "Erreur d'appel à l'API Nexo pour '" + id + "' : " + t);
            return null;
        }
    }

    /** Vrai si la spec (Material ou nexo:id) est utilisable. Sert à /roulette checkitems. */
    public boolean exists(String spec) {
        if (spec.regionMatches(true, 0, "nexo:", 0, 5)) return nexo(spec.substring(5).trim()) != null;
        Material m = Material.matchMaterial(spec);
        return m != null && m.isItem() && !m.isAir();
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) plugin.getLogger().warning(message);
    }

    public ItemStack build(ConfigurationSection s, Material fallback, TagResolver... r) {
        return build(s, null, fallback, r);
    }

    /** specOverride (optionnel) remplace la clé "item" de la section. */
    public ItemStack build(ConfigurationSection s, String specOverride, Material fallback, TagResolver... r) {
        if (s == null) return new ItemStack(fallback);
        String spec = (specOverride != null && !specOverride.isBlank()) ? specOverride : s.getString("item", fallback.name());
        ItemStack it = base(spec, fallback);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;

        Msg msg = plugin.msg();
        if (s.contains("name")) {
            String name = s.getString("name", "");
            meta.displayName(msg.item(name, r));
        }
        List<String> lore = s.getStringList("lore");
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(l -> msg.item(l, r)).toList());
        }
        String model = s.getString("item-model", "");
        if (!model.isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(model.toLowerCase());
            if (key != null) meta.setItemModel(key);
        }
        if (s.getBoolean("hide-tooltip", false)) meta.setHideTooltip(true);
        if (s.getBoolean("glow", false)) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    /** Simple item sans texte (cadre animé). */
    public ItemStack simple(String spec) {
        ItemStack it = base(spec, Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.space());
            meta.setHideTooltip(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    public static ItemStack glow(ItemStack item, boolean on) {
        ItemStack c = item.clone();
        if (!on) return c;
        ItemMeta m = c.getItemMeta();
        if (m != null) {
            m.setEnchantmentGlintOverride(true);
            c.setItemMeta(m);
        }
        return c;
    }
}
