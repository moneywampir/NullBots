package nullbots;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BlockVector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Verwaltet alle Bots: Portal, Formationen, Kampf-Befehle, Allies. */
public class BotArmy {

    private final NullBots plugin;
    private final List<Bot> bots = new ArrayList<>();
    private final List<Bot> pending = new ArrayList<>();
    private final Set<UUID> allies = new HashSet<>();

    private Location anchor;
    private Formation formation = Formation.PATH;
    private Gate gate;
    private BukkitTask task;
    private Team team;

    private int tick, nextSpawnTick, gateCloseTick = -1;
    private int maxBots, emergeDelay, despawnStagger, closeDelay;
    private double spacing, radius, portalDistance;
    private boolean portalEnabled, blockTravel;

    public BotArmy(NullBots plugin) {
        this.plugin = plugin;
        reload();
    }

    public NullBots plugin() { return plugin; }

    public void reload() {
        var c = plugin.getConfig();
        maxBots = Math.max(1, Math.min(200, c.getInt("max-bots", 50)));
        spacing = Math.max(0.8, c.getDouble("formation.spacing", 1.6));
        radius = Math.max(2.0, c.getDouble("formation.radius", 8.0));
        Formation f = Formation.parse(c.getString("formation.type", "PATH"));
        if (f != null) formation = f;
        portalEnabled = c.getBoolean("portal.enabled", true);
        portalDistance = Math.max(3.0, c.getDouble("portal.distance", 7.0));
        emergeDelay = Math.max(1, c.getInt("portal.emerge-delay-ticks", 6));
        despawnStagger = Math.max(0, c.getInt("portal.despawn-stagger-ticks", 4));
        closeDelay = Math.max(10, c.getInt("portal.close-delay-ticks", 40));
        blockTravel = c.getBoolean("portal.block-travel", true);
    }

