package com.linong.recipelookup;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理插件配置：config.yml + lang.yml。
 * menu.yml 由 MenuConfig 单独管理。
 */
public class ConfigManager {

    private final ALCERecipeViewer plugin;
    private FileConfiguration mainConfig;
    private File langFile;
    private FileConfiguration langConfig;

    // config.yml
    private boolean debug;
    private String language;

    // lang.yml
    private String pluginPrefix;
    private String searchTitleSuffix;
    private final Map<String, String> recipeTypeNames = new LinkedHashMap<>();
    private String btnLoreResult, btnLoreType, btnLoreClick;
    private Map<String, String> customItemNames;
    private final Map<String, String> defaultCategoryNames = new LinkedHashMap<>();
    private String chatSearchPrompt, chatSearchCancelled, chatSearchResult;
    private String cmdPlayerOnly, cmdNoRecipes, cmdNoPermission, cmdReloaded, cmdUsageTitle, cmdUsageOpen, cmdUsageReload;
    private final Map<String, String> detailTitles = new LinkedHashMap<>();

    public ConfigManager(ALCERecipeViewer plugin) { this.plugin = plugin; }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.mainConfig = plugin.getConfig();
        debug = mainConfig.getBoolean("features.debug", false);
        language = mainConfig.getString("language", "zh_cn");

        // 按配置语言加载语言文件
        String langPath = "lang/" + language + ".yml";
        this.langFile = new File(plugin.getDataFolder(), langPath);
        if (!langFile.exists()) plugin.saveResource(langPath, false);
        this.langConfig = YamlConfiguration.loadConfiguration(langFile);
        loadLang();

