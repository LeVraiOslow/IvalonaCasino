package fr.slotmachine;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.List;

/** Tous les textes viennent de messages.yml (MiniMessage). */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final SlotPlugin plugin;

    Msg(SlotPlugin plugin) {
        this.plugin = plugin;
    }

    public String raw(String key) {
        return plugin.configs().messages().getString(key, "<red>[message manquant : " + key + "]");
    }

    public List<String> list(String key) {
        return plugin.configs().messages().getStringList(key);
    }

    public Component parse(String text, TagResolver... extra) {
        return MM.deserialize(text, TagResolver.builder()
                .resolver(Placeholder.parsed("prefix", plugin.configs().messages().getString("prefix", "")))
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
