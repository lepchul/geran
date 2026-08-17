package ru.factor.uav;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class UavListener implements Listener {

    private final UavPlugin plugin;
    private final UavManager manager;

    public UavListener(UavPlugin plugin, UavManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ─────────────────── проверка: в клетках именно наши компоненты

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.getRecipe() == null) return;
        ItemStack result = e.getInventory().getResult();
        if (result == null) return;

        String section = null;
        if (UavItem.isUav(plugin, result)) {
            section = "drone-recipe";
        } else {
            for (String part : UavPlugin.PARTS) {
                if (Components.is(plugin, result, part)) {
                    section = "components." + part;
                    break;
                }
            }
        }
        if (section == null) return;

        ItemStack[] matrix = e.getInventory().getMatrix();
        for (int slot = 0; slot < 9 && slot < matrix.length; slot++) {
            String need = plugin.requiredPart(section, slot);
            if (need == null) continue;
            if (!Components.is(plugin, matrix[slot], need)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ─────────────────────────────────────────────── установка

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;

        ItemStack item = e.getItem();
        if (!UavItem.isUav(plugin, item)) return;
        e.setCancelled(true);

        Player p = e.getPlayer();
        if (!p.hasPermission("uav.use")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }
        int limit = plugin.getConfig().getInt("drone.max-per-player", 2);
        if (manager.countOf(p.getUniqueId()) >= limit) {
            p.sendMessage(plugin.msg("too-many", "n", String.valueOf(limit)));
            return;
        }

        Block block = e.getClickedBlock();
        if (block == null) return;
        Location at = block.getRelative(e.getBlockFace()).getLocation().add(0.5, 0.1, 0.5);
        if (at.getWorld() == null) return;
        if (plugin.getConfig().getStringList("protection.worlds-blacklist")
                .contains(at.getWorld().getName())) {
            p.sendMessage(plugin.msg("world-blocked"));
            return;
        }

        manager.place(p, at);
        if (p.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
        at.getWorld().playSound(at, Sound.BLOCK_ANVIL_PLACE, 0.8f, 0.7f);
        p.sendMessage(plugin.msg("placed"));
    }

    // ─────────────────────────────────────────────────── меню

    @EventHandler(ignoreCancelled = true)
    public void onClickDrone(PlayerInteractAtEntityEvent e) {
        if (!manager.isDrone(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        Uav u = manager.byEntity(e.getRightClicked());
        if (u == null) {
            manager.cleanupOrphan(e.getRightClicked());
            return;
        }
        if (u.state == Uav.State.FLYING) {
            p.sendMessage(plugin.msg("already-flying"));
            return;
        }
        if (!p.hasPermission("uav.admin") && !u.owner.equals(p.getUniqueId())) {
            p.sendMessage(plugin.msg("not-yours"));
            return;
        }
        new UavMenu(plugin, u).open(p);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof UavMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Uav u = menu.drone();
        if (u == null || !u.alive()) {
            p.closeInventory();
            p.sendMessage(plugin.msg("drone-gone"));
            return;
        }

        switch (e.getRawSlot()) {
            case UavMenu.SLOT_COORDS -> {
                p.closeInventory();
                int cd = manager.cooldownLeft(p.getUniqueId());
                if (cd > 0) {
                    p.sendMessage(plugin.msg("cooldown", "sec", String.valueOf(cd)));
                    return;
                }
                manager.awaitCoords(p, u);
                p.sendMessage(plugin.msg("ask-coords"));
                p.sendMessage(plugin.msg("ask-coords-hint",
                        "x", String.valueOf(p.getLocation().getBlockX()),
                        "y", String.valueOf(p.getLocation().getBlockY()),
                        "z", String.valueOf(p.getLocation().getBlockZ())));
            }
            case UavMenu.SLOT_BOOM -> {
                p.closeInventory();
                manager.remove(u.id);
                u.detonate(null);
            }
            case UavMenu.SLOT_PICKUP -> {
                p.closeInventory();
                u.remove();
                u.state = Uav.State.DEAD;
                manager.remove(u.id);
                p.getInventory().addItem(UavItem.create(plugin, 1));
                p.sendMessage(plugin.msg("picked-up"));
            }
            default -> { }
        }
    }

    // ───────────────────────────────────────────── ввод координат

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!manager.isAwaiting(p)) return;
        e.setCancelled(true);

        String text = e.getMessage().trim();
        if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
            manager.cancelAwait(p);
            p.sendMessage(plugin.msg("cancelled"));
            return;
        }

        String[] parts = text.split("[\\s,;]+");
        if (parts.length < 3) {
            p.sendMessage(plugin.msg("bad-coords"));
            return;
        }
        final double x, y, z;
        try {
            x = Double.parseDouble(parts[0].replace(',', '.'));
            y = Double.parseDouble(parts[1].replace(',', '.'));
            z = Double.parseDouble(parts[2].replace(',', '.'));
        } catch (NumberFormatException ex) {
            p.sendMessage(plugin.msg("bad-coords"));
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Uav u = manager.awaited(p);
            manager.cancelAwait(p);
            if (u == null || !u.alive()) {
                p.sendMessage(plugin.msg("drone-gone"));
                return;
            }

            var world = u.hitbox.getWorld();
            Location target = new Location(world, x + 0.5, y, z + 0.5);
            if (target.getY() < world.getMinHeight() || target.getY() > world.getMaxHeight()) {
                p.sendMessage(plugin.msg("bad-height"));
                return;
            }

            double range = plugin.getConfig().getDouble("flight.max-range", 3000);
            double dist = u.at().distance(target);
            if (dist > range) {
                p.sendMessage(plugin.msg("too-far",
                        "dist", String.valueOf((int) dist), "max", String.valueOf((int) range)));
                return;
            }

            double prot = plugin.getConfig().getDouble("protection.spawn-radius", 0);
            if (prot > 0 && target.distanceSquared(world.getSpawnLocation()) <= prot * prot) {
                p.sendMessage(plugin.msg("spawn-protected"));
                return;
            }

            u.launch(target);
            manager.startCooldown(p.getUniqueId());
            p.sendMessage(plugin.msg("launched",
                    "x", String.valueOf((int) x), "y", String.valueOf((int) y),
                    "z", String.valueOf((int) z), "sec",
                    String.valueOf((int) (dist / plugin.getConfig()
                            .getDouble("flight.speed-blocks-per-second", 30.0)))));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.cancelAwait(e.getPlayer());
    }

    // ───────────────────────────────────────────────── перехват

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!manager.isDrone(e.getEntity())) return;
        Uav u = manager.byEntity(e.getEntity());
        if (u == null) {
            manager.cleanupOrphan(e.getEntity());
            return;
        }

        Entity damager = e.getDamager();
        boolean byShot = damager instanceof AbstractArrow
                || (damager instanceof Projectile pr && pr.getShooter() instanceof Player);

        if (byShot && plugin.getConfig().getBoolean("drone.arrow-instant-kill", true)) {
            e.setCancelled(true);
            manager.remove(u.id);
            if (plugin.getConfig().getBoolean("drone.explode-when-shot", false)) u.detonate(null);
            else u.fizzle();
            if (damager instanceof Projectile pr && pr.getShooter() instanceof Player shooter) {
                shooter.sendMessage(plugin.msg("you-shot-down"));
            }
            return;
        }

        if (u.hitbox.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            manager.remove(u.id);
            u.fizzle();
        }
    }

    /** Огонь и взрывы не должны валить дрон случайно. */
    @EventHandler(ignoreCancelled = true)
    public void onOtherDamage(EntityDamageEvent e) {
        if (!manager.isDrone(e.getEntity())) return;
        switch (e.getCause()) {
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, DROWNING, SUFFOCATION -> e.setCancelled(true);
            default -> { }
        }
    }
}
