package com.linong.recipelookup;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.linong.recipelookup.bridge.CEBridge;

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

    public void apply(String category, List<CEBridge.RecipeData> recipes) {
        List<String> order = orders.get(category);
        if (order == null || order.isEmpty()) return;
        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < order.size(); i++) indexes.put(order.get(i), i);
        recipes.sort(Comparator.comparingInt(recipe -> indexes.getOrDefault(recipe.resultId, Integer.MAX_VALUE)));
    }

    public void move(String category, String resultId, int direction, List<CEBridge.RecipeData> currentRecipes) {
        if (resultId == null) return;
        List<String> order = orders.computeIfAbsent(category, ignored -> new ArrayList<>());
        for (CEBridge.RecipeData recipe : currentRecipes) {
            if (recipe.resultId != null && !order.contains(recipe.resultId)) order.add(recipe.resultId);
        }
        int current = order.indexOf(resultId);
        if (current < 0) {
            order.add(resultId);
            current = order.size() - 1;
        }
        int target = Math.max(0, Math.min(order.size() - 1, current + direction));
        if (target != current) {
            order.remove(current);
            order.add(target, resultId);
            save();
        }
    }
}
