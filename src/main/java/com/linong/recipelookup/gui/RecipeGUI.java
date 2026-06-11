package com.linong.recipelookup.gui;

import com.linong.recipelookup.ALCERecipeViewer;
import com.linong.recipelookup.ConfigManager;
import com.linong.recipelookup.MenuConfig;
import com.linong.recipelookup.MenuConfig.ButtonDef;
import com.linong.recipelookup.MenuConfig.MenuDef;
import com.linong.recipelookup.bridge.CEBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.Collator;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 三级合成表浏览 GUI。
 * 主菜单/配方列表 = 箱子界面（shape 布局 + 分页）；
 * 配方详情 = 原版工作方块 GUI（WORKBENCH/FURNACE/SMITHING/STONECUTTER/BREWING）。
 */
public class RecipeGUI {

    public static final String TYPE_MAIN = "main";
    public static final String TYPE_LIST = "list";
    public static final String TYPE_DETAIL = "detail";

    private final ALCERecipeViewer plugin;
    private final CEBridge bridge;
    private final ConfigManager config;
    private final MenuConfig menuConfig;

    private final Map<UUID, String> guiType = new HashMap<>();
    private final Map<UUID, String> playerCategory = new HashMap<>();
    private final Map<UUID, Integer> playerPage = new HashMap<>();
    private final Map<UUID, String> searchQuery = new HashMap<>();
    private final Map<UUID, Inventory> openInventories = new HashMap<>();
    /** 每个玩家当前打开的菜单定义（用于点击时查找 action） */
    private final Map<UUID, MenuDef> playerMenuDef = new HashMap<>();

    public RecipeGUI(ALCERecipeViewer plugin) {
        this.plugin = plugin;
        this.bridge = plugin.getCEBridge();
        this.config = plugin.getConfigManager();
        this.menuConfig = plugin.getMenuConfig();
    }

    // ===================== 主菜单 =====================

    public void openMainMenu(Player player) {
        MenuDef menu = menuConfig.getMainMenu();
        if (menu == null) return;

        int size = MenuConfig.shapeSize(menu.shape());
        Inventory inv = Bukkit.createInventory(null, size, menu.title());

        for (int row = 0; row < menu.shape().length; row++) {
            String line = menu.shape()[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                int slot = row * 9 + col;
                ButtonDef btn = menu.buttons().get(c);
                if (btn == null) continue;

                if (btn.dynamic()) {
                    // 主菜单暂无动态按钮
                    continue;
                }

                if (c == '#') {
                    // 背景
                    inv.setItem(slot, MenuConfig.buildButton(btn, null));
                } else {
                    // 分类按钮：替换 {count}
                    int count = plugin.getLoadedRecipes()
                            .getOrDefault(btn.category(), List.of()).size();
                    Map<String, String> v = MenuConfig.vars("count", String.valueOf(count));
                    inv.setItem(slot, MenuConfig.buildButton(btn, v));
                }
            }
        }

        UUID uuid = player.getUniqueId();
        player.openInventory(inv);
        guiType.put(uuid, TYPE_MAIN);
        playerCategory.remove(uuid);
        playerPage.remove(uuid);
        playerMenuDef.put(uuid, menu);
        openInventories.put(uuid, inv);
    }

    // ===================== 配方列表 =====================

    public void openRecipeList(Player player, String categoryId, int page) {
        MenuDef menu = menuConfig.getRecipeList();
        if (menu == null) return;

        String filter = searchQuery.get(player.getUniqueId());
        List<CEBridge.RecipeData> recipes = getSortedRecipes(categoryId, filter);

        List<Integer> itemSlots = MenuConfig.itemSlots(menu.shape());
        int pageSize = itemSlots.size();
        int totalPages = Math.max(1, (recipes.size() + pageSize - 1) / pageSize);
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        // 标题变量替换
        CEBridge.CategoryMeta meta = CEBridge.CATEGORIES.get(categoryId);
        String categoryName = config.getDefaultCategoryName(categoryId);
        if (meta != null && categoryName.equals(categoryId)) categoryName = meta.name;
        String title = menu.title()
                .replace("{category}", categoryName)
                .replace("{page}", String.valueOf(page + 1))
                .replace("{total}", String.valueOf(totalPages));
        if (filter != null && !filter.isEmpty()) {
            title += config.getSearchTitleSuffix(filter);
        }

        int size = MenuConfig.shapeSize(menu.shape());
        Inventory inv = Bukkit.createInventory(null, size, title);

        // 构建 GUI
        int recipeIdx = page * pageSize;
        for (int row = 0; row < menu.shape().length; row++) {
            String line = menu.shape()[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                int slot = row * 9 + col;
                ButtonDef btn = menu.buttons().get(c);
                if (btn == null) continue;

                if (c == 'I') {
                    // 动态物品区
                    if (recipeIdx < recipes.size()) {
                        inv.setItem(slot, createRecipeEntryIcon(recipes.get(recipeIdx)));
                        recipeIdx++;
                    }
                } else if (c == '#') {
                    inv.setItem(slot, MenuConfig.buildButton(btn, null));
                } else {
                    // 导航按钮：替换变量
                    Map<String, String> v = MenuConfig.vars(
                            "page", String.valueOf(page + 1),
                            "total", String.valueOf(totalPages),
                            "query", filter != null ? filter : "无",
                            "category", categoryName
                    );
                    inv.setItem(slot, MenuConfig.buildButton(btn, v));
                }
            }
        }

        UUID uuid = player.getUniqueId();
        player.openInventory(inv);
        guiType.put(uuid, TYPE_LIST);
        playerCategory.put(uuid, categoryId);
        playerPage.put(uuid, page);
        playerMenuDef.put(uuid, menu);
        openInventories.put(uuid, inv);
    }

    public void openFilteredRecipeList(Player player, String categoryId, List<CEBridge.RecipeData> filtered, String query) {
        searchQuery.put(player.getUniqueId(), query);
        openRecipeList(player, categoryId, 0);
    }

    /** 获取排序后的配方列表（与 openRecipeList 显示顺序一致） */
    public List<CEBridge.RecipeData> getSortedRecipes(String categoryId, String filter) {
        List<CEBridge.RecipeData> all = plugin.getLoadedRecipes().getOrDefault(categoryId, List.of());
        List<CEBridge.RecipeData> recipes = new ArrayList<>(
                (filter != null) ? filterRecipes(all, filter) : all);
        recipes.sort(Comparator.comparing(r -> toChineseName(r.resultId), CN_COLLATOR));
        return recipes;
    }

    /** 排序键：用于中文拼音首字母分组 */
    private String sortKey(String itemId) {
        return sortGroup(toChineseName(itemId));
    }

    public void clearSearch(Player player) {
        UUID uuid = player.getUniqueId();
        searchQuery.remove(uuid);
        String category = playerCategory.getOrDefault(uuid, "crafting");
        openRecipeList(player, category, 0);
    }

    // ===================== 新增配方 GUI（管理员）=====================

    public final Map<UUID, String> creatorType = new HashMap<>();
    private final Map<UUID, Integer> creatorTime = new HashMap<>(); // 高炉秒
    private final Map<UUID, Integer> creatorFurnaceTime = new HashMap<>(); // 熔炉秒
    private final Map<UUID, Integer> creatorExp = new HashMap<>();  // 0.1为单位

    public final Map<UUID, String> pendingExpInput = new HashMap<>();

    private final Map<UUID, String> savedCreatorType = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedCreatorItems = new HashMap<>();
    private final Map<UUID, Integer> savedCreatorExpVal = new HashMap<>();
    private final Map<UUID, Integer> savedCreatorTimeVal = new HashMap<>();
    private final Map<UUID, Integer> savedCreatorFurnaceTimeVal = new HashMap<>();

