package com.linong.recipelookup.listener;

import com.linong.recipelookup.ALCERecipeViewer;
import com.linong.recipelookup.ConfigManager;
import com.linong.recipelookup.bridge.CEBridge;
import com.linong.recipelookup.gui.RecipeGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 聊天栏搜索监听器。
 */
public class ChatSearchListener implements Listener {

    private final Map<UUID, String> waitingSearch = new ConcurrentHashMap<>();
    private final Map<UUID, String> searchModeStore = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> adminModeStore = new ConcurrentHashMap<>();
    private final ALCERecipeViewer plugin;
    private final ConfigManager config;

    public ChatSearchListener(ALCERecipeViewer plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    public void expectSearch(Player player, String categoryId, String mode) {
        if (categoryId == null) return;
        UUID uuid = player.getUniqueId();
        waitingSearch.put(uuid, categoryId);
        if (mode != null) searchModeStore.put(uuid, mode);
        else searchModeStore.remove(uuid);
        // 记录当前是否在管理员菜单中
        String guiType = plugin.getRecipeGUI().getGUIType(uuid);
        boolean isAdmin = RecipeGUI.TYPE_ADMIN_LIST.equals(guiType) || RecipeGUI.TYPE_ADMIN_MAIN.equals(guiType);
        adminModeStore.put(uuid, isAdmin);
        player.closeInventory();
        player.sendMessage(config.getChatSearchPrompt());
    }

    public void cancelSearch(Player player) {
        UUID uuid = player.getUniqueId();
        waitingSearch.remove(uuid);
        searchModeStore.remove(uuid);
        adminModeStore.remove(uuid);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage().trim();

        // 经验值输入（优先）
        if (plugin.getRecipeGUI().handleExpInput(player, msg)) {
            event.setCancelled(true);
            return;
        }

        // 搜索输入
        UUID uuid = player.getUniqueId();
        String categoryId = waitingSearch.remove(uuid);
        String mode = searchModeStore.remove(uuid);
        Boolean isAdmin = adminModeStore.remove(uuid);
        if (categoryId == null) return;

        event.setCancelled(true);

        if (msg.equalsIgnoreCase("cancel")) {
            plugin.getFoliaLib().getScheduler().runAtEntity(player, t -> {
                player.sendMessage(config.getChatSearchCancelled());
                if (isAdmin != null && isAdmin) {
                    plugin.getRecipeGUI().openAdminRecipeList(player, categoryId, 0);
                } else {
                    plugin.getRecipeGUI().openRecipeList(player, categoryId, 0);
                }
            });
            return;
        }

        // 搜索：根据模式匹配成品或材料名
        final String q = msg;
        final String searchMode = mode != null ? mode : RecipeGUI.SEARCH_MODE_RESULT;
        final java.util.Locale locale = plugin.getRecipeGUI().resolveLocale();
        final boolean adminSearch = isAdmin != null && isAdmin;
        List<CEBridge.RecipeData> all = plugin.getLoadedRecipes().getOrDefault(categoryId, List.of());
        List<CEBridge.RecipeData> filtered = all.stream()
                .filter(r -> matchesQuery(r, q, locale, searchMode))
                .collect(Collectors.toList());

        plugin.getFoliaLib().getScheduler().runAtEntity(player, t -> {
            if (adminSearch) {
                // 管理员：打开管理员列表（不过滤隐藏）
                plugin.getRecipeGUI().openFilteredAdminRecipeList(player, categoryId, filtered, msg);
            } else {
                plugin.getRecipeGUI().openFilteredRecipeList(player, categoryId, filtered, msg);
            }
            player.sendMessage(config.getChatSearchResult(filtered.size()));
        });
    }

    /** 检查配方是否匹配搜索词（根据搜索模式匹配成品或材料） */
    private boolean matchesQuery(CEBridge.RecipeData r, String msg, java.util.Locale locale, String mode) {
        if (RecipeGUI.SEARCH_MODE_INGREDIENT.equals(mode)) {
            return matchIngredients(r, msg.toLowerCase(), locale);
        }
        return matchItem(r.resultId, msg.toLowerCase(), locale);
    }

    /** 匹配配方的所有原料 */
    private boolean matchIngredients(CEBridge.RecipeData r, String msg, java.util.Locale locale) {
        for (String ingId : r.allIngredientIds) {
            if (matchItem(ingId, msg, locale)) return true;
        }
        return false;
    }

    /** 单个物品 ID 是否匹配搜索词 */
    private boolean matchItem(String itemId, String msg, java.util.Locale locale) {
        if (itemId == null) return false;
        if (itemId.toLowerCase().contains(msg)) return true;
        String cn = plugin.getRecipeGUI().toChineseName(itemId, locale);
        return cn.toLowerCase().contains(msg);
    }
}
