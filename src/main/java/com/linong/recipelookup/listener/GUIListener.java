package com.linong.recipelookup.listener;

import com.linong.recipelookup.ALCERecipeViewer;
import com.linong.recipelookup.MenuConfig;
import com.linong.recipelookup.MenuConfig.ButtonDef;
import com.linong.recipelookup.MenuConfig.MenuDef;
import com.linong.recipelookup.gui.MenuClickService;
import com.linong.recipelookup.gui.RecipeGUI;
import org.bukkit.Material;
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

import java.util.UUID;

/**
 * GUI 事件监听器。禁止取放物品；按钮点击统一经 MenuClickService
 * （PDC 动作 → 冷却 → 音效 → 动作执行器）路由，业务动作见 MenuActionRouter。
 */
public class GUIListener implements Listener {

    private final ALCERecipeViewer plugin;
    private final RecipeGUI gui;
    private final MenuClickService clickService;

    public GUIListener(ALCERecipeViewer plugin) {
        this.plugin = plugin;
        this.gui = plugin.getRecipeGUI();
        this.clickService = new MenuClickService(plugin, this.gui);
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
        int topSize = topInventory.getSize();

        // 配方详情 GUI：整个界面只读，仅导航按钮可触发操作。
        if (RecipeGUI.TYPE_DETAIL.equals(guiType) || RecipeGUI.TYPE_ADMIN_DETAIL.equals(guiType)) {
            event.setCancelled(true);
            if (raw < 0 || raw >= topSize) return;
            routeButton(player, event, guiType);
            return;
        }

        // creator：Paper 1.21 原生拖拽模式，I/R 动态槽放行
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
            MenuDef menu = gui.getCreatorMenuFor(uuid);
            ButtonDef btn = menu != null ? MenuConfig.buttonAt(menu, raw) : null;
            if (btn == null || !btn.dynamic()) {
                event.setCancelled(true);
                routeButton(player, event, guiType);
            }
            // I/R dynamic → 不取消，Paper 原生处理
            return;
        }
        if ("creator_type".equals(guiType)) {
            event.setCancelled(true);
            if (raw >= topSize || raw < 0) return;
            routeButton(player, event, guiType);
            return;
        }

        // 主菜单/配方列表（含管理端）：顶部完全只读，底部玩家背包也禁止操作。
        event.setCancelled(true);
        if (raw < 0 || raw >= topSize) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // 按钮优先走 PDC 动作；配方列表动态区（'I'）合成 recipe_entry 动作
        if (routeButton(player, event, guiType)) return;

        if (RecipeGUI.TYPE_LIST.equals(guiType) || RecipeGUI.TYPE_ADMIN_LIST.equals(guiType)) {
            MenuDef menu = gui.getPlayerMenuDef(uuid);
            if (menu == null) return;
            ButtonDef btn = MenuConfig.buttonAt(menu, event.getSlot());
            if (btn != null && btn.dynamic()) {
                clickService.handleSynthesized(player, "recipe_entry", event.getClick(),
                        guiType, raw, event.getSlot());
            }
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
        int topSize = topInventory.getSize();

        if ("creator_type".equals(type)) { event.setCancelled(true); return; }
        if ("creator".equals(type)) {
            MenuDef menu = gui.getCreatorMenuFor(uuid);
            if (menu != null) {
                for (int raw : event.getRawSlots()) {
                    if (raw < topSize) {
                        ButtonDef btn = MenuConfig.buttonAt(menu, raw);
                        if (btn == null || !btn.dynamic()) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                }
            } else {
                event.setCancelled(true);
            }
            return;
        }
        // 浏览/详情 GUI：打开期间禁止在整个界面拖动物品。
        event.setCancelled(true);
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

        clickService.clearCooldowns(player.getUniqueId());
        if (!gui.isCurrentGUI(player.getUniqueId(), closed)) return;

        String type = gui.getInventoryType(player.getUniqueId(), closed);
        if (RecipeGUI.TYPE_DETAIL.equals(type)) {
            gui.stopRecipeCycle(player);
            gui.removePlayer(player.getUniqueId());
        } else if ("creator".equals(type)) {
            if (!gui.pendingExpInput.containsKey(player.getUniqueId())) {
                gui.returnCreatorItems(player, closed);
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
        clickService.clearCooldowns(player.getUniqueId());
        scheduleGUIItemCleanup(player, true);
        plugin.getFoliaLib().getScheduler().runLater(task -> plugin.getUpdateChecker().notifyAdmin(player), 40);

    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getChatSearchListener().cancelSearch(event.getPlayer());
        clickService.clearCooldowns(event.getPlayer().getUniqueId());
        gui.discardPlayer(event.getPlayer().getUniqueId());
    }

    // ========== 点击路由 ==========

    /** 读取被点物品 PDC 动作并经统一点击链执行；物品无动作返回 false */
    private boolean routeButton(Player player, InventoryClickEvent event, String guiType) {
        return clickService.handleButton(player, event.getCurrentItem(), event.getClick(),
                guiType, event.getRawSlot(), event.getSlot());
    }

    // ========== 辅助 ==========

    private boolean shouldBlockBottomTransfer(InventoryClickEvent event) {
        return event.isShiftClick()
                || event.getClick() == ClickType.DOUBLE_CLICK
                || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR;
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
}
