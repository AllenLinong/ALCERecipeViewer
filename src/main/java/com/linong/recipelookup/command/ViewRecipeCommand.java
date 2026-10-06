package com.linong.recipelookup.command;

import com.linong.recipelookup.ALCERecipeViewer;
import com.linong.recipelookup.ConfigManager;
import com.linong.recipelookup.MenuConfig;
import com.linong.recipelookup.gui.RecipeGUI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * /alcerecipes 命令处理器。
 * 无参数 → 打开主菜单 / reload → 重载 / clear → 清空配方缓存。
 */
public class ViewRecipeCommand implements CommandExecutor, TabCompleter {

    private final ALCERecipeViewer plugin;
    private final RecipeGUI gui;
    private final ConfigManager config;

    public ViewRecipeCommand(ALCERecipeViewer plugin) {
        this.plugin = plugin;
        this.gui = plugin.getRecipeGUI();
        this.config = plugin.getConfigManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.getCmdPlayerOnly());
            return true;
        }

        if (args.length == 0) {
            if (plugin.getLoadedRecipes().isEmpty()) {
                player.sendMessage(config.getPluginPrefix() + " " + config.getCmdNoRecipes());
                return true;
            }
            gui.openMainMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> handleReload(player);
            case "clear" -> handleClear(player);
            case "create", "new" -> handleCreate(player);
            case "admin", "manage" -> handleAdmin(player);
            case "order", "sort" -> handleOrder(player, args);
            default -> sendHelp(player);
        }
        return true;
    }

    private void handleReload(Player player) {
        if (!player.hasPermission("alcerecipeviewer.admin")) {
            player.sendMessage(config.getPluginPrefix() + " " + config.getCmdNoPermission());
            return;
        }
        plugin.getFoliaLib().getScheduler().runAsync(task -> {
            plugin.reloadRecipes();
            plugin.getFoliaLib().getScheduler().runAtEntity(player, t -> {
                int total = plugin.getLoadedRecipes().values().stream()
                        .mapToInt(List::size).sum();
                player.sendMessage(config.getPluginPrefix() + " " + config.getCmdReloaded(total));
            });
        });
    }

    private void handleCreate(Player player) {
        if (!player.hasPermission("alcerecipeviewer.admin")) {
            player.sendMessage(config.getPluginPrefix() + " " + config.getCmdNoPermission());
            return;
        }
        gui.openRecipeCreatorType(player);
    }

    private void handleAdmin(Player player) {
        if (!player.hasPermission("alcerecipeviewer.admin")) {
            player.sendMessage(config.getPluginPrefix() + " " + config.getCmdNoPermission());
            return;
        }
        if (plugin.getLoadedRecipes().isEmpty()) {
            player.sendMessage(config.getPluginPrefix() + " " + config.getCmdNoRecipes());
            return;
        }
        gui.openAdminMainMenu(player);
    }

    private void handleClear(Player player) {
        if (!player.hasPermission("alcerecipeviewer.admin")) {
            player.sendMessage(config.getPluginPrefix() + " " + config.getCmdNoPermission());
            return;
        }
        plugin.clearRecipes();
        player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorCleared());
    }

    /** /alcerecipes order [分类ID] — 打开排序菜单（内容与玩家看到的一致，左键上移/右键下移） */
    private void handleOrder(Player player, String[] args) {
        if (!player.hasPermission("alcerecipeviewer.admin")) {
            player.sendMessage(config.getPluginPrefix() + " " + config.getCmdNoPermission());
            return;
        }
        if (plugin.getLoadedRecipes().isEmpty()) {
            player.sendMessage(config.getPluginPrefix() + " " + config.getCmdNoRecipes());
            return;
        }
        if (args.length >= 2) {
            String category = args[1].toLowerCase();
            if (!plugin.getLoadedRecipes().containsKey(category)) {
                player.sendMessage(config.getPluginPrefix() + " " + config.getOrderNoCategory(category));
                return;
            }
            gui.openOrderRecipeList(player, category, 0);
            return;
        }
        gui.openOrderMainMenu(player);
    }

    private void sendHelp(Player player) {
        player.sendMessage(config.getPluginPrefix() + " " + config.getCmdUsageTitle());
        player.sendMessage(config.getCmdUsageOpen());
        player.sendMessage(config.getCmdUsageReload());
        player.sendMessage(config.getCreatorHelpClear());
        player.sendMessage("§e  /alcerecipes create §7- 打开新增配方菜单（管理员）");
        if (player.hasPermission("alcerecipeviewer.admin")) {
            player.sendMessage("§e  /alcerecipes admin §7- 打开配方管理菜单（管理员）");
            player.sendMessage("§e  /alcerecipes order [分类] §7- 打开排序菜单调整配方顺序（管理员）");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return List.of("reload", "clear", "create", "admin", "manage", "order", "sort").stream()
                    .filter(s -> s.startsWith(prefix)).sorted().toList();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (!("order".equals(sub) || "sort".equals(sub))) return List.of();
            String prefix = args[1].toLowerCase();
            return availableCategories().stream()
                    .filter(s -> s.startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }

    /** 主菜单配置里声明、且当前有配方数据的分类 */
    private List<String> availableCategories() {
        var menu = plugin.getMenuConfig().getMainMenu();
        if (menu == null) return List.of();
        return menu.buttons().values().stream()
                .map(MenuConfig.ButtonDef::category)
                .filter(c -> c != null && !c.isEmpty())
                .filter(c -> plugin.getLoadedRecipes().containsKey(c))
                .distinct()
                .toList();
    }
}
