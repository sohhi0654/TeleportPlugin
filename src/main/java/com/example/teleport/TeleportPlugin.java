package com.example.teleport;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class TeleportPlugin extends JavaPlugin implements CommandExecutor {

    // データの保持用
    // Homes: PlayerUUID -> (HomeName -> Location)
    private final Map<UUID, Map<String, Location>> homes = new HashMap<>();
    // TPA Requests: TargetUUID -> SenderUUID (TPA / TP)
    private final Map<UUID, UUID> tpaRequests = new HashMap<>();
    // TPAHere Requests: TargetUUID -> SenderUUID (TPAHere)
    private final Map<UUID, UUID> tpaHereRequests = new HashMap<>();
    // Auto Accept: PlayerUUID -> boolean
    private final Set<UUID> autoAccept = new HashSet<>();
    // Active Requests for cancel: SenderUUID -> TargetUUID
    private final Map<UUID, UUID> activeSenderTarget = new HashMap<>();

    @Override
    public void onEnable() {
        // コマンドの登録
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
        getLogger().info("TeleportPlugin が有効化されました！");
    }

    @Override
    public void onDisable() {
        getLogger().info("TeleportPluginが無効化されました。");
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
            case "sethome":
                handleSetHome(player, args);
                break;
            case "home":
                handleHome(player, args);
                break;
            case "delhome":
                handleDelHome(player, args);
                break;
            case "rtp":
                handleRtp(player);
                break;
            case "tpa":
            case "tp":
            case "teleport":
                handleTpa(player, args, false);
                break;
            case "tpahere":
            case "teleporthere":
                handleTpa(player, args, true);
                break;
            case "tpaccept":
                handleTpAccept(player);
                break;
            case "tpdeny":
            case "tpadeny":
                handleTpDeny(player);
                break;
            case "tpacancel":
                handleTpCancel(player);
                break;
            case "tpauto":
                handleTpAuto(player);
                break;
        }
        return true;
    }

    // --- ホーム機能 ---
    private void handleSetHome(Player player, String[] args) {
        String homeName = args.length > 0 ? args[0] : "default";
        homes.putIfAbsent(player.getUniqueId(), new HashMap<>());
        homes.get(player.getUniqueId()).put(homeName, player.getLocation());
        player.sendMessage(ChatColor.GREEN + "ホーム '" + homeName + "' を登録しました！");
    }

    private void handleHome(Player player, String[] args) {
        Map<String, Location> playerHomes = homes.get(player.getUniqueId());
        if (playerHomes == null || playerHomes.isEmpty()) {
            player.sendMessage(ChatColor.RED + "登録されたホームがありません。");
            return;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.YELLOW + "=== 登録済みホーム一覧 ===");
            player.sendMessage(ChatColor.AQUA + String.join(", ", playerHomes.keySet()));
            player.sendMessage(ChatColor.YELLOW + "使用方法: /home [名前]");
            return;
        }

        String homeName = args[0];
        Location loc = playerHomes.get(homeName);
        if (loc == null) {
            player.sendMessage(ChatColor.RED + "ホーム '" + homeName + "' は存在しません。");
            return;
        }

        player.teleport(loc);
        player.sendMessage(ChatColor.GREEN + "ホーム '" + homeName + "' にテレポートしました！");
    }

    private void handleDelHome(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "削除するホームの名前を指定してください。 /delhome [名前]");
            return;
        }
        String homeName = args[0];
        Map<String, Location> playerHomes = homes.get(player.getUniqueId());
        if (playerHomes == null || playerHomes.remove(homeName) == null) {
            player.sendMessage(ChatColor.RED + "ホーム '" + homeName + "' が見つかりません。");
            return;
        }
        player.sendMessage(ChatColor.GREEN + "ホーム '" + homeName + "' を削除しました。");
    }

    // --- RTP（ランダムテレポート） ---
    private void handleRtp(Player player) {
        World world = player.getWorld();
        Random random = new Random();
        // -1000 から 1000 の範囲でランダム座標を決定
        int x = random.nextInt(2000) - 1000;
        int z = random.nextInt(2000) - 1000;
        int y = world.getHighestBlockYAt(x, z) + 1;

        Location targetLoc = new Location(world, x + 0.5, y, z + 0.5);
        player.teleport(targetLoc);
        player.sendMessage(ChatColor.GREEN + "ランダムな場所にテレポートしました！ (X: " + x + ", Z: " + z + ")");
    }

    // --- TPA関連 ---
    private void handleTpa(Player player, String[] args, boolean isHere) {
        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "プレイヤー名を指定してください。");
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(ChatColor.RED + "指定されたプレイヤーが見つからないか、オフラインです。");
            return;
        }

        if (target.equals(player)) {
            player.sendMessage(ChatColor.RED + "自分自身に申請を送ることはできません。");
            return;
        }

        // 自動受け入れが有効な場合
        if (autoAccept.contains(target.getUniqueId())) {
            if (isHere) {
                player.teleport(target.getLocation());
                target.sendMessage(ChatColor.GREEN + player.getName() + " が自動受け入れによりあなたの元へテレポートしました。");
                player.sendMessage(ChatColor.GREEN + target.getName() + " の元へテレポートしました。");
            } else {
                target.teleport(player.getLocation());
                target.sendMessage(ChatColor.GREEN + "自動受け入れにより " + player.getName() + " をあなたの元へテレポートさせました。");
                player.sendMessage(ChatColor.GREEN + target.getName() + " があなたの元へテレポートしました。");
            }
            return;
        }

        // 通常の申請フロー
        if (isHere) {
            tpaHereRequests.put(target.getUniqueId(), player.getUniqueId());
            target.sendMessage(ChatColor.YELLOW + player.getName() + " からあなたを自分の元へ呼ぶ申請が届きました。");
            target.sendMessage(ChatColor.YELLOW + "/tpaccept で承認、/tpdeny で拒否できます。");
        } else {
            tpaRequests.put(target.getUniqueId(), player.getUniqueId());
            target.sendMessage(ChatColor.YELLOW + player.getName() + " からあなたへのテレポート申請が届きました。");
            target.sendMessage(ChatColor.YELLOW + "/tpaccept で承認、/tpdeny で拒否できます。");
        }
        activeSenderTarget.put(player.getUniqueId(), target.getUniqueId());
        player.sendMessage(ChatColor.GREEN + target.getName() + " にテレポート申請を送りました。");
    }

    private void handleTpAccept(Player player) {
        UUID senderUuid = tpaRequests.remove(player.getUniqueId());
        boolean isHere = false;

        if (senderUuid == null) {
            senderUuid = tpaHereRequests.remove(player.getUniqueId());
            isHere = true;
        }

        if (senderUuid == null) {
            player.sendMessage(ChatColor.RED + "現在保留中のテレポート申請はありません。");
            return;
        }

        Player sender = Bukkit.getPlayer(senderUuid);
        activeSenderTarget.remove(senderUuid);

        if (sender == null || !sender.isOnline()) {
            player.sendMessage(ChatColor.RED + "申請を送ったプレイヤーはすでにオフラインです。");
            return;
        }

        if (isHere) {
            // tpahere の場合：ターゲット（受取人）を申請者の元へ
            player.teleport(sender.getLocation());
            player.sendMessage(ChatColor.GREEN + sender.getName() + " の元へテレポートしました。");
            sender.sendMessage(ChatColor.GREEN + player.getName() + " があなたの申請を受け入れました。");
        } else {
            // tpa / tp の場合：申請者をターゲット（受取人）の元へ
            sender.teleport(player.getLocation());
            sender.sendMessage(ChatColor.GREEN + player.getName() + " の元へテレポートしました。");
            player.sendMessage(ChatColor.GREEN + sender.getName() + " からのテレポート申請を受け入れました。");
        }
    }

    private void handleTpDeny(Player player) {
        UUID senderUuid = tpaRequests.remove(player.getUniqueId());
        if (senderUuid == null) {
            senderUuid = tpaHereRequests.remove(player.getUniqueId());
        }

        if (senderUuid == null) {
            player.sendMessage(ChatColor.RED + "現在保留中のテレポート申請はありません。");
            return;
        }

        activeSenderTarget.remove(senderUuid);
        player.sendMessage(ChatColor.YELLOW + "テレポート申請を拒否しました。");

        Player sender = Bukkit.getPlayer(senderUuid);
        if (sender != null && sender.isOnline()) {
            sender.sendMessage(ChatColor.RED + player.getName() + " にテレポート申請を拒否されました。");
        }
    }

    private void handleTpCancel(Player player) {
        UUID targetUuid = activeSenderTarget.remove(player.getUniqueId());
        if (targetUuid == null) {
            player.sendMessage(ChatColor.RED + "キャンセルできる送信中の申請はありません。");
            return;
        }

        tpaRequests.remove(targetUuid);
        tpaHereRequests.remove(targetUuid);
        player.sendMessage(ChatColor.YELLOW + "送信したテレポート申請をキャンセルしました。");

        Player target = Bukkit.getPlayer(targetUuid);
        if (target != null && target.isOnline()) {
            target.sendMessage(ChatColor.YELLOW + player.getName() + " が申請をキャンセルしました。");
        }
    }

    private void handleTpAuto(Player player) {
        if (autoAccept.contains(player.getUniqueId())) {
            autoAccept.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "TP申請の自動受け入れを【OFF】にしました。");
        } else {
            autoAccept.add(player.getUniqueId());
            player.sendMessage(ChatColor.GREEN + "TP申請の自動受け入れを【ON】にしました。");
        }
    }
}
