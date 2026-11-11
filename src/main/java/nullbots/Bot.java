package nullbots;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Ein einzelner Null-Bot: Aussehen, Zustand, Bewegung, Kampf. */
public class Bot {

    public enum State { EMERGING, FORMING, HOLDING, FIGHTING, RETURNING, DESPAWNING }

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final BotArmy army;
    private final NullBots plugin;
    private final int index;
    private final String name;
    private final Component display;

    private final EntityType type;
    private final boolean silent;
    private final Color armor;
    private final Material sword;
    private final double walkSpeed, chaseSpeed, reach, damage, maxChase;
    private final int moveInt, combatInt, cooldown;

    private UUID entityId, targetId;
    private Location slot;
    private State state = State.EMERGING;
    private int nextAttack, despawnAt;

    Bot(BotArmy army, int index) {
        this.army = army;
        this.plugin = army.plugin();
        this.index = index;
        FileConfiguration c = plugin.getConfig();

        this.name = randomName(c.getInt("bot.name-length", 12));
        this.display = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(c.getString("bot.name-color", "&d") + name);

        EntityType t;
        try {
            t = EntityType.valueOf(c.getString("bot.base-entity", "VINDICATOR").toUpperCase());
            if (t.getEntityClass() == null || !Mob.class.isAssignableFrom(t.getEntityClass())) t = EntityType.VINDICATOR;
        } catch (Exception ex) { t = EntityType.VINDICATOR; }
        this.type = t;

        this.silent = c.getBoolean("bot.silent", true);
        this.armor = parseColor(c.getString("bot.armor-color", "#7A2BE2"));
        Material s = Material.matchMaterial(c.getString("bot.sword", "NETHERITE_SWORD"));
        this.sword = (s == null) ? Material.NETHERITE_SWORD : s;
        this.walkSpeed = c.getDouble("bot.walk-speed", 0.30);
        this.chaseSpeed = c.getDouble("bot.chase-speed", 0.42);
        this.reach = c.getDouble("combat.reach", 3.0);
        this.damage = c.getDouble("combat.damage", 4.0);
        this.maxChase = c.getDouble("combat.max-chase-distance", 64.0);
        this.cooldown = Math.max(1, c.getInt("combat.attack-cooldown-ticks", 12));
        this.moveInt = Math.max(1, c.getInt("tick.move-interval", 5));
        this.combatInt = Math.max(1, c.getInt("tick.combat-interval", 4));
    }

    private static String randomName(int len) {
        StringBuilder sb = new StringBuilder(Math.max(4, len));
        for (int i = 0; i < Math.max(4, len); i++)
            sb.append(CHARS.charAt(ThreadLocalRandom.current().nextInt(CHARS.length())));
        return sb.toString();
    }

