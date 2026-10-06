package com.linong.recipelookup.command;

import com.linong.recipelookup.ALCERecipeViewer;
import com.linong.recipelookup.ConfigManager;
import com.linong.recipelookup.MenuConfig;
import com.linong.recipelookup.OrderShareManager;
import com.linong.recipelookup.gui.RecipeGUI;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
            // 控制台仅支持分享码导入（完整指令一段式粘贴）
            if (args.length >= 4
                    && ("admin".equalsIgnoreCase(args[0]) || "manage".equalsIgnoreCase(args[0]))
                    && ("order".equalsIgnoreCase(args[1]) || "sort".equalsIgnoreCase(args[1]))
                    && "import".equalsIgnoreCase(args[2])) {
                plugin.getOrderShareManager()
                        .receiveChunk(sender, OrderShareManager.CONSOLE_KEY, args[3]);
                return true;
            }
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
            case "admin", "manage" -> handleAdmin(player, args);
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

    /** /alcerecipes admin - 管理主菜单；/alcerecipes admin order [分类] - 排序菜单 */
    private void handleAdmin(Player player, String[] args) {
        if (!player.hasPermission("alcerecipeviewer.admin")) {
            player.sendMessage(config.getPluginPrefix() + " " + config.getCmdNoPermission());
            return;
        }
        if (args.length >= 2) {
            String sub = args[1].toLowerCase();
            if ("order".equals(sub) || "sort".equals(sub)) {
                handleOrder(player, args);
                return;
            }
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

    /**
     * /alcerecipes admin order [分类ID] - 打开排序菜单（内容与玩家看到的一致）
     * /alcerecipes admin order export [分类ID] - 生成分享码（LuckPerms exportcode 风格）
     * /alcerecipes admin order import <段码> - 逐段粘贴导入；不带段码 = 取消本次导入
     */
    private void handleOrder(Player player, String[] args) {
        String action = args.length >= 3 ? args[2].toLowerCase() : "";
        switch (action) {
            case "export" -> handleExport(player, args.length >= 4 ? args[3].toLowerCase() : null);
            case "import" -> handleImport(player, args);
            default -> {
                if (plugin.getLoadedRecipes().isEmpty()) {
                    player.sendMessage(config.getPluginPrefix() + " " + config.getCmdNoRecipes());
                    return;
                }
                if (args.length >= 3) {
                    String category = args[2].toLowerCase();
                    if (!plugin.getLoadedRecipes().containsKey(category)) {
                        player.sendMessage(config.getPluginPrefix() + " " + config.getOrderNoCategory(category));
                        return;
                    }
                    gui.openOrderRecipeList(player, category, 0);
                    return;
                }
                gui.openOrderMainMenu(player);
            }
        }
    }

    /** 生成分享码：聊天栏按段输出，点击即复制完整导入指令 */
    private void handleExport(Player player, String category) {
        if (category != null && !category.isEmpty() && !plugin.getLoadedRecipes().containsKey(category)) {
            player.sendMessage(config.getPluginPrefix() + " " + config.getOrderNoCategory(category));
            return;
        }
        OrderShareManager.ExportResult result = plugin.getOrderShareManager()
                .exportCode(category == null || category.isEmpty() ? null : category);
        if (result == null) {
            player.sendMessage(config.getPluginPrefix() + " " + config.getShareEmpty());
            return;
        }
        int total = result.parts().size();
        if (total == 1) {
            // 增量很小：单段搞定，点一次粘一次完事
            player.sendMessage(config.getShareExportSingle());
        } else {
            player.sendMessage(config.getShareExportHeader(total));
        }
        for (int i = 0; i < total; i++) {
            String command = "/alcerecipes admin order import " + result.parts().get(i);
            sendCopyable(player,
                    config.getShareChunkLabel(i + 1, total),
                    command,
                    config.getShareChunkHover());
        }
        if (total > 1 && result.fullCommand() != null) {
            // 长码附加一段式完整指令：控制台 / 命令方块粘贴一次导入
            sendCopyable(player,
                    config.getShareFullLabel(),
                    result.fullCommand(),
                    config.getShareFullHover());
        }
        player.sendMessage(config.getPluginPrefix() + " " + config.getShareExportFooter());
    }

    /** 逐段接收分享码；无段码参数时取消进行中的导入 */
    private void handleImport(Player player, String[] args) {
        if (args.length >= 4) {
            plugin.getOrderShareManager().receiveChunk(player, player.getUniqueId(), args[3]);
            return;
        }
        if (plugin.getOrderShareManager().hasSession(player.getUniqueId())) {
            plugin.getOrderShareManager().clearSession(player.getUniqueId());
            player.sendMessage(config.getPluginPrefix() + " " + config.getShareImportCancelled());
        } else {
            player.sendMessage(config.getPluginPrefix() + " " + config.getShareImportUsage());
        }
    }

    /** 发送点击复制到剪贴板的聊天组件（LuckPerms 分享码同款交互） */
    private void sendCopyable(Player player, String display, String copyText, String hover) {
        TextComponent component = new TextComponent(display);
        component.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copyText));
        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
        player.spigot().sendMessage(component);
    }

    private void sendHelp(Player player) {
        player.sendMessage(config.getPluginPrefix() + " " + config.getCmdUsageTitle());
        player.sendMessage(config.getCmdUsageOpen());
        player.sendMessage(config.getCmdUsageReload());
        player.sendMessage(config.getCreatorHelpClear());
        player.sendMessage("§e  /alcerecipes create §7- 打开新增配方菜单（管理员）");
        if (player.hasPermission("alcerecipeviewer.admin")) {
            player.sendMessage("§e  /alcerecipes admin §7- 打开配方管理菜单（管理员）");
            player.sendMessage("§e  /alcerecipes admin order [分类] §7- 打开排序菜单调整配方顺序（管理员）");
            player.sendMessage("§e  /alcerecipes admin order export [分类] §7- 生成排序分享码（管理员）");
            player.sendMessage("§e  /alcerecipes admin order import <段码> §7- 粘贴分享码导入（管理员）");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return List.of("reload", "clear", "create", "admin", "manage").stream()
                    .filter(s -> s.startsWith(prefix)).sorted().toList();
        }
        String sub = args[0].toLowerCase();
        boolean adminCmd = "admin".equals(sub) || "manage".equals(sub);
        if (!adminCmd) return List.of();

        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return List.of("order").stream()
                    .filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 3) {
            String subSub = args[1].toLowerCase();
            if (!("order".equals(subSub) || "sort".equals(subSub))) return List.of();
            String prefix = args[2].toLowerCase();
            List<String> options = new java.util.ArrayList<>(List.of("export", "import"));
            options.addAll(availableCategories());
            return options.stream()
                    .filter(s -> s.startsWith(prefix)).sorted().toList();
        }
        if (args.length == 4) {
            String subSub = args[1].toLowerCase();
            String action = args[2].toLowerCase();
            if (!("order".equals(subSub) || "sort".equals(subSub)) || !"export".equals(action)) return List.of();
            String prefix = args[3].toLowerCase();
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
