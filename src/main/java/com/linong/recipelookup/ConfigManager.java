package com.linong.recipelookup;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理插件配置：config.yml + lang/语言代码.yml。
 * menu.yml 由 MenuConfig 单独管理。
 */
public class ConfigManager {

    private final ALCERecipeViewer plugin;
    private FileConfiguration mainConfig;
    private File langFile;
    private FileConfiguration langConfig;

    // config.yml
    private boolean debug;
    private int multiRecipeCycleSeconds;
    private String language;
    private double buttonCooldownSeconds;
    private String defaultButtonSound;
    private boolean updateCheck;

    // lang.yml
    private String pluginPrefix;
    private String searchTitleSuffix;
    private final Map<String, String> recipeTypeNames = new LinkedHashMap<>();
    private String btnLoreResult, btnLoreType, btnLoreClick, btnLoreMultiRecipes, btnLoreCycleIndicator;
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
        // 合并 jar 中新增的默认键值（保留用户已有设置）
        mergeYamlDefaults(new File(plugin.getDataFolder(), "config.yml"), "config.yml");
        plugin.reloadConfig();
        this.mainConfig = plugin.getConfig();

        debug = mainConfig.getBoolean("features.debug", false);
        multiRecipeCycleSeconds = mainConfig.getInt("features.multi-recipe-cycle-seconds", 5);
        language = mainConfig.getString("language", "zh_cn");
        buttonCooldownSeconds = Math.max(0.0D, mainConfig.getDouble("features.button-cooldown-seconds", 0.3D));
        defaultButtonSound = mainConfig.getString("features.default-button-sound", "block.note_block.pling");
        updateCheck = mainConfig.getBoolean("features.update-check", true);

        // 按配置语言加载语言文件
        String langPath = "lang/" + language + ".yml";
        this.langFile = new File(plugin.getDataFolder(), langPath);
        if (!langFile.exists()) plugin.saveResource(langPath, false);
        // 合并 jar 中新增的默认键值（保留用户已有设置）
        mergeYamlDefaults(langFile, langPath);
        this.langConfig = YamlConfiguration.loadConfiguration(langFile);
        loadLang();

        plugin.getLogger().info("  [OK] 配置加载完成");
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
        btnLoreMultiRecipes = color(langConfig.getString("recipe-lore.multi-recipes", "&d◆ 共{count}种合成方式"));
        btnLoreCycleIndicator = color(langConfig.getString("recipe-lore.cycle-indicator", "&7配方 {current}/{total} &8| &7{interval}秒后切换"));

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

    // ==================== YAML 合并 ====================

