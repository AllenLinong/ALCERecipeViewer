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

        getLogger().info("");
        getLogger().info("  ▪ 插件名称 » ALCERecipeViewer");
        getLogger().info("  ▪ 插件版本 » v" + getDescription().getVersion());
        getLogger().info("  ▪ 作者信息 » Allen_Linong");
        getLogger().info("");
        getLogger().info("════════════════════════════════════════════════════");
        getLogger().info("");
        getLogger().info("  §a✓ §r语言设置 » " + configManager.getLanguage());
        getLogger().info("  " + (ceBridge.isAvailable() ? "§a✓ CraftEngine » 已连接" : "§c✗ CraftEngine » 未检测到"));
        getLogger().info("  §a✓ §r菜单系统 » 已加载");
        getLogger().info("");

        if (ceBridge.isAvailable()) {
            // CE 的 delayed init 比本插件晚 ~5-8 秒，延迟 + 长重试确保拿到数据
            scheduleRecipeLoad(100);  // 5秒后首次尝试
        }
    }

    @Override
    public void onDisable() {
        if (recipeGUI != null) recipeGUI.closeAll();
        if (foliaLib != null) foliaLib.getScheduler().cancelAllTasks();
        getLogger().info("");
        getLogger().info("ALCERecipeViewer » 已禁用");
        getLogger().info("");
    }

    /** 首次加载：尝试缓存，缓存不存在则 CE */
    void loadRecipesInitial() {
        Map<String, List<CEBridge.RecipeData>> cached = loadRecipeCache();
        if (!cached.isEmpty()) {
            this.loadedRecipes = cached;
            getLogger().info("  §a✓ §r配方缓存 » " + cached.values().stream().mapToInt(List::size).sum() + " 个");
            return;
        }
        this.loadedRecipes = ceBridge.loadAllRecipes();
        if (!loadedRecipes.isEmpty()) saveRecipeCache(loadedRecipes);
    }

    /** reload：强制从 CE 重新加载并更新缓存 */
    public void reloadRecipes() {
        configManager.reload();
        menuConfig.reload();
        ceBridge.clearNameCaches(); // 清除名称缓存，强制重新解析 CE 翻译
        this.loadedRecipes = ceBridge.loadAllRecipes();
        if (!loadedRecipes.isEmpty()) {
            saveRecipeCache(loadedRecipes);
            // 预热：收集所有唯一物品 ID 并解析名字，填充到缓存
            java.util.Locale locale = java.util.Locale.SIMPLIFIED_CHINESE;
            for (List<CEBridge.RecipeData> list : loadedRecipes.values()) {
                for (CEBridge.RecipeData r : list) {
                    recipeGUI.toChineseName(r.resultId, locale);
                    for (String ing : r.ingredientIds) recipeGUI.toChineseName(ing, locale);
                }
            }
            getLogger().info("  §a✓ §r名字预热 » 完成");
        }
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
                List<String> allIngIds = yaml.getStringList(key + ".ingredients.all_ids");
                // 兼容旧缓存（没有 all_ids）→ 回退到 ids
                if (allIngIds.isEmpty()) allIngIds = ingIds != null ? ingIds : List.of();
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
                        allIngIds,
                        shapedArr, patternKeys, cookTime, (float) exp));
                // 按当前语言加载对应的名字缓存
                String lang = configManager.getLanguage();
                String name = yaml.getString(key + ".name_" + lang, "");
                if (name.isEmpty()) name = yaml.getString(key + ".name", ""); // 兼容旧格式
                if (!name.isEmpty()) ceBridge.putDisplayName(result, name);
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
                // 按配置语言缓存名字（zh_cn / en_us 各自独立）
                String lang = configManager.getLanguage();
                java.util.Locale loc = "en_us".equals(lang) ? java.util.Locale.US : java.util.Locale.SIMPLIFIED_CHINESE;
                yaml.set(path + "name_" + lang, recipeGUI.toChineseName(r.resultId, loc));
                List<String> ingList = new ArrayList<>(r.ingredientIds);
                List<Integer> cntList = new ArrayList<>(r.ingredientCounts);
                yaml.set(path + "ingredients.ids", ingList);
                yaml.set(path + "ingredients.counts", cntList);
                // 保存全部原料 ID（用于搜索匹配），与 ingredientIds（GUI 显示用）分开
                if (!r.allIngredientIds.isEmpty() && !r.allIngredientIds.equals(r.ingredientIds)) {
                    yaml.set(path + "ingredients.all_ids", new ArrayList<>(r.allIngredientIds));
                }
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
        getLogger().info("  §a✓ §r配方缓存 » 已保存 " + total + " 个");
    }

    /** 带重试的延迟加载（CE 配方可能晚于本插件加载） */
    private void scheduleRecipeLoad(int delayTicks) {
        foliaLib.getScheduler().runLaterAsync(task -> {
            loadRecipesInitial();
            int total = loadedRecipes.values().stream().mapToInt(List::size).sum();
            if (total == 0 && delayTicks < 400) {
                getLogger().info("  §7配方数据暂未就绪，" + ((delayTicks + 60) / 20) + "s 后重试...");
                scheduleRecipeLoad(delayTicks + 60);
            } else {
                getLogger().info("");
                getLogger().info("════════════════════════════════════════════════════");
                getLogger().info("");
                getLogger().info("  §a✓ §r配方加载 » " + total + " 个 | /alcerecipes 打开");
                getLogger().info("");
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
