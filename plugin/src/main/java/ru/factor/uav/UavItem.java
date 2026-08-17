package ru.factor.uav;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public final class UavItem {

    private UavItem() {}

    public static NamespacedKey key(UavPlugin plugin) {
        return new NamespacedKey(plugin, "uav_item");
    }

    public static ItemStack create(UavPlugin plugin, int amount) {
        Material mat = Material.matchMaterial(
                plugin.getConfig().getString("item.material", "COAL_BLOCK").toUpperCase());
        if (mat == null || mat == Material.AIR) mat = Material.COAL_BLOCK;

        ItemStack item = new ItemStack(mat, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getConfig()
                .getString("item.name", "&8&lБПЛА").replace('&', '\u00A7'));

        List<String> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("item.lore")) {
            lore.add(line.replace('&', '\u00A7'));
        }
        if (!lore.isEmpty()) meta.setLore(lore);

        if (plugin.getConfig().getBoolean("item.glow", true)) {
            Cfg.glow(meta);
        }

        meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isUav(UavPlugin plugin, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(key(plugin), PersistentDataType.BYTE);
    }
}
