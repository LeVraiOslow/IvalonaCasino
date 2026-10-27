package fr.slotmachine;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tous les textes viennent des fichiers de config. Formats acceptés, mélangeables :
 *  - MiniMessage : <red>, <#ff8800>, <gradient:red:blue>, <bold>...
 *  - Codes Minecraft : &c, &l, &r ... (et § ), "&&" pour écrire un "&"
 *  - Hexadécimal : &#ff8800, &x&f&f&8&8&0&0, ou simplement #ff8800
 *  - Couleurs nommées personnalisées : section "colors" de config.yml (ex: <orange>)
 * Les balises inconnues (ex: <glyph:xxx>, <shift:-52> de Nexo) sont laissées telles quelles.
 */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern LEGACY = Pattern.compile(
            "[&\u00a7](?:#([0-9a-fA-F]{6})|x((?:[&\u00a7][0-9a-fA-F]){6})|([0-9a-fk-orA-FK-OR]))");
    private static final Pattern BARE_HEX = Pattern.compile("(?<![<:&\u00a7#\\w])#([0-9a-fA-F]{6})(?![0-9a-fA-F\\w])");

    private final SlotPlugin plugin;
    private volatile TagResolver customColors = TagResolver.empty();
    private volatile boolean legacy = true, bareHex = true;

    Msg(SlotPlugin plugin) {
        this.plugin = plugin;
    }

    /** À appeler après chaque (re)chargement de config.yml. */
    void reload() {
        FileConfiguration c = plugin.configs().config();
        legacy = c.getBoolean("text.legacy-codes", true);
        bareHex = c.getBoolean("text.bare-hex", true);

        TagResolver.Builder b = TagResolver.builder();
        ConfigurationSection sec = c.getConfigurationSection("colors");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                String hex = sec.getString(name, "");
                String tag = name.toLowerCase(Locale.ROOT);
                TextColor col = TextColor.fromHexString(hex.startsWith("#") ? hex : "#" + hex);
                if (col == null || !tag.matches("[a-z0-9_-]+")) {
                    plugin.getLogger().warning("config.yml : couleur personnalisée invalide '" + name + "' (" + hex + ")");
                    continue;
                }
                b.resolver(TagResolver.resolver(tag, Tag.styling(col)));
            }
        }
        customColors = b.build();
    }

    /** Convertit les codes &c / &#rrggbb / #rrggbb en balises MiniMessage. */
    public String color(String text) {
        if (text == null || text.isEmpty()) return text;
        if (text.indexOf('&') < 0 && text.indexOf('\u00a7') < 0 && text.indexOf('#') < 0) return text;
        String t = text.replace("&&", "\u0000");

        if (legacy) {
            Matcher m = LEGACY.matcher(t);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String rep;
                if (m.group(1) != null) {
                    rep = "<reset><#" + m.group(1) + ">";
                } else if (m.group(2) != null) {
                    rep = "<reset><#" + m.group(2).replaceAll("[^0-9a-fA-F]", "") + ">";
                } else {
                    rep = legacyTag(m.group(3).charAt(0));
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            t = sb.toString();
        }
        if (bareHex) t = BARE_HEX.matcher(t).replaceAll("<reset><#$1>");
        return t.replace("\u0000", "&");
    }

    // Une couleur remet le style à zéro, comme dans Minecraft ; &l, &o... s'ajoutent.
    private static String legacyTag(char c) {
        return switch (Character.toLowerCase(c)) {
            case '0' -> "<reset><black>";
            case '1' -> "<reset><dark_blue>";
            case '2' -> "<reset><dark_green>";
            case '3' -> "<reset><dark_aqua>";
            case '4' -> "<reset><dark_red>";
            case '5' -> "<reset><dark_purple>";
            case '6' -> "<reset><gold>";
            case '7' -> "<reset><gray>";
            case '8' -> "<reset><dark_gray>";
            case '9' -> "<reset><blue>";
            case 'a' -> "<reset><green>";
            case 'b' -> "<reset><aqua>";
            case 'c' -> "<reset><red>";
            case 'd' -> "<reset><light_purple>";
            case 'e' -> "<reset><yellow>";
            case 'f' -> "<reset><white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            default -> "<reset>"; // 'r'
        };
    }

    public String raw(String key) {
        String v = plugin.configs().messages().getString(key); // single-arg : utilise les défauts du jar
        return v != null ? v : "<red>[message manquant : " + key + "]";
    }

    public List<String> list(String key) {
        return plugin.configs().messages().getStringList(key);
    }

    public Component parse(String text, TagResolver... extra) {
        return MM.deserialize(color(text), TagResolver.builder()
                .resolver(Placeholder.parsed("prefix", color(plugin.configs().messages().getString("prefix", ""))))
                .resolver(customColors)
                .resolvers(extra)
                .build());
    }

    /** Pour les noms / lores d'items : pas d'italique par défaut. */
    public Component item(String text, TagResolver... extra) {
        return parse(text, extra).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public void send(CommandSender to, String key, TagResolver... extra) {
        String raw = raw(key);
        if (raw.isBlank()) return;
        to.sendMessage(parse(raw, extra));
    }

    public void sendRaw(CommandSender to, String text, TagResolver... extra) {
        if (text == null || text.isBlank()) return;
        to.sendMessage(parse(text, extra));
    }
}
