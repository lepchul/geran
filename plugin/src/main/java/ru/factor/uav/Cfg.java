package ru.factor.uav;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Разбор значений из конфига и совместимость между версиями.
 * В 1.20.5 часть имён переименовали, поэтому ищем по нескольким вариантам.
 */
public final class Cfg {

    private Cfg() {}

    /** Первая частица из списка, которая существует в этой версии. */
    public static Particle anyParticle(String... names) {
        for (String n : names) {
            try {
                return Particle.valueOf(n);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Particle.CLOUD;   // есть во всех версиях
    }

    public static Sound anySound(String... names) {
        for (String n : names) {
            try {
                return Sound.valueOf(n);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Sound.BLOCK_STONE_BREAK;
    }

    public static Particle particle(UavPlugin plugin, String path, Particle fallback) {
        String raw = plugin.getConfig().getString(path, "");
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Particle.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // возможно, имя из другой версии — пробуем известные пары
            Particle alt = alias(raw.trim().toUpperCase());
            if (alt != null) return alt;
            plugin.getLogger().warning("Неизвестная частица в " + path + ": " + raw
                    + " — использую " + fallback);
            return fallback;
        }
    }

    /** Старые и новые имена одних и тех же частиц. */
    private static Particle alias(String name) {
        return switch (name) {
            case "SMOKE_NORMAL", "SMOKE"       -> anyParticle("SMOKE", "SMOKE_NORMAL");
            case "SMOKE_LARGE", "LARGE_SMOKE"  -> anyParticle("LARGE_SMOKE", "SMOKE_LARGE");
            case "REDSTONE", "DUST"            -> anyParticle("DUST", "REDSTONE");
            case "BLOCK_CRACK", "BLOCK"        -> anyParticle("BLOCK", "BLOCK_CRACK");
            case "VILLAGER_HAPPY", "HAPPY_VILLAGER" -> anyParticle("HAPPY_VILLAGER", "VILLAGER_HAPPY");
            default -> null;
        };
    }

    public static Sound sound(UavPlugin plugin, String path, Sound fallback) {
        String raw = plugin.getConfig().getString(path, "");
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Sound.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Неизвестный звук в " + path + ": " + raw
                    + " — использую " + fallback);
            return fallback;
        }
    }

    /**
     * Блеск на предмете.
     * В новых версиях есть отдельный флаг, в старых приходится вешать зачарование.
     */
    public static void glow(ItemMeta meta) {
        try {
            meta.setEnchantmentGlintOverride(true);
            return;
        } catch (Throwable ignored) {
            // сервер старее 1.20.5 — идём обходным путём
        }
        Enchantment ench = null;
        for (String key : new String[]{"unbreaking", "durability"}) {
            try {
                ench = Enchantment.getByKey(NamespacedKey.minecraft(key));
            } catch (Throwable ignored) {
            }
            if (ench != null) break;
        }
        if (ench != null) {
            meta.addEnchant(ench, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
    }
}
