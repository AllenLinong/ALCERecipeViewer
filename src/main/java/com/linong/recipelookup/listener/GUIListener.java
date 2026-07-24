package com.linong.recipelookup.listener;

import com.linong.recipelookup.ALCERecipeViewer;
import com.linong.recipelookup.ConfigManager;
import com.linong.recipelookup.MenuConfig;
import com.linong.recipelookup.MenuConfig.ButtonDef;
import com.linong.recipelookup.MenuConfig.MenuDef;
import com.linong.recipelookup.bridge.CEBridge;
import com.linong.recipelookup.gui.RecipeGUI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * GUI 事件监听器。禁止取放物品，根据按钮 action 路由点击。
 */
public class GUIListener implements Listener {

    private final ALCERecipeViewer plugin;
    private final RecipeGUI gui;
    private final ConfigManager config;

    public GUIListener(ALCERecipeViewer plugin) {
        this.plugin = plugin;
        this.gui = plugin.getRecipeGUI();
        this.config = plugin.getConfigManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        UUID uuid = player.getUniqueId();
        Inventory topInventory = event.getView().getTopInventory();
        if (!gui.isOurGUI(uuid, topInventory)) return;
        scheduleGUIItemCleanup(player);
        if (!gui.isCurrentGUI(uuid, topInventory)) {
            event.setCancelled(true);
            return;
        }
        String guiType = gui.getInventoryType(uuid, topInventory);
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        // 配方详情 GUI：顶部只处理导航按钮，底部背包允许正常整理。
        if (RecipeGUI.TYPE_DETAIL.equals(guiType)) {
            if (raw < 0) {
                event.setCancelled(true);
                return;
            }
            if (raw >= topSize) {
                if (shouldBlockBottomTransfer(event)) event.setCancelled(true);
                return;
            }

            event.setCancelled(true);
            MenuDef detailMenu = gui.getPlayerMenuDef(player.getUniqueId());
            if (detailMenu != null) {
                ButtonDef btn = MenuConfig.buttonAt(detailMenu, raw);
                if (btn != null) {
                    playButtonSound(player, btn);
                    switch (btn.action()) {
                        case "RUN_COMMAND" -> runButtonCommand(player, btn);
                        case "BACK" -> {
                            gui.stopRecipeCycle(player);
                            String cat = gui.getPlayerCategory(player.getUniqueId());
                            int page = gui.getPlayerPage(player.getUniqueId());
                            if (cat != null) gui.openRecipeList(player, cat, page);
                        }
                        case "PREV_RECIPE" -> gui.navigateRecipe(player, -1);
                        case "NEXT_RECIPE" -> gui.navigateRecipe(player, 1);
                    }
                }
            }
            return;
        }

        // creator：Paper 1.21 原生拖拽模式（参照 KitEditorListener）
        if ("creator".equals(guiType)) {
            if (raw < 0) {
                event.setCancelled(true);
                return;
            }
            if (raw >= topSize) {
                if (shouldBlockBottomTransfer(event)) event.setCancelled(true);
                return;
            }
            if (event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
                return;
            }
            // top inventory: 只放行创建器声明过的动态输入槽。
            MenuDef menu = getCreatorMenu(player);
            if (menu != null) {
                ButtonDef btn = MenuConfig.buttonAt(menu, raw);
                if (btn == null || !btn.dynamic()) {
                    event.setCancelled(true);
                    if (btn != null && !btn.action().isEmpty()) {
                        handleCreatorAction(player, event, btn);
                    }
                }
                // I/R dynamic → 不取消，Paper 原生处理
            } else {
                event.setCancelled(true);
            }
            return;
        }
        if ("creator_type".equals(guiType)) {
            event.setCancelled(true);
            if (raw >= topSize || raw < 0) return;
            MenuDef menu = plugin.getMenuConfig().getRecipeCreatorType();
            if (menu != null) {
                ButtonDef btn = MenuConfig.buttonAt(menu, raw);
                if (btn != null) handleCreatorAction(player, event, btn);
            }
            return;
        }

        // 主菜单/配方列表：顶部完全只读；底部背包可正常整理。
        if (raw < 0) {
            event.setCancelled(true);
            return;
        }
        if (raw >= topSize) {
            if (shouldBlockBottomTransfer(event)) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);

        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

        int slot = event.getSlot();
        MenuDef menu = gui.getPlayerMenuDef(player.getUniqueId());

        switch (guiType) {
            case RecipeGUI.TYPE_MAIN -> handleMainClick(player, slot, menu);
            case RecipeGUI.TYPE_LIST -> handleListClick(player, slot, menu, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        Inventory topInventory = event.getView().getTopInventory();
        if (!gui.isOurGUI(uuid, topInventory)) return;
        scheduleGUIItemCleanup(player);
        if (!gui.isCurrentGUI(uuid, topInventory)) {
            event.setCancelled(true);
            return;
        }
        String type = gui.getInventoryType(uuid, topInventory);
        int topSize = event.getView().getTopInventory().getSize();

        if ("creator_type".equals(type)) { event.setCancelled(true); return; }
        if ("creator".equals(type)) {
            MenuDef menu = getCreatorMenu(player);
            if (menu != null) {
                for (int raw : event.getRawSlots()) {
                    if (raw < topSize && !isCreatorInputSlot(menu, raw)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            } else {
                event.setCancelled(true);
            }
            return;
        }
        // 浏览/详情 GUI：只阻止拖入顶部菜单，底部背包内拖动保持原版行为。
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** 阻止原版配方书在工作台/熔炉/锻造台详情 GUI 中填充物品 */

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && RecipeGUI.TYPE_DETAIL.equals(gui.getGUIType(player.getUniqueId()))
                && gui.isOurGUI(player.getUniqueId(), event.getView().getTopInventory()))
            event.setCancelled(true);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (org.bukkit.entity.HumanEntity v : event.getViewers()) {
            if (v instanceof Player p
                    && RecipeGUI.TYPE_DETAIL.equals(gui.getGUIType(p.getUniqueId()))
                    && gui.isOurGUI(p.getUniqueId(), event.getView().getTopInventory())) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onSmithItem(SmithItemEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && RecipeGUI.TYPE_DETAIL.equals(gui.getGUIType(player.getUniqueId()))
                && gui.isOurGUI(player.getUniqueId(), event.getView().getTopInventory()))
            event.setCancelled(true);
    }

    @EventHandler
    public void onPrepareSmith(PrepareSmithingEvent event) {
        for (org.bukkit.entity.HumanEntity v : event.getViewers()) {
            if (v instanceof Player p
                    && RecipeGUI.TYPE_DETAIL.equals(gui.getGUIType(p.getUniqueId()))
                    && gui.isOurGUI(p.getUniqueId(), event.getView().getTopInventory())) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory closed = event.getInventory();
        if (!gui.isOurGUI(player.getUniqueId(), closed)) return;
        scheduleGUIItemCleanup(player, true);
        if (!gui.isCurrentGUI(player.getUniqueId(), closed)) return;

        String type = gui.getInventoryType(player.getUniqueId(), closed);
        if (RecipeGUI.TYPE_DETAIL.equals(type)) {
            gui.stopRecipeCycle(player);
            gui.removePlayer(player.getUniqueId());
        } else if ("creator".equals(type)) {
            if (!gui.pendingExpInput.containsKey(player.getUniqueId())) {
                returnCreatorItems(player, closed);
            }
            gui.removePlayer(player.getUniqueId());
        } else {
            gui.removePlayer(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory opened = event.getInventory();
        if (gui.isOurGUI(player.getUniqueId(), opened)) return;
        if (!gui.getGUIType(player.getUniqueId()).isEmpty()) {
            gui.removePlayer(player.getUniqueId());
            scheduleGUIItemCleanup(player, true);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        gui.discardPlayer(player.getUniqueId());
        scheduleGUIItemCleanup(player, true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getChatSearchListener().cancelSearch(event.getPlayer());
        gui.discardPlayer(event.getPlayer().getUniqueId());
    }

    // ========== 按钮声音 ==========

    /** 播放按钮点击声音。支持原版 Sound 键名和 CE 自定义声音 ID（如 namespace:sound_id）。 */
    private void playButtonSound(Player player, ButtonDef btn) {
        if (btn == null || btn.sound() == null || btn.sound().isEmpty()) return;
        String key = btn.sound();
        try {
            player.playSound(player.getLocation(), key, SoundCategory.MASTER, 1.0f, 1.0f);
        } catch (Exception ignored) {
            // 自定义声音可能未注册，静默忽略
        }
    }

    /** 执行按钮的自定义命令 */
    private void runButtonCommand(Player player, ButtonDef btn) {
        String cmd = btn.command();
        if (cmd == null || cmd.isEmpty()) return;
        if (btn.asPlayer()) {
            player.performCommand(cmd);
        } else {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    cmd.replace("{player}", player.getName()));
        }
    }

    // ========== 主菜单 ==========

    private void handleMainClick(Player player, int slot, MenuDef menu) {
        if (menu == null) return;
        ButtonDef btn = MenuConfig.buttonAt(menu, slot);
        if (btn == null || btn.action().isEmpty()) return;

        playButtonSound(player, btn);

        switch (btn.action()) {
            case "OPEN_CATEGORY" -> {
                if (!btn.category().isEmpty()) {
                    gui.openRecipeList(player, btn.category(), 0);
                }
            }
            case "RUN_COMMAND" -> runButtonCommand(player, btn);
            case "CLOSE" -> player.closeInventory();
        }
    }

    // ========== 配方列表 ==========

    private void handleListClick(Player player, int slot, MenuDef menu, InventoryClickEvent event) {
        if (menu == null) return;

        String categoryId = gui.getPlayerCategory(player.getUniqueId());
        if (categoryId == null) return;
        int page = gui.getPlayerPage(player.getUniqueId());

        // 获取槽位对应的按钮定义
        int row = slot / 9;
        int col = slot % 9;
        if (row >= menu.shape().length) return;
        String line = menu.shape()[row];
        if (col >= line.length()) return;
        char c = line.charAt(col);

        if (c == 'I') {
            // 动态物品区 → 打开配方详情（使用 openRecipeList 缓存的排序结果，避免重复排序）
            java.util.Locale locale = gui.resolveLocale();
            List<CEBridge.RecipeData> recipes = gui.getPlayerRecipes(player.getUniqueId());
            if (recipes == null) {
                recipes = gui.getSortedRecipes(categoryId,
                        gui.getSearchQuery(player.getUniqueId()), locale, player.getUniqueId());
            }

            List<Integer> itemSlots = MenuConfig.itemSlots(menu.shape());
            int itemIndex = itemSlots.indexOf(slot);
            if (itemIndex < 0) return;

            int pageSize = itemSlots.size();
            int recipeIdx = page * pageSize + itemIndex;
            if (recipeIdx < recipes.size()) {
                CEBridge.RecipeData recipe = recipes.get(recipeIdx);
                if (config.isDebug()) debugRecipeClick(player, recipe);
                gui.openRecipeDetail(player, recipe, categoryId, page);
            }
            return;
        }

        // 导航按钮
        ButtonDef btn = menu.buttons().get(c);
        if (btn == null || btn.action().isEmpty()) return;

        playButtonSound(player, btn);

        switch (btn.action()) {
            case "RUN_COMMAND" -> runButtonCommand(player, btn);
            case "PREV_PAGE" -> {
                if (page > 0) gui.openRecipeList(player, categoryId, page - 1);
            }
            case "NEXT_PAGE" -> {
                java.util.Locale locale = gui.resolveLocale();
                List<CEBridge.RecipeData> recipes = gui.getSortedRecipes(categoryId,
                        gui.getSearchQuery(player.getUniqueId()), locale, player.getUniqueId());
                int pageSize = MenuConfig.itemSlots(menu.shape()).size();
                int totalPages = Math.max(1, (recipes.size() + pageSize - 1) / pageSize);
                if (page < totalPages - 1) gui.openRecipeList(player, categoryId, page + 1);
            }
            case "SEARCH" -> handleSearchClick(player, categoryId, event);
            case "BACK_TO_MAIN" -> gui.openMainMenu(player);
            case "CREATE_RECIPE" -> {
                if (player.hasPermission("alcerecipeviewer.admin")) gui.openRecipeCreatorType(player);
                else player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorAdminOnly());
            }
            case "CLOSE" -> player.closeInventory();
        }
    }

    private void handleSearchClick(Player player, String categoryId, InventoryClickEvent event) {
        ClickType click = event.getClick();
        if (click == ClickType.SHIFT_LEFT) {
            gui.clearSearch(player);
            plugin.getChatSearchListener().cancelSearch(player);
        } else if (click == ClickType.RIGHT) {
            gui.toggleSearchMode(player);
        } else {
            String mode = gui.getSearchMode(player.getUniqueId());
            plugin.getChatSearchListener().expectSearch(player, categoryId, mode);
        }
    }

    // ========== 新增配方 GUI ==========

    private MenuDef getCreatorMenu(Player player) {
        String cType = gui.creatorType.getOrDefault(player.getUniqueId(), "shaped");
        return switch (cType) {
            case "furnace" -> plugin.getMenuConfig().getRecipeCreatorFurnace();
            case "smoking" -> plugin.getMenuConfig().getRecipeCreatorSmoking();
            case "campfire" -> plugin.getMenuConfig().getRecipeCreatorCampfire();
            case "brewing" -> plugin.getMenuConfig().getRecipeCreatorBrewing();
            case "stonecutting" -> plugin.getMenuConfig().getRecipeCreatorStonecutter();
            case "smithing" -> plugin.getMenuConfig().getRecipeCreatorSmithing();
            case "shapeless" -> plugin.getMenuConfig().getRecipeCreatorShapeless();
            default -> plugin.getMenuConfig().getRecipeCreatorShaped();
        };
    }

    private void handleCreatorAction(Player player, InventoryClickEvent event, ButtonDef btn) {
        if (btn == null || btn.action().isEmpty()) return;
        playButtonSound(player, btn);
        switch (btn.action()) {
            case "RUN_COMMAND" -> runButtonCommand(player, btn);
            case "CREATOR_SHAPED" -> gui.openRecipeCreator(player, "shaped");
            case "CREATOR_SHAPELESS" -> gui.openRecipeCreator(player, "shapeless");
            case "CREATOR_FURNACE" -> gui.openRecipeCreator(player, "furnace");
            case "CREATOR_SMITHING" -> gui.openRecipeCreator(player, "smithing");
            case "CREATOR_STONECUTTER" -> gui.openRecipeCreator(player, "stonecutting");
            case "CREATOR_CAMPFIRE" -> gui.openRecipeCreator(player, "campfire");
            case "CREATOR_SMOKING" -> gui.openRecipeCreator(player, "smoking");
            case "CREATOR_BREWING" -> gui.openRecipeCreator(player, "brewing");
            case "CREATOR_BLAST_TIME" -> gui.adjustCreatorValue(player, "G", event.getClick().isLeftClick());
            case "CREATOR_FURNACE_TIME" -> gui.adjustCreatorValue(player, "P", event.getClick().isLeftClick());
            case "CREATOR_FURNACE_MODE" -> gui.toggleFurnaceMode(player, event.getClick());
            case "CREATOR_SMOKING_TIME" -> gui.adjustCreatorValue(player, "Y", event.getClick().isLeftClick());
            case "CREATOR_CAMPFIRE_TIME" -> gui.adjustCreatorValue(player, "G", event.getClick().isLeftClick());
            case "CREATOR_EXP" -> {
                if (event.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_LEFT) {
                    gui.expectExpInput(player);
                } else {
                    gui.adjustCreatorValue(player, "E", event.getClick().isLeftClick());
                }
            }
            case "SAVE_RECIPE" -> {
                gui.saveCreatorRecipe(player);
                returnCreatorItems(player, gui.getOpenInventory(player.getUniqueId()));
                String cat = gui.getPlayerCategory(player.getUniqueId());
                int page = gui.getPlayerPage(player.getUniqueId());
                gui.removePlayer(player.getUniqueId());
                if (cat != null) gui.openRecipeList(player, cat, page);
                else player.closeInventory();
            }
            case "BACK" -> gui.openRecipeList(player,
                    gui.getPlayerCategory(player.getUniqueId()), gui.getPlayerPage(player.getUniqueId()));
        }
    }

    // ========== 辅助 ==========

    private boolean isCreatorInputSlot(MenuDef menu, int rawSlot) {
        if (menu == null || rawSlot < 0) return false;
        ButtonDef btn = MenuConfig.buttonAt(menu, rawSlot);
        return btn != null && btn.dynamic();
    }

    private boolean shouldBlockBottomTransfer(InventoryClickEvent event) {
        return event.isShiftClick()
                || event.getClick() == ClickType.DOUBLE_CLICK
                || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR;
    }

    private void returnCreatorItems(Player player, Inventory inv) {
        if (inv == null) return;
        MenuDef menu = getCreatorMenu(player);
        if (menu == null) return;

        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (!isCreatorInputSlot(menu, slot)) continue;
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            if (gui.isGUIItem(item)) {
                inv.setItem(slot, null);
                continue;
            }

            inv.setItem(slot, null);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private void scheduleGUIItemCleanup(Player player) {
        scheduleGUIItemCleanup(player, false);
    }

    private void scheduleGUIItemCleanup(Player player, boolean resyncInventory) {
        plugin.getFoliaLib().getScheduler().runAtEntityLater(player, () -> {
            if (!player.isOnline()) return;
            gui.removeLeakedGUIItems(player);
            if (resyncInventory) player.updateInventory();
        }, 1L);
    }

    // ========== 调试 ==========

    private void debugRecipeClick(Player player, CEBridge.RecipeData recipe) {
        java.util.Locale locale = gui.resolveLocale();
        String resultId = recipe.resultId;
        String name = gui.toChineseName(resultId, locale);
        player.sendMessage("§e[调试] §7物品ID: §f" + resultId);
        player.sendMessage("§e[调试] §7显示名: §f" + name);
        player.sendMessage("§e[调试] §7搜索以下关键词可找到此物品:");
        // 显示名前2字、前1字、全名作为搜索建议
        if (name.length() >= 2) player.sendMessage("§e[调试]   §a" + name.substring(0, 2) + " §7→ 前2字");
        if (name.length() >= 1) player.sendMessage("§e[调试]   §a" + name.substring(0, 1) + " §7→ 首字");
        player.sendMessage("§e[调试]   §a" + name + " §7→ 全名");
        // 原料也显示
        for (String ing : recipe.ingredientIds) {
            player.sendMessage("§e[调试] §7原料: §f" + ing + " §7→ §f" + gui.toChineseName(ing, locale));
        }
        plugin.getLogger().info("[调试] 点击配方: " + recipe.id + " resultId=" + resultId + " name=" + name);
    }
}
