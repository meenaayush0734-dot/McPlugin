package com.survivalplus;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class SurvivalPlus extends JavaPlugin implements Listener {

    private static final String TAG = "survivalplus_display";
    private static final String NETA_PREFIX = "\u00a7cNeta \u00a7r";
    private static final String SB_PREFIX = "sp_";
    private static final String DISCORD_DEFAULT = "https://discord.gg/ccYUmyTPsc";
    private static final Pattern TEAM_NAME = Pattern.compile("[A-Za-z0-9_]{3,12}");
    private static final Pattern HOME_NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final long INVITE_TTL_MS = 60_000L;

    // ---- player state ----
    private final Set<UUID> viewers = new HashSet<>();      // /indicator ON
    private final Set<UUID> nvPlayers = new HashSet<>();    // /nightvision ON
    private final Set<UUID> netas = new HashSet<>();        // Neta rank

    // ---- homes ----
    private final Map<UUID, Map<String, Home>> homes = new HashMap<>();
    private final Map<UUID, Long> lastHome = new HashMap<>();

    // ---- teams ----
    private final Map<String, TeamData> teams = new LinkedHashMap<>(); // key = lowercase name
    private final Map<UUID, String> playerTeam = new HashMap<>();      // player -> team key
    private final Map<UUID, Invite> invites = new HashMap<>();

    // ---- HUD ----
    private final Map<String, Holder> holders = new HashMap<>();

    // ---- settings (config.yml) ----
    private int maxHomes, netaMaxHomes, teamMaxHomes;
    private long cooldownMs;
    private boolean showPlayerHearts, showMobHearts, showHotbar, onlyDamaged;
    private double radius;
    private int period;
    private float viewRange, playerHeartsY, hotbarY, mobHeartsY;

    private File dataFile, playersFile;
    private BukkitTask hudTask, nvTask;

    // =====================================================================
    //  Lifecycle
    // =====================================================================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        playersFile = new File(getDataFolder(), "players.yml");

        reloadSettings();
        loadData();
        loadPlayers();
        cleanupStray();

        getServer().getPluginManager().registerEvents(this, this);
        hudTask = Bukkit.getScheduler().runTaskTimer(this, this::hudTick, 20L, period);
        nvTask = Bukkit.getScheduler().runTaskTimer(this, this::nightVisionTick, 20L, 40L);

        for (Player p : Bukkit.getOnlinePlayers()) refreshPlayer(p);
        getLogger().info("SurvivalPlus enabled.");
    }

    @Override
    public void onDisable() {
        if (hudTask != null) hudTask.cancel();
        if (nvTask != null) nvTask.cancel();
        removeAllHolders();
        cleanupStray();
        saveData();
        savePlayers();
    }

    private void reloadSettings() {
        var c = getConfig();
        maxHomes = c.getInt("max-homes", 3);
        netaMaxHomes = c.getInt("neta-max-homes", 5);
        teamMaxHomes = c.getInt("team-max-homes", 3);
        cooldownMs = c.getLong("home-cooldown-minutes", 10L) * 60_000L;

        showPlayerHearts = c.getBoolean("show-player-hearts", true);
        showMobHearts = c.getBoolean("show-mob-hearts", true);
        showHotbar = c.getBoolean("show-player-hotbar", true);
        onlyDamaged = c.getBoolean("mob-hearts-only-when-damaged", false);
        radius = c.getDouble("radius", 24.0);
        period = Math.max(1, c.getInt("update-ticks", 2));
        viewRange = (float) c.getDouble("view-range", 0.5);
        playerHeartsY = (float) c.getDouble("player-hearts-height", 0.8);
        hotbarY = (float) c.getDouble("player-hotbar-height", 1.15);
        mobHeartsY = (float) c.getDouble("mob-hearts-height", 0.45);
    }

    // =====================================================================
    //  Persistence
    // =====================================================================

    private void loadData() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection hs = y.getConfigurationSection("homes");
        if (hs != null) {
            for (String uid : hs.getKeys(false)) {
                UUID u = parseUuid(uid);
                ConfigurationSection sec = hs.getConfigurationSection(uid);
                if (u == null || sec == null) continue;
                Map<String, Home> m = new LinkedHashMap<>();
                for (String hn : sec.getKeys(false)) {
                    Home h = readHome(sec.getConfigurationSection(hn));
                    if (h != null) m.put(hn, h);
                }
                homes.put(u, m);
            }
        }

        ConfigurationSection lh = y.getConfigurationSection("last-home");
        if (lh != null) {
            for (String uid : lh.getKeys(false)) {
                UUID u = parseUuid(uid);
                if (u != null) lastHome.put(u, lh.getLong(uid));
            }
        }

        ConfigurationSection ts = y.getConfigurationSection("teams");
        if (ts != null) {
            for (String key : ts.getKeys(false)) {
                ConfigurationSection t = ts.getConfigurationSection(key);
                if (t == null) continue;
                TeamData td = new TeamData(t.getString("name", key), t.getString("owner", ""));
                try {
                    td.color = ChatColor.valueOf(t.getString("color", "WHITE"));
                } catch (IllegalArgumentException ex) {
                    td.color = ChatColor.WHITE;
                }
                td.admins.addAll(t.getStringList("admins"));
                td.members.addAll(t.getStringList("members"));
                td.allies.addAll(t.getStringList("allies"));
                ConfigurationSection th = t.getConfigurationSection("homes");
                if (th != null) {
                    for (String hn : th.getKeys(false)) {
                        Home h = readHome(th.getConfigurationSection(hn));
                        if (h != null) td.homes.put(hn, h);
                    }
                }
                List<?> raw = t.getList("chest");
                if (raw != null) {
                    td.chest = new ItemStack[54];
                    for (int i = 0; i < Math.min(raw.size(), 54); i++) {
                        Object o = raw.get(i);
                        if (o instanceof ItemStack is) td.chest[i] = is;
                    }
                }
                teams.put(td.key, td);
                for (String m : td.members) {
                    UUID u = parseUuid(m);
                    if (u != null) playerTeam.put(u, td.key);
                }
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Home>> e : homes.entrySet()) {
            for (Map.Entry<String, Home> h : e.getValue().entrySet()) {
                writeHome(y, "homes." + e.getKey() + "." + h.getKey(), h.getValue());
            }
        }
        for (Map.Entry<UUID, Long> e : lastHome.entrySet()) {
            y.set("last-home." + e.getKey(), e.getValue());
        }
        for (TeamData t : teams.values()) {
            String base = "teams." + t.key;
            y.set(base + ".name", t.name);
            y.set(base + ".owner", t.owner);
            y.set(base + ".color", t.color.name());
            y.set(base + ".admins", new ArrayList<>(t.admins));
            y.set(base + ".members", new ArrayList<>(t.members));
            y.set(base + ".allies", new ArrayList<>(t.allies));
            for (Map.Entry<String, Home> h : t.homes.entrySet()) {
                writeHome(y, base + ".homes." + h.getKey(), h.getValue());
            }
            ItemStack[] snap = t.snapshot();
            if (snap != null) y.set(base + ".chest", Arrays.asList(snap));
        }
        try {
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("data.yml save nahi hui: " + ex.getMessage());
        }
    }

    private void loadPlayers() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(playersFile);
        readUuids(viewers, y.getStringList("viewers"));
        readUuids(nvPlayers, y.getStringList("nightvision"));
        readUuids(netas, y.getStringList("netas"));
    }

    private void savePlayers() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("viewers", uuidStrings(viewers));
        y.set("nightvision", uuidStrings(nvPlayers));
        y.set("netas", uuidStrings(netas));
        try {
            y.save(playersFile);
        } catch (IOException ex) {
            getLogger().severe("players.yml save nahi hui: " + ex.getMessage());
        }
    }

    private static void readUuids(Set<UUID> target, List<String> raw) {
        for (String s : raw) {
            UUID u = parseUuid(s);
            if (u != null) target.add(u);
        }
    }

    private static List<String> uuidStrings(Set<UUID> set) {
        List<String> out = new ArrayList<>();
        for (UUID u : set) out.add(u.toString());
        return out;
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    private static void writeHome(YamlConfiguration y, String path, Home h) {
        y.set(path + ".world", h.world());
        y.set(path + ".x", h.x());
        y.set(path + ".y", h.y());
        y.set(path + ".z", h.z());
        y.set(path + ".yaw", (double) h.yaw());
        y.set(path + ".pitch", (double) h.pitch());
    }

    private static Home readHome(ConfigurationSection s) {
        if (s == null) return null;
        String world = s.getString("world");
        if (world == null) return null;
        return new Home(world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    // =====================================================================
    //  Commands
    // =====================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        if (name.equals("neta")) { handleNeta(sender, args); return true; }
        if (name.equals("discordlink")) { handleDiscordLink(sender, args); return true; }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Ye command sirf players ke liye hai.");
            return true;
        }

        switch (name) {
            case "sethome" -> setHome(p, args);
            case "home" -> goHome(p, args);
            case "delhome" -> delHome(p, args);
            case "homes" -> listHomes(p);
            case "indicator" -> cmdIndicator(p, args);
            case "nightvision" -> cmdNightVision(p, args);
            case "ec" -> cmdEc(p);
            case "createteam" -> createTeam(p, args);
            case "team" -> handleTeam(p, args);
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (name.equals("team")) {
            if (args.length == 1) {
                for (String s : List.of("help", "info", "invite", "accept", "leave", "kick", "disband",
                        "color", "admin", "enderchest", "sethome", "delhome", "ally")) {
                    if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
                }
            } else if (args.length == 2) {
                String sub = args[0].toLowerCase(Locale.ROOT);
                if (sub.equals("color")) {
                    for (ChatColor c : ChatColor.values()) if (c.isColor()) out.add(c.name().toLowerCase(Locale.ROOT));
                } else if (sub.equals("invite") || sub.equals("kick") || sub.equals("admin")) {
                    for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
                }
            }
        } else if (name.equals("home") || name.equals("delhome")) {
            if (sender instanceof Player p && args.length == 1) {
                out.addAll(homes.getOrDefault(p.getUniqueId(), Map.of()).keySet());
            }
        } else if (name.equals("neta") && args.length == 1) {
            for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
        }
        return out;
    }

    // ---------------- Homes ----------------

    private int maxHomesFor(Player p) {
        return netas.contains(p.getUniqueId()) ? netaMaxHomes : maxHomes;
    }

    private void setHome(Player p, String[] args) {
        String name = args.length > 0 ? args[0] : "home";
        if (!HOME_NAME.matcher(name).matches()) {
            p.sendMessage("\u00a7c[Home] Home name sirf letters, numbers, _ ya - (max 16) ho sakta hai.");
            return;
        }
        Map<String, Home> m = homes.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
        boolean bypass = p.hasPermission("survivalplus.bypass");
        int limit = bypass ? Integer.MAX_VALUE : maxHomesFor(p);
        if (!m.containsKey(name) && m.size() >= limit) {
            p.sendMessage("\u00a7c[Home] Tum sirf " + limit + " homes set kar sakte ho! Pehle /delhome <name> se ek delete karo.");
            return;
        }
        m.put(name, Home.of(p.getLocation()));
        saveData();
        p.sendMessage("\u00a7e[Home] Home '" + name + "' set ho gaya! (" + m.size() + "/" + (bypass ? "unlimited" : String.valueOf(limit)) + ")");
    }

    private void goHome(Player p, String[] args) {
        UUID id = p.getUniqueId();
        Map<String, Home> mine = homes.getOrDefault(id, Map.of());
        TeamData team = teamOf(id);

        if (args.length == 0) {
            listHomes(p);
            return;
        }
        String name = args[0];
        Home h = mine.get(name);
        if (h == null && team != null) h = team.homes.get(name);
        if (h == null) {
            p.sendMessage("\u00a7c[Home] Home '" + name + "' nahi mila. Homes: " + allHomeNames(p));
            return;
        }

        boolean bypass = p.hasPermission("survivalplus.bypass");
        long now = System.currentTimeMillis();
        if (!bypass) {
            Long last = lastHome.get(id);
            if (last != null && now - last < cooldownMs) {
                p.sendMessage("\u00a7c[Home] Abhi /home use nahi kar sakte. " + formatDuration(cooldownMs - (now - last)) + " baad try karo.");
                return;
            }
        }

        World w = Bukkit.getWorld(h.world());
        if (w == null) {
            p.sendMessage("\u00a7c[Home] Us home ki world load nahi hai.");
            return;
        }
        p.teleport(h.toLocation(w));
        if (!bypass) {
            lastHome.put(id, now);
            saveData();
        }
        p.sendMessage("\u00a7a[Home] Home '" + name + "' pe teleport ho gaye!");
    }

    private void delHome(Player p, String[] args) {
        if (args.length == 0) {
            p.sendMessage("\u00a7c[Home] Use: /delhome <name>");
            return;
        }
        Map<String, Home> m = homes.get(p.getUniqueId());
        if (m == null || m.remove(args[0]) == null) {
            p.sendMessage("\u00a7c[Home] Home '" + args[0] + "' nahi mila.");
            return;
        }
        saveData();
        p.sendMessage("\u00a7e[Home] Home '" + args[0] + "' delete ho gaya.");
    }

    private void listHomes(Player p) {
        UUID id = p.getUniqueId();
        boolean bypass = p.hasPermission("survivalplus.bypass");
        Map<String, Home> mine = homes.getOrDefault(id, Map.of());
        TeamData team = teamOf(id);

        p.sendMessage("\u00a76[Homes] \u00a7ePersonal (" + mine.size() + "/" + (bypass ? "unlimited" : String.valueOf(maxHomesFor(p))) + "): \u00a7f"
                + (mine.isEmpty() ? "koi nahi" : String.join(", ", mine.keySet())));
        if (team != null) {
            p.sendMessage("\u00a76[Homes] \u00a7eTeam " + team.color + team.name + "\u00a7e (" + team.homes.size() + "/" + teamMaxHomes + "): \u00a7f"
                    + (team.homes.isEmpty() ? "koi nahi" : String.join(", ", team.homes.keySet())));
        }
        if (bypass) {
            p.sendMessage("\u00a7a[Home] Aapke paas bypass hai, /home ka cooldown nahi lagta.");
        } else {
            Long last = lastHome.get(id);
            long now = System.currentTimeMillis();
            if (last == null || now - last >= cooldownMs) {
                p.sendMessage("\u00a7a[Home] /home ab use kar sakte ho.");
            } else {
                p.sendMessage("\u00a7c[Home] Agla /home: " + formatDuration(cooldownMs - (now - last)) + " baad.");
            }
        }
    }

    private String allHomeNames(Player p) {
        List<String> names = new ArrayList<>(homes.getOrDefault(p.getUniqueId(), Map.of()).keySet());
        TeamData t = teamOf(p.getUniqueId());
        if (t != null) names.addAll(t.homes.keySet());
        return names.isEmpty() ? "koi nahi" : String.join(", ", names);
    }

    private static String formatDuration(long ms) {
        long totalSec = Math.max(0, (ms + 999) / 1000);
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return min + " min " + sec + " sec";
    }

    // ---------------- Indicator / NightVision / EC ----------------

    private void cmdIndicator(Player p, String[] args) {
        Boolean on = parseSwitch(args);
        if (on == null) {
            p.sendMessage("\u00a7c[Indicator] Use: /indicator true  ya  /indicator false");
            return;
        }
        if (on) {
            viewers.add(p.getUniqueId());
            p.sendMessage("\u00a7a[Indicator] ON \u2192 \u00a77Ab tumhe hearts aur hotbar dikhenge.");
        } else {
            viewers.remove(p.getUniqueId());
            p.sendMessage("\u00a7c[Indicator] OFF \u2192 \u00a77Ab kuch nahi dikhega.");
        }
        savePlayers();
    }

    pr
