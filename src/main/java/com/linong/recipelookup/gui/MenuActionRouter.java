package com.linong.recipelookup.gui;

import com.linong.recipelookup.ALCERecipeViewer;
import com.linong.recipelookup.ConfigManager;
import com.linong.recipelookup.MenuConfig;
import com.linong.recipelookup.bridge.CEBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 统一菜单动作路由器（参照 ALFriends 的注册表模式）。
 * <p>
 * 所有按钮行为统一为 {@code type:value} 动作串，通过 {@code Map} 注册表分发，
 * 替代旧的 per-菜单巨型 switch。通用动作（close/sound/command/op/console/message/open）
 * 与业务动作（open_category/prev_page/save_recipe/...）都在这里注册。
 */
final class MenuActionRouter {

    /** 点击上下文：捕获点击类型、会话菜单类型与槽位，供动作执行时使用 */
    record MenuClickContext(ClickType click, String guiType, int slot) {
    }

    @FunctionalInterface
    interface MenuAction {
        void execute(Player player, MenuClickContext ctx, String value);
    }

    private final ALCERecipeViewer plugin;
    private final RecipeGUI gui;
    private final ConfigManager config;
    private final MenuSoundService sounds;
    private final Map<String, MenuAction> registry = new ConcurrentHashMap<>();
    private final Set<String> unknownActions = ConcurrentHashMap.newKeySet();

    MenuActionRouter(ALCERecipeViewer plugin, RecipeGUI gui, MenuSoundService sounds) {
        this.plugin = plugin;
        this.gui = gui;
        this.config = plugin.getConfigManager();
        this.sounds = sounds;
        registerActions();
    }

