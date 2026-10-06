package com.linong.recipelookup;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.linong.recipelookup.bridge.CEBridge;

/**
 * 配方自定义排序（recipe_order.yml）。
 * <p>
 * 存储的是「玩家可见列表」（已过滤隐藏 + 去重）的完整顺序；
 * 排序菜单里的移动操作直接以可见序列为准做交换，不会与隐藏物品错位。
 */
public final class RecipeOrderManager {
    private final File file;
    private final Map<String, List<String>> orders = new HashMap<>();

    public RecipeOrderManager(ALCERecipeViewer plugin) {
        file = new File(plugin.getDataFolder(), "recipe_order.yml");
        load();
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String category : yaml.getKeys(false)) {
            orders.put(category, new ArrayList<>(yaml.getStringList(category)));
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        orders.forEach(yaml::set);
        try {
            yaml.save(file);
        } catch (Exception ignored) {
        }
    }

    /** 按存储顺序排序：未记录的配方排在已记录的之后（保持 CE 分类排序的相对顺序） */
    public void apply(String category, List<CEBridge.RecipeData> recipes) {
        List<String> order = orders.get(category);
        if (order == null || order.isEmpty()) return;
        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < order.size(); i++) indexes.put(order.get(i), i);
        recipes.sort(Comparator.comparingInt(recipe -> indexes.getOrDefault(recipe.resultId, Integer.MAX_VALUE)));
    }

    /** 全部分类顺序快照（分享码导出用） */
    public Map<String, List<String>> snapshot() {
        Map<String, List<String>> copy = new HashMap<>();
        orders.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        return copy;
    }

    /** 整体替换某分类的顺序（分享码导入用） */
    public void importOrder(String category, List<String> ids) {
        orders.put(category, new ArrayList<>(ids));
        save();
    }

    /**
     * 在可见列表内把 index 位置的物品移动一位（direction: -1 上移 / +1 下移），
     * 并把交换后的完整可见顺序持久化（含跨可见/隐藏边界的正确语义）。
     */
    public void moveVisible(String category, List<String> visibleIds, int index, int direction) {
        if (visibleIds == null || index < 0 || index >= visibleIds.size()) return;
        int target = index + direction;
        if (target < 0 || target >= visibleIds.size()) return;
        moveTo(category, visibleIds, index, target);
    }

    /** 在可见列表内把 from 位置的物品移动到 to 位置（自动钳制到有效范围），持久化完整可见顺序 */
    public void moveTo(String category, List<String> visibleIds, int from, int to) {
        if (visibleIds == null || from < 0 || from >= visibleIds.size()) return;
        int target = Math.max(0, Math.min(visibleIds.size() - 1, to));
        if (target == from) return;
        List<String> order = new ArrayList<>(visibleIds);
        String moved = order.remove(from);
        order.add(target, moved);
        orders.put(category, order);
        save();
    }
}
