package ru.factor.uav;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * БПЛА. Видимая часть — ItemDisplay с приплюснутым блоком,
 * попадания принимает невидимый стенд рядом: дисплеи не умеют получать урон.
 */
public class Uav {

    public enum State { IDLE, FLYING, DEAD }

    public final UUID id;
    public final ItemDisplay model;
    public final ArmorStand hitbox;
    public final UUID owner;

    public State state = State.IDLE;
    public Location target;
    public Location launchPoint;
    public int ticksFlown = 0;

    private final UavPlugin plugin;
    private int engineTimer = 0;
    private int whistleTimer = 0;

    public Uav(UavPlugin plugin, ItemDisplay model, ArmorStand hitbox, UUID owner) {
        this.plugin = plugin;
        this.model = model;
        this.hitbox = hitbox;
        this.owner = owner;
        this.id = hitbox.getUniqueId();
    }

    public boolean alive() {
        return state != State.DEAD && hitbox.isValid() && !hitbox.isDead();
    }

    public Location at() { return hitbox.getLocation(); }

    // ────────────────────────────────────────────────────── запуск

    public void launch(Location target) {
        this.target = target;
        this.launchPoint = at().clone();
        this.state = State.FLYING;
        this.ticksFlown = 0;

        World w = hitbox.getWorld();
        w.playSound(at(), Cfg.sound(plugin, "sounds.launch", Sound.ENTITY_FIREWORK_ROCKET_LAUNCH),
                (float) plugin.getConfig().getDouble("sounds.launch-volume", 4.0), 0.7f);

        if (plugin.getConfig().getBoolean("broadcast.on-launch", true)) {
            org.bukkit.Bukkit.broadcastMessage(plugin.msg("broadcast-launch",
                    "name", plugin.getConfig().getString("model.display-name", "БПЛА")
                            .replace('&', '\u00A7')));
        }
    }

    // ──────────────────────────────────────────────────────── тик

    public boolean tick() {
        if (!alive()) return false;
        if (state != State.FLYING) return true;

        int maxTicks = plugin.getConfig().getInt("flight.max-seconds", 120) * 20;
        double maxRange = plugin.getConfig().getDouble("flight.max-range", 3000);
        ticksFlown++;

        if (ticksFlown > maxTicks) {
            detonate(plugin.msg("lost-battery"));
            return false;
        }
        if (launchPoint != null && launchPoint.getWorld() != null
                && launchPoint.getWorld().equals(hitbox.getWorld())
                && at().distance(launchPoint) > maxRange) {
            detonate(plugin.msg("lost-range"));
            return false;
        }

        double speed = plugin.getConfig().getDouble("flight.speed-blocks-per-second", 30.0) / 20.0;
        Location cur = at();
        Vector diff = target.toVector().subtract(cur.toVector());
        double dist = diff.length();

        if (dist <= speed + 0.5) {
            move(target);
            detonate(null);
            return false;
        }

        Vector dir = diff.normalize();

        // На тридцати блоках в секунду шаг больше блока, поэтому проверяем путь
        // мелкими отрезками — иначе дрон проскакивал бы сквозь стены.
        double sub = plugin.getConfig().getDouble("flight.collision-step", 0.4);
        double travelled = 0;
        Location probe = cur.clone();
        while (travelled < speed) {
            double add = Math.min(sub, speed - travelled);
            probe.add(dir.clone().multiply(add));
            travelled += add;

            Material mat = probe.getBlock().getType();
            if (mat.isSolid()) {
                move(probe);
                detonate(null);
                return false;
            }
            if (probe.getBlock().isLiquid()
                    && plugin.getConfig().getBoolean("flight.water-kills", true)) {
                move(probe);
                fizzle();
                return false;
            }
        }

        probe.setDirection(dir);
        move(probe);
        effects(probe, dir);
        hud(dist);
        return true;
    }

    /** Двигаем обе сущности разом. */
    private void move(Location to) {
        Location h = to.clone();
        hitbox.teleport(h);
        Location m = to.clone().add(0,
                plugin.getConfig().getDouble("model.y-offset", 0.0), 0);
        model.teleport(m);
    }

    // ───────────────────────────────────────────────────── эффекты

    private void effects(Location at, Vector dir) {
        World w = at.getWorld();
        if (w == null) return;

        int count = plugin.getConfig().getInt("effects.trail-count", 3);
        if (count > 0) {
            w.spawnParticle(Cfg.particle(plugin, "effects.trail-particle", Cfg.anyParticle("SMOKE", "SMOKE_NORMAL")),
                    at, count, 0.1, 0.1, 0.1, 0.01);
        }

        int engineEvery = Math.max(1, plugin.getConfig().getInt("sounds.engine-interval-ticks", 4));
        if (++engineTimer >= engineEvery) {
            engineTimer = 0;
            w.playSound(at, Cfg.sound(plugin, "sounds.engine", Sound.ENTITY_BEE_LOOP_AGGRESSIVE),
                    (float) plugin.getConfig().getDouble("sounds.engine-volume", 5.0),
                    (float) plugin.getConfig().getDouble("sounds.engine-pitch", 0.55));
        }

        int whistleEvery = Math.max(1, plugin.getConfig().getInt("sounds.whistle-interval-ticks", 14));
        if (++whistleTimer >= whistleEvery) {
            whistleTimer = 0;
            w.playSound(at, Cfg.sound(plugin, "sounds.whistle", Sound.BLOCK_NOTE_BLOCK_FLUTE),
                    (float) plugin.getConfig().getDouble("sounds.whistle-volume", 4.0),
                    (float) plugin.getConfig().getDouble("sounds.whistle-pitch", 2.0));
        }
    }