        plugin.getLogger().info("  §a✔ 配置加载完成");
    }

    public void reload() { loadConfig(); }

    private void loadLang() {
        pluginPrefix = color(langConfig.getString("plugin-prefix", "&8[&6ALCE合成表&8]&r"));
        searchTitleSuffix = color(langConfig.getString("search-title-suffix", " | \"{query}\""));

        recipeTypeNames.clear();
        ConfigurationSection t = langConfig.getConfigurationSection("recipe-types");
        if (t != null) for (String k : t.getKeys(false)) recipeTypeNames.put(k.toLowerCase(), color(t.getString(k, k)));

        btnLoreResult = color(langConfig.getString("recipe-lore.result", "&7产出: &f{count}个"));
        btnLoreType = color(langConfig.getString("recipe-lore.type", "&7类型: &f{type}"));
        btnLoreClick = color(langConfig.getString("recipe-lore.click", "&e▶ 点击查看合成表"));

        customItemNames = new LinkedHashMap<>();
        ConfigurationSection cn = langConfig.getConfigurationSection("custom-names");
        if (cn != null) for (String k : cn.getKeys(false)) customItemNames.put(k, color(cn.getString(k)));

        defaultCategoryNames.clear();
        ConfigurationSection dc = langConfig.getConfigurationSection("default-categories");
        if (dc != null) for (String k : dc.getKeys(false)) {
            String n = dc.getString(k); if (n != null) defaultCategoryNames.put(k, color(n));
        }

        chatSearchPrompt = color(langConfig.getString("chat.search-prompt", "{prefix} &e请在聊天栏输入搜索词（输入 &ccancel &e取消）"));
        chatSearchCancelled = color(langConfig.getString("chat.search-cancelled", "{prefix} &7搜索已取消。"));
        chatSearchResult = color(langConfig.getString("chat.search-result", "{prefix} &a找到 &f{count} &a个匹配配方。"));

        cmdPlayerOnly = color(langConfig.getString("command.player-only", "&c此命令只能由玩家执行。"));
        cmdNoRecipes = color(langConfig.getString("command.no-recipes", "&c暂无配方数据，请等待加载或使用 /alcerecipes reload"));
        cmdNoPermission = color(langConfig.getString("command.no-permission", "&c你没有权限重载配方。"));
        cmdReloaded = color(langConfig.getString("command.reloaded", "&a配方数据已重新加载！共 {count} 个配方。"));
        cmdUsageTitle = color(langConfig.getString("command.usage-title", "&e用法:"));
        cmdUsageOpen = color(langConfig.getString("command.usage-open", "&e  /alcerecipes &7- 打开合成表分类浏览"));
        cmdUsageReload = color(langConfig.getString("command.usage-reload", "&e  /alcerecipes reload &7- 重新加载配方数据"));

        detailTitles.clear();
        ConfigurationSection dt = langConfig.getConfigurationSection("detail-titles");
        if (dt != null) for (String k : dt.getKeys(false)) detailTitles.put(k, color(dt.getString(k)));
    }

    // creator 消息 getter 直接读 langConfig（懒加载，不需要额外字段）
    public String getCreatorNoItems() { return color(langConfig.getString("creator.no-items", "&c请至少放入原料和结果物品！")); }
    public String getCreatorNoIngredient() { return color(langConfig.getString("creator.no-ingredient", "&c请放入烧炼原料！")); }
    public String getCreatorNoCEDir() { return color(langConfig.getString("creator.no-ce-dir", "&c未找到 CraftEngine 配置目录！")); }
    public String getCreatorSaved(String path) { return color(langConfig.getString("creator.saved", "&a配方已保存至: {path}")).replace("{path}", path); }
    public String getCreatorReload1() { return color(langConfig.getString("creator.reload-hint-1", "&e请在控制台输入: &fce reload all")); }
    public String getCreatorReload2() { return color(langConfig.getString("creator.reload-hint-2", "&e然后输入: &f/alcerecipes clear && /alcerecipes reload")); }
    public String getCreatorAdminOnly() { return color(langConfig.getString("creator.admin-only", "&c只有管理员可以创建配方！")); }
    public String getCreatorCleared() { return color(langConfig.getString("creator.cleared", "&a配方缓存已清空。")); }
    public String getCreatorHelpClear() { return color(langConfig.getString("creator.help-clear", "&e  /alcerecipes clear &7- 清空配方缓存")); }

    private String color(String t) { return t == null ? "" : ChatColor.translateAlternateColorCodes('&', t); }

    public boolean isDebug() { return debug; }
    public String getLanguage() { return language; }
    /** 从语言文件读取指定路径的文本 */
    public String getLangString(String path) {
        String val = langConfig.getString(path);
        return val != null ? color(val) : null;
    }
    public String getPluginPrefix() { return pluginPrefix; }
    public String getSearchTitleSuffix(String q) { return searchTitleSuffix.replace("{query}", q); }
    public String getRecipeTypeName(String t) { return recipeTypeNames.getOrDefault(t.toLowerCase(), t); }
    public String getBtnLoreResult(int c) { return btnLoreResult.replace("{count}", String.valueOf(c)); }
    public String getBtnLoreType(String t) { return btnLoreType.replace("{type}", t); }
    public String getBtnLoreClick() { return btnLoreClick; }
    public String getCustomItemName(String id) {
        if (id == null) return null;
        String e = customItemNames.get(id);
        if (e != null) return e;
        String v = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return customItemNames.get(v);
    }
    public String getDefaultCategoryName(String t) { return defaultCategoryNames.getOrDefault(t, t); }
    public String getChatSearchPrompt() { return chatSearchPrompt.replace("{prefix}", pluginPrefix); }
    public String getChatSearchCancelled() { return chatSearchCancelled.replace("{prefix}", pluginPrefix); }
    public String getChatSearchResult(int c) { return chatSearchResult.replace("{count}", String.valueOf(c)).replace("{prefix}", pluginPrefix); }
    public String getCmdPlayerOnly() { return cmdPlayerOnly; }
    public String getCmdNoRecipes() { return cmdNoRecipes; }
    public String getCmdNoPermission() { return cmdNoPermission; }
    public String getCmdReloaded(int c) { return cmdReloaded.replace("{count}", String.valueOf(c)); }
    public String getCmdUsageTitle() { return cmdUsageTitle; }
    public String getCmdUsageOpen() { return cmdUsageOpen; }
    public String getCmdUsageReload() { return cmdUsageReload; }
    public String getDetailTitle(String t) { return detailTitles.getOrDefault(t, detailTitles.getOrDefault("crafting", "&8配方详情")); }
}