    /** 分发单条动作串 {@code type:value}；未知类型只警告一次 */
    void dispatch(Player player, String action, MenuClickContext ctx) {
        String normalized = action.trim();
        if (normalized.regionMatches(true, 0, "action:", 0, 7)) {
            normalized = normalized.substring(7).trim();
        }
        int separator = normalized.indexOf(':');
        String type = separator < 0 ? normalized.toLowerCase(Locale.ROOT)
                : normalized.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        String value = separator < 0 ? "" : normalized.substring(separator + 1).trim();

        MenuAction handler = registry.get(type);
        if (handler == null) {
            if (unknownActions.add(type)) {
                plugin.getLogger().warning("菜单配置了未知动作类型: " + type + "（来源按钮动作串: " + normalized + "）");
            }
            return;
        }
        try {
            handler.execute(player, ctx, value);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "执行菜单动作失败 (" + player.getName() + "): " + normalized, exception);
        }
    }

    // ==================== 注册 ====================

    private void registerActions() {
        // ---- 通用动作 ----
        registry.put("close", (p, ctx, v) -> p.closeInventory());
        registry.put("silent-close", (p, ctx, v) -> p.closeInventory());
        registry.put("force-close", (p, ctx, v) -> p.closeInventory());
        registry.put("sound", (p, ctx, v) -> sounds.play(p, v));
        registry.put("command", this::playerCommands);
        registry.put("cmd", this::playerCommands);
        registry.put("player", this::playerCommands);
        registry.put("execute", this::playerCommands);
        registry.put("op", this::opCommands);
        registry.put("console", this::consoleCommands);
        registry.put("message", this::sendMessage);
        registry.put("msg", this::sendMessage);
        registry.put("tell", this::sendMessage);
        registry.put("open", this::openMenu);

        // ---- 浏览类业务动作 ----
        registry.put("open_category", this::openCategory);
        registry.put("prev_page", (p, ctx, v) -> turnPage(p, ctx, -1));
        registry.put("next_page", (p, ctx, v) -> turnPage(p, ctx, 1));
        registry.put("search", this::beginSearch);
        registry.put("search_mode", this::toggleSearchMode);
        registry.put("search_clear", this::clearSearch);
        registry.put("back", this::goBack);
        registry.put("back_to_main", this::backToMain);
        registry.put("create_recipe", this::openCreatorTypeMenu);
        registry.put("prev_recipe", (p, ctx, v) -> gui.navigateRecipe(p, -1));
        registry.put("next_recipe", (p, ctx, v) -> gui.navigateRecipe(p, 1));
        registry.put("recipe_entry", this::clickRecipeEntry);

        // ---- 配方创建器业务动作 ----
        registry.put("open_creator", (p, ctx, v) -> {
            if (!v.isEmpty()) gui.openRecipeCreator(p, v);
        });
        registry.put("creator_adjust", (p, ctx, v) -> {
            if (!v.isEmpty()) gui.adjustCreatorValue(p, v, ctx.click().isLeftClick());
        });
        registry.put("creator_mode", (p, ctx, v) -> gui.toggleFurnaceMode(p, ctx.click()));
        registry.put("creator_exp_input", (p, ctx, v) -> gui.expectExpInput(p));
        registry.put("save_recipe", (p, ctx, v) -> saveCreatorRecipe(p));
    }

    // ==================== 通用动作实现 ====================

    private void playerCommands(Player player, MenuClickContext ctx, String value) {
        for (String command : MenuActionParser.splitCommands(value)) {
            String normalized = stripSlash(replacePlaceholders(command, player));
            try {
                player.performCommand(normalized);
            } catch (RuntimeException exception) {
                logCommandFailure("player", player, normalized, exception);
            }
        }
    }

    private void consoleCommands(Player player, MenuClickContext ctx, String value) {
        for (String command : MenuActionParser.splitCommands(value)) {
            String normalized = stripSlash(replacePlaceholders(command, player));
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), normalized);
            } catch (RuntimeException exception) {
                logCommandFailure("console", player, normalized, exception);
            }
        }
    }

    /** 临时 OP 身份执行（参照 ALFriends FakeOpCommandDispatcher：结束/异常后恢复原状态） */
    private void opCommands(Player player, MenuClickContext ctx, String value) {
        List<String> commands = MenuActionParser.splitCommands(value).stream()
                .map(command -> stripSlash(replacePlaceholders(command, player)))
                .toList();
        if (commands.isEmpty()) return;
        if (!player.isOnline()) return;

        boolean wasOp = player.isOp();
        try {
            if (!wasOp) player.setOp(true);
            for (String command : commands) {
                if (!player.isOnline()) break;
                try {
                    Bukkit.dispatchCommand(player, command);
                } catch (RuntimeException exception) {
                    logCommandFailure("op", player, command, exception);
                }
            }
        } finally {
            if (player.isOp() != wasOp) {
                try {
                    player.setOp(wasOp);
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.SEVERE,
                            "恢复玩家 OP 状态失败: " + player.getName(), exception);
                }
            }
        }
    }

    private void sendMessage(Player player, MenuClickContext ctx, String value) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', replacePlaceholders(value, player)));
    }

    private void openMenu(Player player, MenuClickContext ctx, String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "main" -> gui.openMainMenu(player);
            case "admin_main" -> gui.openAdminMainMenu(player);
            case "order_main" -> gui.openOrderMainMenu(player);
            case "creator_type" -> gui.openRecipeCreatorType(player);
            default -> {
                if (unknownActions.add("open:" + value)) {
                    plugin.getLogger().warning("open 动作指定了未知菜单: " + value);
                }
            }
        }
    }

    // ==================== 业务动作实现 ====================

    private void openCategory(Player player, MenuClickContext ctx, String value) {
        if (value.isEmpty()) return;
        if (RecipeGUI.TYPE_ADMIN_ORDER_MAIN.equals(ctx.guiType())) {
            gui.openOrderRecipeList(player, value, 0);
            return;
        }
        if (isAdmin(ctx)) gui.openAdminRecipeList(player, value, 0);
        else gui.openRecipeList(player, value, 0);
    }

    private void turnPage(Player player, MenuClickContext ctx, int direction) {
        UUID uuid = player.getUniqueId();
        String categoryId = gui.getPlayerCategory(uuid);
        if (categoryId == null) return;
        int page = gui.getPlayerPage(uuid);

        // 排序菜单翻页：与玩家列表同一套数据（隐藏已过滤）
        if (RecipeGUI.TYPE_ADMIN_ORDER.equals(ctx.guiType())) {
            if (direction < 0) {
                if (page > 0) gui.openOrderRecipeList(player, categoryId, page - 1);
                return;
            }
            java.util.Locale locale = gui.resolveLocale();
            List<CEBridge.RecipeData> recipes = gui.getSortedRecipes(categoryId, null, locale, uuid);
            MenuConfig.MenuDef menu = gui.getPlayerMenuDef(uuid);
            if (menu == null) return;
            int pageSize = MenuConfig.itemSlots(menu.shape()).size();
            int totalPages = Math.max(1, (recipes.size() + pageSize - 1) / pageSize);
            if (page < totalPages - 1) gui.openOrderRecipeList(player, categoryId, page + 1);
            return;
        }

        if (direction < 0) {
            if (page <= 0) return;
            if (isAdmin(ctx)) gui.openAdminRecipeList(player, categoryId, page - 1);
            else gui.openRecipeList(player, categoryId, page - 1);
            return;
        }

        java.util.Locale locale = gui.resolveLocale();
        List<CEBridge.RecipeData> recipes = isAdmin(ctx)
                ? gui.getSortedRecipesAdmin(categoryId, gui.getSearchQuery(uuid), locale, uuid)
                : gui.getSortedRecipes(categoryId, gui.getSearchQuery(uuid), locale, uuid);
        MenuConfig.MenuDef menu = gui.getPlayerMenuDef(uuid);
        if (menu == null) return;
        int pageSize = MenuConfig.itemSlots(menu.shape()).size();
        int totalPages = Math.max(1, (recipes.size() + pageSize - 1) / pageSize);
        if (page < totalPages - 1) {
            if (isAdmin(ctx)) gui.openAdminRecipeList(player, categoryId, page + 1);
            else gui.openRecipeList(player, categoryId, page + 1);
        }
    }

    private void beginSearch(Player player, MenuClickContext ctx, String value) {
        UUID uuid = player.getUniqueId();
        String categoryId = gui.getPlayerCategory(uuid);
        if (categoryId == null) return;
        String mode = gui.getSearchMode(uuid);
        plugin.getChatSearchListener().expectSearch(player, categoryId, mode);
    }

    private void toggleSearchMode(Player player, MenuClickContext ctx, String value) {
        gui.toggleSearchMode(player);
        if (isAdmin(ctx)) {
            // toggleSearchMode 内部会重开普通列表，管理端需要再刷回管理列表
            String category = gui.getPlayerCategory(player.getUniqueId());
            int page = gui.getPlayerPage(player.getUniqueId());
            gui.openAdminRecipeList(player, category, page);
        }
    }

    private void clearSearch(Player player, MenuClickContext ctx, String value) {
        if (isAdmin(ctx)) gui.clearAdminSearch(player);
        else gui.clearSearch(player);
        plugin.getChatSearchListener().cancelSearch(player);
    }

    private void goBack(Player player, MenuClickContext ctx, String value) {
        UUID uuid = player.getUniqueId();
        if (RecipeGUI.TYPE_DETAIL.equals(ctx.guiType()) || RecipeGUI.TYPE_ADMIN_DETAIL.equals(ctx.guiType())) {
            gui.stopRecipeCycle(player);
        }
        String categoryId = gui.getPlayerCategory(uuid);
        int page = gui.getPlayerPage(uuid);
        if (categoryId == null) {
            // 无上级分类信息时按旧行为回主菜单（openRecipeList(null) 即主菜单）
            if (!RecipeGUI.TYPE_DETAIL.equals(ctx.guiType())
                    && !RecipeGUI.TYPE_ADMIN_DETAIL.equals(ctx.guiType())) {
                gui.openRecipeList(player, null, page);
            }
            return;
        }
        if (RecipeGUI.TYPE_ADMIN_ORDER.equals(ctx.guiType())) {
            gui.openOrderMainMenu(player);
            return;
        }
        if (isAdmin(ctx)) gui.openAdminRecipeList(player, categoryId, page);
        else gui.openRecipeList(player, categoryId, page);
    }

    private void backToMain(Player player, MenuClickContext ctx, String value) {
        if (RecipeGUI.TYPE_ADMIN_ORDER.equals(ctx.guiType())
                || RecipeGUI.TYPE_ADMIN_ORDER_MAIN.equals(ctx.guiType())) {
            gui.openOrderMainMenu(player);
            return;
        }
        if (isAdmin(ctx)) gui.openAdminMainMenu(player);
        else gui.openMainMenu(player);
    }

    private void openCreatorTypeMenu(Player player, MenuClickContext ctx, String value) {
        if (player.hasPermission("alcerecipeviewer.admin")) {
            gui.openRecipeCreatorType(player);
        } else {
            player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorAdminOnly());
        }
    }

    private void saveCreatorRecipe(Player player) {
        UUID uuid = player.getUniqueId();
        gui.saveCreatorRecipe(player);
        gui.returnCreatorItems(player, gui.getOpenInventory(uuid));
        String categoryId = gui.getPlayerCategory(uuid);
        int page = gui.getPlayerPage(uuid);
        gui.removePlayer(uuid);
        if (categoryId != null) gui.openRecipeList(player, categoryId, page);
        else player.closeInventory();
    }

    /** 配方列表动态区（'I'）点击：普通列表打开详情，管理列表切换可见性，排序列表左键上移/右键下移 */
    private void clickRecipeEntry(Player player, MenuClickContext ctx, String value) {
        UUID uuid = player.getUniqueId();
        String categoryId = gui.getPlayerCategory(uuid);
        if (categoryId == null) return;
        int page = gui.getPlayerPage(uuid);
        MenuConfig.MenuDef menu = gui.getPlayerMenuDef(uuid);
        if (menu == null) return;

        List<Integer> itemSlots = MenuConfig.itemSlots(menu.shape());
        int itemIndex = itemSlots.indexOf(ctx.slot());
        if (itemIndex < 0) return;

        java.util.Locale locale = gui.resolveLocale();
        boolean admin = isAdmin(ctx);
        List<CEBridge.RecipeData> recipes = gui.getPlayerRecipes(uuid);
        if (recipes == null) {
            recipes = admin
                    ? gui.getSortedRecipesAdmin(categoryId, gui.getSearchQuery(uuid), locale, uuid)
                    : gui.getSortedRecipes(categoryId, gui.getSearchQuery(uuid), locale, uuid);
        }

        int recipeIdx = page * itemSlots.size() + itemIndex;
        if (recipeIdx >= recipes.size()) return;
        CEBridge.RecipeData recipe = recipes.get(recipeIdx);

        // 排序菜单：左键上移 / 右键下移，Shift+左/右键进入聊天输入一次移动多格
        if (RecipeGUI.TYPE_ADMIN_ORDER.equals(ctx.guiType())) {
            if (ctx.click().isShiftClick() && ctx.click().isLeftClick()) {
                gui.expectOrderMoveInput(player, categoryId, recipe.resultId, -1, page);
            } else if (ctx.click().isShiftClick() && ctx.click().isRightClick()) {
                gui.expectOrderMoveInput(player, categoryId, recipe.resultId, 1, page);
            } else if (ctx.click().isLeftClick()) {
                gui.moveVisibleRecipe(categoryId, recipes, recipeIdx, -1);
                gui.openOrderRecipeList(player, categoryId, page);
            } else if (ctx.click().isRightClick()) {
                gui.moveVisibleRecipe(categoryId, recipes, recipeIdx, 1);
                gui.openOrderRecipeList(player, categoryId, page);
            }
            return;
        }

        if (ctx.click().isRightClick()) return;

        if (admin) {
            gui.toggleRecipeVisibility(player, recipe);
            gui.openAdminRecipeList(player, categoryId, page);
        } else {
            if (config.isDebug()) debugRecipeClick(player, recipe);
            gui.openRecipeDetail(player, recipe, categoryId, page);
        }
    }

    // ==================== 辅助 ====================

    private boolean isAdmin(MenuClickContext ctx) {
        return ctx.guiType() != null && ctx.guiType().startsWith("admin");
    }

    private String replacePlaceholders(String value, Player player) {
        return value
                .replace("{player}", player.getName())
                .replace("%player_name%", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("%player_uuid%", player.getUniqueId().toString())
                .replace("{world}", player.getWorld().getName())
                .replace("%world%", player.getWorld().getName());
    }

    private String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1).trim() : command;
    }

    private void logCommandFailure(String senderType, Player player, String command, Exception exception) {
        plugin.getLogger().log(Level.SEVERE,
                "执行菜单 " + senderType + " 命令失败 (" + player.getName() + "): " + command, exception);
    }

    // ==================== 调试 ====================

    private void debugRecipeClick(Player player, CEBridge.RecipeData recipe) {
        java.util.Locale locale = gui.resolveLocale();
        String resultId = recipe.resultId;
        String name = gui.toChineseName(resultId, locale);
        player.sendMessage("§e[调试] §7物品ID: §f" + resultId);
        player.sendMessage("§e[调试] §7显示名: §f" + name);
        player.sendMessage("§e[调试] §7搜索以下关键词可找到此物品:");
        if (name.length() >= 2) player.sendMessage("§e[调试]   §a" + name.substring(0, 2) + " §7→ 前2字");
        if (name.length() >= 1) player.sendMessage("§e[调试]   §a" + name.substring(0, 1) + " §7→ 首字");
        player.sendMessage("§e[调试]   §a" + name + " §7→ 全名");
        for (String ing : recipe.ingredientIds) {
            player.sendMessage("§e[调试] §7原料: §f" + ing + " §7→ §f" + gui.toChineseName(ing, locale));
        }
        plugin.getLogger().info("[调试] 点击配方: " + recipe.id + " resultId=" + resultId + " name=" + name);
    }
}