    private void hud(double dist) {
        if (!plugin.getConfig().getBoolean("hud.enabled", true)) return;
        Player op = org.bukkit.Bukkit.getPlayer(owner);
        if (op != null && op.isOnline()) {
            op.sendActionBar(plugin.msg("hud",
                    "dist", String.valueOf((int) dist),
                    "sec", String.valueOf(ticksFlown / 20)));
        }
    }

    // ─────────────────────────────────────────────────── завершение

    public void detonate(String reason) {
        if (state == State.DEAD) return;
        Location boom = at().clone();
        state = State.DEAD;

        float power = (float) plugin.getConfig().getDouble("explosion.power", 6.0);
        boolean fire = plugin.getConfig().getBoolean("explosion.set-fire", true);
        boolean blocks = plugin.getConfig().getBoolean("explosion.break-blocks", true);

        World w = boom.getWorld();
        if (w != null && plugin.getConfig().getStringList("protection.no-grief-worlds")
                .contains(w.getName())) blocks = false;

        remove();
        if (w != null) {
            w.createExplosion(boom, power, fire, blocks);
            int extra = plugin.getConfig().getInt("explosion.extra-fire-radius", 3);
            if (fire && extra > 0) scatterFire(w, boom, extra);
        }

        Player op = org.bukkit.Bukkit.getPlayer(owner);
        if (op != null && op.isOnline()) {
            op.sendActionBar("");
            op.sendMessage(reason != null ? reason : plugin.msg("hit",
                    "x", String.valueOf(boom.getBlockX()),
                    "y", String.valueOf(boom.getBlockY()),
                    "z", String.valueOf(boom.getBlockZ())));
        }
        if (plugin.getConfig().getBoolean("broadcast.on-impact", false)) {
            org.bukkit.Bukkit.broadcastMessage(plugin.msg("broadcast-impact"));
        }
    }

    /** Раскидываем очаги огня вокруг воронки. */
    private void scatterFire(World w, Location center, int radius) {
        java.util.Random rng = new java.util.Random();
        int tries = radius * radius * 2;
        for (int i = 0; i < tries; i++) {
            int dx = rng.nextInt(radius * 2 + 1) - radius;
            int dz = rng.nextInt(radius * 2 + 1) - radius;
            int dy = rng.nextInt(3) - 1;
            Location spot = center.clone().add(dx, dy, dz);
            if (!spot.getBlock().getType().isAir()) continue;
            if (!spot.clone().subtract(0, 1, 0).getBlock().getType().isSolid()) continue;
            spot.getBlock().setType(Material.FIRE, false);
        }
    }

    public void fizzle() {
        if (state == State.DEAD) return;
        Location at = at().clone();
        state = State.DEAD;
        remove();

        World w = at.getWorld();
        if (w != null) {
            w.spawnParticle(Cfg.anyParticle("LARGE_SMOKE", "SMOKE_LARGE"), at, 40, 0.6, 0.6, 0.6, 0.06);
            w.playSound(at, Cfg.sound(plugin, "sounds.fizzle",
                    Sound.ENTITY_GENERIC_EXTINGUISH_FIRE), 2f, 0.8f);
        }
        Player op = org.bukkit.Bukkit.getPlayer(owner);
        if (op != null && op.isOnline()) {
            op.sendActionBar("");
            op.sendMessage(plugin.msg("shot-down"));
        }
    }

    public void remove() {
        if (model.isValid()) model.remove();
        if (hitbox.isValid()) hitbox.remove();
    }

    // ─────────────────────────────────────────────────────── модель

    /** Плоский повёрнутый блок — сверху читается как треугольное крыло. */
    public static void applyShape(UavPlugin plugin, ItemDisplay d) {
        Material mat = Material.matchMaterial(
                plugin.getConfig().getString("model.material", "COAL_BLOCK").toUpperCase());
        if (mat == null || mat == Material.AIR) mat = Material.COAL_BLOCK;
        d.setItemStack(new ItemStack(mat));

        float sx = (float) plugin.getConfig().getDouble("model.scale-x", 1.4);
        float sy = (float) plugin.getConfig().getDouble("model.scale-y", 0.16);
        float sz = (float) plugin.getConfig().getDouble("model.scale-z", 1.4);
        float yaw = (float) plugin.getConfig().getDouble("model.rotation-degrees", 45);

        d.setTransformation(new Transformation(
                new Vector3f(0f, 0f, 0f),
                new Quaternionf().rotateY((float) Math.toRadians(yaw)),
                new Vector3f(sx, sy, sz),
                new Quaternionf()));
        d.setBillboard(Display.Billboard.FIXED);

        // Сглаживание движения на клиенте: без него на 30 бл/с дёргается
        int smooth = plugin.getConfig().getInt("model.smoothing-ticks", 3);
        try {
            d.setInterpolationDuration(smooth);
            d.setInterpolationDelay(0);
        } catch (Throwable ignored) {
        }
        try {
            d.setTeleportDuration(Math.max(1, Math.min(59, smooth)));
        } catch (Throwable ignored) {
            // на серверах старее 1.20.2 метода нет — просто без сглаживания
        }
    }
}
