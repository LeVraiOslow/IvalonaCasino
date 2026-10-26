package fr.slotmachine;

import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

public final class SlotListener implements Listener {

    private final SlotPlugin plugin;

    SlotListener(SlotPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof SlotSession session)) return;
        // Tout est bloqué (y compris shift-clic depuis l'inventaire du joueur)
        e.setCancelled(true);
        if (e.getClickedInventory() == e.getView().getTopInventory() && e.getWhoClicked() instanceof Player) {
            session.click(e.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof SlotSession) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof SlotSession session) session.onClose();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        SlotSession session = plugin.sessions().get(e.getPlayer().getUniqueId());
        if (session != null) session.onClose();
        plugin.betMemory().remove(e.getPlayer().getUniqueId());
    }

    /** Les feux d'artifice de victoire ne blessent personne. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework fw
                && fw.getPersistentDataContainer().has(plugin.fireworkKey(), PersistentDataType.BYTE)) {
            e.setCancelled(true);
        }
    }
}
