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
import java.util.stream.Collectors;

/**
 * 聊天栏搜索监听器。
 */
public class ChatSearchListener implements Listener {

    private final Map<UUID, String> waitingSearch = new HashMap<>();
    private final ALCERecipeViewer plugin;
    private final ConfigManager config;

    public ChatSearchListener(ALCERecipeViewer plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    public void expectSearch(Player player, String categoryId) {
        waitingSearch.put(player.getUniqueId(), categoryId);
        player.closeInventory();
        player.sendMessage(config.getChatSearchPrompt());
    }

    public void cancelSearch(Player player) {
        waitingSearch.remove(player.getUniqueId());
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
        String categoryId = waitingSearch.remove(player.getUniqueId());
        if (categoryId == null) return;

        event.setCancelled(true);

        if (msg.equalsIgnoreCase("cancel")) {
            plugin.getFoliaLib().getScheduler().runNextTick(t -> {
                player.sendMessage(config.getChatSearchCancelled());
                plugin.getRecipeGUI().openRecipeList(player, categoryId, 0);
            });
            return;
        }

        // 搜索：同时匹配英文 ID 和中文名
        final String q = msg;
        List<CEBridge.RecipeData> all = plugin.getLoadedRecipes().getOrDefault(categoryId, List.of());
        List<CEBridge.RecipeData> filtered = all.stream()
                .filter(r -> matchesQuery(r, q))
                .collect(Collectors.toList());

        plugin.getFoliaLib().getScheduler().runNextTick(t -> {
            plugin.getRecipeGUI().openFilteredRecipeList(player, categoryId, filtered, msg);
            player.sendMessage(config.getChatSearchResult(filtered.size()));
        });
    }

    /** 检查配方是否匹配搜索词（ID + 中文名） */
    private boolean matchesQuery(CEBridge.RecipeData r, String msg) {
        String q = msg.toLowerCase();
        // 匹配结果物品
        if (matchItem(r.resultId, q)) return true;
        // 匹配原料
        for (String ing : r.ingredientIds) {
            if (matchItem(ing, q)) return true;
        }
        // 匹配 pattern key 物品
        if (r.patternKeyIds != null) {
            for (String pk : r.patternKeyIds.values()) {
                if (matchItem(pk, q)) return true;
            }
        }
        return false;
    }

    /** 单个物品 ID 是否匹配搜索词 */
    private boolean matchItem(String itemId, String msg) {
        if (itemId == null) return false;
        // 英文 ID 匹配
        if (itemId.toLowerCase().contains(msg)) return true;
        // 中文名匹配
        String cn = plugin.getRecipeGUI().toChineseName(itemId);
        return cn.toLowerCase().contains(msg);
    }
}
