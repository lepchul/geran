package ru.factor.uav;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UavPlugin extends JavaPlugin {

    /** Идентификаторы компонентов, в порядке сборки. */
    public static final List<String> PARTS = Arrays.asList("WARHEAD", "FAN", "HULL", "JAMMER");
    public static final String DRONE = "DRONE";

    public static NamespacedKey KEY_ENTITY;
    public static NamespacedKey KEY_OWNER;

    private UavManager manager;
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        KEY_ENTITY = new NamespacedKey(this, "uav_entity");
        KEY_OWNER  = new NamespacedKey(this, "uav_owner");

        manager = new UavManager(this);
        getServer().getPluginManager().registerEvents(new UavListener(this, manager), this);

        UavCommand cmd = new UavCommand(this, manager);
        if (getCommand("uav") != null) {
            getCommand("uav").setExecutor(cmd);
            getCommand("uav").setTabCompleter(cmd);
        }

        registerRecipes();
        manager.start();

        getLogger().info("БПЛА запущен. Скорость: "
                + getConfig().getDouble("flight.speed-blocks-per-second", 30.0)
                + " бл/с, мощность: " + getConfig().getDouble("explosion.power", 6.0));
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
        for (NamespacedKey k : recipeKeys) Bukkit.removeRecipe(k);
    }

    public UavManager manager() { return manager; }

    // ────────────────────────────────────────────────────── рецепты

    public void registerRecipes() {
        for (NamespacedKey k : recipeKeys) Bukkit.removeRecipe(k);
        recipeKeys.clear();

        for (String part : PARTS) {
            add(part, "components." + part, Components.build(this, part,
                    getConfig().getInt("components." + part + ".amount", 1)));
        }
        add(DRONE, "drone-recipe", UavItem.create(this, 1));
    }

    private void add(String id, String section, ItemStack result) {
        if (!getConfig().getBoolean(section + ".craftable", true)) return;

        String[] rows = {
                getConfig().getString(section + ".row1", ""),
                getConfig().getString(section + ".row2", ""),
                getConfig().getString(section + ".row3", "")
        };

        Map<String, Character> letters = new HashMap<>();
        Map<Character, Material> mats = new HashMap<>();
        char next = 'a';
        String[] shape = new String[3];

        for (int r = 0; r < 3; r++) {
            String[] cells = rows[r].split(",");
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < 3; c++) {
                String raw = c < cells.length ? cells[c].trim().toUpperCase() : "AIR";
                if (raw.isEmpty()) raw = "AIR";

                Material m;
                if (raw.startsWith("@")) {
                    // ссылка на компонент: форма по материалу, а сходство проверим отдельно
                    m = Components.material(this, raw.substring(1));
                } else {
                    m = Material.matchMaterial(raw);
                    if (m == null) {
                        getLogger().warning("Неизвестный предмет в " + section + ": " + raw);
                        m = Material.AIR;
                    }
                }

                if (m == Material.AIR) {
                    line.append(' ');
                } else {
                    Character ch = letters.get(raw);
                    if (ch == null) {
                        ch = next++;
                        letters.put(raw, ch);
                        mats.put(ch, m);
                    }
                    line.append(ch);
                }
            }
            shape[r] = line.toString();
        }

        if (mats.isEmpty()) {
            getLogger().warning("Пустой рецепт в " + section);
            return;
        }

        NamespacedKey key = new NamespacedKey(this, "craft_" + id.toLowerCase());
        Bukkit.removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(shape);
        mats.forEach(recipe::setIngredient);
        Bukkit.addRecipe(recipe);
        recipeKeys.add(key);
    }

    /** Какой компонент обязан лежать в клетке рецепта, или null. */
    public String requiredPart(String section, int slot) {
        String row = getConfig().getString(section + ".row" + (slot / 3 + 1), "");
        String[] cells = row.split(",");
        int c = slot % 3;
        if (c >= cells.length) return null;
        String raw = cells[c].trim().toUpperCase();
        return raw.startsWith("@") ? raw.substring(1) : null;
    }

    public String msg(String path, String... kv) {
        String s = getConfig().getString("messages." + path, path);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s.replace('&', '\u00A7');
    }
}
