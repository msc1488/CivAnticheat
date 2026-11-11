package ru.depervs.anticheatai;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiCheatAI extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Set<UUID> training = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alerts = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingKick = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("ac")).setExecutor(this);
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, Math.max(1L, getConfig().getLong("training.sample-interval-ticks", 2)));
        getLogger().info("AntiCheatAI enabled. Training/anomaly analysis is active.");
    }

    @Override public void onDisable() {
        if (task != null) task.cancel();
        for (UUID id : training) saveSession(id);
        for (Player viewer : Bukkit.getOnlinePlayers()) for (Player target : Bukkit.getOnlinePlayers()) if (!viewer.equals(target)) viewer.showPlayer(this, target);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerState s = states.computeIfAbsent(p.getUniqueId(), k -> new PlayerState(p.getUniqueId()));
            s.update(p);
            if (training.contains(p.getUniqueId())) s.record(p);
            if (getConfig().getBoolean("detection.enabled", true) && !p.hasPermission("anticheatai.bypass")) analyze(p, s);
        }
        if (getConfig().getBoolean("visual-mask.enabled", true)) updateMasking();
    }

    private void analyze(Player p, PlayerState s) {
        double deltaYaw = Math.abs(wrapDegrees(p.getLocation().getYaw() - s.lastYaw));
        double deltaPitch = Math.abs(p.getLocation().getPitch() - s.lastPitch);
        if (deltaYaw >= getConfig().getDouble("detection.snap-yaw-deg", 75.0) && deltaPitch >= 2.0) s.add("AimSnap", 1.2, "large camera snap");
        if (deltaPitch >= getConfig().getDouble("detection.snap-pitch-deg", 55.0)) s.add("AimSnap", 0.7, "large pitch change");

        long now = System.currentTimeMillis();
        while (!s.attacks.isEmpty() && now - s.attacks.peekFirst() > getConfig().getLong("detection.max-cps-window-ms", 1000)) s.attacks.removeFirst();
        if (s.attacks.size() > getConfig().getInt("detection.max-cps", 18)) s.add("AutoClick", 1.5, "high attack rate");

        if (s.lastAttackTarget != null && s.lastAttackTime > 0) {
            double reach = p.getEyeLocation().distance(s.lastAttackTarget.getLocation());
            if (reach > getConfig().getDouble("detection.max-reach", 3.15) + 0.15) s.add("Reach", 2.0, String.format(Locale.US, "distance %.2f", reach));
        }

        // Adaptive AI: compare current rotation/movement against the player's own learned baseline.
        if (getConfig().getBoolean("ai.enabled", true) && s.baseline.samples >= getConfig().getInt("ai.baseline-min-samples", 200)) {
            double zYaw = s.baseline.z(deltaYaw, s.baseline.meanYaw, s.baseline.sdYaw);
            double zSpeed = s.baseline.z(s.speed, s.baseline.meanSpeed, s.baseline.sdSpeed);
            double threshold = getConfig().getDouble("ai.z-score-threshold", 3.0);
            if (Math.abs(zYaw) > threshold) s.add("AI-Rotation", 0.5, "outside personal baseline");
            if (Math.abs(zSpeed) > threshold) s.add("AI-Movement", 0.4, "outside personal baseline");
        }

        if (s.score >= getConfig().getDouble("detection.alert-threshold", 8.0)) alert(p, s);
        if (s.score >= getConfig().getDouble("detection.kick-threshold", 35.0)) scheduleKick(p, s);
        s.decay(getConfig().getDouble("detection.decay-per-second", 0.15) * 0.05);
    }

    private void updateMasking() {
        double threshold = getConfig().getDouble("visual-mask.score-threshold", 22.0);
        double maxDistance = getConfig().getDouble("visual-mask.max-distance", 64.0);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            PlayerState vs = states.get(viewer.getUniqueId());
            if (vs == null || vs.score < threshold || viewer.hasPermission("anticheatai.bypass")) {
                for (Player target : Bukkit.getOnlinePlayers()) if (!viewer.equals(target)) viewer.showPlayer(this, target);
                continue;
            }
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(target) || !viewer.getWorld().equals(target.getWorld())) continue;
                if (viewer.getLocation().distanceSquared(target.getLocation()) > maxDistance * maxDistance) continue;
                boolean visible = canSeeAnyPart(viewer, target);
                if (visible) viewer.showPlayer(this, target); else viewer.hidePlayer(this, target);
            }
        }
    }

    private boolean canSeeAnyPart(Player viewer, Player target) {
        Location start = viewer.getEyeLocation();
        Location base = target.getLocation();
        Location[] points = {target.getEyeLocation(), base.clone().add(0, 1.5, 0), base.clone().add(0, 1.1, 0), base.clone().add(0, .65, 0), base.clone().add(0, .15, 0)};
        for (Location end : points) {
            Vector d = end.toVector().subtract(start.toVector());
            double distance = d.length();
            if (distance <= .01) return true;
            var hit = start.getWorld().rayTraceBlocks(start, d.normalize(), distance, org.bukkit.FluidCollisionMode.NEVER, false);
            if (hit == null) return true;
        }
        return false;
    }

    private void alert(Player p, PlayerState s) {
        if (!getConfig().getBoolean("alerts.enabled", true)) return;
        if (!alerts.contains(p.getUniqueId())) return;
        String msg = ChatColor.RED + "[AntiCheatAI] " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + " suspicious: " + ChatColor.YELLOW + s.topCheck() + ChatColor.GRAY + " | VL " + ChatColor.RED + String.format(Locale.US, "%.1f", s.score);
        for (Player admin : Bukkit.getOnlinePlayers()) if (admin.hasPermission("anticheatai.admin")) admin.sendMessage(msg);
        if (getConfig().getBoolean("alerts.console", true)) getLogger().warning(ChatColor.stripColor(msg));
    }

    private void scheduleKick(Player p, PlayerState s) {
        if (!pendingKick.add(p.getUniqueId())) return;
        int delay = getConfig().getInt("detection.kick-delay-seconds", 3);
        new BukkitRunnable() {
            int left = delay;
            @Override public void run() {
                if (!p.isOnline()) { pendingKick.remove(p.getUniqueId()); cancel(); return; }
                if (left <= 0) {
                    p.kickPlayer(ChatColor.DARK_RED + "§lANTI-CHEAT\n\n" + ChatColor.RED + "Cheat-like activity detected\n" + ChatColor.GRAY + "Check: " + ChatColor.WHITE + s.topCheck() + "\n" + ChatColor.DARK_GRAY + "Code: AC-" + p.getUniqueId().toString().substring(0, 6).toUpperCase(Locale.ROOT));
                    pendingKick.remove(p.getUniqueId());
                    cancel();
                    return;
                }
                p.sendTitle(ChatColor.RED + "§l⚠ ANTI-CHEAT", ChatColor.GRAY + "Analysis in progress... " + ChatColor.YELLOW + left, 0, 15, 0);
                p.sendActionBar(ChatColor.RED + "Suspicious activity detected " + ChatColor.DARK_GRAY + "• " + ChatColor.GRAY + "disconnect in " + ChatColor.YELLOW + left);
                left--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    @EventHandler public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        PlayerState s = states.computeIfAbsent(p.getUniqueId(), k -> new PlayerState(p.getUniqueId()));
        s.attacks.addLast(System.currentTimeMillis());
        if (e.getEntity() instanceof Player target) {
            s.lastAttackTarget = target;
            s.lastAttackTime = System.currentTimeMillis();
            if (!canSeeAnyPart(p, target)) s.wallEvents++;
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { if (training.remove(e.getPlayer().getUniqueId())) saveSession(e.getPlayer().getUniqueId()); states.remove(e.getPlayer().getUniqueId()); pendingKick.remove(e.getPlayer().getUniqueId()); }

    private double wrapDegrees(float d) { d %= 360f; if (d > 180) d -= 360; if (d < -180) d += 360; return d; }

    private void saveSession(UUID id) {
        PlayerState s = states.get(id); if (s == null || s.samples.isEmpty()) return;
        File dir = new File(getDataFolder(), "training"); dir.mkdirs();
        File f = new File(dir, id + "-" + System.currentTimeMillis() + ".csv");
        try (FileWriter w = new FileWriter(f, StandardCharsets.UTF_8)) {
            w.write("time,x,y,z,yaw,pitch,speed,score,wallEvents\n");
            for (String row : s.samples) w.write(row + "\n");
        } catch (IOException ex) { getLogger().warning("Could not save training session: " + ex.getMessage()); }
        s.samples.clear();
    }

    private void status(CommandSender sender, Player p) {
        PlayerState s = states.get(p.getUniqueId());
        if (s == null) { sender.sendMessage(ChatColor.RED + "No data for " + p.getName()); return; }
        sender.sendMessage(ChatColor.DARK_AQUA + "══ AntiCheatAI: " + p.getName() + " ══");
        sender.sendMessage(ChatColor.GRAY + "Score: " + ChatColor.YELLOW + String.format(Locale.US, "%.2f", s.score));
        sender.sendMessage(ChatColor.GRAY + "Top check: " + ChatColor.RED + s.topCheck());
        sender.sendMessage(ChatColor.GRAY + "Attacks/sec window: " + ChatColor.WHITE + s.attacks.size());
        sender.sendMessage(ChatColor.GRAY + "Wall events: " + ChatColor.WHITE + s.wallEvents);
        sender.sendMessage(ChatColor.GRAY + "Training: " + (training.contains(p.getUniqueId()) ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
        sender.sendMessage(ChatColor.GRAY + "Baseline samples: " + ChatColor.WHITE + s.baseline.samples);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!sender.hasPermission("anticheatai.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
        if (a.length == 0) { sender.sendMessage(ChatColor.AQUA + "/ac train <player> [legit|cheat]" + ChatColor.GRAY + " - record gameplay"); sender.sendMessage(ChatColor.AQUA + "/ac watch <player>" + ChatColor.GRAY + " - start live monitoring"); sender.sendMessage(ChatColor.AQUA + "/ac status <player>" + ChatColor.GRAY + " - live AI status"); sender.sendMessage(ChatColor.AQUA + "/ac report <player>" + ChatColor.GRAY + " - detailed report"); sender.sendMessage(ChatColor.AQUA + "/ac alerts" + ChatColor.GRAY + " - toggle alerts"); sender.sendMessage(ChatColor.AQUA + "/ac reset <player>" + ChatColor.GRAY + " - reset score"); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "train", "watch" -> {
                if (a.length < 2) { sender.sendMessage(ChatColor.RED + "Use: /ac " + a[0] + " <player>"); return true; }
                if (a[1].equalsIgnoreCase("stop")) { for (UUID id : new HashSet<>(training)) saveSession(id); training.clear(); sender.sendMessage(ChatColor.GREEN + "Training stopped."); return true; }
                Player p = Bukkit.getPlayerExact(a[1]); if (p == null) { sender.sendMessage(ChatColor.RED + "Player not found."); return true; }
                training.add(p.getUniqueId());
                if (a[0].equalsIgnoreCase("train")) p.sendMessage(ChatColor.GREEN + "AntiCheatAI training mode enabled. Play normally; movement, camera and combat telemetry are being recorded.");
                sender.sendMessage(ChatColor.GREEN + "Monitoring " + p.getName() + " in real time.");
            }
            case "status" -> { if (a.length < 2) return true; Player p = Bukkit.getPlayerExact(a[1]); if (p != null) status(sender, p); }
            case "report" -> { if (a.length < 2) return true; Player p = Bukkit.getPlayerExact(a[1]); if (p != null) { status(sender, p); PlayerState s = states.get(p.getUniqueId()); sender.sendMessage(ChatColor.GRAY + "Checks: " + s.checks); sender.sendMessage(ChatColor.GRAY + "Recorded samples: " + s.samples.size()); } }
            case "alerts" -> { UUID id = sender instanceof Player pl ? pl.getUniqueId() : null; if (id != null && !alerts.add(id)) alerts.remove(id); sender.sendMessage(ChatColor.GREEN + "Alerts: " + (id != null && alerts.contains(id) ? "ON" : "OFF")); }
            case "reset" -> { if (a.length < 2) return true; Player p = Bukkit.getPlayerExact(a[1]); if (p != null) { PlayerState s = states.get(p.getUniqueId()); if (s != null) s.reset(); sender.sendMessage(ChatColor.GREEN + "Reset " + p.getName()); } }
            case "reload" -> { reloadConfig(); sender.sendMessage(ChatColor.GREEN + "AntiCheatAI config reloaded."); }
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
        }
        return true;
    }

    private final class PlayerState {
        final UUID id; float lastYaw, lastPitch; double speed; double score; int wallEvents; long lastAttackTime; Player lastAttackTarget;
        final Deque<Long> attacks = new ArrayDeque<>(); final List<String> samples = new ArrayList<>(); final Map<String, Double> checks = new HashMap<>(); final Baseline baseline = new Baseline();
        long lastRecord;
        PlayerState(UUID id) { this.id = id; }
        void update(Player p) { Location l = p.getLocation(); if (lastRecord != 0) { double dx=l.getX()-lastX, dy=l.getY()-lastY, dz=l.getZ()-lastZ; speed=Math.sqrt(dx*dx+dy*dy+dz*dz); baseline.observe(Math.abs(wrapDegrees(l.getYaw()-lastYaw)), speed); } lastX=l.getX(); lastY=l.getY(); lastZ=l.getZ(); lastYaw=l.getYaw(); lastPitch=l.getPitch(); }
        double lastX,lastY,lastZ;
        void record(Player p) { long now=System.currentTimeMillis(); samples.add(now+","+p.getLocation().getX()+","+p.getLocation().getY()+","+p.getLocation().getZ()+","+p.getLocation().getYaw()+","+p.getLocation().getPitch()+","+String.format(Locale.US,"%.4f",speed)+","+String.format(Locale.US,"%.3f",score)+","+wallEvents); int max=getConfig().getInt("training.max-memory-samples",20000); if(samples.size()>max) samples.remove(0); if(now-lastRecord>getConfig().getLong("training.save-every-seconds",10)*1000L){ lastRecord=now; saveSession(id); } }
        void add(String check,double amount,String reason){ score+=amount; checks.merge(check, amount, Double::sum); }
        void decay(double amount){score=Math.max(0,score-amount);}
        String topCheck(){return checks.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("none");}
        void reset(){score=0;wallEvents=0;checks.clear();attacks.clear();}
    }

    private static final class Baseline {
        long samples; double meanYaw, m2Yaw, meanSpeed, m2Speed;
        void observe(double yaw,double speed){samples++; double dy=yaw-meanYaw; meanYaw+=dy/samples; m2Yaw+=dy*(yaw-meanYaw); double ds=speed-meanSpeed; meanSpeed+=ds/samples; m2Speed+=ds*(speed-meanSpeed);}
        double sdYaw(){return samples<2?0.001:Math.sqrt(m2Yaw/(samples-1));}
        double sdSpeed(){return samples<2?0.001:Math.sqrt(m2Speed/(samples-1));}
        double z(double x,double mean,double sd){return (x-mean)/Math.max(sd,0.001);}
    }

}