    /** 将 jar 内默认 YAML 的新键合并到磁盘文件（保留用户已有设置，新键连同注释写入） */
    public void mergeYamlDefaults(File diskFile, String jarResourcePath) {
        InputStream in = plugin.getResource(jarResourcePath);
        if (in == null) return;
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            YamlConfiguration jarDefaults = YamlConfiguration.loadConfiguration(reader);
            YamlConfiguration disk = YamlConfiguration.loadConfiguration(diskFile);
            boolean changed = mergeDefaults(disk, jarDefaults);
            if (changed) disk.save(diskFile);
        } catch (IOException e) {
            plugin.getLogger().warning("配置文件合并失败: " + diskFile.getName() + " - " + e.getMessage());
        }
    }

    /** 递归合并：target 中不存在的键才从 source 写入，新键的注释一并复制。静态纯函数，便于单测 */
    static boolean mergeDefaults(ConfigurationSection target, ConfigurationSection source) {
        return mergeSection(target, source);
    }

    private static boolean mergeSection(ConfigurationSection target, ConfigurationSection source) {
        boolean changed = false;
        Set<String> keys = source.getKeys(false);
        for (String key : keys) {
            if (target.contains(key)) {
                // 如果两边都是节，递归合并
                if (target.isConfigurationSection(key) && source.isConfigurationSection(key)) {
                    changed |= mergeSection(
                            target.getConfigurationSection(key),
                            source.getConfigurationSection(key));
                }
                // 否则跳过（保留用户值）
            } else if (source.isConfigurationSection(key)) {
                // 新增的节：先建空节（带注释），再逐叶子填入，保证每个新键都带注释
                target.createSection(key);
                copyComments(target, key, source);
                changed = true;
                changed |= mergeSection(
                        target.getConfigurationSection(key),
                        source.getConfigurationSection(key));
            } else {
                target.set(key, source.get(key));
                copyComments(target, key, source);
                changed = true;
            }
        }
        return changed;
    }

    /** 把 source 节中 key 的注释复制到 target 节的同名 key（核心不支持注释 API 时静默降级） */
    private static void copyComments(ConfigurationSection target, String key, ConfigurationSection source) {
        try {
            List<String> comments = source.getComments(key);
            if (comments != null && !comments.isEmpty()) {
                target.setComments(key, comments);
            }
            List<String> inline = source.getInlineComments(key);
            if (inline != null && !inline.isEmpty()) {
                target.setInlineComments(key, inline);
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== 搜索模式 ====================

    public String getSearchModeResult() { return color(langConfig.getString("menu.search_mode_result", "&7搜索模式: &e成品名称")); }
    public String getSearchModeIngredient() { return color(langConfig.getString("menu.search_mode_ingredient", "&7搜索模式: &e材料名称")); }

    public boolean isDebug() { return debug; }

    /** 菜单按钮点击冷却（秒），0 = 关闭 */
    public double getButtonCooldownSeconds() { return buttonCooldownSeconds; }

    /** 按钮未配置音效时的默认点击音 */
    public String getDefaultButtonSound() { return defaultButtonSound; }
    public boolean isUpdateCheckEnabled() { return updateCheck; }
    public int getMultiRecipeCycleSeconds() { return Math.max(1, multiRecipeCycleSeconds); }
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
    public String getBtnLoreMultiRecipes(int count) { return btnLoreMultiRecipes.replace("{count}", String.valueOf(count)); }
    public String getBtnLoreCycleIndicator(int current, int total, int interval) {
        return btnLoreCycleIndicator.replace("{current}", String.valueOf(current))
                .replace("{total}", String.valueOf(total))
                .replace("{interval}", String.valueOf(interval));
    }
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

    // 管理员菜单
    public String getAdminMenuTitle() {
        String v = getLangString("menu.admin_main_title");
        return v != null ? v : "§8管理员 - 配方管理";
    }
    public String getAdminListTitle() {
        String v = getLangString("menu.admin_list_title");
        return v != null ? v : "§8管理员 - {category} ({page}/{total})";
    }
    public String getAdminToggleHidden() {
        String v = getLangString("admin.toggle_hidden");
        return v != null ? v : "§c已隐藏配方";
    }
    public String getAdminToggleShown() {
        String v = getLangString("admin.toggle_shown");
        return v != null ? v : "§a已显示配方";
    }

    // 排序菜单（/alcerecipes admin order）
    public String getOrderMainTitle() {
        String v = getLangString("order.main_title");
        return v != null ? v : "§8排序管理 - 选择分类";
    }
    public String getOrderListTitle() {
        String v = getLangString("menu.order_list_title");
        return v != null ? v : "§8排序管理 - {category} ({page}/{total})";
    }
    public String getOrderClick() {
        String v = getLangString("order.click_order");
        return v != null ? v : "§e▶ 点击调整配方顺序";
    }
    public String getOrderPosition(int index) {
        String v = getLangString("order.position");
        return (v != null ? v : "§7当前位置: &f#{index}")
                .replace("{index}", String.valueOf(index));
    }
    public String getOrderMoveUp() {
        String v = getLangString("order.move_up");
        return v != null ? v : "§e◀ 左键: 上移一位";
    }
    public String getOrderMoveDown() {
        String v = getLangString("order.move_down");
        return v != null ? v : "§e▶ 右键: 下移一位";
    }
    public String getOrderSavedHint() {
        String v = getLangString("order.saved_hint");
        return v != null ? v : "§7调整即时保存，玩家列表同步生效";
    }
    public String getOrderNoCategory(String category) {
        String v = getLangString("order.no_category");
        return (v != null ? v : "§c未知分类: {category}")
                .replace("{category}", category != null ? category : "");
    }
}
