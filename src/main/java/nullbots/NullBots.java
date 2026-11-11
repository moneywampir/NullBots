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
                if (t == null) { sender.sendMessage("§cSpieler nicht online: " + args[
