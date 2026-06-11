package com.linong.recipelookup;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

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
    private File file;
    private FileConfiguration yaml;

    // 已解析的菜单
    private MenuDef mainMenu;
    private MenuDef recipeList;
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

    public void load() {
        file = new File(plugin.getDataFolder(), "menu.yml");
        if (!file.exists()) plugin.saveResource("menu.yml", false);
        yaml = YamlConfiguration.loadConfiguration(file);

        // 同时加载 recipesmenu.yml（新增配方 GUI）
        File creatorFile = new File(plugin.getDataFolder(), "recipesmenu.yml");
        if (!creatorFile.exists()) plugin.saveResource("recipesmenu.yml", false);
        org.bukkit.configuration.file.YamlConfiguration creatorYaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(creatorFile);
        // 合并到主 yaml
        for (String key : creatorYaml.getKeys(false)) yaml.set(key, creatorYaml.get(key));

        mainMenu = parseMenu("main_menu");
        recipeList = parseMenu("recipe_list");
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

    // ==================== 解析 ====================

    private MenuDef parseMenu(String key) {
        ConfigurationSection sec = yaml.getConfigurationSection(key);
        if (sec == null) {
            plugin.getLogger().warning("menu.yml 缺少节: " + key);
            return null;
        }

        String title = color(sec.getString("title", ""));
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

        return new ButtonDef(mat, name, lore, action, category, dynamic, ceItem);
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

    /** 根据 ButtonDef 构建 ItemStack（替换 {变量}）。
     *  如果 ceItem 不为 null，返回 PAPER（由调用方用 CE 物品构建） */
    public static ItemStack buildButton(ButtonDef btn, Map<String, String> vars) {
        if (btn.ceItem != null) {
            // CE 物品：返回 PAPER 标记，调用方用 ceItem() 获取 ID 后自行构建
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                String name = btn.name;
                if (vars != null) for (Map.Entry<String, String> e : vars.entrySet())
                    name = name.replace("{" + e.getKey() + "}", e.getValue());
                meta.setDisplayName(color(name));
                List<String> processed = new ArrayList<>();
                for (String line : btn.lore) {
                    String l = line;
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

        // 名称变量替换
        String name = btn.name;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                name = name.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        meta.setDisplayName(color(name));

        // Lore 变量替换
        if (!btn.lore.isEmpty()) {
            List<String> processed = new ArrayList<>();
            for (String line : btn.lore) {
                String l = line;
                if (vars != null) {
                    for (Map.Entry<String, String> e : vars.entrySet()) {
                        l = l.replace("{" + e.getKey() + "}", e.getValue());
                    }
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
                            String ceItem) {
        /** CE 物品 ID（如 internal:cooking_info），null 表示使用标准 Material */
        public String ceItem() { return ceItem; }
    }

    // ==================== 工具方法 ====================

    private static String color(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
