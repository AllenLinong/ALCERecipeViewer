package com.linong.recipelookup.listener;

import com.linong.recipelookup.ALCERecipeViewer;
import com.linong.recipelookup.ConfigManager;
import com.linong.recipelookup.MenuConfig;
import com.linong.recipelookup.MenuConfig.ButtonDef;
import com.linong.recipelookup.MenuConfig.MenuDef;
import com.linong.recipelookup.bridge.CEBridge;
import com.linong.recipelookup.gui.RecipeGUI;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.Inventory;

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

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String guiType = gui.getGUIType(player.getUniqueId());
        if (guiType.isEmpty()) return;

        // 配方详情 GUI：取消点击，但检查导航按钮
        if (RecipeGUI.TYPE_DETAIL.equals(guiType)) {
            event.setCancelled(true);
            int slot = event.getSlot();
            if (slot >= 0) {
                MenuDef detailMenu = gui.getPlayerMenuDef(player.getUniqueId());
                if (detailMenu != null) {
                    ButtonDef btn = MenuConfig.buttonAt(detailMenu, slot);
                    if (btn != null) {
                        switch (btn.action()) {
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
            }
            return;
        }

        // creator：Paper 1.21 原生拖拽模式（参照 KitEditorListener）
        if ("creator".equals(guiType)) {
            int raw = event.getRawSlot();
            int topSize = event.getView().getTopInventory().getSize();
            if (raw >= topSize) return; // 玩家背包 → 放行
            if (raw < 0) return;
            // top inventory: 只拦截按钮，其余放行
            MenuDef menu = getCreatorMenu(player);
            if (menu != null) {
                ButtonDef btn = MenuConfig.buttonAt(menu, raw);
                if (btn != null && !btn.action().isEmpty()) {
                    event.setCancelled(true);
                    handleCreatorAction(player, event, btn);
                } else if (btn != null && !btn.dynamic()) {
                    event.setCancelled(true); // 玻璃/装饰 → 保护
                }
                // I/R dynamic → 不取消，Paper 原生处理
            }
            return;
        }
        if ("creator_type".equals(guiType)) {
            int raw = event.getRawSlot();
            int topSize = event.getView().getTopInventory().getSize();
            if (raw >= topSize) return; // 玩家背包 → 放行
            if (raw < 0) return;
            event.setCancelled(true);
            MenuDef menu = plugin.getMenuConfig().getRecipeCreatorType();
            if (menu != null) {
                ButtonDef btn = MenuConfig.buttonAt(menu, raw);
                if (btn != null) handleCreatorAction(player, event, btn);
            }
            return;
        }

        // 主菜单/配方列表：拦截所有点击，防止物品进出 GUI
        event.setCancelled(true);
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (raw >= topSize || raw < 0) return; // 玩家背包 → 不处理按钮逻辑

        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

        int slot = event.getSlot();
        MenuDef menu = gui.getPlayerMenuDef(player.getUniqueId());

        switch (guiType) {
            case RecipeGUI.TYPE_MAIN -> handleMainClick(player, slot, menu);
            case RecipeGUI.TYPE_LIST -> handleListClick(player, slot, menu, event);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String type = gui.getGUIType(player.getUniqueId());
        if (type.isEmpty()) return;
        int topSize = event.getView().getTopInventory().getSize();

        if ("creator_type".equals(type)) { event.setCancelled(true); return; }
        if ("creator".equals(type)) {
            MenuDef menu = getCreatorMenu(player);
            if (menu != null) {
                for (int raw : event.getRawSlots()) {
                    if (raw < topSize) {
                        ButtonDef btn = MenuConfig.buttonAt(menu, raw);
                        if (btn != null && !btn.dynamic()) { event.setCancelled(true); return; }
                    }
                }
            }
            return;
        }
        // 浏览 GUI：只拦截顶部 inventory 拖拽，放行背包
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) { event.setCancelled(true); return; }
        }
    }

    /** 阻止原版配方书在工作台/熔炉/锻造台详情 GUI 中填充物品 */

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && RecipeGUI.TYPE_DETAIL.equals(gui.getGUIType(player.getUniqueId())))
            event.setCancelled(true);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (org.bukkit.entity.HumanEntity v : event.getViewers()) {
            if (v instanceof Player p && RecipeGUI.TYPE_DETAIL.equals(gui.getGUIType(p.getUniqueId()))) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onSmithItem(SmithItemEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && RecipeGUI.TYPE_DETAIL.equals(gui.getGUIType(player.getUniqueId())))
            event.setCancelled(true);
    }

    @EventHandler
    public void onPrepareSmith(PrepareSmithingEvent event) {
        for (org.bukkit.entity.HumanEntity v : event.getViewers()) {
            if (v instanceof Player p && RecipeGUI.TYPE_DETAIL.equals(gui.getGUIType(p.getUniqueId()))) {
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

        String type = gui.getGUIType(player.getUniqueId());
        if (RecipeGUI.TYPE_DETAIL.equals(type)) {
            gui.stopRecipeCycle(player);
            String cat = gui.getPlayerCategory(player.getUniqueId());
            int page = gui.getPlayerPage(player.getUniqueId());
            if (cat != null) {
                plugin.getFoliaLib().getScheduler().runNextTick(t ->
                        gui.openRecipeList(player, cat, page));
            } else {
                gui.removePlayer(player.getUniqueId());
            }
        } else {
            gui.removePlayer(player.getUniqueId());
        }
    }

    // ========== 主菜单 ==========

    private void handleMainClick(Player player, int slot, MenuDef menu) {
        if (menu == null) return;
        ButtonDef btn = MenuConfig.buttonAt(menu, slot);
        if (btn == null || btn.action().isEmpty()) return;

        switch (btn.action()) {
            case "OPEN_CATEGORY" -> {
                if (!btn.category().isEmpty()) {
                    gui.openRecipeList(player, btn.category(), 0);
                }
            }
            case "CLOSE" -> player.closeInventory();
            // 其他 action 在主菜单无意义
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
                        gui.getSearchQuery(player.getUniqueId()), locale);
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

        switch (btn.action()) {
            case "PREV_PAGE" -> {
                if (page > 0) gui.openRecipeList(player, categoryId, page - 1);
            }
            case "NEXT_PAGE" -> {
                java.util.Locale locale = gui.resolveLocale();
                List<CEBridge.RecipeData> recipes = gui.getSortedRecipes(categoryId,
                        gui.getSearchQuery(player.getUniqueId()), locale);
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

    @EventHandler
    public void onCreatorClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        String type = gui.getGUIType(player.getUniqueId());
        if ("creator_type".equals(type)) {
            gui.getGUITypeMap().remove(player.getUniqueId());
            gui.getOpenInventoryMap().remove(player.getUniqueId());
            return;
        }
        if (!"creator".equals(type)) return;
        // 经验输入关闭不保存
        if (gui.pendingExpInput.containsKey(player.getUniqueId())) return;
        // ESC 关闭不保存，只清理状态
        gui.removePlayer(player.getUniqueId());
    }

    private void handleSearchClick(Player player, String categoryId, InventoryClickEvent event) {
        if (event.getClick() == ClickType.SHIFT_LEFT) {
            gui.clearSearch(player);
            plugin.getChatSearchListener().cancelSearch(player);
        } else {
            plugin.getChatSearchListener().expectSearch(player, categoryId);
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
        switch (btn.action()) {
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