    /** Shift+左键经验按钮 → 保存状态，关闭 GUI 等待聊天输入 */
    public void expectExpInput(Player player) {
        UUID uuid = player.getUniqueId();
        savedCreatorType.put(uuid, creatorType.getOrDefault(uuid, "shaped"));
        // 保存当前数值（closeInventory 会触发 removePlayer 清空状态）
        savedCreatorExpVal.put(uuid, creatorExp.getOrDefault(uuid, 10));
        savedCreatorTimeVal.put(uuid, creatorTime.getOrDefault(uuid, 5));
        savedCreatorFurnaceTimeVal.put(uuid, creatorFurnaceTime.getOrDefault(uuid, 10));
        // 保存物品栏内容
        Inventory inv = openInventories.get(uuid);
        if (inv != null) savedCreatorItems.put(uuid, inv.getContents().clone());
        pendingExpInput.put(uuid, "exp");
        player.closeInventory();
        player.sendMessage(config.getPluginPrefix() + " §e请在聊天栏输入经验值（如 1.5，范围 0.1~360.0，输入 cancel 取消）");
    }

    /** 处理聊天栏输入的经验值 */
    public boolean handleExpInput(Player player, String input) {
        UUID uuid = player.getUniqueId();
        if (!"exp".equals(pendingExpInput.remove(uuid))) return false;
        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage(config.getPluginPrefix() + " §7已取消。");
            reopenCreatorAfterInput(player, false);
            return true;
        }
        try {
            double val = Double.parseDouble(input);
            if (val <= 0 || val > 360.0) { player.sendMessage(config.getPluginPrefix() + " §c请输入 0.1~360.0 之间的数值"); reopenCreatorAfterInput(player, false); return true; }
            int tenths = Math.max(1, Math.min(3600, (int) Math.round(val * 10)));
            creatorExp.put(uuid, tenths);
            savedCreatorExpVal.put(uuid, tenths); // 同步更新快照，否则 reopenCreatorAfterInput 会读到旧值
            player.sendMessage(config.getPluginPrefix() + " §a经验已设为: " + String.format("%.1f", tenths / 10.0));
        } catch (NumberFormatException e) {
            player.sendMessage(config.getPluginPrefix() + " §c无效数字，请输入如 1.5");
        }
        reopenCreatorAfterInput(player, true);
        return true;
    }

    private void reopenCreatorAfterInput(Player player, boolean keepDefaults) {
        UUID uuid = player.getUniqueId();
        String saved = savedCreatorType.remove(uuid);
        if (saved == null) saved = creatorType.getOrDefault(uuid, "shaped");
        final String type = saved;
        // 优先从快照读取（expectExpInput 在 closeInventory 前保存），回退到当前值
        Integer savedExpObj = savedCreatorExpVal.remove(uuid);
        Integer savedTimeObj = savedCreatorTimeVal.remove(uuid);
        Integer savedFurnaceTimeObj = savedCreatorFurnaceTimeVal.remove(uuid);
        final int savedExp = savedExpObj != null ? savedExpObj : creatorExp.getOrDefault(uuid, 10);
        final int savedTime = savedTimeObj != null ? savedTimeObj : creatorTime.getOrDefault(uuid, 5);
        final int savedFurnaceTime = savedFurnaceTimeObj != null ? savedFurnaceTimeObj : creatorFurnaceTime.getOrDefault(uuid, 10);
        final ItemStack[] items = savedCreatorItems.remove(uuid);
        plugin.getFoliaLib().getScheduler().runNextTick(t -> {
            if ("creator".equals(guiType.get(uuid))) return;
            openRecipeCreator(player, type);
            if (items != null) {
                Inventory inv2 = openInventories.get(uuid);
                if (inv2 != null) inv2.setContents(items);
            }
            if (keepDefaults) {
                creatorExp.put(uuid, savedExp);
                creatorTime.put(uuid, savedTime);
                creatorFurnaceTime.put(uuid, savedFurnaceTime);
                // 刷新 GUI 中显示的数值按钮（必须在 setContents 之后，否则会被旧物品快照覆盖）
                Inventory inv2 = openInventories.get(uuid);
                if (inv2 != null) refreshCreatorButtons(inv2, type, uuid);
            }
        });
    }

    private void refreshExpButton(Inventory inv, String type, UUID uuid) {
        MenuDef menu = switch (type) {
            case "furnace" -> menuConfig.getRecipeCreatorFurnace();
            case "smoking" -> menuConfig.getRecipeCreatorSmoking();
            default -> null;
        };
        if (menu == null) return;
        List<Integer> eSlots = charSlots(menu.shape(), 'E');
        if (eSlots.isEmpty()) return;
        ButtonDef eBtn = menu.buttons().get('E');
        if (eBtn == null) return;
        String s = String.format("%.1f", creatorExp.getOrDefault(uuid, 10) / 10.0);
        inv.setItem(eSlots.get(0), MenuConfig.buildButton(eBtn, MenuConfig.vars("time", s, "exp", s)));
    }

    /** 刷新配方编辑器中所有数值按钮（时间/经验） */
    private void refreshCreatorButtons(Inventory inv, String type, UUID uuid) {
        MenuDef menu = switch (type) {
            case "furnace" -> menuConfig.getRecipeCreatorFurnace();
            case "smoking" -> menuConfig.getRecipeCreatorSmoking();
            case "campfire" -> menuConfig.getRecipeCreatorCampfire();
            default -> null;
        };
        if (menu == null) return;
        // 刷新所有时间按钮（G/P/Y）
        for (char ch : new char[]{'G', 'P', 'Y'}) {
            ButtonDef btn = menu.buttons().get(ch);
            if (btn == null) continue;
            List<Integer> slots = charSlots(menu.shape(), ch);
            if (slots.isEmpty()) continue;
            int value;
            if (ch == 'P') value = creatorFurnaceTime.getOrDefault(uuid, 10);
            else if (ch == 'G' && "campfire".equals(type)) value = creatorTime.getOrDefault(uuid, 30);
            else value = creatorTime.getOrDefault(uuid, "campfire".equals(type) ? 30 : 5);
            inv.setItem(slots.get(0), MenuConfig.buildButton(btn, MenuConfig.vars("time", String.valueOf(value))));
        }
        // 刷新经验按钮（E）
        ButtonDef eBtn = menu.buttons().get('E');
        if (eBtn != null) {
            List<Integer> eSlots = charSlots(menu.shape(), 'E');
            if (!eSlots.isEmpty()) {
                String s = String.format("%.1f", creatorExp.getOrDefault(uuid, 10) / 10.0);
                inv.setItem(eSlots.get(0), MenuConfig.buildButton(eBtn, MenuConfig.vars("time", s, "exp", s)));
            }
        }
    }

    public void openRecipeCreatorType(Player player) {
        MenuDef menu = menuConfig.getRecipeCreatorType();
        if (menu == null) return;
        UUID uuid = player.getUniqueId();
        Inventory inv = buildSimpleGUI(menu);
        player.openInventory(inv);
        guiType.put(uuid, "creator_type");
        openInventories.put(uuid, inv);
    }

    public void openRecipeCreator(Player player, String type) {
        MenuDef menu = switch (type) {
            case "furnace" -> menuConfig.getRecipeCreatorFurnace();
            case "smoking" -> menuConfig.getRecipeCreatorSmoking();
            case "campfire" -> menuConfig.getRecipeCreatorCampfire();
            case "brewing" -> menuConfig.getRecipeCreatorBrewing();
            case "smithing" -> menuConfig.getRecipeCreatorSmithing();
            case "stonecutting" -> menuConfig.getRecipeCreatorStonecutter();
            case "shaped" -> menuConfig.getRecipeCreatorShaped();
            case "shapeless" -> menuConfig.getRecipeCreatorShapeless();
            default -> menuConfig.getRecipeCreatorShaped();
        };
        if (menu == null) return;
        UUID uuid = player.getUniqueId();
        // 根据类型设不同默认值
        switch (type) {
            case "furnace" -> creatorFurnaceTime.put(uuid, 10); // 熔炉10s，高炉自动=一半
            case "smoking" -> creatorTime.put(uuid, 5);   // 烟熏5s
            case "campfire" -> creatorTime.put(uuid, 30); // 营火30s
            default -> { creatorTime.put(uuid, 5); creatorFurnaceTime.put(uuid, 10); }
        }
        creatorExp.put(uuid, 10); // 经验默认1.0 (10×0.1)
        Inventory inv = buildCreatorGUI(menu, uuid);
        player.openInventory(inv);
        guiType.put(uuid, "creator");
        creatorType.put(uuid, type != null ? type : "crafting");
        openInventories.put(uuid, inv);
    }

    /** 左键/右键调整时间或经验 */
    public void adjustCreatorValue(Player player, String slotChar, boolean increase) {
        UUID uuid = player.getUniqueId();
        if (!"creator".equals(guiType.getOrDefault(uuid, ""))) return;
        Inventory inv = openInventories.get(uuid);
        if (inv == null) return;
        String type = creatorType.getOrDefault(uuid, "crafting");
        MenuDef menu = switch (type) {
            case "furnace" -> menuConfig.getRecipeCreatorFurnace();
            case "smoking" -> menuConfig.getRecipeCreatorSmoking();
            case "campfire" -> menuConfig.getRecipeCreatorCampfire();
            case "brewing" -> menuConfig.getRecipeCreatorBrewing();
            default -> null;
        };
        if (menu == null) return;

        Map<UUID, Integer> map;
        int def;
        if ("P".equals(slotChar)) { map = creatorFurnaceTime; def = 10; }
        else if ("E".equals(slotChar)) { map = creatorExp; def = 10; }
        else { map = creatorTime; def = ("campfire".equals(type) ? 30 : 5); }

        int max = "E".equals(slotChar) ? 3600 : 3600; // 经验上限360.0，时间上限3600秒
        int newVal = map.getOrDefault(uuid, def) + (increase ? 1 : -1);
        if (newVal < 1) newVal = 1; if (newVal > max) newVal = max;
        map.put(uuid, newVal);

        // 刷新单槽按钮
        ButtonDef btn = menu.buttons().get(slotChar.charAt(0));
        if (btn == null) return;
        List<Integer> slots = charSlots(menu.shape(), slotChar.charAt(0));
        if (slots.isEmpty()) return;
        String label = "E".equals(slotChar)
                ? String.format("%.1f", creatorExp.getOrDefault(uuid, 10) / 10.0)
                : String.valueOf(newVal);
        inv.setItem(slots.get(0), MenuConfig.buildButton(btn, MenuConfig.vars("time", label, "exp", label)));
    }

    private Inventory buildCreatorGUI(MenuDef menu, UUID uuid) {
        Inventory inv = Bukkit.createInventory(null, MenuConfig.shapeSize(menu.shape()), menu.title());
        // 背景放玻璃板（装饰），I/R 留空（编辑区），控制按钮放对应物品
        for (int row = 0; row < menu.shape().length; row++) {
            String line = menu.shape()[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                ButtonDef btn = menu.buttons().get(c);
                if (btn == null) continue;
                if (btn.dynamic()) continue; // I/R 留空
                int slot = row * 9 + col;
                Map<String, String> v = null;
                if (c == 'G') v = MenuConfig.vars("time", String.valueOf(creatorTime.getOrDefault(uuid, 5)));
                else if (c == 'P') v = MenuConfig.vars("time", String.valueOf(creatorFurnaceTime.getOrDefault(uuid, 10)));
                else if (c == 'Y') v = MenuConfig.vars("time", String.valueOf(creatorTime.getOrDefault(uuid, 5)));
                else if (c == 'S') v = MenuConfig.vars("time", String.valueOf(creatorTime.getOrDefault(uuid, 20)));
                else if (c == 'E') {
                    String s = String.format("%.1f", creatorExp.getOrDefault(uuid, 10) / 10.0);
                    v = MenuConfig.vars("time", s, "exp", s);
                }
                inv.setItem(slot, MenuConfig.buildButton(btn, v));
            }
        }
        return inv;
    }

    private Inventory buildSimpleGUI(MenuDef menu) {
        Inventory inv = Bukkit.createInventory(null, MenuConfig.shapeSize(menu.shape()), menu.title());
        for (int row = 0; row < menu.shape().length; row++) {
            String line = menu.shape()[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                ButtonDef btn = menu.buttons().get(c);
                if (btn != null && !btn.dynamic()) inv.setItem(row * 9 + col, MenuConfig.buildButton(btn, null));
            }
        }
        return inv;
    }

    /** 检查是否至少有一个原料和结果 */
    private boolean hasAnyItem(Inventory inv, List<Integer> slots) {
        for (int s : slots) {
            ItemStack item = inv.getItem(s);
            if (item != null && !item.getType().isAir()) return true;
        }
        return false;
    }

    /** 保存配方到 CE 配置文件 */
    public void saveCreatorRecipe(Player player) {
        UUID uuid = player.getUniqueId();
        Inventory inv = openInventories.get(uuid);
        if (inv == null) return;
        String type = creatorType.getOrDefault(uuid, "crafting");

        // 收集原料和结果（根据不同的 creator 类型）
        MenuDef menu = switch (type) {
            case "furnace" -> menuConfig.getRecipeCreatorFurnace();
            case "smoking" -> menuConfig.getRecipeCreatorSmoking();
            case "campfire" -> menuConfig.getRecipeCreatorCampfire();
            case "smithing" -> menuConfig.getRecipeCreatorSmithing();
            case "stonecutting" -> menuConfig.getRecipeCreatorStonecutter();
            case "brewing" -> menuConfig.getRecipeCreatorBrewing();
            case "shapeless" -> menuConfig.getRecipeCreatorShapeless();
            default -> menuConfig.getRecipeCreatorShaped();
        };
        if (menu == null) return;

        List<Integer> iSlots = charSlots(menu.shape(), 'I');
        List<Integer> rSlots = charSlots(menu.shape(), 'R');
        if (iSlots.isEmpty() || rSlots.isEmpty()) { player.sendMessage(config.getPluginPrefix() + " §c菜单配置缺少I或R槽位！"); return; }

        // 验证：至少有一个原料和一个结果
        if (!hasAnyItem(inv, iSlots) || !hasAnyItem(inv, rSlots)) {
            player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorNoItems());
            return;
        }

        ItemStack result = inv.getItem(rSlots.get(0));
        String resultId = getItemId(result);
        int resultCount = result.getAmount();

        java.io.File ceDir = new java.io.File(plugin.getCEBridge().getCEPlugin().getDataFolder(), "resources");
        if (!ceDir.exists()) ceDir = new java.io.File("plugins/CraftEngine/resources");
        if (!ceDir.exists()) { player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorNoCEDir()); return; }
        // CE 要求 resources/<pack>/configuration/ 结构
        java.io.File packDir = new java.io.File(ceDir, "alcerecipeviewer");
        java.io.File configDir = new java.io.File(packDir, "configuration");
        if (!configDir.exists()) configDir.mkdirs();
        // 首次创建 pack.yml
        java.io.File packYml = new java.io.File(packDir, "pack.yml");
        if (!packYml.exists()) {
            try (java.io.FileWriter fw = new java.io.FileWriter(packYml)) {
                fw.write("namespace: alcerecipeviewer\nauthor: ALCERecipeViewer\nversion: 1.0\n");
            } catch (Exception ignored) {}
        }
        java.io.File recipeFile = new java.io.File(configDir, "custom_recipes.yml");
        boolean recipeFileExists = recipeFile.exists();

        // 读取已有文件内容，用于去重
        String existingContent = "";
        if (recipeFileExists) {
            try {
                existingContent = new String(java.nio.file.Files.readAllBytes(recipeFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }

        String recipeName = sanitize(resultId);
        StringBuilder yaml = new StringBuilder();
        if (!recipeFileExists) {
            yaml.append("recipes:\n");
        }

        switch (type) {
            case "stonecutting" -> {
                String inputId = getSlotItemId(inv, iSlots, 0);
                if (inputId == null) return;
                yaml.append("  ").append(uniqueKey(recipeName, "stonecut", existingContent)).append(":\n");
                yaml.append("    type: stonecutting\n");
                yaml.append("    ingredient: ").append(inputId).append("\n");
                yaml.append("    result:\n      id: ").append(resultId).append("\n      count: 1\n");
            }
            case "furnace" -> {
                String inputId = getSlotItemId(inv, iSlots, 0);
                if (inputId == null) return;
                int fTime = creatorFurnaceTime.getOrDefault(uuid, 10);
                int bTime = Math.max(1, fTime / 2); // 高炉时间自动=熔炉时间÷2
                int expTenths = creatorExp.getOrDefault(uuid, 10);
                yaml.append("  ").append(uniqueKey(recipeName, "smelted", existingContent)).append(":\n");
                yaml.append("    type: smelting\n    category: misc\n");
                yaml.append("    time: ").append(String.valueOf(fTime * 20)).append("\n");
                yaml.append("    experience: ").append(String.format("%.1f", expTenths / 10.0)).append("\n");
                yaml.append("    ingredient: ").append(inputId).append("\n");
                yaml.append("    result:\n      id: ").append(resultId).append("\n      count: ").append(resultCount).append("\n");
                // 高炉
                yaml.append("  ").append(uniqueKey(recipeName, "blasted", existingContent)).append(":\n");
                yaml.append("    type: blasting\n    category: misc\n");
                yaml.append("    time: ").append(String.valueOf(bTime * 20)).append("\n");
                yaml.append("    experience: ").append(String.format("%.1f", expTenths / 10.0)).append("\n");
                yaml.append("    ingredient: ").append(inputId).append("\n");
                yaml.append("    result:\n      id: ").append(resultId).append("\n      count: ").append(resultCount).append("\n");
            }
            case "smoking" -> {
                String inputId = getSlotItemId(inv, iSlots, 0);
                if (inputId == null) return;
                int yTime = creatorTime.getOrDefault(uuid, 5);
                int expTenths = creatorExp.getOrDefault(uuid, 10);
                yaml.append("  ").append(uniqueKey(recipeName, "smoked", existingContent)).append(":\n");
                yaml.append("    type: smoking\n    category: food\n");
                yaml.append("    time: ").append(String.valueOf(yTime * 20)).append("\n");
                yaml.append("    experience: ").append(String.format("%.1f", expTenths / 10.0)).append("\n");
                yaml.append("    ingredient: ").append(inputId).append("\n");
                yaml.append("    result:\n      id: ").append(resultId).append("\n      count: ").append(resultCount).append("\n");
            }
            case "campfire" -> {
                String inputId = getSlotItemId(inv, iSlots, 0);
                if (inputId == null) return;
                int gTime = creatorTime.getOrDefault(uuid, 30);
                yaml.append("  ").append(uniqueKey(recipeName, "campfire", existingContent)).append(":\n");
                yaml.append("    type: campfire_cooking\n    category: food\n");
                yaml.append("    time: ").append(String.valueOf(gTime * 20)).append("\n");
                yaml.append("    ingredient: ").append(inputId).append("\n");
                yaml.append("    result:\n      id: ").append(resultId).append("\n      count: ").append(resultCount).append("\n");
            }
            case "brewing" -> {
                String ingredient = getSlotItemId(inv, iSlots, 0);
                String container = getSlotItemId(inv, iSlots, 1);
                if (ingredient == null || container == null) return;
                yaml.append("  ").append(uniqueKey(recipeName, "brew", existingContent)).append(":\n");
                yaml.append("    type: brewing\n");
                yaml.append("    ingredient: ").append(ingredient).append("\n");
                yaml.append("    container: ").append(container).append("\n");
                yaml.append("    result:\n      id: ").append(resultId).append("\n      count: ").append(resultCount).append("\n");
            }
            case "smithing" -> {
                // I 槽顺序：模板 → 装备 → 材质
                String template = getSlotItemId(inv, iSlots, 0);
                String base = getSlotItemId(inv, iSlots, 1);
                String addition = getSlotItemId(inv, iSlots, 2);
                if (base == null || addition == null) { player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorNoItems()); return; }
                String recipeId = uniqueKey(recipeName, "smithing", existingContent);
                yaml.append("  ").append(recipeId).append(":\n");
                yaml.append("    type: smithing_transform\n");
                if (template != null) yaml.append("    template_type: ").append(template).append("\n");
                else yaml.append("    template_type: ").append(base).append("\n");
                yaml.append("    base: ").append(base).append("\n");
                yaml.append("    addition: ").append(addition).append("\n");
                yaml.append("    result:\n");
                yaml.append("      id: ").append(resultId).append("\n");
                yaml.append("      count: ").append(resultCount).append("\n");
            }
            default -> {
                // crafting：按用户选择的子类型决定 shaped/shapless
                boolean isShaped = "shaped".equals(type);
                List<String> ingredientList = new ArrayList<>();
                for (int idx = 0; idx < iSlots.size(); idx++) {
                    String id = getSlotItemId(inv, iSlots, idx);
                    if (id != null) ingredientList.add(id);
                }
                if (ingredientList.isEmpty()) { player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorNoItems()); return; }
                String recipeId = uniqueKey(recipeName, isShaped ? "shaped" : "shapeless", existingContent);
                yaml.append("  ").append(recipeId).append(":\n");
                if (isShaped) {
                    // 构建 pattern 和 ingredient 映射
                    Map<String, String> letterToId = new LinkedHashMap<>(); // 字母→物品ID
                    Map<String, Character> idToLetter = new LinkedHashMap<>(); // 物品ID→字母
                    char letter = 'A';
                    String[] pattern = new String[3];
                    for (int row = 0; row < 3; row++) {
                        StringBuilder rs = new StringBuilder();
                        for (int col = 0; col < 3; col++) {
                            String id = getSlotItemId(inv, iSlots, row * 3 + col);
                            if (id != null) {
                                Character l = idToLetter.get(id);
                                if (l == null) { l = letter++; idToLetter.put(id, l); letterToId.put(String.valueOf(l), id); }
                                rs.append(l);
                            } else {
                                rs.append(' ');
                            }
                        }
                        pattern[row] = rs.toString();
                    }
                    yaml.append("    type: shaped\n    category: misc\n    pattern:\n");
                    for (String p : pattern) yaml.append("      - \"").append(p).append("\"\n");
                    yaml.append("    ingredients:\n");
                    for (Map.Entry<String, String> e : letterToId.entrySet())
                        yaml.append("      ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                } else {
                    yaml.append("    type: shapeless\n");
                    yaml.append("    category: misc\n");
                    yaml.append("    ingredients:\n");
                    for (String ing : ingredientList)
                        yaml.append("      - id: ").append(ing).append("\n        count: 1\n");
                }
                yaml.append("    result:\n");
                yaml.append("      id: ").append(resultId).append("\n");
                yaml.append("      count: ").append(resultCount).append("\n");
            }
        }

        try (java.io.FileWriter fw = new java.io.FileWriter(recipeFile, true)) {
            fw.write("\n" + yaml.toString());
        } catch (Exception e) {
            player.sendMessage(config.getPluginPrefix() + " §c保存失败: " + e.getMessage()); return;
        }
        player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorSaved(recipeFile.getPath()));
        player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorReload1());
        player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorReload2());
        plugin.getLogger().info("[ALCE] " + player.getName() + " 创建配方: " + type);
    }

    private String getSlotItemId(Inventory inv, List<Integer> slots, int index) {
        if (index >= slots.size()) return null;
        ItemStack item = inv.getItem(slots.get(index));
        return item != null && !item.getType().isAir() ? getItemId(item) : null;
    }

    private String sanitize(String id) {
        if (id == null) return "unknown";
        String v = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return v.replaceAll("[^a-z0-9_]", "_").toLowerCase();
    }

    /** 生成唯一配方键，避免同名覆盖 */
    private String uniqueKey(String baseName, String suffix, String existingContent) {
        String key = "alcerecipeviewer:" + baseName + "_" + suffix;
        if (!existingContent.contains(key)) return key;
        int n = 2;
        while (existingContent.contains(key + "_" + n)) n++;
        return key + "_" + n;
    }

    /** 获取物品的完整 ID（namespace:value） */
    private String getItemId(ItemStack item) {
        if (item == null) return null;
        if (item.getType().isAir()) return null;
        String ceId = bridge.getCEItemId(item);
        if (ceId != null) return ceId;
        return item.getType().getKey().getNamespace() + ":" + item.getType().getKey().getKey();
    }

    /** 暴露 CEPlugin 给命令系统 */
    public CEBridge getBridge() { return bridge; }

    public void openRecipeCreator(Player player) {
        openRecipeCreator(player, "crafting");
    }

    // ===================== 配方详情 → 原版工作方块 GUI =====================

    public void openRecipeDetail(Player player, CEBridge.RecipeData recipe, String categoryId, int listPage) {
        Inventory inv;
        MenuDef menu = null;
        switch (recipe.type) {
            case "crafting" -> { inv = createCraftingTableGUI(recipe); menu = menuConfig.getDetailCrafting(); }
            case "smithing"  -> { inv = createSmithingTableGUI(recipe); menu = menuConfig.getDetailSmithing(); }
            case "smelting", "blasting", "smoking", "campfire_cooking" -> {
                inv = createFurnaceGUI(recipe); menu = menuConfig.getDetailFurnace();
            }
            case "stonecutting" -> { inv = createStonecutterGUI(recipe); menu = menuConfig.getDetailStonecutter(); }
            case "brewing" -> { inv = createBrewingGUI(recipe); menu = menuConfig.getDetailBrewing(); }
            default -> { inv = createCraftingTableGUI(recipe); menu = menuConfig.getDetailCrafting(); }
        }

        UUID uuid = player.getUniqueId();
        player.openInventory(inv);
        guiType.put(uuid, TYPE_DETAIL);
        playerCategory.put(uuid, categoryId);
        playerPage.put(uuid, listPage);
        playerMenuDef.put(uuid, menu);
        openInventories.put(uuid, inv);
    }

    /** 工作台 GUI：shape 驱动 */
    private Inventory createCraftingTableGUI(CEBridge.RecipeData recipe) {
        MenuDef menu = menuConfig.getDetailCrafting();
        if (menu == null) return fallbackDetail("crafting", recipe);
        Inventory inv = buildDetailGUI(menu);
        fillDetailSlots(inv, menu, recipe);
        return inv;
    }

    /** 熔炉 GUI：shape 驱动 + 配方信息 */
    private Inventory createFurnaceGUI(CEBridge.RecipeData recipe) {
        MenuDef menu = menuConfig.getDetailFurnace();
        if (menu == null) return fallbackDetail("furnace", recipe);
        Inventory inv = buildDetailGUI(menu);
        fillDetailSlots(inv, menu, recipe);
        // H 槽：CE 纹理图标 + 燃烧时间/经验（material 写 CE 物品 ID 即可，软编码）
        List<Integer> hSlots = charSlots(menu.shape(), 'H');
        if (!hSlots.isEmpty()) {
            ButtonDef hBtn = menu.buttons().get('H');
            if (hBtn != null) {
                Map<String, String> v = MenuConfig.vars(
                        "fuel_time", formatCookingTime(recipe.cookingTime),
                        "fuel_exp", recipe.experience > 0 ? String.valueOf(recipe.experience) : "0"
                );
                inv.setItem(hSlots.get(0), buildButtonOrCE(hBtn, v));
            }
        }
        // Q 槽：配方信息 + 烹饪时间/经验
        List<Integer> qSlots = charSlots(menu.shape(), 'Q');
        if (!qSlots.isEmpty() && recipe.cookingTime > 0) {
            ButtonDef qBtn = menu.buttons().get('Q');
            if (qBtn != null) {
                Map<String, String> v = MenuConfig.vars(
                        "time", formatCookingTime(recipe.cookingTime),
                        "exp", recipe.experience > 0 ? String.valueOf(recipe.experience) : "0"
                );
                inv.setItem(qSlots.get(0), MenuConfig.buildButton(qBtn, v));
            }
        }
        return inv;
    }

    /** 构建 CE 内部物品（如 cooking_info），失败返回 PAPER */
    private ItemStack ceItem(String itemId) {
        ItemStack item = bridge.buildItemStack(itemId, 1);
        return item.getType() != Material.PAPER ? item : null;
    }

    /** 通用：填充原料 I + 结果 R 到 detail GUI */
    private void fillDetailSlots(Inventory inv, MenuDef menu, CEBridge.RecipeData recipe) {
        List<Integer> iSlots = charSlots(menu.shape(), 'I');
        List<Integer> rSlots = charSlots(menu.shape(), 'R');
        List<String> ings = recipe.ingredientIds;
        // 有序合成：按 pattern 填充
        if (recipe.shapedPattern != null && recipe.patternKeyIds != null && iSlots.size() >= 9) {
            String[] pattern = recipe.shapedPattern;
            Map<String, String> keyMap = recipe.patternKeyIds;
            for (int r = 0; r < pattern.length && r < 3; r++) {
                for (int c = 0; c < pattern[r].length() && c < 3; c++) {
                    char ch = pattern[r].charAt(c);
                    int idx = r * 3 + c;
                    if (ch != ' ' && keyMap.containsKey(String.valueOf(ch)) && idx < iSlots.size())
                        inv.setItem(iSlots.get(idx), createNamedItem(keyMap.get(String.valueOf(ch)), 1));
                }
            }
        } else {
            for (int i = 0; i < ings.size() && i < iSlots.size(); i++)
                inv.setItem(iSlots.get(i), createNamedItem(ings.get(i), 1));
        }
        // 结果
        if (!rSlots.isEmpty())
            inv.setItem(rSlots.get(0), createNamedItem(recipe.resultId, recipe.resultCount));
    }

    private Inventory fallbackDetail(String type, CEBridge.RecipeData recipe) {
        Inventory inv = Bukkit.createInventory(null, 27, config.getDetailTitle(type));
        List<String> ings = recipe.ingredientIds;
        if (!ings.isEmpty()) inv.setItem(11, createNamedItem(ings.get(0), 1));
        inv.setItem(15, createNamedItem(recipe.resultId, recipe.resultCount));
        return inv;
    }

    private String formatCookingTime(int ticks) {
        double seconds = ticks / 20.0;
        if (seconds < 60) return String.format("%.1f秒", seconds);
        int min = (int) seconds / 60;
        int sec = (int) seconds % 60;
        return sec > 0 ? min + "分" + sec + "秒" : min + "分钟";
    }

    /** 锻造台 GUI：模板 → 装备 → 材质 → 结果 */
    private Inventory createSmithingTableGUI(CEBridge.RecipeData recipe) {
        MenuDef menu = menuConfig.getDetailSmithing();
        if (menu == null) return Bukkit.createInventory(null, 27, config.getDetailTitle("smithing"));
        Inventory inv = buildDetailGUI(menu);
        List<Integer> iSlots = charSlots(menu.shape(), 'I');
        List<Integer> rSlots = charSlots(menu.shape(), 'R');
        List<String> ings = recipe.ingredientIds; // CE: [base, template, addition]
        // 重排：模板(template) → 装备(base) → 材质(addition)
        if (iSlots.size() >= 3) {
            if (ings.size() >= 2) inv.setItem(iSlots.get(0), createNamedItem(ings.get(1), 1)); // template
            if (ings.size() >= 1) inv.setItem(iSlots.get(1), createNamedItem(ings.get(0), 1)); // base
            if (ings.size() >= 3) inv.setItem(iSlots.get(2), createNamedItem(ings.get(2), 1)); // addition
        } else {
            for (int i = 0; i < ings.size() && i < iSlots.size(); i++)
                inv.setItem(iSlots.get(i), createNamedItem(ings.get(i), 1));
        }
        if (!rSlots.isEmpty())
            inv.setItem(rSlots.get(0), createNamedItem(recipe.resultId, recipe.resultCount));
        return inv;
    }

    /** 切石机 GUI：shape 驱动 */
    private Inventory createStonecutterGUI(CEBridge.RecipeData recipe) {
        MenuDef menu = menuConfig.getDetailStonecutter();
        if (menu == null) return Bukkit.createInventory(null, 27, config.getDetailTitle("stonecutting"));
        Inventory inv = buildDetailGUI(menu);
        List<Integer> iSlots = charSlots(menu.shape(), 'I');
        List<Integer> rSlots = charSlots(menu.shape(), 'R');
        List<String> ings = recipe.ingredientIds;
        if (!ings.isEmpty() && !iSlots.isEmpty())
            inv.setItem(iSlots.get(0), createNamedItem(ings.get(0), 1));
        if (!rSlots.isEmpty())
            inv.setItem(rSlots.get(0), createNamedItem(recipe.resultId, recipe.resultCount));
        return inv;
    }

    /** 酿造台 GUI：shape 驱动 */
    /** 酿造台 GUI：R=材料 Y=药水底 I=成品 */
    /** 酿造台 GUI：I₁=材料 I₂=药水瓶 R=成品 H=烈焰粉 */
    private Inventory createBrewingGUI(CEBridge.RecipeData recipe) {
        MenuDef menu = menuConfig.getDetailBrewing();
        if (menu == null) return fallbackDetail("brewing", recipe);
        Inventory inv = buildDetailGUI(menu);
        List<Integer> iSlots = charSlots(menu.shape(), 'I');
        List<Integer> rSlots = charSlots(menu.shape(), 'R');
        List<String> ings = recipe.ingredientIds; // CE: [container, ingredient]
        if (ings.size() >= 2 && iSlots.size() >= 2) {
            inv.setItem(iSlots.get(0), createNamedItem(ings.get(1), 1)); // 材料
            inv.setItem(iSlots.get(1), createNamedItem(ings.get(0), 1)); // 药水瓶
        }
        if (!rSlots.isEmpty())
            inv.setItem(rSlots.get(0), createNamedItem(recipe.resultId, recipe.resultCount));
        return inv;
    }

    /** 根据 shape 构建带背景的空白 GUI */
    private Inventory buildDetailGUI(MenuDef menu) {
        Inventory inv = Bukkit.createInventory(null, MenuConfig.shapeSize(menu.shape()), menu.title());
        for (int row = 0; row < menu.shape().length; row++) {
            String line = menu.shape()[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                ButtonDef btn = menu.buttons().get(c);
                if (btn == null || btn.dynamic()) continue;
                inv.setItem(row * 9 + col, buildButtonOrCE(btn, null));
            }
        }
        return inv;
    }

    /** 构建按钮：优先 CE 物品，回退标准 Material */
    private ItemStack buildButtonOrCE(ButtonDef btn, Map<String, String> vars) {
        // CE 物品
        if (btn.ceItem() != null) {
            ItemStack item = bridge.buildItemStack(btn.ceItem(), 1);
            if (item.getType() != Material.PAPER && item.getType() != Material.AIR) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    String name = btn.name();
                    if (vars != null) for (Map.Entry<String, String> e : vars.entrySet())
                        name = name.replace("{" + e.getKey() + "}", e.getValue());
                    meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
                    List<String> lore = new ArrayList<>();
                    for (String l : btn.lore()) {
                        if (vars != null) for (Map.Entry<String, String> e : vars.entrySet())
                            l = l.replace("{" + e.getKey() + "}", e.getValue());
                        lore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', l));
                    }
                    meta.setLore(lore);
                    item.setItemMeta(meta);
                }
                return item;
            }
        }
        return MenuConfig.buildButton(btn, vars);
    }

    /** 提取 shape 中指定字符的所有槽位 */
    private static List<Integer> charSlots(String[] shape, char target) {
        List<Integer> slots = new ArrayList<>();
        for (int row = 0; row < shape.length; row++) {
            String line = shape[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                if (line.charAt(col) == target) slots.add(row * 9 + col);
            }
        }
        return slots;
    }

    // ===================== 物品图标 =====================

    private ItemStack createNamedItem(String itemId, int count) {
        ItemStack item = bridge.buildItemStack(itemId, Math.max(1, Math.min(count, 64)));
        // CE 物品有 item_name 组件，原版物品客户端自带翻译 → 都不覆盖 displayName
        return item;
    }

    private ItemStack createRecipeEntryIcon(CEBridge.RecipeData recipe) {
        ItemStack item = bridge.buildItemStack(recipe.resultId, recipe.resultCount);

        String typeName = config.getRecipeTypeName(recipe.type);
        List<String> lore = List.of(
                config.getBtnLoreResult(recipe.resultCount),
                config.getBtnLoreType(typeName),
                "", config.getBtnLoreClick()
        );

        // CE 物品有 item_name 组件，原版物品客户端自带翻译 → 都不覆盖 displayName
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ===================== 搜索过滤 =====================

    private List<CEBridge.RecipeData> filterRecipes(List<CEBridge.RecipeData> all, String query) {
        String q = query.toLowerCase();
        return all.stream().filter(r -> {
            if (matchItem(r.resultId, q)) return true;
            for (String ing : r.ingredientIds) { if (matchItem(ing, q)) return true; }
            if (r.patternKeyIds != null) {
                for (String pk : r.patternKeyIds.values()) { if (matchItem(pk, q)) return true; }
            }
            return false;
        }).collect(Collectors.toList());
    }

    private boolean matchItem(String itemId, String query) {
        if (itemId == null) return false;
        // 只匹配物品名称，不匹配 namespace:id
        return toChineseName(itemId).toLowerCase().contains(query.toLowerCase());
    }

    // ===================== A-Z 排序 =====================

    /** Collator 排序器（拼音顺序，复用实例） */
    private static final Collator CN_COLLATOR = createCollator();
    private static Collator createCollator() {
        Collator c = Collator.getInstance(Locale.CHINESE);
        c.setStrength(Collator.PRIMARY);
        return c;
    }

    /**
     * 获取分组键（A-Z）。中文通过 Collator 与锚点字比较确定拼音首字母，
     * 覆盖所有 Unicode 汉字，不会出现无法排序的情况。
     */
    static String sortGroup(String name) {
        if (name == null || name.isEmpty()) return "#";
        char first = name.charAt(0);
        if (first >= 'A' && first <= 'Z') return String.valueOf(first);
        if (first >= 'a' && first <= 'z') return String.valueOf(Character.toUpperCase(first));
        if (first >= '0' && first <= '9') return String.valueOf(first);
        if (first < 0x4E00 || first > 0x9FFF) return "#";

        // 中文：通过 Collator 与各字母锚点字比较，确定首字母分组
        String target = name.substring(0, 1);
        for (char anchor : ANCHORS) {
            if (CN_COLLATOR.compare(target, String.valueOf(anchor)) <= 0) {
                String py = ANCHOR_PINYIN.get(anchor);
                return py != null ? py : "#";
            }
        }
        return "Z"; // 比所有锚点都大 → Z 组尾部
    }

    /** 各拼音首字母的锚点字（该字母拼音排序最靠前的常见字） */
    private static final char[] ANCHORS = {'啊','巴','擦','大','蛾','发','嘎','哈','击','咖','拉','妈','那','哦','趴','七','然','撒','他','挖','西','压','匝'};
    private static final Map<Character, String> ANCHOR_PINYIN = Map.ofEntries(
            Map.entry('啊',"A"), Map.entry('巴',"B"), Map.entry('擦',"C"), Map.entry('大',"D"),
            Map.entry('蛾',"E"), Map.entry('发',"F"), Map.entry('嘎',"G"), Map.entry('哈',"H"),
            Map.entry('击',"J"), Map.entry('咖',"K"), Map.entry('拉',"L"), Map.entry('妈',"M"),
            Map.entry('那',"N"), Map.entry('哦',"O"), Map.entry('趴',"P"), Map.entry('七',"Q"),
            Map.entry('然',"R"), Map.entry('撒',"S"), Map.entry('他',"T"), Map.entry('挖',"W"),
            Map.entry('西',"X"), Map.entry('压',"Y"), Map.entry('匝',"Z")
    );

    // ===================== 中文名映射 =====================

    public String toChineseName(String id) {
        if (id == null) return "未知";
        // 1. lang.yml 自定义名称
        String custom = config.getCustomItemName(id);
        if (custom != null) return custom;
        // 2. 内置中文名映射（原版物品优先，CI_MAP 覆盖最常用词）
        String value = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String mapped = CN_MAP.get(value);
        if (mapped != null) return mapped;
        // 3. CE 翻译 + 原版 NMS 翻译
        String displayName = bridge.readItemDisplayName(id);
        if (displayName != null && !displayName.isEmpty()) return displayName;
        // 4. 格式化兜底
        return formatItemName(value);
    }

    private static String formatItemName(String name) {
        if (name == null || name.isEmpty()) return "未知";
        String[] words = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) sb.append(word.substring(1).toLowerCase());
        }
        return sb.isEmpty() ? name : sb.toString();
    }

    private static final Map<String, String> CN_MAP = Map.ofEntries(
            Map.entry("oak_planks", "橡木木板"), Map.entry("spruce_planks", "云杉木板"),
            Map.entry("birch_planks", "白桦木板"), Map.entry("jungle_planks", "丛林木板"),
            Map.entry("acacia_planks", "金合欢木板"), Map.entry("dark_oak_planks", "深色橡木木板"),
            Map.entry("crimson_planks", "绯红木板"), Map.entry("warped_planks", "诡异木板"),
            Map.entry("mangrove_planks", "红树林木板"), Map.entry("cherry_planks", "樱花木板"),
            Map.entry("bamboo_planks", "竹板"), Map.entry("oak_log", "橡木原木"),
            Map.entry("stone", "石头"), Map.entry("cobblestone", "圆石"),
            Map.entry("deepslate", "深板岩"), Map.entry("cobbled_deepslate", "深板岩圆石"),
            Map.entry("blackstone", "黑石"), Map.entry("smooth_stone", "平滑石头"),
            Map.entry("iron_ingot", "铁锭"), Map.entry("gold_ingot", "金锭"),
            Map.entry("copper_ingot", "铜锭"), Map.entry("netherite_ingot", "下界合金锭"),
            Map.entry("diamond", "钻石"), Map.entry("emerald", "绿宝石"),
            Map.entry("lapis_lazuli", "青金石"), Map.entry("redstone", "红石粉"),
            Map.entry("coal", "煤炭"), Map.entry("charcoal", "木炭"),
            Map.entry("quartz", "下界石英"), Map.entry("amethyst_shard", "紫水晶碎片"),
            Map.entry("raw_iron", "粗铁"), Map.entry("raw_gold", "粗金"), Map.entry("raw_copper", "粗铜"),
            Map.entry("iron_nugget", "铁粒"), Map.entry("gold_nugget", "金粒"),
            Map.entry("iron_block", "铁块"), Map.entry("gold_block", "金块"),
            Map.entry("diamond_block", "钻石块"), Map.entry("emerald_block", "绿宝石块"),
            Map.entry("netherite_block", "下界合金块"), Map.entry("copper_block", "铜块"),
            Map.entry("coal_block", "煤炭块"), Map.entry("redstone_block", "红石块"),
            Map.entry("lapis_block", "青金石块"),
            Map.entry("iron_sword", "铁剑"), Map.entry("iron_pickaxe", "铁镐"),
            Map.entry("iron_axe", "铁斧"), Map.entry("iron_shovel", "铁锹"), Map.entry("iron_hoe", "铁锄"),
            Map.entry("golden_sword", "金剑"), Map.entry("golden_pickaxe", "金镐"),
            Map.entry("diamond_sword", "钻石剑"), Map.entry("diamond_pickaxe", "钻石镐"),
            Map.entry("diamond_axe", "钻石斧"), Map.entry("diamond_shovel", "钻石锹"), Map.entry("diamond_hoe", "钻石锄"),
            Map.entry("netherite_sword", "下界合金剑"), Map.entry("netherite_pickaxe", "下界合金镐"),
            Map.entry("netherite_axe", "下界合金斧"), Map.entry("netherite_shovel", "下界合金锹"),
            Map.entry("netherite_hoe", "下界合金锄"), Map.entry("wooden_sword", "木剑"),
            Map.entry("stone_sword", "石剑"), Map.entry("stone_pickaxe", "石镐"),
            Map.entry("iron_helmet", "铁头盔"), Map.entry("iron_chestplate", "铁胸甲"),
            Map.entry("iron_leggings", "铁护腿"), Map.entry("iron_boots", "铁靴子"),
            Map.entry("diamond_helmet", "钻石头盔"), Map.entry("diamond_chestplate", "钻石胸甲"),
            Map.entry("diamond_leggings", "钻石护腿"), Map.entry("diamond_boots", "钻石靴子"),
            Map.entry("netherite_helmet", "下界合金头盔"), Map.entry("netherite_chestplate", "下界合金胸甲"),
            Map.entry("netherite_leggings", "下界合金护腿"), Map.entry("netherite_boots", "下界合金靴子"),
            Map.entry("golden_helmet", "金头盔"), Map.entry("golden_chestplate", "金胸甲"),
            Map.entry("leather_helmet", "皮革头盔"), Map.entry("leather_chestplate", "皮革胸甲"),
            Map.entry("stick", "木棍"), Map.entry("string", "线"), Map.entry("feather", "羽毛"),
            Map.entry("flint", "燧石"), Map.entry("leather", "皮革"),
            Map.entry("paper", "纸"), Map.entry("book", "书"), Map.entry("bone", "骨头"),
            Map.entry("gunpowder", "火药"), Map.entry("blaze_rod", "烈焰棒"),
            Map.entry("ender_pearl", "末影珍珠"), Map.entry("slime_ball", "粘液球"),
            Map.entry("bowl", "碗"), Map.entry("bucket", "铁桶"),
            Map.entry("sugar", "糖"), Map.entry("egg", "鸡蛋"), Map.entry("wheat", "小麦"),
            Map.entry("carrot", "胡萝卜"), Map.entry("potato", "马铃薯"), Map.entry("apple", "苹果"),
            Map.entry("golden_apple", "金苹果"), Map.entry("bread", "面包"),
            Map.entry("cooked_beef", "熟牛肉"), Map.entry("cooked_porkchop", "熟猪排"),
            Map.entry("cooked_chicken", "熟鸡肉"), Map.entry("cooked_cod", "熟鳕鱼"),
            Map.entry("chest", "箱子"), Map.entry("furnace", "熔炉"),
            Map.entry("crafting_table", "工作台"), Map.entry("anvil", "铁砧"),
            Map.entry("smithing_table", "锻造台"), Map.entry("brewing_stand", "酿造台"),
            Map.entry("hopper", "漏斗"), Map.entry("dropper", "投掷器"), Map.entry("dispenser", "发射器"),
            Map.entry("observer", "侦测器"), Map.entry("piston", "活塞"), Map.entry("sticky_piston", "粘性活塞"),
            Map.entry("netherite_upgrade_smithing_template", "下界合金升级锻造模板"),
            Map.entry("magma_block", "岩浆块"), Map.entry("magma_cream", "岩浆膏"),
            Map.entry("blaze_powder", "烈焰粉"),
            Map.entry("sugar_cane", "甘蔗"),
            Map.entry("nether_brick", "下界砖"), Map.entry("netherite_scrap", "下界合金碎片"),
            Map.entry("nether_star", "下界之星"),
            Map.entry("ender_eye", "末影之眼"), Map.entry("fire_charge", "火焰弹"),
            Map.entry("flint_and_steel", "打火石"), Map.entry("shears", "剪刀"),
            Map.entry("shield", "盾牌"), Map.entry("totem_of_undying", "不死图腾"),
            Map.entry("elytra", "鞘翅"), Map.entry("firework_rocket", "烟花火箭"),
            Map.entry("enchanted_golden_apple", "附魔金苹果"), Map.entry("golden_carrot", "金胡萝卜"),
            Map.entry("glass_bottle", "玻璃瓶"), Map.entry("water_bucket", "水桶"),
            Map.entry("lava_bucket", "岩浆桶"), Map.entry("clock", "时钟"),
            Map.entry("compass", "指南针"), Map.entry("recovery_compass", "追溯指针"),
            Map.entry("name_tag", "命名牌"), Map.entry("lead", "拴绳"),
            Map.entry("saddle", "鞍"), Map.entry("iron_horse_armor", "铁马铠"),
            Map.entry("golden_horse_armor", "金马铠"), Map.entry("diamond_horse_armor", "钻石马铠"),
            Map.entry("trident", "三叉戟"), Map.entry("crossbow", "弩"),
            Map.entry("bow", "弓"), Map.entry("arrow", "箭"),
            Map.entry("spectral_arrow", "光灵箭"), Map.entry("tipped_arrow", "药箭"),
            Map.entry("chainmail_helmet", "锁链头盔"), Map.entry("chainmail_chestplate", "锁链胸甲"),
            Map.entry("chainmail_leggings", "锁链护腿"), Map.entry("chainmail_boots", "锁链靴子"),
            Map.entry("turtle_helmet", "海龟壳"),
            Map.entry("fishing_rod", "钓鱼竿"), Map.entry("carrot_on_a_stick", "胡萝卜钓竿"),
            Map.entry("warped_fungus_on_a_stick", "诡异菌钓竿"),
            Map.entry("music_disc_11", "音乐唱片"), Map.entry("music_disc_13", "音乐唱片"),
            Map.entry("music_disc_blocks", "音乐唱片"), Map.entry("music_disc_cat", "音乐唱片"),
            Map.entry("music_disc_chirp", "音乐唱片"), Map.entry("music_disc_far", "音乐唱片"),
            Map.entry("music_disc_mall", "音乐唱片"), Map.entry("music_disc_mellohi", "音乐唱片"),
            Map.entry("music_disc_stal", "音乐唱片"), Map.entry("music_disc_strad", "音乐唱片"),
            Map.entry("music_disc_ward", "音乐唱片"), Map.entry("music_disc_wait", "音乐唱片"),
            Map.entry("music_disc_5", "音乐唱片"), Map.entry("music_disc_otherside", "音乐唱片"),
            Map.entry("music_disc_pigstep", "音乐唱片"), Map.entry("music_disc_relic", "音乐唱片"),
            Map.entry("skeleton_skull", "生物头颅"), Map.entry("wither_skeleton_skull", "凋零骷髅头颅"),
            Map.entry("zombie_head", "僵尸头"), Map.entry("creeper_head", "苦力怕头"),
            Map.entry("dragon_head", "龙首"), Map.entry("piglin_head", "猪灵头")
    );

    // ===================== 玩家状态 =====================

    public String getGUIType(UUID uuid) { return guiType.getOrDefault(uuid, ""); }
    public String getPlayerCategory(UUID uuid) { return playerCategory.get(uuid); }
    public int getPlayerPage(UUID uuid) { return playerPage.getOrDefault(uuid, 0); }
    public String getSearchQuery(UUID uuid) { return searchQuery.get(uuid); }
    public Inventory getOpenInventory(UUID uuid) { return openInventories.get(uuid); }
    public boolean isOurGUI(UUID uuid, Inventory inv) { Inventory t = openInventories.get(uuid); return t != null && t.equals(inv); }
    public Map<UUID, String> getGUITypeMap() { return guiType; }
    public Map<UUID, Inventory> getOpenInventoryMap() { return openInventories; }

    /** 获取玩家当前菜单的 shape 定义（GUIListener 点击路由使用） */
    public MenuDef getPlayerMenuDef(UUID uuid) { return playerMenuDef.get(uuid); }

    public void removePlayer(UUID uuid) {
        guiType.remove(uuid);
        playerCategory.remove(uuid);
        playerPage.remove(uuid);
        playerMenuDef.remove(uuid);
        openInventories.remove(uuid);
        creatorType.remove(uuid);
        creatorTime.remove(uuid);
        creatorFurnaceTime.remove(uuid);
        creatorExp.remove(uuid);
        // 注意：savedCreator* 快照不在此清理，它们在 expectExpInput 中设置，
        // 在 reopenCreatorAfterInput 中消费，期间 removePlayer 会被触发但不能清掉。
    }

    public void closeAll() {
        for (UUID uuid : new ArrayList<>(openInventories.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.closeInventory();
            removePlayer(uuid);
        }
    }
}
