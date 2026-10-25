package com.supatpa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SupaTPA extends JavaPlugin implements Listener, TabExecutor {
    private final Map<UUID, Map<UUID, TeleportRequest>> incoming = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Location> teleportOrigins = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> teleportTasks = new ConcurrentHashMap<>();
    private final Set<UUID> tpaDisabled = ConcurrentHashMap.newKeySet();
    private final Set<UUID> tpaHereDisabled = ConcurrentHashMap.newKeySet();

    private enum RequestType { TO, HERE }
    private record TeleportRequest(UUID sender, UUID recipient, RequestType type, long expiresAt) {}
    private enum MenuType { SEND_TO, SEND_HERE, ACCEPT }
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("gui.yml");
        for (String name : List.of("tpa", "tpahere", "tpaccept", "tpacancel", "tpatoggle", "tpaheretoggle")) {
            PluginCommand command = getCommand(name);
            if (command != null) { command.setExecutor(this); command.setTabCompleter(this); }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("SupaTPA enabled.");
    }

    private void saveResourceIfMissing(String name) {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        if (!new java.io.File(getDataFolder(), name).exists()) saveResource(name, false);
    }

    @Override
    public void onDisable() {
        teleportTasks.values().forEach(BukkitTask::cancel);
        teleportTasks.clear();
        incoming.clear();
    }

    private String message(String key) {
        return color(getConfig().getString("messages." + key, key));
    }

    private String color(String text) {
        if (text == null) return "";
        // Supports legacy ampersand colors and MiniMessage-style hex tags such as <#FB5454>.
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("<#([A-Fa-f0-9]{6})>").matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(buffer, "§x§" + matcher.group(1).charAt(0) + "§" + matcher.group(1).charAt(1) + "§" + matcher.group(1).charAt(2) + "§" + matcher.group(1).charAt(3) + "§" + matcher.group(1).charAt(4) + "§" + matcher.group(1).charAt(5));
        matcher.appendTail(buffer);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    private void tell(Player player, String key) {
        player.sendMessage(message(key));
        player.sendActionBar(LEGACY.deserialize(message(key)));
    }

    private void deny(Player player) {
        tell(player, "error");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    private Player onlineTarget(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null && sender instanceof Player player) tell(player, "not-online");
        return target;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "tpa" -> {
                if (args.length != 1) { tell(player, "usage-tpa"); return true; }
                Player target = onlineTarget(player, args[0]);
                if (target == null) return true;
                if (target.equals(player)) { deny(player); return true; }
                if (tpaDisabled.contains(target.getUniqueId())) { tell(player, "tpa-disabled"); return true; }
                if (isOnCooldown(player, target)) { tell(player, "cooldown"); return true; }
                openConfirmation(player, target, MenuType.SEND_TO);
            }
            case "tpahere" -> {
                if (args.length != 1) { tell(player, "usage-tpahere"); return true; }
                Player target = onlineTarget(player, args[0]);
                if (target == null) return true;
                if (target.equals(player)) { deny(player); return true; }
                if (tpaHereDisabled.contains(target.getUniqueId())) { tell(player, "tpahere-disabled"); return true; }
                if (isOnCooldown(player, target)) { tell(player, "cooldown"); return true; }
                openConfirmation(player, target, MenuType.SEND_HERE);
            }
            case "tpaccept" -> {
                TeleportRequest request = findRequest(player, args.length == 0 ? null : args[0]);
                if (request == null) { tell(player, "request-missing"); return true; }
                Player requester = Bukkit.getPlayer(request.sender());
                if (requester == null) { removeRequest(request); tell(player, "not-online"); return true; }
                openConfirmation(player, requester, MenuType.ACCEPT);
            }
            case "tpacancel" -> {
                if (args.length != 1) { tell(player, "usage-tpacancel"); return true; }
                Player requester = Bukkit.getPlayerExact(args[0]);
                if (requester == null) { tell(player, "request-missing"); return true; }
                TeleportRequest request = incoming.getOrDefault(player.getUniqueId(), Map.of()).get(requester.getUniqueId());
                if (request == null) { tell(player, "request-missing"); return true; }
                removeRequest(request);
                tell(player, "denied");
                requester.sendMessage(color("&b" + player.getName() + " &7denied your teleport request."));
                requester.sendActionBar(LEGACY.deserialize(color("&b" + player.getName() + " &7denied your teleport request.")));
            }
            case "tpatoggle" -> {
                toggle(tpaDisabled, player.getUniqueId());
                player.sendMessage(color("&7Teleport requests to you are now " + (tpaDisabled.contains(player.getUniqueId()) ? "&cdisabled" : "&aenabled") + "&7."));
            }
            case "tpaheretoggle" -> {
                toggle(tpaHereDisabled, player.getUniqueId());
                player.sendMessage(color("&7Teleport-here requests to you are now " + (tpaHereDisabled.contains(player.getUniqueId()) ? "&cdisabled" : "&aenabled") + "&7."));
            }
        }
        return true;
    }

    private void toggle(Set<UUID> set, UUID id) { if (!set.add(id)) set.remove(id); }

    private boolean isOnCooldown(Player sender, Player target) {
        long last = cooldowns.getOrDefault(sender.getUniqueId() + ":" + target.getUniqueId(), 0L);
        return System.currentTimeMillis() - last < getConfig().getLong("request-cooldown-seconds", 60) * 1000L;
    }

    private void openConfirmation(Player viewer, Player other, MenuType type) {
        String path = "menus." + type.name().toLowerCase(Locale.ROOT);
        String title = color(getConfig().getString("gui." + type.name().toLowerCase(Locale.ROOT) + ".title", type == MenuType.ACCEPT ? "&8Accept Request" : "&8Confirm Request"));
        int size = Math.max(9, Math.min(54, getConfig().getInt("gui." + type.name().toLowerCase(Locale.ROOT) + ".rows", 3) * 9));
        MenuHolder holder = new MenuHolder(type, other.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        fill(inventory);
        String section = "gui." + type.name().toLowerCase(Locale.ROOT) + ".";
        setButton(inventory, getConfig().getInt(section + "cancel-slot", 10), getConfig().getString(section + "cancel-material", "RED_STAINED_GLASS_PANE"), color(getConfig().getString(section + "cancel-name", "&cCancel")), List.of(color("&7Close this menu")));
        setButton(inventory, getConfig().getInt(section + "player-slot", 13), "PLAYER_HEAD", color("&a" + other.getName()), List.of(color("&7Teleport request target")));
        setButton(inventory, getConfig().getInt(section + "location-slot", 12), worldMaterial(other.getWorld()), color("&aLocation"), List.of(color("&7World: " + other.getWorld().getName())));
        setButton(inventory, getConfig().getInt(section + "confirm-slot", 16), getConfig().getString(section + "confirm-material", "LIME_STAINED_GLASS_PANE"), color(getConfig().getString(section + "confirm-name", type == MenuType.ACCEPT ? "&aAccept" : "&aConfirm")), List.of(color(type == MenuType.ACCEPT ? "&7Accept this request" : "&7Send teleport request")));
        viewer.openInventory(inventory);
    }

    private void fill(Inventory inventory) {
        if (!getConfig().getBoolean("gui.fill-empty-slots", true)) return;
        Material material = Material.matchMaterial(getConfig().getString("gui.filler-material", "BLACK_STAINED_GLASS_PANE"));
        if (material == null || !material.isItem()) material = Material.BLACK_STAINED_GLASS_PANE;
        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) { meta.displayName(Component.text(" ")); filler.setItemMeta(meta); }
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
    }

    private Material worldMaterial(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
    }

    private void setButton(Inventory inventory, int slot, String materialName, String name, List<String> lore) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        Material material = Material.matchMaterial(materialName == null ? "" : materialName.toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) material = Material.PAPER;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name));
            meta.lore(lore.stream().map(LEGACY::deserialize).toList());
            item.setItemMeta(meta);
        }
        inventory.setItem(slot, item);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        String section = "gui." + holder.type.name().toLowerCase(Locale.ROOT) + ".";
        int slot = event.getRawSlot();
        if (slot == getConfig().getInt(section + "cancel-slot", 10)) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1f, 1f);
            return;
        }
        if (slot != getConfig().getInt(section + "confirm-slot", 16)) return;
        Player other = Bukkit.getPlayer(holder.other);
        player.closeInventory();
        if (other == null || !other.isOnline()) { tell(player, "not-online"); return; }
        if (holder.type == MenuType.SEND_TO || holder.type == MenuType.SEND_HERE) {
            RequestType type = holder.type == MenuType.SEND_TO ? RequestType.TO : RequestType.HERE;
            if ((type == RequestType.TO && tpaDisabled.contains(other.getUniqueId())) || (type == RequestType.HERE && tpaHereDisabled.contains(other.getUniqueId()))) {
                tell(player, type == RequestType.TO ? "tpa-disabled" : "tpahere-disabled");
                return;
            }
            if (isOnCooldown(player, other)) { tell(player, "cooldown"); return; }
            TeleportRequest request = new TeleportRequest(player.getUniqueId(), other.getUniqueId(), type, System.currentTimeMillis() + 60_000L);
            incoming.computeIfAbsent(other.getUniqueId(), ignored -> new ConcurrentHashMap<>()).put(player.getUniqueId(), request);
            cooldowns.put(player.getUniqueId() + ":" + other.getUniqueId(), System.currentTimeMillis());
            player.sendMessage(color("&7You sent " + (type == RequestType.TO ? "&b" + other.getName() + " &7a teleport request." : "&b" + other.getName() + " &7a teleport-here request.")));
            player.sendActionBar(LEGACY.deserialize(color("&7Teleport request sent.")));
            other.sendMessage(color("&b" + player.getName() + " &7sent you a " + (type == RequestType.TO ? "tpa" : "tpahere") + " request."));
            other.sendMessage(color("&aUse /tpaccept " + player.getName() + " &7to accept, or /tpacancel " + player.getName() + " to deny."));
            other.sendActionBar(LEGACY.deserialize(color("&b" + player.getName() + " &7sent a teleport request.")));
            playSuccess(player); playSuccess(other);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Map<UUID, TeleportRequest> requests = incoming.get(other.getUniqueId());
                if (requests != null && requests.get(player.getUniqueId()) == request) {
                    requests.remove(player.getUniqueId());
                    player.sendMessage(color("&7Your teleport request to &b" + other.getName() + " &7expired."));
                }
            }, 20L * 60L);
        } else {
            TeleportRequest request = incoming.getOrDefault(player.getUniqueId(), Map.of()).get(other.getUniqueId());
            if (request == null || request.expiresAt() < System.currentTimeMillis()) { tell(player, "request-missing"); return; }
            removeRequest(request);
            Player requester = Bukkit.getPlayer(request.sender());
            if (requester == null) { tell(player, "not-online"); return; }
            Player moving = request.type() == RequestType.TO ? requester : player;
            Player destination = request.type() == RequestType.TO ? player : requester;
            requester.sendMessage(color("&b" + player.getName() + " &7accepted your teleport request."));
            player.sendMessage(color("&7You accepted &b" + requester.getName() + "&7's request."));
            beginTeleport(moving, destination);
        }
    }

    private void beginTeleport(Player moving, Player destination) {
        cancelTeleport(moving, false);
        Location origin = moving.getLocation().clone();
        teleportOrigins.put(moving.getUniqueId(), origin);
        int seconds = Math.max(0, getConfig().getInt("teleport-delay-seconds", 5));
        moving.sendMessage(color("&7Teleporting in &b" + seconds + " &7seconds. Do not move."));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            int remaining = seconds;
            @Override public void run() {
                if (!moving.isOnline() || !destination.isOnline() || !teleportOrigins.containsKey(moving.getUniqueId())) {
                    cancelTeleport(moving, false); return;
                }
                if (remaining <= 0) {
                    moving.teleport(destination.getLocation());
                    moving.sendMessage(message("teleported"));
                    moving.sendActionBar(LEGACY.deserialize(message("teleported")));
                    moving.playSound(moving.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    cancelTeleport(moving, false);
                    return;
                }
                moving.sendActionBar(LEGACY.deserialize(color("&7Teleporting in &b" + remaining + "&7...")));
                moving.playSound(moving.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
                remaining--;
            }
        }, 0L, 20L);
        teleportTasks.put(moving.getUniqueId(), task);
    }

    private void cancelTeleport(Player player, boolean notify) {
        BukkitTask task = teleportTasks.remove(player.getUniqueId());
        if (task != null) task.cancel();
        boolean wasTeleporting = teleportOrigins.remove(player.getUniqueId()) != null;
        if (notify && wasTeleporting) {
            tell(player, "moved");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!teleportOrigins.containsKey(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > 0.25) cancelTeleport(event.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancelTeleport(event.getPlayer(), false);
        incoming.remove(id);
        incoming.values().forEach(requests -> requests.remove(id));
    }

    private TeleportRequest findRequest(Player recipient, String senderName) {
        Map<UUID, TeleportRequest> requests = incoming.getOrDefault(recipient.getUniqueId(), Map.of());
        if (senderName != null) {
            Player named = Bukkit.getPlayerExact(senderName);
            if (named == null) return null;
            TeleportRequest request = requests.get(named.getUniqueId());
            return request != null && request.expiresAt() >= System.currentTimeMillis() ? request : null;
        }
        return requests.values().stream().filter(r -> r.expiresAt() >= System.currentTimeMillis()).findFirst().orElse(null);
    }

    private void removeRequest(TeleportRequest request) {
        Map<UUID, TeleportRequest> requests = incoming.get(request.recipient());
        if (requests != null) {
            requests.remove(request.sender());
            if (requests.isEmpty()) incoming.remove(request.recipient());
        }
    }

    private void playSuccess(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        if (command.getName().equalsIgnoreCase("tpaccept") || command.getName().equalsIgnoreCase("tpacancel")) {
            if (sender instanceof Player player) return incoming.getOrDefault(player.getUniqueId(), Map.of()).keySet().stream()
                .map(Bukkit::getPlayer).filter(Objects::nonNull).map(Player::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
            .filter(n -> !sender.getName().equalsIgnoreCase(n) && n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final UUID other;
        private Inventory inventory;
        private MenuHolder(MenuType type, UUID other) { this.type = type; this.other = other; }
        @Override public Inventory getInventory() { return inventory; }
    }
}