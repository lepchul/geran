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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * БПЛА. Видимая часть — ItemDisplay с приплюснутым блоком,
 * попадания принимает невидимый стенд рядом: дисплеи не умеют получать урон.
 */
public class Uav {

    public enum State { IDLE, FLYING, DEAD }
    public enum Phase { CLIMB, CRUISE, DIVE }

    public final UUID id;
    public final List<ItemDisplay> parts;
    public final ArmorStand hitbox;
    public final UUID owner;

    public State state = State.IDLE;
    public Location target;
    public Location launchPoint;
    public int ticksFlown = 0;
    public Phase phase = Phase.CLIMB;
    private double cruiseY = 0;

    private final UavPlugin plugin;
    private int engineTimer = 0;
    private int whistleTimer = 0;

    public Uav(UavPlugin plugin, List<ItemDisplay> parts, ArmorStand hitbox, UUID owner) {
        this.plugin = plugin;
        this.parts = parts;
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
        this.phase = Phase.CLIMB;

        double abs = plugin.getConfig().getDouble("flight.cruise-altitude", 0);
        double climb = plugin.getConfig().getDouble("flight.climb-height", 60);
        double ceiling = hitbox.getWorld().getMaxHeight() - 6;
        this.cruiseY = abs > 0
                ? Math.min(abs, ceiling)
                : Math.min(ceiling, Math.max(launchPoint.getY(), target.getY()) + climb);

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
        double vSpeed = plugin.getConfig().getDouble("flight.vertical-speed", 0.7);
        double diveDist = plugin.getConfig().getDouble("flight.dive-distance", 40);

        Location cur = at();
        Vector flat = target.toVector().subtract(cur.toVector()).setY(0);
        double horiz = flat.length();
        double full = cur.distance(target);

        if (horiz > 0.01) flat = flat.normalize();
        else flat = new Vector(0, 0, 0);

        // ── выбор фазы ──────────────────────────────────────────────
        if (horiz <= diveDist) {
            phase = Phase.DIVE;
        } else if (phase == Phase.CLIMB && cur.getY() >= cruiseY - 1.5) {
            phase = Phase.CRUISE;
        }

        // ── куда хотим по высоте ────────────────────────────────────
        double wantY;
        if (phase == Phase.DIVE) {
            wantY = target.getY();
        } else {
            // Обход рельефа: смотрим вперёд и держимся выше самого высокого,
            // что попадётся по курсу — деревья, холмы, постройки.
            double lookahead = plugin.getConfig().getDouble("flight.terrain-lookahead", 28);
            double clearance = plugin.getConfig().getDouble("flight.terrain-clearance", 8);
            double step = Math.max(1, plugin.getConfig().getDouble("flight.terrain-scan-step", 2));
            double ceiling = cur.getWorld().getMaxHeight() - 6;

            double obstacle = scanAhead(cur, flat, lookahead, step);
            wantY = Math.min(ceiling, Math.max(cruiseY, obstacle + clearance));
        }

        // ── шаг ─────────────────────────────────────────────────────
        Vector move;
        if (phase == Phase.DIVE) {
            move = target.toVector().subtract(cur.toVector());
            if (move.lengthSquared() > 0.0001) move = move.normalize().multiply(speed);
        } else {
            double dy = wantY - cur.getY();
            double vStep = Math.max(-vSpeed, Math.min(vSpeed, dy));

            // На наборе высоты идём вперёд медленнее, как настоящий аппарат
            double hFactor = (phase == Phase.CLIMB)
                    ? plugin.getConfig().getDouble("flight.climb-forward-factor", 0.35)
                    : 1.0;
            double hStep = Math.min(speed * hFactor, horiz);

            move = flat.clone().multiply(hStep).setY(vStep);
        }

        if (full <= speed + 0.5) {
            move(target);
            detonate(null);
            return false;
        }

        double stepLen = move.length();
        Vector dir = stepLen > 0.0001 ? move.clone().normalize() : new Vector(0, 1, 0);

        // На тридцати блоках в секунду шаг больше блока, поэтому проверяем путь
        // мелкими отрезками — иначе аппарат проскакивал бы сквозь стены.
        double sub = plugin.getConfig().getDouble("flight.collision-step", 0.4);
        double travelled = 0;
        Location probe = cur.clone();
        while (travelled < stepLen) {
            double add = Math.min(sub, stepLen - travelled);
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

        probe.setDirection(flat.lengthSquared() > 0.001 ? flat : dir);
        move(probe);
        effects(probe, dir);
        hud(full);
        return true;
    }

    /**
     * Самая высокая точка рельефа по курсу.
     * Берём готовую карту высот мира — это один вызов на колонку, дёшево.
     */
    private double scanAhead(Location from, Vector flatDir, double lookahead, double step) {
        if (flatDir.lengthSquared() < 0.001) return from.getY();
        World w = from.getWorld();
        double highest = w.getMinHeight();
        for (double d = 2; d <= lookahead; d += step) {
            Location probe = from.clone().add(flatDir.clone().multiply(d));
            int top = w.getHighestBlockYAt(probe.getBlockX(), probe.getBlockZ());
            if (top > highest) highest = top;
        }
        return highest;
    }

    /** Двигаем хитбокс и все пластины корпуса разом. */
    private void move(Location to) {
        hitbox.teleport(to.clone());
        Location m = to.clone().add(0,
                plugin.getConfig().getDouble("model.y-offset", 0.0), 0);
        for (ItemDisplay d : parts) {
            if (d.isValid()) d.teleport(m);
        }
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
            String ph = switch (phase) {
                case CLIMB  -> plugin.msg("phase-climb");
                case CRUISE -> plugin.msg("phase-cruise");
                case DIVE   -> plugin.msg("phase-dive");
            };
            op.sendActionBar(plugin.msg("hud",
                    "dist", String.valueOf((int) dist),
                    "sec", String.valueOf(ticksFlown / 20),
                    "alt", String.valueOf(at().getBlockY()),
                    "phase", ph));
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
        for (ItemDisplay d : parts) {
            if (d.isValid()) d.remove();
        }
        if (hitbox.isValid()) hitbox.remove();
    }

    // ─────────────────────────────────────────────────────── модель

    /**
     * Одна пластина корпуса: смещение вперёд, ширина, длина, поворот.
     * Из нескольких таких складывается сплошной треугольник.
     */
    public record Slab(double x, double y, double z,
                       double sx, double sy, double sz, double rot) {}

    /**
     * Готовый треугольник: пластины сужаются к носу.
     * Ширина падает от хвоста к носу, сверху читается как сплошной клин.
     */
    public static List<Slab> triangle(UavPlugin plugin) {
        int steps = Math.max(3, plugin.getConfig().getInt("model.triangle.slabs", 7));
        double length = plugin.getConfig().getDouble("model.triangle.length", 1.8);
        double width = plugin.getConfig().getDouble("model.triangle.width", 1.7);
        double thick = plugin.getConfig().getDouble("model.triangle.thickness", 0.13);
        double noseWidth = plugin.getConfig().getDouble("model.triangle.nose-width", 0.16);

        List<Slab> out = new ArrayList<>();
        double slabLen = length / steps;
        for (int i = 0; i < steps; i++) {
            double t = (double) i / (steps - 1);          // 0 — нос, 1 — хвост
            double z = length / 2 - slabLen * i - slabLen / 2;
            double w = noseWidth + (width - noseWidth) * t;
            out.add(new Slab(0, 0, z, w, thick, slabLen * 1.05, 0));
        }
        return out;
    }

    /** Пластины из конфига, если задана своя форма. */
    public static List<Slab> customSlabs(UavPlugin plugin) {
        List<Slab> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("model.parts")) {
            out.add(new Slab(
                    num(m, "x", 0), num(m, "y", 0), num(m, "z", 0),
                    num(m, "sx", 1), num(m, "sy", 0.15), num(m, "sz", 1),
                    num(m, "rot", 0)));
        }
        return out;
    }

