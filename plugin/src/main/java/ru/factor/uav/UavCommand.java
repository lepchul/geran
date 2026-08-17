package ru.factor.uav;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UavCommand implements CommandExecutor, TabCompleter {

    private final UavPlugin plugin;
    private final UavManager manager;

    public UavCommand(UavPlugin plugin, UavManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("\u00A7e/uav give [ник] [кол-во] \u00A77— выдать БПЛА");
            s.sendMessage("\u00A7e/uav part <деталь> [ник] \u00A77— выдать компонент");
            s.sendMessage("\u00A7e/uav boom \u00A77— подорвать свои дроны");
            s.sendMessage("\u00A7e/uav reload \u00A77— перечитать конфиг и рецепты");
            s.sendMessage("\u00A77Детали: " + String.join(", ", UavPlugin.PARTS));
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "give" -> {
                if (!s.hasPermission("uav.admin")) { s.sendMessage("\u00A7cНет прав."); return true; }
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player p ? p : null);
                if (t == null) { s.sendMessage("\u00A7cИгрок не найден."); return true; }
                int n = 1;
                if (a.length > 2) {
                    try { n = Math.max(1, Math.min(64, Integer.parseInt(a[2]))); }
                    catch (NumberFormatException ignored) { }
                }
                t.getInventory().addItem(UavItem.create(plugin, n));
                s.sendMessage("\u00A7aВыдано " + n + " шт.");
            }
            case "part" -> {
                if (!s.hasPermission("uav.admin")) { s.sendMessage("\u00A7cНет прав."); return true; }
                if (a.length < 2) { s.sendMessage("\u00A7cУкажите деталь."); return true; }
                String id = a[1].toUpperCase();
                if (!UavPlugin.PARTS.contains(id)) {
                    s.sendMessage("\u00A7cДетали: " + String.join(", ", UavPlugin.PARTS));
                    return true;
                }
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : (s instanceof Player p ? p : null);
                if (t == null) { s.sendMessage("\u00A7cИгрок не найден."); return true; }
                t.getInventory().addItem(Components.build(plugin, id, 1));
                s.sendMessage("\u00A7aВыдано: " + id);
            }
            case "boom" -> {
                if (!(s instanceof Player p)) { s.sendMessage("\u00A7cТолько в игре."); return true; }
                List<Uav> list = manager.flyingOf(p.getUniqueId());
                if (list.isEmpty()) { p.sendMessage("\u00A77Нет дронов в воздухе."); return true; }
                for (Uav u : list) { manager.remove(u.id); u.detonate(null); }
                p.sendMessage("\u00A7cПодорвано: " + list.size());
            }
            case "reload" -> {
                if (!s.hasPermission("uav.admin")) { s.sendMessage("\u00A7cНет прав."); return true; }
                plugin.reloadConfig();
                plugin.registerRecipes();
                s.sendMessage("\u00A7aКонфиг и рецепты перечитаны.");
            }
            default -> s.sendMessage("\u00A7cНеизвестная команда.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 1) return Arrays.asList("give", "part", "boom", "reload");
        if (a.length == 2 && a[0].equalsIgnoreCase("part")) return UavPlugin.PARTS;
        if (a.length == 2 || a.length == 3) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        return List.of();
    }
}
