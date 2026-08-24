package com.example.teleport;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class TeleportPlugin extends JavaPlugin implements CommandExecutor, Listener {

    // ホーム
    private final Map<UUID, Map<String, Location>> homes = new HashMap<>();
    // TPAリクエスト
    private final Map<UUID, UUID> tpaRequests = new HashMap<>();
    private final Map<UUID, UUID> tpaHereRequests = new HashMap<>();
    // 自動受け入れ
    private final Set<UUID> autoAccept = new HashSet<>();
    // キャンセル用
    private final Map<UUID, UUID> activeSenderTarget = new HashMap<>();
    
    // PvP状態管理 (デフォルトfalseなので、含まれていない場合はPvP不可)
    private final Set<UUID> pvpEnabled = new HashSet<>();

    // RTPの最大範囲
    private final int RTP_RADIUS = 10000;

    @Override
    public void onEnable() {
        // コマンド登録
        String[] commands = {
            "sethome", "home", "delhome", "rtp",
            "tpa", "tp", "teleport",
            "tpahere", "teleporthere",
            "tpaccept", "tpdeny", "tpadeny",
            "tpacancel", "tpauto", "pvp"
        };
        for (String cmd : commands) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
            }
        }
        
        // イベント登録
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("TeleportPlugin が有効化されたよ！");
    }

    @Override
    public void onDisable() {
        getLogger().info("TeleportPlugin が無効化されたよ。");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行可能です。");
            return true;
        }

        Player player = (Player) sender;
        String name = command.getName().toLowerCase();

        switch (name) {
            case "sethome": handleSetHome(player, args); break;
            case "home": handleHome(player, args); break;
            case "delhome": handleDelHome(player, args); break;
            case "rtp": openRtpGui(player); break;
            case "pvp": handlePvpToggle(player); break;
            case "tpa": case "tp": case "teleport": handleTpa(player, args, false); break;
            case "tpahere": case "teleporthere": handleTpa(player, args, true); break;
            case "tpaccept": handleTpAccept(player); break;
            case "tpdeny": case "tpadeny": handleTpDeny(player); break;
            case "tpacancel": handleTpCancel(player); break;
            case "tpauto": handleTpAuto(player); break;
        }
        return true;
    }

    // --- PvPトグル ---
    private void handlePvpToggle(Player player) {
        if (pvpEnabled.contains(player.getUniqueId())) {
            pvpEnabled.remove(player.getUniqueId());
            player.sendMessage(ChatColor.AQUA + "PvPを【無効】にしたよ。");
        } else {
            pvpEnabled.add(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "PvPを【有効】にしました。※人との殺し合いは禁止です！");
        }
    }

    // PvPダメージイベントリスナー
    @EventHandler
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player && event.getDamager() instanceof Player) {
            Player victim = (Player) event.getEntity();
            Player attacker = (Player) event.getDamager();
            
            // どちらかがPvPオフならダメージキャンセル
            if (!pvpEnabled.contains(victim.getUniqueId()) || !pvpEnabled.contains(attacker.getUniqueId())) {
                event.setCancelled(true);
                attacker.sendMessage(ChatColor.RED + "PvPが無効になっているため攻撃できないよ！");
            }
        }
    }

    // --- RTP GUIと安全なテレポート ---
    private void openRtpGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, "RTP - ワールド選択");

        gui.setItem(2, createGuiItem(Material.GRASS_BLOCK, ChatColor.GREEN + "オーバーワールド"));
        gui.setItem(4, createGuiItem(Material.NETHERRACK, ChatColor.RED + "ネザー"));
        gui.setItem(6, createGuiItem(Material.END_STONE, ChatColor.YELLOW + "エンド"));

        player.openInventory(gui);
    }

    private ItemStack createGuiItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals("RTP - ワールド選択")) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null) return;
            
            Player player = (Player) event.getWhoClicked();
            Material type = event.getCurrentItem().getType();
            player.closeInventory();

            if (type == Material.GRASS_BLOCK) performSafeRtp(player, Bukkit.getWorld("world"));
            else if (type == Material.NETHERRACK) performSafeRtp(player, Bukkit.getWorld("world_nether"));
            else if (type == Material.END_STONE) performSafeRtp(player, Bukkit.getWorld("world_the_end"));
        }
    }

    private void performSafeRtp(Player player, World world) {
        if (world == null) {
            player.sendMessage(ChatColor.RED + "指定されたワールドが見つからないよ。");
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "安全な場所を探しているよ...");

        // 重くならないように非同期で場所を探す
        new BukkitRunnable() {
            @Override
            public void run() {
                Location safeLoc = findSafeLocation(world);
                
                // テレポートはメインスレッドで実行
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (safeLoc != null) {
                            player.teleport(safeLoc);
                            player.sendMessage(ChatColor.GREEN + "ランダムな場所にテレポートしたよ！ (X: " + safeLoc.getBlockX() + ", Z: " + safeLoc.getBlockZ() + ")");
                        } else {
                            player.sendMessage(ChatColor.RED + "安全な場所が見つからなかった... もう一度試してみて！");
                        }
                    }
                }.runTask(TeleportPlugin.this);
            }
        }.runTaskAsynchronously(this);
    }

    private Location findSafeLocation(World world) {
        Random random = new Random();
        for (int i = 0; i < 20; i++) { // 最大20回まで安全な場所を探す
            int x = random.nextInt(RTP_RADIUS * 2) - RTP_RADIUS;
            int z = random.nextInt(RTP_RADIUS * 2) - RTP_RADIUS;

            if (world.getEnvironment() == World.Environment.NETHER) {
                // ネザーは岩盤(Y=127)を避けるため、Y=120から下へ探す
                for (int y = 120; y > 30; y--) {
                    Block block = world.getBlockAt(x, y, z);
                    Block above = world.getBlockAt(x, y + 1, z);
                    Block above2 = world.getBlockAt(x, y + 2, z);
                    
                    if (block.getType().isSolid() && above.getType() == Material.AIR && above2.getType() == Material.AIR) {
                        if (block.getType() != Material.LAVA && block.getType() != Material.MAGMA_BLOCK) {
                            return new Location(world, x + 0.5, y + 1, z + 0.5);
                        }
                    }
                }
            } else {
                // オーバーワールドとエンド
                int y = world.getHighestBlockYAt(x, z);
                Block block = world.getBlockAt(x, y, z);
                
                // エンドの奈落(Y=0以下)や溶岩、水を避ける
                if (y > 0 && block.getType().isSolid() && block.getType() != Material.LAVA && block.getType() != Material.WATER) {
                    return new Location(world, x + 0.5, y + 1, z + 0.5);
                }
            }
        }
        return null;
    }

    // --- テレポート待機処理 ---
    private void teleportWithDelay(Player targetToMove, Location loc, String msgForMover, Player otherTarget, String msgForOther) {
        targetToMove.sendMessage(ChatColor.YELLOW + "3秒後にテレポートするよ。そのまま待っててね！");
        if (otherTarget != null && otherTarget.isOnline()) {
            otherTarget.sendMessage(ChatColor.YELLOW + "3秒後にテレポートが実行されるよ...");
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (targetToMove.isOnline()) {
                    targetToMove.teleport(loc);
                    targetToMove.sendMessage(ChatColor.GREEN + msgForMover);
                    if (otherTarget != null && otherTarget.isOnline()) {
                        otherTarget.sendMessage(ChatColor.GREEN + msgForOther);
                    }
                }
            }
        }.runTaskLater(this, 60L); // 20 ticks = 1秒。60Lで3秒。
    }


    // --- TPA関連 ---
    private void handleTpa(Player player, String[] args, boolean isHere) {
        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "プレイヤー名を指定してね。");
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(ChatColor.RED + "そのプレイヤーは見つからないか、オフラインみたい。");
            return;
        }

        if (target.equals(player)) {
            player.sendMessage(ChatColor.RED + "自分自身には申請できないよ。");
            return;
        }

        // 自動受け入れが有効な場合（3秒待機）
        if (autoAccept.contains(target.getUniqueId())) {
            if (isHere) {
                teleportWithDelay(player, target.getLocation(), target.getName() + " の元へテレポートしたよ！", target, player.getName() + " が自動受け入れで来たよ。");
            } else {
                teleportWithDelay(target, player.getLocation(), player.getName() + " の元へテレポートしたよ！", player, target.getName() + " が自動受け入れで来たよ。");
            }
            return;
        }

        // 通常の申請フロー
        if (isHere) {
            tpaHereRequests.put(target.getUniqueId(), player.getUniqueId());
            target.sendMessage(ChatColor.YELLOW + player.getName() + " から「自分のところへ来ないか」って申請が届いたよ。");
        } else {
            tpaRequests.put(target.getUniqueId(), player.getUniqueId());
            target.sendMessage(ChatColor.YELLOW + player.getName() + " からテレポート申請が届いたよ。");
        }
        target.sendMessage(ChatColor.AQUA + "/tpaccept で承認、/tpdeny で拒否できるよ。");
        activeSenderTarget.put(player.getUniqueId(), target.getUniqueId());
        player.sendMessage(ChatColor.GREEN + target.getName() + " にテレポート申請を送ったよ。");
    }

    private void handleTpAccept(Player player) {
        UUID senderUuid = tpaRequests.remove(player.getUniqueId());
        boolean isHere = false;

        if (senderUuid == null) {
            senderUuid = tpaHereRequests.remove(player.getUniqueId());
            isHere = true;
        }

        if (senderUuid == null) {
            player.sendMessage(ChatColor.RED + "今は保留中の申請はないよ。");
            return;
        }

        Player sender = Bukkit.getPlayer(senderUuid);
        activeSenderTarget.remove(senderUuid);

        if (sender == null || !sender.isOnline()) {
            player.sendMessage(ChatColor.RED + "申請を送ってきたプレイヤーはもうオフラインみたい。");
            return;
        }

        if (isHere) {
            // tpahere の場合：受取人(player)が、申請者(sender)の元へ飛ぶ
            teleportWithDelay(player, sender.getLocation(), sender.getName() + " の元へテレポートしたよ！", sender, player.getName() + " が申請を受け入れたよ。");
        } else {
            // tpa / tp の場合：申請者(sender)が、受取人(player)の元へ飛ぶ
            teleportWithDelay(sender, player.getLocation(), player.getName() + " の元へテレポートしたよ！", player, sender.getName() + " からの申請を受け入れたよ。");
        }
    }

    private void handleTpDeny(Player player) {
        UUID senderUuid = tpaRequests.remove(player.getUniqueId());
        if (senderUuid == null) {
            senderUuid = tpaHereRequests.remove(player.getUniqueId());
        }

        if (senderUuid == null) {
            player.sendMessage(ChatColor.RED + "今は保留中の申請はないよ。");
            return;
        }

        activeSenderTarget.remove(senderUuid);
        player.sendMessage(ChatColor.YELLOW + "テレポート申請を拒否したよ。");

        Player sender = Bukkit.getPlayer(senderUuid);
        if (sender != null && sender.isOnline()) {
            sender.sendMessage(ChatColor.RED + player.getName() + " に申請を拒否されちゃった。");
        }
    }

    private void handleTpCancel(Player player) {
        UUID targetUuid = activeSenderTarget.remove(player.getUniqueId());
        if (targetUuid == null) {
            player.sendMessage(ChatColor.RED + "キャンセルできる申請がないよ。");
            return;
        }

        tpaRequests.remove(targetUuid);
        tpaHereRequests.remove(targetUuid);
        player.sendMessage(ChatColor.YELLOW + "申請をキャンセルしたよ。");

        Player target = Bukkit.getPlayer(targetUuid);
        if (target != null && target.isOnline()) {
            target.sendMessage(ChatColor.YELLOW + player.getName() + " が申請を取り消したよ。");
        }
    }

    private void handleTpAuto(Player player) {
        if (autoAccept.contains(player.getUniqueId())) {
            autoAccept.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "TP申請の自動受け入れを【OFF】にしたよ。");
        } else {
            autoAccept.add(player.getUniqueId());
            player.sendMessage(ChatColor.GREEN + "TP申請の自動受け入れを【ON】にしたよ！");
        }
    }

    // --- ホーム関連 (変更なし・省略せずに記載) ---
    private void handleSetHome(Player player, String[] args) {
        String homeName = args.length > 0 ? args[0] : "default";
        homes.putIfAbsent(player.getUniqueId(), new HashMap<>());
        homes.get(player.getUniqueId()).put(homeName, player.getLocation());
        player.sendMessage(ChatColor.GREEN + "ホーム '" + homeName + "' を登録したよ！");
    }

    private void handleHome(Player player, String[] args) {
        Map<String, Location> playerHomes = homes.get(player.getUniqueId());
        if (playerHomes == null || playerHomes.isEmpty()) {
            player.sendMessage(ChatColor.RED + "まだホームが登録されてないよ。");
            return;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.YELLOW + "=== 登録済みホーム一覧 ===");
            player.sendMessage(ChatColor.AQUA + String.join(", ", playerHomes.keySet()));
            player.sendMessage(ChatColor.YELLOW + "使い方: /home [名前]");
            return;
        }

        String homeName = args[0];
        Location loc = playerHomes.get(homeName);
        if (loc == null) {
            player.sendMessage(ChatColor.RED + "ホーム '" + homeName + "' は見つからないな。");
            return;
        }
        player.teleport(loc);
        player.sendMessage(ChatColor.GREEN + "ホーム '" + homeName + "' にテレポートしたよ！");
    }

    private void handleDelHome(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "削除するホームの名前を指定してね。 /delhome [名前]");
            return;
        }
        String homeName = args[0];
        Map<String, Location> playerHomes = homes.get(player.getUniqueId());
        if (playerHomes == null || playerHomes.remove(homeName) == null) {
            player.sendMessage(ChatColor.RED + "ホーム '" + homeName + "' が見つからないよ。");
            return;
        }
        player.sendMessage(ChatColor.GREEN + "ホーム '" + homeName + "' を削除したよ。");
    }
}