    public void start() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        team = sb.getTeam("nullbots");
        if (team == null) team = sb.registerNewTeam("nullbots");
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (Bot b : bots) b.destroy();
        bots.clear();
        pending.clear();
        if (gate != null) gate.remove();
        saveAllies();
    }

    // ---------- Herzschlag ----------
    private void tick() {
        tick++;
        if (!pending.isEmpty() && tick >= nextSpawnTick) {
            Location origin = gateCentre() != null ? gateCentre() : anchor;
            if (origin == null) pending.clear();
            else {
                Bot b = pending.remove(0);
                b.create(origin);
                b.setState(Bot.State.EMERGING);
                bots.add(b);
                nextSpawnTick = tick + emergeDelay;
            }
        }
        for (Bot b : new ArrayList<>(bots)) b.tick(tick);

        if (gateCloseTick > 0 && tick >= gateCloseTick && pending.isEmpty() && !anyDespawning()) {
            if (gate != null) gate.remove();
            gateCloseTick = -1;
        }
    }

    private boolean anyDespawning() {
        for (Bot b : bots) if (b.state() == Bot.State.DESPAWNING) return true;
        return false;
    }

    // ---------- Registrierung ----------
    void addToTeam(Mob m) { if (team != null) team.addEntry(m.getUniqueId().toString()); }
    void removeFromTeam(Mob m) { if (team != null) team.removeEntry(m.getUniqueId().toString()); }
    void forget(Bot b) { bots.remove(b); }

    public boolean isBot(Entity e) {
        return e != null && e.getPersistentDataContainer().has(plugin.botKey(), PersistentDataType.BYTE);
    }

    public Bot byEntity(UUID id) {
        if (id == null) return null;
        for (Bot b : bots) if (id.equals(b.entityId())) return b;
        return null;
    }

    public int count() { return bots.size() + pending.size(); }
    public int fighting() { int n = 0; for (Bot b : bots) if (b.state() == Bot.State.FIGHTING) n++; return n; }
    public String formationName() { return formation.name(); }

    // ---------- Allies ----------
    public boolean isAlly(UUID id) { return allies.contains(id); }
    public Set<UUID> allies() { return allies; }

    public boolean addAlly(UUID id) { boolean ok = allies.add(id); saveAllies(); return ok; }
    public boolean removeAlly(UUID id) { boolean ok = allies.remove(id); saveAllies(); return ok; }

    public void loadAllies() {
        allies.clear();
        for (String s : plugin.getConfig().getStringList("allies")) {
            try { allies.add(UUID.fromString(s.split("\\|")[0])); } catch (Exception ignored) { }
        }
    }

    public void saveAllies() {
        List<String> out = new ArrayList<>();
        for (UUID id : allies) {
            String n = Bukkit.getOfflinePlayer(id).getName();
            out.add(id + (n == null ? "" : "|" + n));
        }
        plugin.getConfig().set("allies", out);
        plugin.saveConfig();
    }

    // ---------- Befehle ----------
    public String spawn(Player p, int n) {
        if (n < 1) return "§cAnzahl muss mindestens 1 sein.";
        if (anchor == null) anchor = p.getLocation();
        int total = count();
        if (total + n > maxBots) return "§cLimit: " + maxBots + " Bots. Aktuell: " + total + ".";
        ensureGate();
        for (int i = 0; i < n; i++) pending.add(new Bot(this, total + i));
        reassignSlots();
        if (nextSpawnTick < tick) nextSpawnTick = tick;
        gateCloseTick = -1;
        return "§a" + n + " Null-Bot(s) kommen aus dem Portal...";
    }

    public String despawn(int n) {
        pending.clear();
        if (bots.isEmpty()) return "§cKeine Bots aktiv.";
        ensureGate();
        List<Bot> chosen = new ArrayList<>();
        for (Bot b : bots) if (b.state() != Bot.State.DESPAWNING) chosen.add(b);
        if (chosen.isEmpty()) return "§cAlle Bots gehen bereits.";
        if (n >= 0) chosen = chosen.subList(0, Math.min(n, chosen.size()));
        int i = 0;
        for (Bot b : chosen) b.orderDespawn(tick + (i++) * despawnStagger);
        gateCloseTick = tick + closeDelay;
        return "§a" + chosen.size() + " Bot(s) gehen zurück ins Portal.";
    }

    public String fight(String amount, Player target) {
        if (isAlly(target.getUniqueId()))
            return "§c" + target.getName() + " ist ein Ally. Erst: /null ally remove " + target.getName();
        List<Bot> avail = new ArrayList<>();
        for (Bot b : bots) if (b.state() != Bot.State.DESPAWNING && b.mob() != null) avail.add(b);
        if (avail.isEmpty()) return "§cKeine Bots da. Erst: /null spawn <anzahl>";
        avail.sort(Comparator.comparingDouble((Bot b) -> dist(b, target)));
        int n;
        if (amount.equalsIgnoreCase("all")) n = avail.size();
        else {
            try { n = Integer.parseInt(amount); }
            catch (Exception ex) { return "§cAnzahl muss eine Zahl oder 'all' sein."; }
            if (n < 1) return "§cAnzahl muss mindestens 1 sein.";
            n = Math.min(n, avail.size());
        }
        for (int i = 0; i < n; i++) avail.get(i).orderFight(target);
        return "§a" + n + " Bot(s) greifen §f" + target.getName() + "§a an.";
    }

    public String stop(int n) {
        List<Bot> f = new ArrayList<>();
        for (Bot b : bots) if (b.state() == Bot.State.FIGHTING) f.add(b);
        if (f.isEmpty()) return "§cKeine Bots kämpfen gerade.";
        int count = (n < 0) ? f.size() : Math.min(n, f.size());
        for (int i = 0; i < count; i++) f.get(i).orderReturn();
        return "§a" + count + " Bot(s) kehren in die Formation zurück.";
    }

    public String formation(String type, Double sp, Double rad) {
        Formation f = Formation.parse(type);
        if (f == null) return "§cUnbekannt. Nutze: grid, circle, path, split";
        formation = f;
        if (sp != null) spacing = Math.max(0.8, sp);
        if (rad != null) radius = Math.max(2.0, rad);
        reassignSlots();
        return "§aFormation: §f" + f.name() + "§a (Abstand " + spacing + ", Radius " + radius + ")";
    }

    public String anchor(Location loc) {
        anchor = loc;
        reassignSlots();
        return "§aAnker auf deine Position gesetzt. Bots formieren sich neu.";
    }

    public void reassignSlots() {
        if (anchor == null) return;
        List<Bot> active = new ArrayList<>();
        for (Bot b : bots) if (b.state() != Bot.State.DESPAWNING) active.add(b);
        active.addAll(pending);
        List<Location> slots = formation.slots(anchor, active.size(), spacing, radius);
        for (int i = 0; i < active.size(); i++) {
            Bot b = active.get(i);
            b.setSlot(slots.get(i));
            if (b.state() == Bot.State.HOLDING || b.state() == Bot.State.FORMING) b.setState(Bot.State.FORMING);
        }
    }

    private static double dist(Bot b, Player p) {
        Mob m = b.mob();
        if (m == null || !m.getWorld().equals(p.getWorld())) return Double.MAX_VALUE;
        return m.getLocation().distance(p.getLocation());
    }

    // ---------- Portal ----------
    Location gateCentre() { return (gate != null && gate.built) ? gate.centre : null; }
    public boolean blockTravel() { return blockTravel; }
    public boolean isGatePortal(Location loc) { return gate != null && gate.isPortalBlock(loc); }

    private void ensureGate() {
        if (!portalEnabled || anchor == null) return;
        if (gate != null && gate.built) return;
        gate = new Gate();
        double yaw = Math.toRadians(anchor.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        Location c = anchor.clone().subtract(fx * portalDistance, 0, fz * portalDistance);
        gate.build(c, Math.abs(fz) >= Math.abs(fx));
        if (anchor.getWorld() != null) anchor.getWorld().playSound(c, "block.portal.ambient", 1f, 1f);
    }

    // ---------- Geometrie ----------
    static float faceToward(Location from, Location to) {
        return (float) Math.toDegrees(Math.atan2(-(to.getX() - from.getX()), to.getZ() - from.getZ()));
    }

    static double snapY(World w, int x, int z, double startY) {
        int top = (int) Math.floor(startY) + 3, bottom = (int) Math.floor(startY) - 8;
        for (int y = top; y >= bottom; y--) {
            if (!w.getBlockAt(x, y, z).getType().isSolid()) continue;
            if (!w.getBlockAt(x, y + 1, z).getType().isSolid() && !w.getBlockAt(x, y + 2, z).getType().isSolid())
                return y + 1.0;
        }
        return startY;
    }

    private static Location place(World w, Location a, double fx, double fz, double rx, double rz,
                                  double right, double fwd, float yaw) {
        double x = a.getX() + rx * right + fx * fwd;
        double z = a.getZ() + rz * right + fz * fwd;
        Location l = new Location(w, x, snapY(w, (int) Math.floor(x), (int) Math.floor(z), a.getY()), z);
        l.setYaw(yaw);
        return l;
    }

    public enum Formation {
        GRID, CIRCLE, PATH, SPLIT;

        static Formation parse(String s) {
            if (s == null) return null;
            for (Formation f : values()) if (f.name().equalsIgnoreCase(s)) return f;
            return null;
        }

        List<Location> slots(Location a, int count, double sp, double rad) {
            List<Location> out = new ArrayList<>(count);
            if (count <= 0) return out;
            double yaw = Math.toRadians(a.getYaw());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
            World w = a.getWorld();

            if (this == GRID) {
                int cols = (int) Math.ceil(Math.sqrt(count));
                for (int i = 0; i < count; i++) {
                    double right = ((i % cols) - (cols - 1) / 2.0) * sp;
                    out.add(place(w, a, fx, fz, rx, rz, right, -(i / cols) * sp, a.getYaw()));
                }
            } else if (this == CIRCLE) {
                double r = Math.max(rad, count * sp / (2 * Math.PI));
                for (int i = 0; i < count; i++) {
                    double ang = 2 * Math.PI * i / count;
                    Location l = place(w, a, fx, fz, rx, rz, Math.cos(ang) * r, Math.sin(ang) * r, 0f);
                    l.setYaw(faceToward(l, a));
                    out.add(l);
                }
            } else if (this == PATH) {
                double half = Math.max(1.5, rad / 2.0);
                for (int i = 0; i < count; i++) {
                    double fwd = (i / 2) * sp;
                    Location l = place(w, a, fx, fz, rx, rz, (i % 2 == 0) ? -half : half, fwd, 0f);
                    Location mid = new Location(w, a.getX() + fx * fwd, a.getY(), a.getZ() + fz * fwd);
                    l.setYaw(faceToward(l, mid));
                    out.add(l);
                }
            } else {
                int leftCount = count / 2 + count % 2;
                double gap = Math.max(3.0, rad);
                int cols = (int) Math.ceil(Math.sqrt(Math.max(1, leftCount)));
                for (int i = 0; i < count; i++) {
                    boolean left = i < leftCount;
                    int j = left ? i : i - leftCount;
                    double right = (left ? -gap : gap) + ((j % cols) - (cols - 1) / 2.0) * sp;
                    out.add(place(w, a, fx, fz, rx, rz, right, -(j / cols) * sp, a.getYaw()));
                }
            }
            return out;
        }
    }

    /** Das Netherportal, aus dem die Bots kommen und in das sie zurückgehen. */
    private static final class Gate {
        final Map<Block, BlockData> previous = new LinkedHashMap<>();
        final Set<BlockVector> portal = new HashSet<>();
        Location centre;
        World world;
        boolean built;

        void build(Location want, boolean alongX) {
            if (built || want.getWorld() == null) return;
            world = want.getWorld();
            int bx = alongX ? want.getBlockX() - 1 : want.getBlockX();
            int bz = alongX ? want.getBlockZ() : want.getBlockZ() - 1;
            int by = (int) snapY(world, bx, bz, want.getY()) - 1;
            Axis axis = alongX ? Axis.X : Axis.Z;

            for (int i = 0; i < 4; i++) {
                for (int h = 0; h < 5; h++) {
                    if ((i == 0 || i == 3) && (h == 0 || h == 4)) continue;
                    boolean frame = (i == 0 || i == 3 || h == 0 || h == 4);
                    int x = alongX ? bx + i : bx;
                    int z = alongX ? bz : bz + i;
                    Block b = world.getBlockAt(x, by + h, z);
                    BlockData data;
                    if (frame) data = Material.OBSIDIAN.createBlockData();
                    else {
                        Orientable o = (Orientable) Material.NETHER_PORTAL.createBlockData();
                        o.setAxis(axis);
                        data = o;
                        portal.add(new BlockVector(x, by + h, z));
                    }
                    previous.put(b, b.getBlockData().clone());
                    b.setBlockData(data, false);
                }
            }
            centre = new Location(world, alongX ? bx + 1.5 : bx + 0.5, by + 1, alongX ? bz + 0.5 : bz + 1.5);
            built = true;
        }

        void remove() {
            if (!built) return;
            for (Map.Entry<Block, BlockData> e : previous.entrySet()) e.getKey().setBlockData(e.getValue(), false);
            previous.clear();
            portal.clear();
            built = false;
            centre = null;
        }

        boolean isPortalBlock(Location loc) {
            if (!built || loc.getWorld() == null || !loc.getWorld().equals(world)) return false;
            return portal.contains(new BlockVector(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        }
    }
}
