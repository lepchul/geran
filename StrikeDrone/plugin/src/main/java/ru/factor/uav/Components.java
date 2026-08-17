package ru.factor.uav;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Компоненты сборки. Каждый помечен в PersistentDataContainer,
 * поэтому подделать его наковальней или подсунуть обычный предмет нельзя.
 */
public final class Components {

    private Components() {}

    /** Ключ метки для компонента с указанным идентификатором. */
    public static NamespacedKey key(UavPlugin plugin, String id) {
        return new NamespacedKey(plugin, "comp_" + id.toLowerCase());
    }

    /** Материал, на котором «висит» компонент. */
    public static Material material(UavPlugin plugin, String id) {
        String raw = plugin.getConfig().getString("components." + id + ".material", "STONE");
        Material m = Material.matchMaterial(raw.toUpperCase());
        return (m == null || m == Material.AIR) ? Material.STONE : m;
    }

    public static ItemStack build(UavPlugin plugin, String id, int amount) {
        ItemStack item = new ItemStack(material(plugin, id), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getConfig()
                .getString("components." + id + ".name", id).replace('&', '\u00A7'));

        List<String> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("components." + id + ".lore")) {
            lore.add(line.replace('&', '\u00A7'));
        }
        if (!lore.isEmpty()) meta.setLore(lore);

        if (plugin.getConfig().getBoolean("components." + id + ".glow", false)) {
            Cfg.glow(meta);
        }

        meta.getPersistentDataContainer().set(key(plugin, id), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean is(UavPlugin plugin, ItemStack item, String id) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(key(plugin, id), PersistentDataType.BYTE);
    }
}
