package com.linong.recipelookup;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 管理配方的可见性（隐藏/显示）。
 * 隐藏状态按 resultId（物品ID）存储，被隐藏的物品配方不会在普通玩家菜单中显示。
 */
public class RecipeVisibilityManager {

    private final ALCERecipeViewer plugin;
    private final Set<String> hiddenItemIds = new HashSet<>();
    private File file;

    public RecipeVisibilityManager(ALCERecipeViewer plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "hidden_recipes.yml");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (Exception ignored) {}
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> list = yaml.getStringList("hidden_items");
        hiddenItemIds.clear();
        if (list != null) hiddenItemIds.addAll(list);
        plugin.getLogger().info("  [OK] 配方可见性 » 已加载 " + hiddenItemIds.size() + " 个隐藏物品");
    }

    public void reload() {
        load();
    }

    private void save() {
        if (file == null) return;
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("hidden_items", new java.util.ArrayList<>(hiddenItemIds));
        try {
            yaml.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("保存隐藏配方案失败: " + e.getMessage());
        }
    }

    /** 检查物品（resultId）是否被隐藏 */
    public boolean isHidden(String resultId) {
        return resultId != null && hiddenItemIds.contains(resultId);
    }

    /** 隐藏物品的配方 */
    public void hide(String resultId) {
        if (resultId != null) {
            hiddenItemIds.add(resultId);
            save();
        }
    }

    /** 显示物品的配方 */
    public void show(String resultId) {
        if (resultId != null) {
            hiddenItemIds.remove(resultId);
            save();
        }
    }

    /** 切换物品配方的显示状态，返回切换后的状态（true=已隐藏，false=已显示） */
    public boolean toggle(String resultId) {
        if (resultId == null) return false;
        if (hiddenItemIds.contains(resultId)) {
            show(resultId);
            return false;
        } else {
            hide(resultId);
            return true;
        }
    }

    public Set<String> getHiddenItemIds() {
        return new HashSet<>(hiddenItemIds);
    }

    public int getHiddenCount() {
        return hiddenItemIds.size();
    }
}
