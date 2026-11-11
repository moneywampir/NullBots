package nullbots;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class NullBots extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private NamespacedKey botKey;
    private BotArmy army;

    public NamespacedKey botKey() { return botKey; }

    @Override
    public void onEnable() {
        botKey = new NamespacedKey(this, "null_bot");
        writeDefaultConfig();
        purgeOrphans();

        army = new BotArmy(this);
        army.loadAllies();
        army.start();

        PluginCommand cmd = getCommand("null");
        if (cmd == null) getLogger().severe("Befehl 'null' nicht registriert - plugin.yml pruefen!");
        else { cmd.setExecutor(this); cmd.setTabCompleter(this); }

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("NullBots aktiv. /null help");
    }

    @Override
    public void onDisable() { if (army != null) army.shutdown(); }

    private void writeDefaultConfig() {
        if (new File(getDataFolder(), "config.yml").exists()) return;
        var c = getConfig();
        c.set("max-bots", 50);
        c.set("bot.base-entity", "VINDICATOR");
        c.set("bot.name-length", 12);
        c.set("bot.name-color", "&d");
        c.set("bot.armor-color", "#7A2BE2");
        c.set("bot.sword", "NETHERITE_SWORD");
        c.set("bot.silent", true);
        c.set("bot.walk-speed", 0.30);
        c.set("bot.chase-speed", 0.42);
        c.set("formation.type", "PATH");
        c.set("formation.spacing", 1.6);
        c.set("formation.radius", 8.0);
        c.set("combat.reach", 3.0);
        c.set("combat.damage", 4.0);
        c.set("combat.attack-cooldown-ticks", 12);
        c.set("combat.max-chase-distance", 64.0);
        c.set("portal.enabled", true);
        c.set("portal.distance", 7.0);
        c.set("portal.emerge-delay-ticks", 6);
        c.set("portal.despawn-stagger-ticks", 4);
        c.set("portal.close-delay-ticks", 40);
        c.set("portal.block-travel", true);
        c.set("tick.move-interval", 5);
        c.set("tick.combat-interval", 4);
        c.set("allies", new ArrayList<String>());
        saveConfig();
    }

    private void purgeOrphans() {
        int n = 0;
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntities())
                if (e.getPersistentDataContainer().has(botKey, PersistentDataType.BYTE)) { e.remove(); n++; }
        if (n > 0) getLogger().warning(n + " verwaiste Bots vom letzten Lauf entfernt.");
    }

    // ================= Befehle =================
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("nullbots.use")) { sender.sendMessage("§cDazu hast du keine Rechte."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("bot")) args = Arrays.copyOfRange(args, 1, args.length);
        if (args.length == 0) { help(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "help" -> help(sender);
            case "spawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cNur im Spiel."); return true; }
                int n = intArg(args, 1, -1);
                if (n < 1) { sender.sendMessage("§cNutze: /null spawn <1-50>"); return true; }
                sender.sendMessage(army.spawn(p, n));
            }
            case "despawn" -> {
                int n = (args.length < 2 || args[1].equalsIgnoreCase("all")) ? -1 : intArg(args, 1, -1);
                sender.sendMessage(army.despawn(n));
            }
            case "formation", "form" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cNutze: /null formation <grid|circle|path|split> [abstand] [radius]");
                    return true;
                }
                sender.sendMessage(army.formation(args[1], doubleArg(args, 2), doubleArg(args, 3)));
            }
            case "fight", "attack" -> {
                if (args.length < 3) { sender.sendMessage("§cNutze: /null fight <anzahl|all> <spieler>"); return true; }
                Player t = Bukkit.getPlayerExact(args[2]);
                if (t == null) { sender.sendMessage("§cSpieler nicht online: " + args[2]); return true; }
                sender.sendMessage(army.fight(args[1], t));
            }
            case "stop" -> {
                int n = (args.length < 2 || args[1].equalsIgnoreCase("all")) ? -1 : intArg(args, 1, -1);
                sender.sendMessage(army.stop(n));
            }
            case "ally" -> ally(sender, args);
            case "anchor" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cNur im Spiel."); return true; }
                sender.sendMessage(army.anchor(p.getLocation()));
            }
            case "list", "status" -> {
                sender.sendMessage("§8--- §dNullBots §8---");
                sender.sendMessage("§7Bots: §f" + army.count() + " §7| Kaempfen: §f" + army.fighting());
                sender.sendMessage("§7Formation: §f" + army.formationName());
                sender.sendMessage("§7Allies: §f" + army.allies().size());
            }
            case "reload" -> {
                reloadConfig();
                army.reload();
                army.loadAllies();
                sender.sendMessage("§aConfig neu geladen. (Bot-Aussehen gilt erst fuer neue Bots)");
            }
            default -> sender.sendMessage("§cUnbekannt. /null help");
        }
        return true;
    }

    private void ally(CommandSender s, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            s.sendMessage("§7Allies (" + army.allies().size() + "):");
            for (UUID id : army.allies()) {
                String n = Bukkit.getOfflinePlayer(id).getName();
                s.sendMessage("§7 - §f" + (n == null ? id.toString() : n));
            }
            return;
        }
        if (args.length < 3) { s.sendMessage("§cNutze: /null ally <add|remove|list> <spieler>"); return; }

        OfflinePlayer op = Bukkit.getPlayerExact(args[2]);
        if (op == null) op = Bukkit.getOfflinePlayerIfCached(args[2]);
        if (op == null) { s.sendMessage("§cUnbekannt - Spieler muss schon mal online gewesen sein."); return; }

        if (args[1].equalsIgnoreCase("add")) {
            s.sendMessage(army.addAlly(op.getUniqueId())
                    ? "§a" + op.getName() + " ist jetzt Ally. Bots greifen ihn nicht an."
                    : "§c" + op.getName() + " war schon Ally.");
        } else if (args[1].equalsIgnoreCase("remove")) {
            s.sendMessage(army.removeAlly(op.getUniqueId())
                    ? "§a" + op.getName() + " ist kein Ally mehr."
                    : "§c" + op.getName() + " war kein Ally.");
        } else s.sendMessage("§cNutze: add, remove oder list");
    }

    private void help(CommandSender s) {
        s.sendMessage("§8----- §d§lNULL BOTS §8-----");
        s.sendMessage("§d/null spawn <1-50> §7- Bots kommen aus dem Portal");
        s.sendMessage("§d/null despawn [anzahl|all] §7- zurueck ins Portal");
        s.sendMessage("§d/null formation <grid|circle|path|split> [abstand] [radius]");
        s.sendMessage("§d/null fight <anzahl|all> <spieler> §7- angreifen");
        s.sendMessage("§d/null stop [anzahl|all] §7- zurueck in die Formation");
        s.sendMessage("§d/null ally <add|remove|list> [spieler]");
        s.sendMessage("§d/null anchor §7- Formation auf deine Position");
        s.sendMessage("§d/null list §7- Status");
        s.sendMessage("§8Tipp: /null bot spawn 20 funktioniert auch.");
    }

    private static int intArg(String[] a, int i, int def) {
        if (a.length <= i) return def;
        try { return Integer.parseInt(a[i]); } catch (Exception ex) { return def; }
    }

    private static Double doubleArg(String[] a, int i) {
        if (a.length <= i) return null;
        try { return Double.parseDouble(a[i]); } catch (Exception ex) { return null; }
    }

    // ================= Tab-Vervollstaendigung =================
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("nullbots.use")) return List.of();
        if (args.length > 0 && args[0].equalsIgnoreCase("bot")) args = Arrays.copyOfRange(args, 1, args.length);
        if (args.length <= 1)
            return filter(List.of("spawn", "despawn", "formation", "fight", "stop", "ally", "anchor", "list", "reload", "help"),
                    args.length == 0 ? "" : args[0]);

        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            switch (sub) {
                case "formation": return filter(List.of("grid", "circle", "path", "split"), args[1]);
                case "fight": return filter(List.of("all", "5", "10", "20"), args[1]);
                case "stop": case "despawn": return filter(List.of("all", "5", "10"), args[1]);
                case "ally": return filter(List.of("add", "remove", "list"), args[1]);
                case "spawn": return filter(List.of("1", "10", "20", "50"), args[1]);
                default: return List.of();
            }
        }
        if (args.length == 3 && (sub.equals("fight") || (sub.equals("ally") && !args[1].equalsIgnoreCase("list")))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> opts, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase().startsWith(prefix.toLowerCase())) out.add(o);
        return out;
    }

    // ================= Schutz: Bots sind unsterblich =================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) { if (army.isBot(e.getEntity())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBotHit(EntityDamageByEntityEvent e) {
        if (!army.isBot(e.getDamager())) return;
        Bot b = army.byEntity(e.getDamager().getUniqueId());
        if (b == null || !b.isAssignedTarget(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (!army.isBot(e.getEntity())) return;
        Entity t = e.getTarget();
        if (t == null) return;
        Bot b = army.byEntity(e.getEntity().getUniqueId());
        if (b == null || !b.isAssignedTarget(t)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) { if (army.isBot(e.getEntity())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) { if (army.isBot(e.getEntity())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDoor(EntityBreakDoorEvent e) { if (army.isBot(e.getEntity())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent e) { if (army.isBot(e.getEntity())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (army.blockTravel() && army.isGatePortal(e.getFrom())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!army.isBot(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Bot b = army.byEntity(e.getEntity().getUniqueId());
        if (b != null) army.forget(b);
    }
}
