package com.example.teleport;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
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

    // RTPの最大範囲
    private final int RTP_RADIUS = 10000;

    @Override
    public void onEnable() {
        String[] commands = {
            "sethome", "home", "delhome", "rtp",
            "tpa", "tp", "teleport",
            "tpahere", "teleporthere",
            "tpaccept", "tpdeny", "tpadeny",
            "tpacancel", "tpauto"
        };
        for (String cmd : commands) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
            }
        }
        
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
            case "tpa": case "tp": case "teleport": handleTpaCommand(player, args, false); break;
            case "tpahere": case "teleporthere": handleTpaCommand(player, args, true); break;
            case "tpaccept": handleTpAccept(player); break;
            case "tpdeny": case "tpadeny": handleTpDeny(player); break;
            case "tpacancel": handleTpCancel(player); break;
            case "tpauto": handleTpAuto(player); break;
        }
        return true;
    }

    // --- GUIクリックイベント ---
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        Player player = (Player) event.getWhoClicked();
        ItemStack currentItem = event.getCurrentItem();

        if (currentItem == null || currentItem.getType() == Material.AIR) return;

        // RTPのGUI
        if (title.equals("RTP - ワールド選択")) {
            event.setCancelled(true);
            Material type = currentItem.getType();
            player.closeInventory();

            if (type == Material.GRASS_BLOCK) performSafeRtp(player, Bukkit.getWorld("world"));
            else if (type == Material.NETHERRACK) performSafeRtp(player, Bukkit.getWorld("world_nether"));
            else if (type == Material.END_STONE) performSafeRtp(player, Bukkit.getWorld("world_the_end"));
        }
        
        // TPAプレイヤー一覧のGUI
        else if (title.equals("プレイヤー選択 (TPA)") || title.equals("プレイヤー選択 (TPAHere)")) {
            event.setCancelled(true);
            if (currentItem.getType() != Material.PLAYER_HEAD) return;
            
            boolean isHere = title.contains("TPAHere");
            String targetName = ChatColor.stripColor(currentItem.getItemMeta().getDisplayName());
            Player target = Bukkit.getPlayerExact(targetName);
            
            if (target != null && target.isOnline()) {
                openTargetGui(player, target, isHere);
            } else {
                player.sendMessage(ChatColor.RED + "そのプレイヤーはオフラインみたい。");
                player.closeInventory();
            }
        }
        
        // TPA特定のターゲットへの申請GUI
        else if (title.startsWith("TPA申請 - ") || title.startsWith("TPAHere申請 - ")) {
            event.setCancelled(true);
            // 15番スロット(右側のボタン)がクリックされたかチェック
            if (event.getRawSlot() == 15) {
                boolean isHere = title.startsWith("TPAHere");
                String prefix = isHere ? "TPAHere申請 - " : "TPA申請 - ";
                String targetName = title.substring(prefix.length());
                Player target = Bukkit.getPlayerExact(targetName);
                
                player.closeInventory();
                
                if (target != null && target.isOnline()) {
                    sendTpaRequest(player, target, isHere);
                } else {
                    player.sendMessage(ChatColor.RED + "そのプレイヤーはもういないみたい。");
                }
            }
        }
    }

    // --- TPAコマンドの入り口 ---
    private void handleTpaCommand(Player player, String[] args, boolean isHere) {
        if (args.length == 0) {
            openPlayerListGui(player, isHere);
        } else {
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                player.sendMessage(ChatColor.RED + "そのプレイヤーは見つからないか、オフラインみたい。");
                return;
            }
            if (target.equals(player)) {
                player.sendMessage(ChatColor.RED + "自分自身には申請できないよ！");
                return;
            }
            openTargetGui(player, target, isHere);
        }
    }

    // --- プレイヤー一覧GUI ---
    private void openPlayerListGui(Player player, boolean isHere) {
        String title = isHere ? "プレイヤー選択 (TPAHere)" : "プレイヤー選択 (TPA)";
        Inventory gui = Bukkit.createInventory(null, 54, title);
        
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(player)) continue; // 自分は除外

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(target);
                meta.setDisplayName(ChatColor.YELLOW + target.getName());
                
                String dimName = getDimensionName(target.getWorld());
                meta.setLore(Arrays.asList(
                    ChatColor.WHITE + "現在地: " + dimName,
                    "",
                    ChatColor.GREEN + "▶ クリックして申請画面に進む！"
                ));
                head.setItemMeta(meta);
            }
            gui.addItem(head); // 空いているスロットから順番に詰める
        }
        player.openInventory(gui);
    }

    // --- 特定プレイヤーへの申請確認GUI ---
    private void openTargetGui(Player player, Player target, boolean isHere) {
        String title = (isHere ? "TPAHere申請 - " : "TPA申請 - ") + target.getName();
        Inventory gui = Bukkit.createInventory(null, 27, title);
        
        // 1. 相手の頭 (スロット 11)
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta headMeta = (SkullMeta) head.getItemMeta();
        if (headMeta != null) {
            headMeta.setOwningPlayer(target);
            headMeta.setDisplayName(ChatColor.YELLOW + target.getName());
            head.setItemMeta(headMeta);
        }
        gui.setItem(11, head);

        // 2. 相手のいるディメンションのブロック (スロット 13)
        Material dimMat;
        World.Environment env = target.getWorld().getEnvironment();
        if (env == World.Environment.NETHER) dimMat = Material.NETHERRACK;
        else if (env == World.Environment.THE_END) dimMat = Material.END_STONE;
        else dimMat = Material.GRASS_BLOCK;

        ItemStack dimBlock = new ItemStack(dimMat);
        ItemMeta dimMeta = dimBlock.getItemMeta();
        if (dimMeta != null) {
            dimMeta.setDisplayName(ChatColor.WHITE + "現在地: " + getDimensionName(target.getWorld()));
            dimBlock.setItemMeta(dimMeta);
        }
        gui.setItem(13, dimBlock);

        // 3. 申請を送るボタン (スロット 15)
        ItemStack sendBtn = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta btnMeta = sendBtn.getItemMeta();
        if (btnMeta != null) {
            btnMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "テレポート申請を送る！");
            sendBtn.setItemMeta(btnMeta);
        }
        gui.setItem(15, sendBtn);

        player.openInventory(gui);
    }

    private String getDimensionName(World world) {
        switch (world.getEnvironment()) {
            case NETHER: return ChatColor.RED + "ネザー";
            case THE_END: return ChatColor.LIGHT_PURPLE + "エンド";
            default: return ChatColor.GREEN + "オーバーワールド";
        }
    }

    // --- 実際の申請処理 ---
    private void sendTpaRequest(Player player, Player target, boolean isHere) {
        if (autoAccept.contains(target.getUniqueId())) {
            if (isHere) {
                teleportWithDelay(player, target.getLocation(), target.getName() + " の元へテレポートしたよ！", target, player.getName() + " が自動受け入れで来たよ。");
            } else {
                teleportWithDelay(target, player.getLocation(), player.getName() + " の元へテレポートしたよ！", player, target.getName() + " が自動受け入れで来たよ。");
            }
            return;
        }

        if (isHere) {
            tpaHereRequests.put(target.getUniqueId(), player.getUniqueId());
            target.sendMessage(ChatColor.YELLOW + player.getName() + " から「自分のところへ来ないか」って申請が届いたよ。");
        } else {
            tpaRequests.put(target.getUniqueId(), player.getUniqueId());
            target.sendMessage(ChatColor.YELLOW + player.getName() + " からテレポート申請が届いたよ。");
        }
        target.sendMessage(ChatColor.AQUA + "/tpaccept で承認、/tpdeny で拒否できるよ。");
        activeSenderTarget.put(player.getUniqueId(), target.getUniqueId());
        player.sendMessage(ChatColor.GREEN + target.getName() + " にテレポート申請を送ったよ！");
    }

    // --- カウントダウンと効果音付きのテレポート待機処理 ---
    private void teleportWithDelay(Player targetToMove, Location loc, String msgForMover, Player otherTarget, String msgForOther) {
        if (otherTarget != null && otherTarget.isOnline()) {
            otherTarget.sendMessage(ChatColor.YELLOW + "テレポートが実行されるよ...");
        }

        new BukkitRunnable() {
            int count = 3;

            @Override
            public void run() {
                if (!targetToMove.isOnline()) {
                    this.cancel();
                    return;
                }

                if (count > 0) {
                    targetToMove.sendMessage(ChatColor.YELLOW + "テレポートまで... " + count);
                    targetToMove.playSound(targetToMove.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    count--;
                } else {
                    targetToMove.teleport(loc);
                    targetToMove.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                    targetToMove.sendMessage(ChatColor.GREEN + msgForMover);
                    if (otherTarget != null && otherTarget.isOnline()) {
                        otherTarget.sendMessage(ChatColor.GREEN + msgForOther);
                    }
                    this.cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L); // 0秒後に開始し、20ティック(1秒)ごとに実行
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

    private void performSafeRtp(Player player, World world) {
        if (world == null) {
            player.sendMessage(ChatColor.RED + "指定されたワールドが見つからないよ。");
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "安全な場所を探しているよ...");

        new BukkitRunnable() {
            @Override
            public void run() {
                Location safeLoc = findSafeLocation(world);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (safeLoc != null) {
                            player.teleport(safeLoc);
                            player.playSound(safeLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
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
        for (int i = 0; i < 20; i++) { 
            int x = random.nextInt(RTP_RADIUS * 2) - RTP_RADIUS;
            int z = random.nextInt(RTP_RADIUS * 2) - RTP_RADIUS;

            if (world.getEnvironment() == World.Environment.NETHER) {
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
                int y = world.getHighestBlockYAt(x, z);
                Block block = world.getBlockAt(x, y, z);
                if (y > 0 && block.getType().isSolid() && block.getType() != Material.LAVA && block.getType() != Material.WATER) {
                    return new Location(world, x + 0.5, y + 1, z + 0.5);
                }
            }
        }
        return null;
    }

    // --- その他コマンド ---
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
            teleportWithDelay(player, sender.getLocation(), sender.getName() + " の元へテレポートしたよ！", sender, player.getName() + " が申請を受け入れたよ。");
        } else {
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

    // --- ホーム関連 ---
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
        player.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
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
