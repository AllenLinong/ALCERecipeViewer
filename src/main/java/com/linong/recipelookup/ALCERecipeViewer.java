package com.linong.recipelookup;

import com.linong.recipelookup.bridge.CEBridge;
import com.linong.recipelookup.command.ViewRecipeCommand;
import com.linong.recipelookup.gui.RecipeGUI;
import com.linong.recipelookup.listener.ChatSearchListener;
import com.linong.recipelookup.listener.GUIListener;
import com.tcoded.folialib.FoliaLib;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

public final class ALCERecipeViewer extends JavaPlugin {

    private static ALCERecipeViewer instance;
    private FoliaLib foliaLib;
    private ConfigManager configManager;
    private MenuConfig menuConfig;
    private CEBridge ceBridge;
    private RecipeGUI recipeGUI;
    private ChatSearchListener chatSearchListener;

    /** typeId → 配方列表 */
    private Map<String, List<CEBridge.RecipeData>> loadedRecipes = Map.of();

    @Override
    public void onEnable() {
        instance = this;

        try {
            this.foliaLib = new FoliaLib(this);
        } catch (Exception e) {
            getLogger().severe("FoliaLib 初始化失败: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.configManager = new ConfigManager(this);
        configManager.loadConfig();

        this.menuConfig = new MenuConfig(this);
        menuConfig.load();

        this.ceBridge = new CEBridge(getLogger());
        if (!ceBridge.isAvailable()) {
            getLogger().warning("CraftEngine 未安装或版本不兼容！");
            getLogger().warning("请确认 CraftEngine 已安装且版本匹配。");
            // 不禁用插件，允许使用 /alcerecipes reload 重试
        }

        this.recipeGUI = new RecipeGUI(this);

        try {
            Objects.requireNonNull(getCommand("alcerecipes"))
                    .setExecutor(new ViewRecipeCommand(this));
        } catch (Exception e) {
            getLogger().severe("注册命令失败: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(new GUIListener(this), this);
        this.chatSearchListener = new ChatSearchListener(this);
        Bukkit.getPluginManager().registerEvents(chatSearchListener, this);

        getLogger().info("AL合成表查看器 v" + getDescription().getVersion() + " 已启用！");
        getLogger().info("CE桥接: " + (ceBridge.isAvailable() ? "就绪" : "不可用"));
        getLogger().info("使用 /alcerecipes 打开合成表。");

        if (ceBridge.isAvailable()) {
            // CE 的 delayed init 比本插件晚 ~5-8 秒，延迟 + 长重试确保拿到数据
            scheduleRecipeLoad(100);  // 5秒后首次尝试
        }
    }

    @Override
    public void onDisable() {
        if (recipeGUI != null) recipeGUI.closeAll();
        if (foliaLib != null) foliaLib.getScheduler().cancelAllTasks();
        getLogger().info("AL合成表查看器已禁用。");
    }

    /** 首次加载：尝试缓存，缓存不存在则 CE */
    void loadRecipesInitial() {
        Map<String, List<CEBridge.RecipeData>> cached = loadRecipeCache();
        if (!cached.isEmpty()) {
            this.loadedRecipes = cached;
            getLogger().info("从缓存加载 " + cached.values().stream().mapToInt(List::size).sum() + " 个配方");
            return;
        }
        this.loadedRecipes = ceBridge.loadAllRecipes();
        if (!loadedRecipes.isEmpty()) saveRecipeCache(loadedRecipes);
    }

    /** reload：强制从 CE 重新加载并更新缓存 */
    public void reloadRecipes() {
        configManager.reload();
        menuConfig.reload();
        this.loadedRecipes = ceBridge.loadAllRecipes();
        if (!loadedRecipes.isEmpty()) saveRecipeCache(loadedRecipes);
    }

    /** 清空所有缓存 */
    public void clearRecipes() {
        this.loadedRecipes = Map.of();
        File recipeDir = new File(getDataFolder(), "recipes");
        if (recipeDir.exists()) {
            File[] files = recipeDir.listFiles();
            if (files != null) for (File f : files) f.delete();
            recipeDir.delete();
        }
        getLogger().info("配方缓存已清空（内存 + 文件）。");
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<CEBridge.RecipeData>> loadRecipeCache() {
        File recipeDir = new File(getDataFolder(), "recipes");
        if (!recipeDir.exists()) return Map.of();
        Map<String, List<CEBridge.RecipeData>> recipes = new LinkedHashMap<>();
        File[] files = recipeDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return Map.of();
        for (File f : files) {
            String typeId = f.getName().replace(".yml", "");
            List<CEBridge.RecipeData> list = new ArrayList<>();
            org.bukkit.configuration.file.YamlConfiguration yaml =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            for (String key : yaml.getKeys(false)) {
                String type = yaml.getString(key + ".type", "");
                String result = yaml.getString(key + ".result", "");
                int count = yaml.getInt(key + ".result_count", 1);
                List<String> ingIds = yaml.getStringList(key + ".ingredients.ids");
                List<Integer> ingCounts = yaml.getIntegerList(key + ".ingredients.counts");
                List<String> pattern = yaml.getStringList(key + ".pattern");
                boolean shaped = yaml.getBoolean(key + ".shaped", false);
                int cookTime = yaml.getInt(key + ".cook_time", 0);
                double exp = yaml.getDouble(key + ".experience", 0);

                String[] shapedArr = shaped && !pattern.isEmpty() ? pattern.toArray(new String[0]) : null;
                Map<String, String> patternKeys = null;
                if (shaped) {
                    org.bukkit.configuration.ConfigurationSection pkSec = yaml.getConfigurationSection(key + ".pattern_keys");
                    if (pkSec != null) {
                        patternKeys = new LinkedHashMap<>();
                        for (String k : pkSec.getKeys(false)) patternKeys.put(k, pkSec.getString(k));
                    }
                }
                List<String> ids = ingIds != null ? ingIds : List.of();
                list.add(new CEBridge.RecipeData(key, type, result, count,
                        ids,
                        ingCounts != null ? ingCounts : List.of(),
                        ids,
                        shapedArr, patternKeys, cookTime, (float) exp));
            }
            recipes.put(typeId, list);
        }
        return recipes;
    }

    private void saveRecipeCache(Map<String, List<CEBridge.RecipeData>> recipes) {
        File recipeDir = new File(getDataFolder(), "recipes");
        if (!recipeDir.exists()) recipeDir.mkdirs();
        for (Map.Entry<String, List<CEBridge.RecipeData>> e : recipes.entrySet()) {
            File f = new File(recipeDir, e.getKey() + ".yml");
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            for (CEBridge.RecipeData r : e.getValue()) {
                String path = r.id + ".";
                yaml.set(path + "type", r.type);
                yaml.set(path + "result", r.resultId);
                yaml.set(path + "result_count", r.resultCount);
                List<String> ingList = new ArrayList<>(r.ingredientIds);
                List<Integer> cntList = new ArrayList<>(r.ingredientCounts);
                yaml.set(path + "ingredients.ids", ingList);
                yaml.set(path + "ingredients.counts", cntList);
                if (r.cookingTime > 0) yaml.set(path + "cook_time", r.cookingTime);
                if (r.experience > 0) yaml.set(path + "experience", (double) r.experience);
                if (r.shapedPattern != null) {
                    yaml.set(path + "shaped", true);
                    yaml.set(path + "pattern", java.util.Arrays.asList(r.shapedPattern));
                    if (r.patternKeyIds != null) {
                        for (Map.Entry<String, String> pk : r.patternKeyIds.entrySet())
                            yaml.set(path + "pattern_keys." + pk.getKey(), pk.getValue());
                    }
                }
            }
            try { yaml.save(f); } catch (Exception ignored) {}
        }
        int total = recipes.values().stream().mapToInt(List::size).sum();
        getLogger().info("配方缓存已保存: " + total + " 个 → " + recipeDir.getPath());
    }

    /** 带重试的延迟加载（CE 配方可能晚于本插件加载） */
    private void scheduleRecipeLoad(int delayTicks) {
        foliaLib.getScheduler().runLaterAsync(task -> {
            loadRecipesInitial();
            int total = loadedRecipes.values().stream().mapToInt(List::size).sum();
            if (total == 0 && delayTicks < 400) {
                getLogger().info("配方数据为空，" + ((delayTicks + 60) / 20) + "s 后重试...");
                scheduleRecipeLoad(delayTicks + 60);
            } else {
                getLogger().info(configManager.getPluginPrefix() +
                        " 配方加载完成！共 " + total + " 个配方，使用 /alcerecipes 打开。");
            }
        }, delayTicks);
    }

    public static ALCERecipeViewer getInstance() { return instance; }
    public FoliaLib getFoliaLib() { return foliaLib; }
    public ConfigManager getConfigManager() { return configManager; }
    public MenuConfig getMenuConfig() { return menuConfig; }
    public CEBridge getCEBridge() { return ceBridge; }
    public RecipeGUI getRecipeGUI() { return recipeGUI; }
    public ChatSearchListener getChatSearchListener() { return chatSearchListener; }
    public Map<String, List<CEBridge.RecipeData>> getLoadedRecipes() { return loadedRecipes; }
}
