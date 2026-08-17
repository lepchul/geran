package ru.factor.uav;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class UavManager {

    private final UavPlugin plugin;
    private final Map<UUID, Uav> drones = new HashMap<>();
    private final Map<UUID, UUID> awaitingCoords = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private BukkitTask task;

    public UavManager(UavPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (Uav u : new ArrayList<>(drones.values())) {
            if (u.state == Uav.State.FLYING) u.remove();
        }
        drones.clear();
    }

    private void tickAll() {
        Iterator<Map.Entry<UUID, Uav>> it = drones.entrySet().iterator();
        while (it.hasNext()) {
            Uav u = it.next().getValue();
            try {
                if (!u.tick()) it.remove();
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка тика БПЛА: " + e.getMessage());
                u.remove();
                it.remove();
            }
        }
    }

    // ────────────────────────────────────────────────────── спавн

    public Uav place(Player owner, Location at) {
        String name = plugin.getConfig()
                .getString("model.display-name", "&8БПЛА").replace('&', '\u00A7');

        ArmorStand box = at.getWorld().spawn(at, ArmorStand.class, s -> {
            s.setVisible(false);
            s.setGravity(false);
            s.setMarker(false);          // marker=true убрал бы хитбокс
            s.setSmall(true);
            s.setBasePlate(false);
            s.setArms(false);
            s.setCollidable(false);
            s.setPersistent(true);
            s.setCustomName(name);
            s.setCustomNameVisible(plugin.getConfig().getBoolean("model.show-name", true));
            double hp = plugin.getConfig().getDouble("drone.health", 8.0);
            var attr = s.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (attr != null) attr.setBaseValue(hp);
            s.setHealth(Math.min(hp, attr == null ? hp : attr.getValue()));
            s.getPersistentDataContainer().set(UavPlugin.KEY_ENTITY, PersistentDataType.BYTE, (byte) 1);
            s.getPersistentDataContainer().set(UavPlugin.KEY_OWNER, PersistentDataType.STRING,
                    owner.getUniqueId().toString());
        });

        Location modelAt = at.clone().add(0, plugin.getConfig().getDouble("model.y-offset", 0.0), 0);
        java.util.List<Uav.Slab> shape = Uav.shapeOf(plugin);
        java.util.List<ItemDisplay> parts = new ArrayList<>();
        for (Uav.Slab slab : shape) {
            ItemDisplay d = at.getWorld().spawn(modelAt, ItemDisplay.class, disp -> {
                Uav.applySlab(plugin, disp, slab);
                disp.setPersistent(true);
                disp.getPersistentDataContainer()
                        .set(UavPlugin.KEY_ENTITY, PersistentDataType.BYTE, (byte) 1);
            });
            parts.add(d);
        }

        Uav u = new Uav(plugin, parts, box, owner.getUniqueId());
        drones.put(u.id, u);
        return u;
    }

    public Uav byEntity(Entity e) {
        if (e == null) return null;
        return drones.get(e.getUniqueId());
    }

    public boolean isDrone(Entity e) {
        return e != null && e.getPersistentDataContainer()
                .has(UavPlugin.KEY_ENTITY, PersistentDataType.BYTE);
    }

    /** Осиротевшие сущности после рестарта — просто убираем. */
    public void cleanupOrphan(Entity e) {
        if (isDrone(e) && byEntity(e) == null) e.remove();
    }

    public int countOf(UUID owner) {
        int n = 0;
        for (Uav u : drones.values()) if (owner.equals(u.owner) && u.alive()) n++;
        return n;
    }

    public java.util.List<Uav> flyingOf(UUID owner) {
        java.util.List<Uav> out = new ArrayList<>();
        for (Uav u : drones.values()) {
            if (owner.equals(u.owner) && u.state == Uav.State.FLYING) out.add(u);
        }
        return out;
    }

    public void remove(UUID id) { drones.remove(id); }

    // ────────────────────────────────────────────── ожидание ввода

    public void awaitCoords(Player p, Uav u) { awaitingCoords.put(p.getUniqueId(), u.id); }
    public void cancelAwait(Player p)        { awaitingCoords.remove(p.getUniqueId()); }
    public boolean isAwaiting(Player p)      { return awaitingCoords.containsKey(p.getUniqueId()); }

    public Uav awaited(Player p) {
        UUID id = awaitingCoords.get(p.getUniqueId());
        return id == null ? null : drones.get(id);
    }

    // ───────────────────────────────────────────────────── кулдаун

    public int cooldownLeft(UUID player) {
        Long until = cooldowns.get(player);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    public void startCooldown(UUID player) {
        int sec = plugin.getConfig().getInt("drone.cooldown-seconds", 60);
        if (sec > 0) cooldowns.put(player, System.currentTimeMillis() + sec * 1000L);
    }
}
