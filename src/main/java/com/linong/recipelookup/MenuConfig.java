package com.linong.recipelookup;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import com.linong.recipelookup.gui.MenuTriggerResolver;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 解析 shape 布局格式的 menu.yml，构建 GUI。
 * <p>
 * Shape 语法：
 * <ul>
 *   <li>{@code #} — 背景填充</li>
 *   <li>{@code A-Z} — 按钮位置</li>
 *   <li>{@code I} — 动态物品区（配方列表）</li>
 *   <li>每行必须正好 9 个字符</li>
 * </ul>
 */
public class MenuConfig {

    private final ALCERecipeViewer plugin;
    private ConfigManager config;
    private File file;
    private FileConfiguration yaml;

    // 已解析的菜单
    private MenuDef mainMenu;
    private MenuDef recipeList;
    private MenuDef orderList;
    private MenuDef detailCrafting;
    private MenuDef detailFurnace;
    private MenuDef detailSmithing;
    private MenuDef detailStonecutter;
    private MenuDef detailBrewing;
    private MenuDef recipeCreatorShaped;
    private MenuDef recipeCreatorShapeless;
    private MenuDef recipeCreatorFurnace;
    private MenuDef recipeCreatorSmoking;
    private MenuDef recipeCreatorCampfire;
    private MenuDef recipeCreatorBrewing;
    private MenuDef recipeCreatorStonecutter;
    private MenuDef recipeCreatorSmithing;
    private MenuDef recipeCreatorType;

    public MenuConfig(ALCERecipeViewer plugin) {
        this.plugin = plugin;
    }

    // ==================== 加载 ====================

    /** menu.yml 配置版本：v3 = 新增排序管理菜单 order_list（1.1.2） */
    private static final int CONFIG_VERSION = 3;
    /** recipesmenu.yml 配置版本（与 menu.yml 各自独立维护） */
    private static final int CREATOR_CONFIG_VERSION = 2;

    public void load() {
        this.config = plugin.getConfigManager();
        migrateMenuFile("menu.yml", CONFIG_VERSION);
        migrateMenuFile("recipesmenu.yml", CREATOR_CONFIG_VERSION);

        file = new File(plugin.getDataFolder(), "menu.yml");
        yaml = YamlConfiguration.loadConfiguration(file);

        // 同时加载 recipesmenu.yml（新增配方 GUI）
        File creatorFile = new File(plugin.getDataFolder(), "recipesmenu.yml");
        org.bukkit.configuration.file.YamlConfiguration creatorYaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(creatorFile);
        // 合并到主 yaml（config_version 跳过，两文件各自维护）
        for (String key : creatorYaml.getKeys(false)) {
            if ("config_version".equals(key)) continue;
            yaml.set(key, creatorYaml.get(key));
        }

        mainMenu = parseMenu("main_menu");
        recipeList = parseMenu("recipe_list");
        orderList = parseMenu("order_list");
        detailCrafting = parseMenu("detail_crafting");
        detailFurnace = parseMenu("detail_furnace");
        detailSmithing = parseMenu("detail_smithing");
        detailStonecutter = parseMenu("detail_stonecutter");
        detailBrewing = parseMenu("detail_brewing");
        recipeCreatorShaped = parseMenu("recipe_creator_shaped");
        recipeCreatorShapeless = parseMenu("recipe_creator_shapeless");
        recipeCreatorFurnace = parseMenu("recipe_creator_furnace");
        recipeCreatorSmoking = parseMenu("recipe_creator_smoking");
        recipeCreatorCampfire = parseMenu("recipe_creator_campfire");
        recipeCreatorBrewing = parseMenu("recipe_creator_brewing");
        recipeCreatorStonecutter = parseMenu("recipe_creator_stonecutter");
        recipeCreatorSmithing = parseMenu("recipe_creator_smithing");
        recipeCreatorType = parseMenu("recipe_creator_type");
    }

    public void reload() { load(); }

    /**
     * 菜单配置升级（参照 ALFriends 的 config_version 机制）：
     * <ul>
     *   <li>磁盘无该文件 → 从 jar 释放（自带最新版本与注释）；</li>
     *   <li>版本低于当前 → 备份为 {@code *.bak}，然后只补缺失的键（连同注释），
     *       用户自定义过的键一律保留原样，旧版 {@code action:} 写法由兼容层继续识别；</li>
     *   <li>版本已达当前 → 什么都不做。</li>
     * </ul>
     */
    private void migrateMenuFile(String name, int targetVersion) {
        File diskFile = new File(plugin.getDataFolder(), name);
        if (!diskFile.exists()) {
            plugin.saveResource(name, false);
            return;
        }

        YamlConfiguration disk = YamlConfiguration.loadConfiguration(diskFile);
        int version = disk.getInt("config_version", 1);
        if (version >= targetVersion) return;

        // 旧版本先备份，升级出问题可随时用 .bak 回滚
        File backup = new File(plugin.getDataFolder(), name + ".bak");
        try {
            java.nio.file.Files.copy(diskFile.toPath(), backup.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            plugin.getLogger().warning("备份 " + name + " 失败: " + e.getMessage());
        }

        // 只补缺失键（含注释），不动用户已有键
        config.mergeYamlDefaults(diskFile, name);

        // 确保版本号已写入（merge 已带出 jar 里的 config_version，这里兜底）
        try {
            disk = YamlConfiguration.loadConfiguration(diskFile);
            if (disk.getInt("config_version", 0) < targetVersion) {
                disk.set("config_version", targetVersion);
                disk.save(diskFile);
            }
            plugin.getLogger().info("  [OK] " + name + " 已升级到 v" + targetVersion
                    + "（旧文件备份为 " + name + ".bak，自定义修改已保留）");
        } catch (Exception e) {
            plugin.getLogger().warning("写入 " + name + " 版本号失败: " + e.getMessage());
        }
    }

    // ==================== 解析 ====================

    private MenuDef parseMenu(String key) {
        ConfigurationSection sec = yaml.getConfigurationSection(key);
        if (sec == null) {
            plugin.getLogger().warning("menu.yml 缺少节: " + key);
            return null;
        }

        String title = resolveLang(color(sec.getString("title", "")));
        List<String> shapeList = sec.getStringList("shape");
        if (shapeList.isEmpty()) {
            plugin.getLogger().warning(key + ".shape 为空");
            return null;
        }
        String[] shape = shapeList.toArray(new String[0]);

        Map<Character, ButtonDef> buttons = new LinkedHashMap<>();
        ConfigurationSection btnSec = sec.getConfigurationSection("buttons");
        if (btnSec != null) {
            for (String btnKey : btnSec.getKeys(false)) {
                if (btnKey.length() == 1) {
                    char c = btnKey.charAt(0);
                    // # 和 I 有默认定义
                    if (c == '#') {
                        buttons.put('#', parseButton(btnSec, btnKey, Material.BLACK_STAINED_GLASS_PANE, " ", null));
                    } else if (c == 'I') {
                        buttons.put('I', new ButtonDef(null, "", List.of(), "", "", true, null));
                    } else {
                        buttons.put(c, parseButton(btnSec, btnKey, null, null, null));
                    }
                }
            }
        }
        // 确保 # 和 I 默认存在
        buttons.putIfAbsent('#', new ButtonDef(Material.BLACK_STAINED_GLASS_PANE, " ", List.of(), "", "", false, null));
        buttons.putIfAbsent('I', new ButtonDef(null, "", List.of(), "", "", true, null));

        return new MenuDef(title, shape, buttons);
    }

    private ButtonDef parseButton(ConfigurationSection parent, String key,
                                   Material defaultMat, String defaultName, String defaultAction) {
        ConfigurationSection s = parent.getConfigurationSection(key);
        if (s == null) {
            return new ButtonDef(defaultMat, defaultName != null ? defaultName : "",
                    List.of(), defaultAction != null ? defaultAction : "", "", false, null);
        }

        Material mat = defaultMat;
        String matStr = s.getString("material");
        String ceItem = null;
        if (matStr != null && !matStr.isEmpty()) {
            if (matStr.contains(":")) {
                ceItem = matStr; // CE 物品 ID（如 internal:cooking_info）
                mat = Material.PAPER; // 占位
            } else {
                try { mat = Material.valueOf(matStr.toUpperCase()); } catch (IllegalArgumentException ignored) {}
            }
        }

        String name = s.getString("name", defaultName != null ? defaultName : "");
        boolean dynamic = s.getBoolean("dynamic", false);

        List<String> lore = s.getStringList("lore");
        lore = lore.stream().map(l -> color(l)).collect(Collectors.toList());

        String action = s.getString("action", defaultAction != null ? defaultAction : "");
        String category = s.getString("category", "");
        String sound = s.getString("sound", "block.note_block.pling");
        String command = s.getString("command", "");
        boolean asPlayer = s.getBoolean("as_player", true);
        Map<String, List<String>> triggers = new LinkedHashMap<>();
        ConfigurationSection triggerSection = s.getConfigurationSection("triggers");
        if (triggerSection != null) {
            for (String trigger : triggerSection.getKeys(false)) {
                List<String> actions = triggerSection.getStringList(trigger).stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(actionEntry -> !actionEntry.isEmpty())
                        .toList();
                if (!actions.isEmpty()) triggers.put(trigger.toLowerCase(Locale.ROOT), actions);
            }
        }

        return withEncodedTriggers(new ButtonDef(mat, name, lore, action, category, dynamic,
                ceItem, sound, command, asPlayer, Map.copyOf(triggers)));
    }

    /** 加载时预计算四种点击类型的动作编码串，写按钮物品 PDC 用（点击路径零配置查询） */
    private static ButtonDef withEncodedTriggers(ButtonDef def) {
        Map<String, String> encoded = new LinkedHashMap<>();
        for (String click : new String[]{
                MenuTriggerResolver.CLICK_LEFT, MenuTriggerResolver.CLICK_RIGHT,
                MenuTriggerResolver.CLICK_SHIFT_LEFT, MenuTriggerResolver.CLICK_SHIFT_RIGHT}) {
            String resolved = MenuTriggerResolver.resolve(def, click);
            if (resolved != null && !resolved.isBlank()) encoded.put(click, resolved);
        }
        return new ButtonDef(def.material(), def.name(), def.lore(), def.action(), def.category(),
                def.dynamic(), def.ceItem(), def.sound(), def.command(), def.asPlayer(),
                def.triggers(), Map.copyOf(encoded));
    }

    // ==================== 槽位计算 ====================

    /** 将 shape 字符串数组转换为槽位号数组（shape[i] → slots[i]） */
    public static int[] shapeToSlots(String[] shape) {
        int count = shape.length * 9;
        int[] slots = new int[count];
        for (int i = 0; i < count; i++) slots[i] = i;
        return slots;
    }

    /** 菜单大小（格子数） */
    public static int shapeSize(String[] shape) {
        return shape.length * 9;
    }

    /** 提取 shape 中所有动态物品槽位（字符 'I'）的位置列表 */
    public static List<Integer> itemSlots(String[] shape) {
        List<Integer> slots = new ArrayList<>();
        for (int row = 0; row < shape.length; row++) {
            String line = shape[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                if (line.charAt(col) == 'I') {
                    slots.add(row * 9 + col);
                }
            }
        }
        return slots;
    }

    private static int countItemSlots(MenuDef menu) {
        return itemSlots(menu.shape).size();
    }

    // ==================== 按钮构建 ====================

    /** 解析文本中的 {@key} 语言引用 */
    public String resolveLang(String text) {
        if (text == null || !text.contains("{@")) return text;
        String result = text;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{@([^}]+)\\}").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String val = config != null ? config.getLangString("menu." + key) : null;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val != null ? val : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 根据 ButtonDef 构建 ItemStack（替换 {变量} 和 {@lang-key}）。
     *  如果 ceItem 不为 null，返回 PAPER（由调用方用 CE 物品构建） */
    public ItemStack buildButton(ButtonDef btn, Map<String, String> vars) {
        if (btn.ceItem != null) {
            // CE 物品：返回 PAPER 标记，调用方用 ceItem() 获取 ID 后自行构建
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                String name = resolveLang(btn.name);
                if (vars != null) for (Map.Entry<String, String> e : vars.entrySet())
                    name = name.replace("{" + e.getKey() + "}", e.getValue());
                meta.setDisplayName(color(name));
                List<String> processed = new ArrayList<>();
                for (String line : btn.lore) {
                    String l = resolveLang(line);
                    if (vars != null) for (Map.Entry<String, String> e : vars.entrySet())
                        l = l.replace("{" + e.getKey() + "}", e.getValue());
                    processed.add(color(l));
                }
                meta.setLore(processed);
                item.setItemMeta(meta);
            }
            return item;
        }
        ItemStack item = new ItemStack(btn.material != null ? btn.material : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // 名称：先解析 {@lang-key}（展开后可能含 {变量}），再替换 {变量}
        String name = resolveLang(btn.name);
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet())
                name = name.replace("{" + e.getKey() + "}", e.getValue());
        }
        meta.setDisplayName(color(name));

        // Lore：同上
        if (!btn.lore.isEmpty()) {
            List<String> processed = new ArrayList<>();
            for (String line : btn.lore) {
                String l = resolveLang(line);
                if (vars != null) {
                    for (Map.Entry<String, String> e : vars.entrySet())
                        l = l.replace("{" + e.getKey() + "}", e.getValue());
                }
                processed.add(color(l));
            }
            meta.setLore(processed);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** 获取 shape 中某个槽位的按钮定义 */
    public static ButtonDef buttonAt(MenuDef menu, int slot) {
        int row = slot / 9;
        int col = slot % 9;
        if (row >= menu.shape.length) return null;
        String line = menu.shape[row];
        if (col >= line.length()) return null;
        char c = line.charAt(col);
        return menu.buttons.get(c);
    }

    // ==================== 变量替换辅助 ====================

    public static Map<String, String> vars(String... kvs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            map.put(kvs[i], kvs[i + 1]);
        }
        return map;
    }

    // ==================== Getter ====================

    public MenuDef getMainMenu() { return mainMenu; }
    public MenuDef getRecipeList() { return recipeList; }
    public MenuDef getOrderList() { return orderList; }
    public MenuDef getDetailCrafting() { return detailCrafting; }
    public MenuDef getDetailFurnace() { return detailFurnace; }
    public MenuDef getDetailSmithing() { return detailSmithing; }
    public MenuDef getDetailStonecutter() { return detailStonecutter; }
    public MenuDef getDetailBrewing() { return detailBrewing; }
    public MenuDef getRecipeCreatorShaped() { return recipeCreatorShaped; }
    public MenuDef getRecipeCreatorShapeless() { return recipeCreatorShapeless; }
    public MenuDef getRecipeCreatorFurnace() { return recipeCreatorFurnace; }
    public MenuDef getRecipeCreatorSmoking() { return recipeCreatorSmoking; }
    public MenuDef getRecipeCreatorCampfire() { return recipeCreatorCampfire; }
    public MenuDef getRecipeCreatorBrewing() { return recipeCreatorBrewing; }
    public MenuDef getRecipeCreatorStonecutter() { return recipeCreatorStonecutter; }
    public MenuDef getRecipeCreatorSmithing() { return recipeCreatorSmithing; }
    public MenuDef getRecipeCreatorType() { return recipeCreatorType; }

    // ==================== 数据类 ====================

    public record MenuDef(String title, String[] shape, Map<Character, ButtonDef> buttons) {}

    public record ButtonDef(Material material, String name, List<String> lore,
                            String action, String category, boolean dynamic,
                            String ceItem, String sound, String command, boolean asPlayer,
                            Map<String, List<String>> triggers,
                            Map<String, String> encodedTriggers) {
        /** CE 物品 ID（如 internal:cooking_info），null 表示使用标准 Material */
        public String ceItem() { return ceItem; }
        /** 按钮点击声音（原版 Sound 键名或 CE 自定义声音 ID），默认 "block.note_block.pling" */
        public String sound() { return sound; }
        /** 自定义命令（旧 action=RUN_COMMAND 兼容用），"" 表示无 */
        public String command() { return command; }
        /** 命令以谁的身份执行：true=玩家，false=控制台 */
        public boolean asPlayer() { return asPlayer; }
        public Map<String, List<String>> triggers() { return triggers; }
        /** 加载时按 MenuTriggerResolver 预计算的各点击类型动作编码串（click → encoded），点击路径零配置查询 */
        public Map<String, String> encodedTriggers() { return encodedTriggers; }

        public ButtonDef(Material material, String name, List<String> lore,
                         String action, String category, boolean dynamic,
                         String ceItem, String sound, String command, boolean asPlayer,
                         Map<String, List<String>> triggers) {
            this(material, name, lore, action, category, dynamic, ceItem, sound, command,
                    asPlayer, triggers, Map.of());
        }

        public ButtonDef(Material material, String name, List<String> lore,
                         String action, String category, boolean dynamic,
                         String ceItem, String sound, String command, boolean asPlayer) {
            this(material, name, lore, action, category, dynamic, ceItem, sound, command,
                    asPlayer, Map.of(), Map.of());
        }

        /** 便利构造器（无 sound/command） */
        public ButtonDef(Material material, String name, List<String> lore,
                         String action, String category, boolean dynamic,
                         String ceItem) {
            this(material, name, lore, action, category, dynamic, ceItem, "block.note_block.pling", "", true);
        }
    }

    // ==================== 工具方法 ====================

    private static String color(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
