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
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 三级合成表浏览 GUI。
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
    private final Map<UUID, MenuDef> playerMenuDef = new HashMap<>();
    /** 缓存排序后的配方列表，避免点击时重复排序 */
    private final Map<UUID, List<CEBridge.RecipeData>> playerRecipes = new HashMap<>();

    public RecipeGUI(ALCERecipeViewer plugin) {
        this.plugin = plugin;
        this.bridge = plugin.getCEBridge();
        this.config = plugin.getConfigManager();
        this.menuConfig = plugin.getMenuConfig();
    }

    public Locale resolveLocale() {
        String lang = config.getLanguage();
        if (lang != null && lang.contains("_")) {
            String[] parts = lang.split("_", 2);
            return new Locale(parts[0], parts[1]);
        }
        return "en_us".equals(lang) ? Locale.US : Locale.SIMPLIFIED_CHINESE;
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

                if (btn.dynamic()) continue;

                if (c == '#') {
                    inv.setItem(slot, menuConfig.buildButton(btn, null));
                } else {
                    int count = plugin.getLoadedRecipes()
                            .getOrDefault(btn.category(), List.of()).size();
                    Map<String, String> v = MenuConfig.vars("count", String.valueOf(count));
                    inv.setItem(slot, menuConfig.buildButton(btn, v));
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
        if (categoryId == null) {
            openMainMenu(player);
            return;
        }
        MenuDef menu = menuConfig.getRecipeList();
        if (menu == null) return;

        Locale locale = resolveLocale();
        String filter = searchQuery.get(player.getUniqueId());
        List<CEBridge.RecipeData> recipes = getSortedRecipes(categoryId, filter, locale);

        List<Integer> itemSlots = MenuConfig.itemSlots(menu.shape());
        int pageSize = itemSlots.size();
        int totalPages = Math.max(1, (recipes.size() + pageSize - 1) / pageSize);
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

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

        int recipeIdx = page * pageSize;
        for (int row = 0; row < menu.shape().length; row++) {
            String line = menu.shape()[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                int slot = row * 9 + col;
                ButtonDef btn = menu.buttons().get(c);
                if (btn == null) continue;

                if (c == 'I') {
                    if (recipeIdx < recipes.size()) {
                        inv.setItem(slot, createRecipeEntryIcon(recipes.get(recipeIdx)));
                        recipeIdx++;
                    }
                } else if (c == '#') {
                    inv.setItem(slot, menuConfig.buildButton(btn, null));
                } else {
                    Map<String, String> v = MenuConfig.vars(
                            "page", String.valueOf(page + 1),
                            "total", String.valueOf(totalPages),
                            "query", filter != null ? filter : "无",
                            "category", categoryName
                    );
                    inv.setItem(slot, menuConfig.buildButton(btn, v));
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
        playerRecipes.put(uuid, recipes); // 缓存排序结果
    }

    public void openFilteredRecipeList(Player player, String categoryId, List<CEBridge.RecipeData> filtered, String query) {
        searchQuery.put(player.getUniqueId(), query);
        openRecipeList(player, categoryId, 0);
    }

    /** 获取排序后的配方列表（O(N) 预计算名字，避免 O(N log N) 次比较器调用） */
    public List<CEBridge.RecipeData> getSortedRecipes(String categoryId, String filter, Locale locale) {
        List<CEBridge.RecipeData> all = plugin.getLoadedRecipes().getOrDefault(categoryId, List.of());
        List<CEBridge.RecipeData> recipes = new ArrayList<>(
                (filter != null) ? filterRecipes(all, filter, locale) : all);
        Map<CEBridge.RecipeData, String> nameMap = new HashMap<>();
        for (CEBridge.RecipeData r : recipes) {
            nameMap.put(r, toChineseName(r.resultId, locale));
        }
        recipes.sort(Comparator.comparing(nameMap::get, CN_COLLATOR));
        return recipes;
    }

    public void clearSearch(Player player) {
        UUID uuid = player.getUniqueId();
        searchQuery.remove(uuid);
        String category = playerCategory.getOrDefault(uuid, "crafting");
        openRecipeList(player, category, 0);
    }

    // ===================== 新增配方 GUI（管理员）=====================

    public final Map<UUID, String> creatorType = new HashMap<>();
    private final Map<UUID, Integer> creatorTime = new HashMap<>();
    private final Map<UUID, Integer> creatorFurnaceTime = new HashMap<>();
    private final Map<UUID, Integer> creatorExp = new HashMap<>();
    /** 烧炼模式: "both" | "furnace" | "blast" */
    private final Map<UUID, String> creatorFurnaceMode = new HashMap<>();

    public final Map<UUID, String> pendingExpInput = new HashMap<>();

    private final Map<UUID, String> savedCreatorType = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedCreatorItems = new HashMap<>();
    private final Map<UUID, Integer> savedCreatorExpVal = new HashMap<>();
    private final Map<UUID, Integer> savedCreatorTimeVal = new HashMap<>();
    private final Map<UUID, Integer> savedCreatorFurnaceTimeVal = new HashMap<>();

    public void expectExpInput(Player player) {
        UUID uuid = player.getUniqueId();
        savedCreatorType.put(uuid, creatorType.getOrDefault(uuid, "shaped"));
        savedCreatorExpVal.put(uuid, creatorExp.getOrDefault(uuid, 10));
        savedCreatorTimeVal.put(uuid, creatorTime.getOrDefault(uuid, 5));
        savedCreatorFurnaceTimeVal.put(uuid, creatorFurnaceTime.getOrDefault(uuid, 10));
        Inventory inv = openInventories.get(uuid);
        if (inv != null) savedCreatorItems.put(uuid, inv.getContents().clone());
        pendingExpInput.put(uuid, "exp");
        player.closeInventory();
        player.sendMessage(config.getPluginPrefix() + " §e请在聊天栏输入经验值（如 1.5，范围 0.1~360.0，输入 cancel 取消）");
    }

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
            savedCreatorExpVal.put(uuid, tenths);
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
        inv.setItem(eSlots.get(0), menuConfig.buildButton(eBtn, MenuConfig.vars("time", s, "exp", s)));
    }

    private void refreshCreatorButtons(Inventory inv, String type, UUID uuid) {
        MenuDef menu = switch (type) {
            case "furnace" -> menuConfig.getRecipeCreatorFurnace();
            case "smoking" -> menuConfig.getRecipeCreatorSmoking();
            case "campfire" -> menuConfig.getRecipeCreatorCampfire();
            default -> null;
        };
        if (menu == null) return;
        String fMode = "furnace".equals(type) ? creatorFurnaceMode.getOrDefault(uuid, "both") : null;
        for (char ch : new char[]{'G', 'P', 'Y'}) {
            ButtonDef btn = menu.buttons().get(ch);
            if (btn == null) continue;
            List<Integer> slots = charSlots(menu.shape(), ch);
            if (slots.isEmpty()) continue;
            int value;
            if (ch == 'P') {
                if (fMode != null && "blast".equals(fMode)) value = creatorTime.getOrDefault(uuid, 5);
                else value = creatorFurnaceTime.getOrDefault(uuid, 10);
            }
            else if (ch == 'G' && "campfire".equals(type)) value = creatorTime.getOrDefault(uuid, 30);
            else value = creatorTime.getOrDefault(uuid, "campfire".equals(type) ? 30 : 5);
            Map<String, String> pVars;
            if (ch == 'P' && fMode != null)
                pVars = MenuConfig.vars("time", String.valueOf(value), "mode", furnaceModeDisplay(fMode));
            else
                pVars = MenuConfig.vars("time", String.valueOf(value));
            inv.setItem(slots.get(0), menuConfig.buildButton(btn, pVars));
        }
        if (fMode != null) {
            ButtonDef sBtn = menu.buttons().get('S');
            if (sBtn != null) {
                List<Integer> sSlots = charSlots(menu.shape(), 'S');
                if (!sSlots.isEmpty())
                    inv.setItem(sSlots.get(0), buildModeButton(sBtn, fMode));
            }
        }
        ButtonDef eBtn = menu.buttons().get('E');
        if (eBtn != null) {
            List<Integer> eSlots = charSlots(menu.shape(), 'E');
            if (!eSlots.isEmpty()) {
                String s = String.format("%.1f", creatorExp.getOrDefault(uuid, 10) / 10.0);
                inv.setItem(eSlots.get(0), menuConfig.buildButton(eBtn, MenuConfig.vars("time", s, "exp", s)));
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
        switch (type) {
            case "furnace" -> {
                creatorFurnaceTime.put(uuid, 10);
                creatorFurnaceMode.put(uuid, "both");
            }
            case "smoking" -> creatorTime.put(uuid, 5);
            case "campfire" -> creatorTime.put(uuid, 30);
            default -> { creatorTime.put(uuid, 5); creatorFurnaceTime.put(uuid, 10); }
        }
        creatorExp.put(uuid, 10);
        Inventory inv = buildCreatorGUI(menu, uuid);
        player.openInventory(inv);
        guiType.put(uuid, "creator");
        creatorType.put(uuid, type != null ? type : "crafting");
        openInventories.put(uuid, inv);
    }

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
        if ("P".equals(slotChar)) {
            String mode = creatorFurnaceMode.getOrDefault(uuid, "both");
            if ("blast".equals(mode)) { map = creatorTime; def = 5; }
            else { map = creatorFurnaceTime; def = 10; }
        }
        else if ("E".equals(slotChar)) { map = creatorExp; def = 10; }
        else { map = creatorTime; def = ("campfire".equals(type) ? 30 : 5); }

        int max = "E".equals(slotChar) ? 3600 : 3600;
        int newVal = map.getOrDefault(uuid, def) + (increase ? 1 : -1);
        if (newVal < 1) newVal = 1; if (newVal > max) newVal = max;
        map.put(uuid, newVal);

        ButtonDef btn = menu.buttons().get(slotChar.charAt(0));
        if (btn == null) return;
        List<Integer> slots = charSlots(menu.shape(), slotChar.charAt(0));
        if (slots.isEmpty()) return;
        String label = "E".equals(slotChar)
                ? String.format("%.1f", creatorExp.getOrDefault(uuid, 10) / 10.0)
                : String.valueOf(newVal);
        Map<String, String> vars;
        if ("P".equals(slotChar) && "furnace".equals(type)) {
            String mode = creatorFurnaceMode.getOrDefault(uuid, "both");
            vars = MenuConfig.vars("time", label, "mode", furnaceModeDisplay(mode));
        } else {
            vars = MenuConfig.vars("time", label, "exp", label);
        }
        inv.setItem(slots.get(0), menuConfig.buildButton(btn, vars));
    }

    private Inventory buildCreatorGUI(MenuDef menu, UUID uuid) {
        Inventory inv = Bukkit.createInventory(null, MenuConfig.shapeSize(menu.shape()), menu.title());
        for (int row = 0; row < menu.shape().length; row++) {
            String line = menu.shape()[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                ButtonDef btn = menu.buttons().get(c);
                if (btn == null) continue;
                if (btn.dynamic()) continue;
                int slot = row * 9 + col;
                Map<String, String> v = null;
                if (c == 'G') v = MenuConfig.vars("time", String.valueOf(creatorTime.getOrDefault(uuid, 5)));
                else if (c == 'P') {
                    String mode = creatorFurnaceMode.getOrDefault(uuid, "both");
                    String modeDisplay = furnaceModeDisplay(mode);
                    int timeVal = "blast".equals(mode)
                            ? creatorTime.getOrDefault(uuid, 5)
                            : creatorFurnaceTime.getOrDefault(uuid, 10);
                    v = MenuConfig.vars("time", String.valueOf(timeVal), "mode", modeDisplay);
                }
                else if (c == 'Y') v = MenuConfig.vars("time", String.valueOf(creatorTime.getOrDefault(uuid, 5)));
                else if (c == 'S') {
                    String mode = creatorFurnaceMode.getOrDefault(uuid, "both");
                    inv.setItem(slot, buildModeButton(btn, mode));
                    continue;
                }
                else if (c == 'E') {
                    String s = String.format("%.1f", creatorExp.getOrDefault(uuid, 10) / 10.0);
                    v = MenuConfig.vars("time", s, "exp", s);
                }
                inv.setItem(slot, menuConfig.buildButton(btn, v));
            }
        }
        return inv;
    }

    private static String furnaceModeDisplay(String mode) {
        return switch (mode) {
            case "blast" -> "仅高炉";
            case "furnace" -> "仅熔炉";
            default -> "熔炉+高炉";
        };
    }

    private ItemStack buildModeButton(ButtonDef btn, String mode) {
        Material mat = switch (mode) {
            case "blast" -> Material.BLAST_FURNACE;
            case "furnace" -> Material.FURNACE;
            default -> btn.material();
        };
        ItemStack item = new ItemStack(mat != null ? mat : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = menuConfig.resolveLang(btn.name()).replace("{mode}", furnaceModeDisplay(mode));
            meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
            List<String> lore = new ArrayList<>();
            for (String l : btn.lore())
                lore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', menuConfig.resolveLang(l)));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void toggleFurnaceMode(Player player, org.bukkit.event.inventory.ClickType click) {
        UUID uuid = player.getUniqueId();
        String mode = switch (click) {
            case LEFT -> "blast";
            case RIGHT -> "furnace";
            case SHIFT_LEFT -> "both";
            default -> null;
        };
        if (mode == null) return;
        creatorFurnaceMode.put(uuid, mode);
        String modeDisplay = furnaceModeDisplay(mode);
        player.sendMessage(config.getPluginPrefix() + " §e烧炼模式已切换为: §f" + modeDisplay);

        Inventory inv = openInventories.get(uuid);
        if (inv == null) return;
        MenuDef menu = plugin.getMenuConfig().getRecipeCreatorFurnace();
        if (menu == null) return;
        ButtonDef sBtn = menu.buttons().get('S');
        List<Integer> sSlots = charSlots(menu.shape(), 'S');
        if (sBtn != null && !sSlots.isEmpty())
            inv.setItem(sSlots.get(0), buildModeButton(sBtn, mode));
        ButtonDef pBtn = menu.buttons().get('P');
        List<Integer> pSlots = charSlots(menu.shape(), 'P');
        if (pBtn != null && !pSlots.isEmpty()) {
            int timeVal = "blast".equals(mode) ? creatorTime.getOrDefault(uuid, 5) : creatorFurnaceTime.getOrDefault(uuid, 10);
            inv.setItem(pSlots.get(0), menuConfig.buildButton(pBtn, MenuConfig.vars("time", String.valueOf(timeVal), "mode", modeDisplay)));
        }
    }

    private Inventory buildSimpleGUI(MenuDef menu) {
        Inventory inv = Bukkit.createInventory(null, MenuConfig.shapeSize(menu.shape()), menu.title());
        for (int row = 0; row < menu.shape().length; row++) {
            String line = menu.shape()[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                ButtonDef btn = menu.buttons().get(c);
                if (btn != null && !btn.dynamic()) inv.setItem(row * 9 + col, menuConfig.buildButton(btn, null));
            }
        }
        return inv;
    }

    private boolean hasAnyItem(Inventory inv, List<Integer> slots) {
        for (int s : slots) {
            ItemStack item = inv.getItem(s);
            if (item != null && !item.getType().isAir()) return true;
        }
        return false;
    }

    /** 从 ItemStack 获取物品 ID */
    private String getItemId(ItemStack item) {
        if (item == null) return null;
        if (item.getType().isAir()) return null;
        String ceId = bridge.getCEItemId(item);
        if (ceId != null) return ceId;
        return item.getType().getKey().getNamespace() + ":" + item.getType().getKey().getKey();
    }

    public void saveCreatorRecipe(Player player) {
        UUID uuid = player.getUniqueId();
        Inventory inv = openInventories.get(uuid);
        if (inv == null) return;
        String type = creatorType.getOrDefault(uuid, "crafting");

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

        // 主线程：收集数据，构建 YAML 字符串
        java.io.File packDir = new java.io.File(ceDir, "alcerecipeviewer");
        java.io.File configDir = new java.io.File(packDir, "configuration");
        java.io.File packYml = new java.io.File(packDir, "pack.yml");
        boolean needPackYml = !packYml.exists();
        java.io.File recipeFile = new java.io.File(configDir, recipeFileName(type));
        boolean recipeFileExists = recipeFile.exists();

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
                String mode = creatorFurnaceMode.getOrDefault(uuid, "both");
                int expTenths = creatorExp.getOrDefault(uuid, 10);
                if (!"blast".equals(mode)) {
                    int fTime = creatorFurnaceTime.getOrDefault(uuid, 10);
                    yaml.append("  ").append(uniqueKey(recipeName, "smelted", existingContent)).append(":\n");
                    yaml.append("    type: smelting\n    category: misc\n");
                    yaml.append("    time: ").append(String.valueOf(fTime * 20)).append("\n");
                    yaml.append("    experience: ").append(String.format("%.1f", expTenths / 10.0)).append("\n");
                    yaml.append("    ingredient: ").append(inputId).append("\n");
                    yaml.append("    result:\n      id: ").append(resultId).append("\n      count: ").append(resultCount).append("\n");
                }
                if (!"furnace".equals(mode)) {
                    int bTime;
                    if ("blast".equals(mode))
                        bTime = creatorTime.getOrDefault(uuid, 5);
                    else
                        bTime = Math.max(1, creatorFurnaceTime.getOrDefault(uuid, 10) / 2);
                    yaml.append("  ").append(uniqueKey(recipeName, "blasted", existingContent)).append(":\n");
                    yaml.append("    type: blasting\n    category: misc\n");
                    yaml.append("    time: ").append(String.valueOf(bTime * 20)).append("\n");
                    yaml.append("    experience: ").append(String.format("%.1f", expTenths / 10.0)).append("\n");
                    yaml.append("    ingredient: ").append(inputId).append("\n");
                    yaml.append("    result:\n      id: ").append(resultId).append("\n      count: ").append(resultCount).append("\n");
                }
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
                    Map<String, String> letterToId = new LinkedHashMap<>();
                    Map<String, Character> idToLetter = new LinkedHashMap<>();
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

        // 异步写文件，避免卡主线程
        final String yamlContent = yaml.toString();
        final String creatorTypeStr = type;
        final java.io.File finalRecipeFile = recipeFile;
        final java.io.File finalPackYml = packYml;
        final java.io.File finalConfigDir = configDir;
        final boolean finalNeedPackYml = needPackYml;
        final String recipeFilePath = recipeFile.getPath();
        final String playerName = player.getName();
        plugin.getFoliaLib().getScheduler().runAsync(task -> {
            try {
                if (!finalConfigDir.exists()) finalConfigDir.mkdirs();
                if (finalNeedPackYml) {
                    try (java.io.FileWriter fw = new java.io.FileWriter(finalPackYml)) {
                        fw.write("namespace: alcerecipeviewer\nauthor: ALCERecipeViewer\nversion: 1.0\n");
                    }
                }
                try (java.io.FileWriter fw = new java.io.FileWriter(finalRecipeFile, true)) {
                    fw.write("\n" + yamlContent);
                }
            } catch (Exception e) {
                plugin.getFoliaLib().getScheduler().runNextTick(t ->
                    player.sendMessage(config.getPluginPrefix() + " §c保存失败: " + e.getMessage()));
                return;
            }
            plugin.getFoliaLib().getScheduler().runNextTick(t -> {
                player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorSaved(recipeFilePath));
                player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorReload1());
                player.sendMessage(config.getPluginPrefix() + " " + config.getCreatorReload2());
                plugin.getLogger().info("[ALCE] " + playerName + " 创建配方: " + creatorTypeStr);
            });
        });
    }

    private String getSlotItemId(Inventory inv, List<Integer> slots, int index) {
        if (index >= slots.size()) return null;
        ItemStack item = inv.getItem(slots.get(index));
        return item != null && !item.getType().isAir() ? getItemId(item) : null;
    }

    private static String recipeFileName(String type) {
        return switch (type) {
            case "shaped" -> "custom_recipes_shaped.yml";
            case "shapeless" -> "custom_recipes_shapeless.yml";
            case "furnace" -> "custom_recipes_furnace.yml";
            case "smoking" -> "custom_recipes_smoking.yml";
            case "campfire" -> "custom_recipes_campfire.yml";
            case "stonecutting" -> "custom_recipes_stonecutter.yml";
            case "smithing" -> "custom_recipes_smithing.yml";
            case "brewing" -> "custom_recipes_brewing.yml";
            default -> "custom_recipes.yml";
        };
    }

    private String sanitize(String id) {
        if (id == null) return "unknown";
        String v = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return v.replaceAll("[^a-z0-9_]", "_").toLowerCase();
    }

    private String uniqueKey(String baseName, String suffix, String existingContent) {
        String key = "alcerecipeviewer:" + baseName + "_" + suffix;
        if (!existingContent.contains(key)) return key;
        int n = 2;
        while (existingContent.contains(key + "_" + n)) n++;
        return key + "_" + n;
    }

    public CEBridge getBridge() { return bridge; }

    public void openRecipeCreator(Player player) {
        openRecipeCreator(player, "crafting");
    }

    // ===================== 配方详情 =====================

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

    private Inventory createCraftingTableGUI(CEBridge.RecipeData recipe) {
        MenuDef menu = menuConfig.getDetailCrafting();
        if (menu == null) return fallbackDetail("crafting", recipe);
        Inventory inv = buildDetailGUI(menu);
        fillDetailSlots(inv, menu, recipe);
        return inv;
    }

    private Inventory createFurnaceGUI(CEBridge.RecipeData recipe) {
        MenuDef menu = menuConfig.getDetailFurnace();
        if (menu == null) return fallbackDetail("furnace", recipe);
        Inventory inv = buildDetailGUI(menu);
        fillDetailSlots(inv, menu, recipe);
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
        List<Integer> qSlots = charSlots(menu.shape(), 'Q');
        if (!qSlots.isEmpty() && recipe.cookingTime > 0) {
            ButtonDef qBtn = menu.buttons().get('Q');
            if (qBtn != null) {
                Map<String, String> v = MenuConfig.vars(
                        "time", formatCookingTime(recipe.cookingTime),
                        "exp", recipe.experience > 0 ? String.valueOf(recipe.experience) : "0"
                );
                inv.setItem(qSlots.get(0), menuConfig.buildButton(qBtn, v));
            }
        }
        return inv;
    }

    private void fillDetailSlots(Inventory inv, MenuDef menu, CEBridge.RecipeData recipe) {
        List<Integer> iSlots = charSlots(menu.shape(), 'I');
        List<Integer> rSlots = charSlots(menu.shape(), 'R');
        List<String> ings = recipe.ingredientIds;
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

    private Inventory createSmithingTableGUI(CEBridge.RecipeData recipe) {
        MenuDef menu = menuConfig.getDetailSmithing();
        if (menu == null) return Bukkit.createInventory(null, 27, config.getDetailTitle("smithing"));
        Inventory inv = buildDetailGUI(menu);
        List<Integer> iSlots = charSlots(menu.shape(), 'I');
        List<Integer> rSlots = charSlots(menu.shape(), 'R');
        List<String> ings = recipe.ingredientIds;
        if (iSlots.size() >= 3) {
            if (ings.size() >= 2) inv.setItem(iSlots.get(0), createNamedItem(ings.get(1), 1));
            if (ings.size() >= 1) inv.setItem(iSlots.get(1), createNamedItem(ings.get(0), 1));
            if (ings.size() >= 3) inv.setItem(iSlots.get(2), createNamedItem(ings.get(2), 1));
        } else {
            for (int i = 0; i < ings.size() && i < iSlots.size(); i++)
                inv.setItem(iSlots.get(i), createNamedItem(ings.get(i), 1));
        }
        if (!rSlots.isEmpty())
            inv.setItem(rSlots.get(0), createNamedItem(recipe.resultId, recipe.resultCount));
        return inv;
    }

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

    private Inventory createBrewingGUI(CEBridge.RecipeData recipe) {
        MenuDef menu = menuConfig.getDetailBrewing();
        if (menu == null) return fallbackDetail("brewing", recipe);
        Inventory inv = buildDetailGUI(menu);
        List<Integer> iSlots = charSlots(menu.shape(), 'I');
        List<Integer> rSlots = charSlots(menu.shape(), 'R');
        List<String> ings = recipe.ingredientIds;
        if (ings.size() >= 2 && iSlots.size() >= 2) {
            inv.setItem(iSlots.get(0), createNamedItem(ings.get(1), 1));
            inv.setItem(iSlots.get(1), createNamedItem(ings.get(0), 1));
        }
        if (!rSlots.isEmpty())
            inv.setItem(rSlots.get(0), createNamedItem(recipe.resultId, recipe.resultCount));
        return inv;
    }

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

    private ItemStack buildButtonOrCE(ButtonDef btn, Map<String, String> vars) {
        if (btn.ceItem() != null) {
            ItemStack item = bridge.buildItemStack(btn.ceItem(), 1);
            if (item.getType() != Material.PAPER && item.getType() != Material.AIR) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    String name = menuConfig.resolveLang(btn.name());
                    if (vars != null) for (Map.Entry<String, String> e : vars.entrySet())
                        name = name.replace("{" + e.getKey() + "}", e.getValue());
                    meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
                    List<String> lore = new ArrayList<>();
                    for (String l : btn.lore()) {
                        l = menuConfig.resolveLang(l);
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
        return menuConfig.buildButton(btn, vars);
    }

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
        return bridge.buildItemStack(itemId, Math.max(1, Math.min(count, 64)));
    }

    private ItemStack createRecipeEntryIcon(CEBridge.RecipeData recipe) {
        ItemStack item = bridge.buildItemStack(recipe.resultId, recipe.resultCount);
        String typeName = config.getRecipeTypeName(recipe.type);
        List<String> lore = List.of(
                config.getBtnLoreResult(recipe.resultCount),
                config.getBtnLoreType(typeName),
                "", config.getBtnLoreClick()
        );
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ===================== 搜索过滤 =====================

    private List<CEBridge.RecipeData> filterRecipes(List<CEBridge.RecipeData> all, String query, Locale locale) {
        String q = query.toLowerCase();
        return all.stream().filter(r -> matchItem(r.resultId, q, locale))
                .collect(Collectors.toList());
    }

    private boolean matchItem(String itemId, String query, Locale locale) {
        if (itemId == null) return false;
        return toChineseName(itemId, locale).toLowerCase().contains(query.toLowerCase());
    }

    // ===================== A-Z 排序 =====================

    private static final Collator CN_COLLATOR = createCollator();
    private static Collator createCollator() {
        Collator c = Collator.getInstance(Locale.CHINESE);
        c.setStrength(Collator.PRIMARY);
        return c;
    }

    static String sortGroup(String name) {
        if (name == null || name.isEmpty()) return "#";
        char first = name.charAt(0);
        if (first >= 'A' && first <= 'Z') return String.valueOf(first);
        if (first >= 'a' && first <= 'z') return String.valueOf(Character.toUpperCase(first));
        if (first >= '0' && first <= '9') return String.valueOf(first);
        if (first < 0x4E00 || first > 0x9FFF) return "#";
        String target = name.substring(0, 1);
        for (char anchor : ANCHORS) {
            if (CN_COLLATOR.compare(target, String.valueOf(anchor)) <= 0) {
                String py = ANCHOR_PINYIN.get(anchor);
                return py != null ? py : "#";
            }
        }
        return "Z";
    }

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

    public String toChineseName(String id, Locale locale) {
        if (id == null) return "未知";
        // 1. lang.yml 自定义名称
        String custom = config.getCustomItemName(id);
        if (custom != null) return custom;
        // 2. 内置中文名映射
        String value = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (locale != null && "zh".equals(locale.getLanguage())) {
            String mapped = CN_MAP.get(value);
            if (mapped != null) return mapped;
        }
        // 3. CE API hoverName + 翻译（跟随玩家客户端语言）
        String displayName = bridge.readItemDisplayName(id, locale);
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
            Map.entry("dragon_head", "龙首"), Map.entry("piglin_head", "猪灵头"),
            // === 农作物/食物 ===
            Map.entry("beetroot", "甜菜根"), Map.entry("beetroot_seeds", "甜菜种子"),
            Map.entry("beetroot_soup", "甜菜汤"), Map.entry("pumpkin", "南瓜"),
            Map.entry("carved_pumpkin", "雕刻南瓜"), Map.entry("jack_o_lantern", "南瓜灯"),
            Map.entry("pumpkin_pie", "南瓜派"), Map.entry("pumpkin_seeds", "南瓜种子"),
            Map.entry("melon", "西瓜"), Map.entry("melon_slice", "西瓜片"),
            Map.entry("melon_seeds", "西瓜种子"), Map.entry("baked_potato", "烤马铃薯"),
            Map.entry("poisonous_potato", "毒马铃薯"), Map.entry("cookie", "曲奇"),
            Map.entry("cake", "蛋糕"), Map.entry("mushroom_stew", "蘑菇煲"),
            Map.entry("beet", "甜菜根"), Map.entry("rabbit_stew", "兔肉煲"),
            Map.entry("suspicious_stew", "迷之炖菜"), Map.entry("beef", "生牛肉"),
            Map.entry("porkchop", "生猪排"), Map.entry("chicken", "生鸡肉"),
            Map.entry("mutton", "生羊肉"), Map.entry("rabbit", "生兔肉"),
            Map.entry("cod", "生鳕鱼"), Map.entry("salmon", "生鲑鱼"),
            Map.entry("tropical_fish", "热带鱼"), Map.entry("pufferfish", "河豚"),
            Map.entry("cooked_mutton", "熟羊肉"), Map.entry("cooked_rabbit", "熟兔肉"),
            Map.entry("cooked_salmon", "熟鲑鱼"), Map.entry("dried_kelp", "干海带"),
            Map.entry("dried_kelp_block", "干海带块"), Map.entry("sea_pickle", "海泡菜"),
            Map.entry("honey_bottle", "蜂蜜瓶"), Map.entry("honeycomb", "蜜脾"),
            Map.entry("honey_block", "蜂蜜块"), Map.entry("honeycomb_block", "蜜脾块"),
            // === 桶/液体 ===
            Map.entry("milk_bucket", "牛奶桶"), Map.entry("powder_snow_bucket", "细雪桶"),
            Map.entry("cod_bucket", "鳕鱼桶"), Map.entry("salmon_bucket", "鲑鱼桶"),
            Map.entry("pufferfish_bucket", "河豚桶"), Map.entry("tropical_fish_bucket", "热带鱼桶"),
            Map.entry("axolotl_bucket", "美西螈桶"), Map.entry("tadpole_bucket", "蝌蚪桶"),
            // === 工具/武器 ===
            Map.entry("wooden_pickaxe", "木镐"), Map.entry("wooden_axe", "木斧"),
            Map.entry("wooden_shovel", "木锹"), Map.entry("wooden_hoe", "木锄"),
            Map.entry("stone_axe", "石斧"), Map.entry("stone_shovel", "石锹"),
            Map.entry("stone_hoe", "石锄"), Map.entry("golden_axe", "金斧"),
            Map.entry("golden_shovel", "金锹"), Map.entry("golden_hoe", "金锄"),
            Map.entry("golden_leggings", "金护腿"), Map.entry("golden_boots", "金靴子"),
            Map.entry("leather_leggings", "皮革护腿"), Map.entry("leather_boots", "皮革靴子"),
            // === 建材/方块 ===
            Map.entry("grass_block", "草方块"), Map.entry("dirt", "泥土"),
            Map.entry("coarse_dirt", "砂土"), Map.entry("podzol", "灰化土"),
            Map.entry("rooted_dirt", "缠根泥土"), Map.entry("mud", "泥巴"),
            Map.entry("packed_mud", "泥坯"), Map.entry("clay", "粘土块"),
            Map.entry("clay_ball", "粘土球"), Map.entry("gravel", "沙砾"),
            Map.entry("sand", "沙子"), Map.entry("red_sand", "红沙"),
            Map.entry("sandstone", "砂岩"), Map.entry("red_sandstone", "红砂岩"),
            Map.entry("ice", "冰"), Map.entry("packed_ice", "浮冰"),
            Map.entry("blue_ice", "蓝冰"), Map.entry("snow_block", "雪块"),
            Map.entry("snowball", "雪球"), Map.entry("obsidian", "黑曜石"),
            Map.entry("crying_obsidian", "哭泣的黑曜石"), Map.entry("glass", "玻璃"),
            Map.entry("tinted_glass", "遮光玻璃"), Map.entry("terracotta", "陶瓦"),
            Map.entry("brick", "红砖"),
            Map.entry("red_nether_bricks", "红色下界砖块"),
            Map.entry("end_stone", "末地石"), Map.entry("end_stone_bricks", "末地石砖"),
            Map.entry("purpur_block", "紫珀块"), Map.entry("purpur_pillar", "紫珀柱"),
            Map.entry("netherrack", "下界岩"), Map.entry("soul_sand", "灵魂沙"),
            Map.entry("soul_soil", "灵魂土"), Map.entry("basalt", "玄武岩"),
            Map.entry("glowstone", "荧石"), Map.entry("glowstone_dust", "荧石粉"),
            Map.entry("shroomlight", "菌光体"), Map.entry("moss_block", "苔藓块"),
            Map.entry("moss_carpet", "苔藓地毯"), Map.entry("calcite", "方解石"),
            Map.entry("tuff", "凝灰岩"), Map.entry("dripstone_block", "滴水石块"),
            Map.entry("pointed_dripstone", "滴水石锥"), Map.entry("amethyst_block", "紫水晶块"),
            Map.entry("budding_amethyst", "紫水晶母岩"), Map.entry("sculk", "幽匿块"),
            Map.entry("sculk_catalyst", "幽匿催发体"), Map.entry("sculk_shrieker", "幽匿尖啸体"),
            Map.entry("sculk_sensor", "幽匿感测体"), Map.entry("sculk_vein", "幽匿脉络"),
            // === 原木/木头 ===
            Map.entry("spruce_log", "云杉原木"), Map.entry("birch_log", "白桦原木"),
            Map.entry("jungle_log", "丛林原木"), Map.entry("acacia_log", "金合欢原木"),
            Map.entry("dark_oak_log", "深色橡木原木"), Map.entry("crimson_stem", "绯红菌柄"),
            Map.entry("warped_stem", "诡异菌柄"), Map.entry("mangrove_log", "红树原木"),
            Map.entry("cherry_log", "樱花原木"), Map.entry("bamboo", "竹子"),
            Map.entry("bamboo_block", "竹块"), Map.entry("oak_wood", "橡木"),
            // === 矿石/矿物 ===
            Map.entry("iron_ore", "铁矿石"), Map.entry("gold_ore", "金矿石"),
            Map.entry("copper_ore", "铜矿石"), Map.entry("diamond_ore", "钻石矿石"),
            Map.entry("emerald_ore", "绿宝石矿石"), Map.entry("coal_ore", "煤矿石"),
            Map.entry("redstone_ore", "红石矿石"), Map.entry("lapis_ore", "青金石矿石"),
            Map.entry("nether_quartz_ore", "下界石英矿石"), Map.entry("nether_gold_ore", "下界金矿石"),
            Map.entry("deepslate_iron_ore", "深层铁矿石"), Map.entry("deepslate_gold_ore", "深层金矿石"),
            Map.entry("deepslate_copper_ore", "深层铜矿石"), Map.entry("deepslate_diamond_ore", "深层钻石矿石"),
            Map.entry("deepslate_emerald_ore", "深层绿宝石矿石"), Map.entry("deepslate_coal_ore", "深层煤矿石"),
            Map.entry("deepslate_redstone_ore", "深层红石矿石"), Map.entry("deepslate_lapis_ore", "深层青金石矿石"),
            Map.entry("ancient_debris", "远古残骸"),
            // === 作物/种子 ===
            Map.entry("wheat_seeds", "小麦种子"), Map.entry("cocoa_beans", "可可豆"),
            Map.entry("cactus", "仙人掌"), Map.entry("brown_mushroom", "棕色蘑菇"),
            Map.entry("red_mushroom", "红色蘑菇"), Map.entry("nether_wart", "下界疣"),
            Map.entry("chorus_fruit", "紫颂果"), Map.entry("chorus_flower", "紫颂花"),
            Map.entry("glow_berries", "发光浆果"), Map.entry("sweet_berries", "甜浆果"),
            Map.entry("kelp", "海带"), Map.entry("seagrass", "海草"),
            Map.entry("vine", "藤蔓"),
            Map.entry("lily_pad", "睡莲"), Map.entry("fern", "蕨"),
            Map.entry("large_fern", "大型蕨"), Map.entry("dead_bush", "枯死的灌木"),
            Map.entry("dandelion", "蒲公英"), Map.entry("poppy", "虞美人"), Map.entry("golden_dandelion", "金蒲公英"),
            // === 染料/花朵 ===
            Map.entry("white_dye", "白色染料"), Map.entry("orange_dye", "橙色染料"),
            Map.entry("magenta_dye", "品红色染料"), Map.entry("light_blue_dye", "淡蓝色染料"),
            Map.entry("yellow_dye", "黄色染料"), Map.entry("lime_dye", "黄绿色染料"),
            Map.entry("pink_dye", "粉红色染料"), Map.entry("gray_dye", "灰色染料"),
            Map.entry("light_gray_dye", "淡灰色染料"), Map.entry("cyan_dye", "青色染料"),
            Map.entry("purple_dye", "紫色染料"), Map.entry("blue_dye", "蓝色染料"),
            Map.entry("brown_dye", "棕色染料"), Map.entry("green_dye", "绿色染料"),
            Map.entry("red_dye", "红色染料"), Map.entry("black_dye", "黑色染料"),
            Map.entry("ink_sac", "墨囊"), Map.entry("glow_ink_sac", "荧光墨囊"),
            Map.entry("bone_meal", "骨粉"),
            // === 生物掉落 ===
            Map.entry("rotten_flesh", "腐肉"), Map.entry("spider_eye", "蜘蛛眼"),
            Map.entry("fermented_spider_eye", "发酵蛛眼"), Map.entry("ghast_tear", "恶魂之泪"),
            Map.entry("rabbit_hide", "兔子皮"), Map.entry("rabbit_foot", "兔子脚"),
            Map.entry("phantom_membrane", "幻翼膜"), Map.entry("shulker_shell", "潜影壳"),
            Map.entry("prismarine_shard", "海晶碎片"), Map.entry("prismarine_crystals", "海晶砂粒"),
            Map.entry("nautilus_shell", "鹦鹉螺壳"), Map.entry("heart_of_the_sea", "海洋之心"),
            Map.entry("scute", "鳞甲"), Map.entry("echo_shard", "回响碎片"),
            Map.entry("goat_horn", "山羊角"), Map.entry("disc_fragment_5", "唱片残片5"),
            // === 染色方块 ===
            Map.entry("white_wool", "白色羊毛"), Map.entry("orange_wool", "橙色羊毛"),
            Map.entry("magenta_wool", "品红色羊毛"), Map.entry("light_blue_wool", "淡蓝色羊毛"),
            Map.entry("yellow_wool", "黄色羊毛"), Map.entry("lime_wool", "黄绿色羊毛"),
            Map.entry("pink_wool", "粉红色羊毛"), Map.entry("gray_wool", "灰色羊毛"),
            Map.entry("light_gray_wool", "淡灰色羊毛"), Map.entry("cyan_wool", "青色羊毛"),
            Map.entry("purple_wool", "紫色羊毛"), Map.entry("blue_wool", "蓝色羊毛"),
            Map.entry("brown_wool", "棕色羊毛"), Map.entry("green_wool", "绿色羊毛"),
            Map.entry("red_wool", "红色羊毛"), Map.entry("black_wool", "黑色羊毛"),
            // === 红石/机械 ===
            Map.entry("repeater", "红石中继器"), Map.entry("comparator", "红石比较器"),
            Map.entry("redstone_torch", "红石火把"), Map.entry("lever", "拉杆"),
            Map.entry("stone_button", "石头按钮"), Map.entry("oak_button", "橡木按钮"),
            Map.entry("stone_pressure_plate", "石头压力板"), Map.entry("oak_pressure_plate", "橡木压力板"),
            Map.entry("heavy_weighted_pressure_plate", "重质测重压力板"), Map.entry("light_weighted_pressure_plate", "轻质测重压力板"),
            Map.entry("note_block", "音符盒"), Map.entry("jukebox", "唱片机"),
            Map.entry("trapped_chest", "陷阱箱"), Map.entry("tnt", "TNT"),
            Map.entry("target", "标靶"), Map.entry("lightning_rod", "避雷针"),
            Map.entry("daylight_detector", "阳光探测器"), Map.entry("tripwire_hook", "绊线钩"),
            Map.entry("rail", "铁轨"), Map.entry("powered_rail", "充能铁轨"),
            Map.entry("detector_rail", "探测铁轨"), Map.entry("activator_rail", "激活铁轨"),
            Map.entry("minecart", "矿车"), Map.entry("chest_minecart", "运输矿车"),
            Map.entry("hopper_minecart", "漏斗矿车"), Map.entry("furnace_minecart", "动力矿车"),
            Map.entry("tnt_minecart", "TNT矿车"),
            // === 门/栅栏 ===
            Map.entry("oak_door", "橡木门"), Map.entry("iron_door", "铁门"),
            Map.entry("oak_fence", "橡木栅栏"), Map.entry("oak_fence_gate", "橡木栅栏门"),
            Map.entry("oak_trapdoor", "橡木活板门"), Map.entry("iron_trapdoor", "铁活板门"),
            Map.entry("ladder", "梯子"), Map.entry("scaffolding", "脚手架"),
            Map.entry("iron_bars", "铁栏杆"), Map.entry("chain", "锁链"),
            Map.entry("lantern", "灯笼"), Map.entry("soul_lantern", "灵魂灯笼"),
            Map.entry("torch", "火把"), Map.entry("soul_torch", "灵魂火把"),
            Map.entry("campfire", "营火"), Map.entry("soul_campfire", "灵魂营火"),
            Map.entry("candle", "蜡烛"), Map.entry("white_candle", "白色蜡烛"),
            // === 床/装饰 ===
            Map.entry("white_bed", "白色床"), Map.entry("painting", "画"),
            Map.entry("item_frame", "物品展示框"), Map.entry("glow_item_frame", "荧光物品展示框"),
            Map.entry("armor_stand", "盔甲架"), Map.entry("bell", "钟"),
            Map.entry("loom", "织布机"), Map.entry("cartography_table", "制图台"),
            Map.entry("fletching_table", "制箭台"), Map.entry("grindstone", "砂轮"),
            Map.entry("stonecutter", "切石机"), Map.entry("lectern", "讲台"),
            Map.entry("composter", "堆肥桶"), Map.entry("cauldron", "炼药锅"),
            Map.entry("flower_pot", "花盆"), Map.entry("decorated_pot", "饰纹陶罐"),
            Map.entry("brush", "刷子"),
            // === 下界/末地 ===
            Map.entry("crimson_fungus", "绯红菌"), Map.entry("warped_fungus", "诡异菌"),
            Map.entry("weeping_vines", "垂泪藤"),
            Map.entry("twisting_vines", "缠怨藤"), Map.entry("warped_nylium", "诡异菌岩"),
            Map.entry("crimson_nylium", "绯红菌岩"), Map.entry("warped_wart_block", "诡异疣块"),
            Map.entry("nether_wart_block", "下界疣块"),
            Map.entry("shulker_box", "潜影盒"),
            Map.entry("end_crystal", "末影水晶"), Map.entry("end_rod", "末地烛"),
            Map.entry("dragon_breath", "龙息"), Map.entry("dragon_egg", "龙蛋"),
            Map.entry("respawn_anchor", "重生锚"), Map.entry("lodestone", "磁石"),
            // === 锻造模板 ===
            Map.entry("sentry_armor_trim_smithing_template", "哨兵盔甲纹饰锻造模板"),
            Map.entry("vex_armor_trim_smithing_template", "恼鬼盔甲纹饰锻造模板"),
            Map.entry("wild_armor_trim_smithing_template", "荒野盔甲纹饰锻造模板"),
            Map.entry("coast_armor_trim_smithing_template", "海岸盔甲纹饰锻造模板"),
            Map.entry("dune_armor_trim_smithing_template", "沙丘盔甲纹饰锻造模板"),
            Map.entry("ward_armor_trim_smithing_template", "监守盔甲纹饰锻造模板"),
            Map.entry("eye_armor_trim_smithing_template", "眼眸盔甲纹饰锻造模板"),
            Map.entry("tide_armor_trim_smithing_template", "潮汐盔甲纹饰锻造模板"),
            Map.entry("snout_armor_trim_smithing_template", "猪鼻盔甲纹饰锻造模板"),
            Map.entry("rib_armor_trim_smithing_template", "肋骨盔甲纹饰锻造模板"),
            Map.entry("spire_armor_trim_smithing_template", "塔尖盔甲纹饰锻造模板"),
            Map.entry("wayfinder_armor_trim_smithing_template", "向导盔甲纹饰锻造模板"),
            Map.entry("raiser_armor_trim_smithing_template", "牧民盔甲纹饰锻造模板"),
            Map.entry("shaper_armor_trim_smithing_template", "塑造盔甲纹饰锻造模板"),
            Map.entry("host_armor_trim_smithing_template", "雇主盔甲纹饰锻造模板"),
            Map.entry("silence_armor_trim_smithing_template", "静谧盔甲纹饰锻造模板"),
            Map.entry("flow_armor_trim_smithing_template", "旋流盔甲纹饰锻造模板"),
            Map.entry("bolt_armor_trim_smithing_template", "镶铆盔甲纹饰锻造模板"),
            Map.entry("mace", "重锤"), Map.entry("breeze_rod", "旋风棒"),
            Map.entry("wind_charge", "风弹"), Map.entry("ominous_bottle", "不祥之瓶"),
            Map.entry("ominous_trial_key", "不祥试炼钥匙"), Map.entry("trial_key", "试炼钥匙"),
            Map.entry("heavy_core", "沉重核心"), Map.entry("armadillo_scute", "犰狳鳞甲"),
            Map.entry("wolf_armor", "狼铠"), Map.entry("bundle", "收纳袋"),
            // === 1.21 铜系列 ===
            Map.entry("copper_bulb", "铜灯"), Map.entry("exposed_copper_bulb", "斑驳的铜灯"),
            Map.entry("weathered_copper_bulb", "锈蚀的铜灯"), Map.entry("oxidized_copper_bulb", "氧化的铜灯"),
            Map.entry("waxed_copper_bulb", "涂蜡铜灯"), Map.entry("waxed_exposed_copper_bulb", "涂蜡的斑驳铜灯"),
            Map.entry("waxed_weathered_copper_bulb", "涂蜡的锈蚀铜灯"), Map.entry("waxed_oxidized_copper_bulb", "涂蜡的氧化铜灯"),
            Map.entry("copper_grate", "铜格栅"), Map.entry("exposed_copper_grate", "斑驳的铜格栅"),
            Map.entry("weathered_copper_grate", "锈蚀的铜格栅"), Map.entry("oxidized_copper_grate", "氧化的铜格栅"),
            Map.entry("waxed_copper_grate", "涂蜡铜格栅"), Map.entry("waxed_exposed_copper_grate", "涂蜡的斑驳铜格栅"),
            Map.entry("waxed_weathered_copper_grate", "涂蜡的锈蚀铜格栅"), Map.entry("waxed_oxidized_copper_grate", "涂蜡的氧化铜格栅"),
            Map.entry("copper_door", "铜门"), Map.entry("exposed_copper_door", "斑驳的铜门"),
            Map.entry("weathered_copper_door", "锈蚀的铜门"), Map.entry("oxidized_copper_door", "氧化的铜门"),
            Map.entry("waxed_copper_door", "涂蜡铜门"), Map.entry("waxed_exposed_copper_door", "涂蜡的斑驳铜门"),
            Map.entry("waxed_weathered_copper_door", "涂蜡的锈蚀铜门"), Map.entry("waxed_oxidized_copper_door", "涂蜡的氧化铜门"),
            Map.entry("copper_trapdoor", "铜活板门"), Map.entry("exposed_copper_trapdoor", "斑驳的铜活板门"),
            Map.entry("weathered_copper_trapdoor", "锈蚀的铜活板门"), Map.entry("oxidized_copper_trapdoor", "氧化的铜活板门"),
            Map.entry("waxed_copper_trapdoor", "涂蜡铜活板门"), Map.entry("waxed_exposed_copper_trapdoor", "涂蜡的斑驳铜活板门"),
            Map.entry("waxed_weathered_copper_trapdoor", "涂蜡的锈蚀铜活板门"), Map.entry("waxed_oxidized_copper_trapdoor", "涂蜡的氧化铜活板门"),
            Map.entry("chiseled_copper", "雕纹铜块"), Map.entry("exposed_chiseled_copper", "斑驳的雕纹铜块"),
            Map.entry("weathered_chiseled_copper", "锈蚀的雕纹铜块"), Map.entry("oxidized_chiseled_copper", "氧化的雕纹铜块"),
            Map.entry("waxed_chiseled_copper", "涂蜡雕纹铜块"), Map.entry("waxed_exposed_chiseled_copper", "涂蜡的斑驳雕纹铜块"),
            Map.entry("waxed_weathered_chiseled_copper", "涂蜡的锈蚀雕纹铜块"), Map.entry("waxed_oxidized_chiseled_copper", "涂蜡的氧化雕纹铜块"),
            Map.entry("exposed_copper", "斑驳的铜块"), Map.entry("weathered_copper", "锈蚀的铜块"),
            Map.entry("oxidized_copper", "氧化的铜块"), Map.entry("waxed_copper_block", "涂蜡铜块"),
            Map.entry("waxed_exposed_copper", "涂蜡的斑驳铜块"), Map.entry("waxed_weathered_copper", "涂蜡的锈蚀铜块"),
            Map.entry("waxed_oxidized_copper", "涂蜡的氧化铜块"), Map.entry("cut_copper", "切制铜块"),
            Map.entry("exposed_cut_copper", "斑驳的切制铜块"), Map.entry("weathered_cut_copper", "锈蚀的切制铜块"),
            Map.entry("oxidized_cut_copper", "氧化的切制铜块"), Map.entry("waxed_cut_copper", "涂蜡切制铜块"),
            Map.entry("waxed_exposed_cut_copper", "涂蜡的斑驳切制铜块"), Map.entry("waxed_weathered_cut_copper", "涂蜡的锈蚀切制铜块"),
            Map.entry("waxed_oxidized_cut_copper", "涂蜡的氧化切制铜块"),
            // === 1.21 凝灰岩/合成器/宝库 ===
            Map.entry("crafter", "合成器"), Map.entry("tuff_slab", "凝灰岩台阶"),
            Map.entry("tuff_stairs", "凝灰岩楼梯"), Map.entry("tuff_wall", "凝灰岩墙"),
            Map.entry("polished_tuff", "磨制凝灰岩"), Map.entry("polished_tuff_slab", "磨制凝灰岩台阶"),
            Map.entry("polished_tuff_stairs", "磨制凝灰岩楼梯"), Map.entry("polished_tuff_wall", "磨制凝灰岩墙"),
            Map.entry("tuff_bricks", "凝灰岩砖"), Map.entry("tuff_brick_slab", "凝灰岩砖台阶"),
            Map.entry("tuff_brick_stairs", "凝灰岩砖楼梯"), Map.entry("tuff_brick_wall", "凝灰岩砖墙"),
            Map.entry("chiseled_tuff", "雕纹凝灰岩"), Map.entry("chiseled_tuff_bricks", "雕纹凝灰岩砖"),
            Map.entry("trial_spawner", "试炼刷怪笼"), Map.entry("vault", "宝库"),
            Map.entry("ominous_vault", "不祥宝库")
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
    public MenuDef getPlayerMenuDef(UUID uuid) { return playerMenuDef.get(uuid); }
    public List<CEBridge.RecipeData> getPlayerRecipes(UUID uuid) { return playerRecipes.get(uuid); }

    public void removePlayer(UUID uuid) {
        guiType.remove(uuid);
        playerCategory.remove(uuid);
        playerPage.remove(uuid);
        playerMenuDef.remove(uuid);
        openInventories.remove(uuid);
        playerRecipes.remove(uuid);
        creatorType.remove(uuid);
        creatorTime.remove(uuid);
        creatorFurnaceTime.remove(uuid);
        creatorExp.remove(uuid);
        creatorFurnaceMode.remove(uuid);
    }

    public void closeAll() {
        for (UUID uuid : new ArrayList<>(openInventories.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.closeInventory();
            removePlayer(uuid);
        }
    }
}