    private static double num(Map<?, ?> m, String key, double def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    public static List<Slab> shapeOf(UavPlugin plugin) {
        String shape = plugin.getConfig().getString("model.shape", "TRIANGLE").toUpperCase();
        if (shape.equals("CUSTOM")) {
            List<Slab> custom = customSlabs(plugin);
            if (!custom.isEmpty()) return custom;
            plugin.getLogger().warning("model.parts пуст — беру треугольник по умолчанию.");
        }
        if (shape.equals("FLAT")) {
            double s1 = plugin.getConfig().getDouble("model.triangle.width", 1.7);
            return List.of(new Slab(0, 0, 0, s1, 
                    plugin.getConfig().getDouble("model.triangle.thickness", 0.13), s1, 45));
        }
        return triangle(plugin);
    }

    /** Настраиваем одну пластину. */
    public static void applySlab(UavPlugin plugin, ItemDisplay d, Slab s) {
        Material mat = Material.matchMaterial(
                plugin.getConfig().getString("model.material", "COAL_BLOCK").toUpperCase());
        if (mat == null || mat == Material.AIR) mat = Material.COAL_BLOCK;
        d.setItemStack(new ItemStack(mat));

        d.setTransformation(new Transformation(
                new Vector3f((float) s.x(), (float) s.y(), (float) s.z()),
                new Quaternionf().rotateY((float) Math.toRadians(s.rot())),
                new Vector3f((float) s.sx(), (float) s.sy(), (float) s.sz()),
                new Quaternionf()));
        d.setBillboard(Display.Billboard.FIXED);

        int smooth = plugin.getConfig().getInt("model.smoothing-ticks", 3);
        try {
            d.setInterpolationDuration(smooth);
            d.setInterpolationDelay(0);
        } catch (Throwable ignored) {
        }
        try {
            d.setTeleportDuration(Math.max(1, Math.min(59, smooth)));
        } catch (Throwable ignored) {
            // на серверах старее 1.20.2 сглаживания не будет
        }
    }
}