    private static Color parseColor(String hex) {
        try { return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16)); }
        catch (Exception ex) { return Color.fromRGB(0x7A2BE2); }
    }

    // ---------- Zugriff ----------
    public int index() { return index; }
    public String name() { return name; }
    public State state() { return state; }
    public void setState(State s) { state = s; }
    public void setSlot(Location l) { slot = l; }
    public UUID entityId() { return entityId; }
    public UUID targetId() { return targetId; }

    public boolean isAssignedTarget(Entity e) {
        return targetId != null && targetId.equals(e.getUniqueId());
    }

    Mob mob() {
        if (entityId == null) return null;
        Entity e = Bukkit.getEntity(entityId);
        return (e instanceof Mob m && e.isValid()) ? m : null;
    }

    // ---------- Erschaffen ----------
    void create(Location at) {
        if (at.getWorld() == null) return;
        Entity e = at.getWorld().spawnEntity(at, type);
        if (!(e instanceof Mob m)) { e.remove(); return; }
        entityId = m.getUniqueId();

        m.setPersistent(true);
        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        m.setSilent(silent);
        m.setInvulnerable(true);   // kann nicht sterben
        m.setAI(true);
        m.setAware(false);         // steht still, bis er laufen soll
        if (m instanceof Ageable a) a.setAdult();

        m.getPersistentDataContainer().set(plugin.botKey(), PersistentDataType.BYTE, (byte) 1);
        m.customName(display);
        m.setCustomNameVisible(true);
        dress(m);
        army.addToTeam(m);
        m.setRotation(at.getYaw(), 0f);
    }

    private void dress(Mob m) {
        EntityEquipment eq = m.getEquipment();
        if (eq == null) return;
        eq.setHelmet(leather(Material.LEATHER_HELMET));
        eq.setChestplate(leather(Material.LEATHER_CHESTPLATE));
        eq.setLeggings(leather(Material.LEATHER_LEGGINGS));
        eq.setBoots(leather(Material.LEATHER_BOOTS));
        eq.setItemInMainHand(new ItemStack(sword));
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
        eq.setItemInMainHandDropChance(0f);
    }

    private ItemStack leather(Material mat) {
        ItemStack it = new ItemStack(mat);
        if (it.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(armor);
            meta.setUnbreakable(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    // ---------- Befehle ----------
    void orderFight(Player p) { targetId = p.getUniqueId(); state = State.FIGHTING; nextAttack = 0; }
    void orderReturn() { targetId = null; state = State.RETURNING; }
    void orderDespawn(int startTick) { targetId = null; state = State.DESPAWNING; despawnAt = startTick; }

    // ---------- Tick ----------
    void tick(int t) {
        Mob m = mob();
        if (m == null) {
            if (state == State.DESPAWNING) { army.forget(this); return; }
            // Chunk war entladen: Bot neu aufbauen, sobald der Chunk wieder da ist.
            if (slot != null && slot.getWorld() != null
                    && slot.getWorld().isChunkLoaded(slot.getBlockX() >> 4, slot.getBlockZ() >> 4)) {
                create(slot);
                state = State.HOLDING;
            }
            return;
        }

        switch (state) {
            case EMERGING, FORMING, RETURNING -> {
                if ((t + index) % moveInt != 0) return;
                if (slot == null || !m.getWorld().equals(slot.getWorld())) { hold(m); state = State.HOLDING; return; }
                if (m.getLocation().distance(slot) < 0.7) {
                    m.teleport(slot);            // exakt einrasten
                    hold(m);
                    state = State.HOLDING;
                } else walk(m, slot, walkSpeed);
            }
            case HOLDING -> { }                  // absichtlich eingefroren
            case FIGHTING -> combat(t, m);
            case DESPAWNING -> {
                if (t < despawnAt) return;
                if ((t + index) % moveInt != 0) return;
                Location g = army.gateCentre();
                if (g == null || !m.getWorld().equals(g.getWorld())) { poof(m); army.forget(this); return; }
                if (m.getLocation().distance(g) < 1.5) { poof(m); army.forget(this); }
                else walk(m, g, walkSpeed);
            }
        }
    }

    private void combat(int t, Mob m) {
        Player target = resolveTarget();
        if (target == null || !m.getWorld().equals(target.getWorld())) { state = State.RETURNING; return; }
        double d = m.getLocation().distance(target.getLocation());
        if (d > maxChase) { state = State.RETURNING; return; }

        if ((t + index) % combatInt == 0) {
            if (d > reach) walk(m, target.getLocation(), chaseSpeed);
            else m.getPathfinder().stopPathfinding();
        }
        if (d <= reach && t >= nextAttack) {
            nextAttack = t + cooldown;
            m.setRotation(BotArmy.faceToward(m.getLocation(), target.getLocation()), 0f);
            m.swingMainHand();
            target.damage(damage, m);
            m.getWorld().playSound(m.getLocation(), "entity.player.attack.strong", 1f, 1f);
        }
    }

    private Player resolveTarget() {
        if (targetId == null) return null;
        Player p = Bukkit.getPlayer(targetId);
        if (p == null || !p.isOnline() || p.isDead()) return null;
        if (p.getGameMode() == GameMode.SPECTATOR) return null;
        if (army.isAlly(p.getUniqueId())) return null;
        return p;
    }

    private void hold(Mob m) {
        m.getPathfinder().stopPathfinding();
        m.setAware(false);
        if (slot != null) m.setRotation(slot.getYaw(), 0f);
    }

    private void walk(Mob m, Location to, double speed) {
        if (!m.getWorld().equals(to.getWorld())) return;
        if (!m.isAware()) m.setAware(true);
        m.getPathfinder().moveTo(to, speed);
    }

    private void poof(Mob m) {
        m.getWorld().spawnParticle(Particle.PORTAL, m.getLocation().add(0, 1, 0), 60, 0.3, 0.9, 0.3, 0.5);
        m.getWorld().playSound(m.getLocation(), "entity.enderman.teleport", 0.8f, 1.2f);
        destroy();
    }

    void destroy() {
        Mob m = mob();
        if (m != null) { army.removeFromTeam(m); m.remove(); }
        entityId = null;
    }
}
