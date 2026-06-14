package com.linong.recipelookup.bridge;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * CraftEngine 桥接层。
 * 通过反射访问 CE API，兼容 Paper 类加载器隔离。
 */
public class CEBridge {

    private final Logger logger;
    private final Plugin cePlugin;
    private Object actualCEPlugin; // 真正的 BukkitCraftEngine 实例（穿透 Paper 壳后）
    private boolean available;

    // 反射缓存
    private ClassLoader ceLoader;
    private Class<?> keyClass;
    private Object ceRecipeManager;
    private Method recipesByTypeMethod;
    private Method isDataPackRecipeMethod;
    private Object ceItemManager;
    private Method getItemDefinitionMethod;
    private Method getBuildableItemMethod;
    private Method getMaterialMethod;
    private Method getIdMethod;
    // CE 物品构建
    private Object emptyBuildContext;
    private Method buildItemMethod;
    private Method minecraftItemMethod;
    // CraftItemStack.asBukkitCopy
    private Method asBukkitCopyMethod;
    // Item.hoverNameComponent() → Optional<Component>（GUI 显示名）
    private Method itemHoverNameComponentMethod;
    // java.util.Optional.orElse(null)
    private Method optionalOrElseMethod;

    /** CE 配方类型 ID → 分类元数据 */
    public static final Map<String, CategoryMeta> CATEGORIES = new LinkedHashMap<>();

    static {
        int order = 0;
        CATEGORIES.put("crafting", new CategoryMeta("工作台配方", Material.CRAFTING_TABLE, "有序合成 / 无序合成", order++));
        CATEGORIES.put("smelting", new CategoryMeta("熔炉烧炼", Material.FURNACE, "使用熔炉烧炼物品", order++));
        CATEGORIES.put("blasting", new CategoryMeta("高炉烧炼", Material.BLAST_FURNACE, "使用高炉快速烧炼", order++));
        CATEGORIES.put("smoking", new CategoryMeta("烟熏炉", Material.SMOKER, "使用烟熏炉烹饪食物", order++));
        CATEGORIES.put("campfire_cooking", new CategoryMeta("营火烹饪", Material.CAMPFIRE, "使用营火烹饪食物", order++));
        CATEGORIES.put("stonecutting", new CategoryMeta("切石机", Material.STONECUTTER, "使用切石机加工石料", order++));
        CATEGORIES.put("smithing", new CategoryMeta("锻造台", Material.SMITHING_TABLE, "锻造变换 / 锻造纹饰", order++));
        CATEGORIES.put("brewing", new CategoryMeta("酿造台", Material.BREWING_STAND, "使用酿造台制作药水", order++));
    }

    public CEBridge(Logger logger) {
        this.logger = logger;
        this.cePlugin = Bukkit.getPluginManager().getPlugin("CraftEngine");

        if (cePlugin != null && cePlugin.isEnabled()) {
            try {
                initReflection();
                this.available = true;
                logger.info("  §a✓ §rCraftEngine v" + cePlugin.getDescription().getVersion() + " » 桥接就绪");
            } catch (Exception e) {
                logger.warning("CraftEngine 反射初始化失败: " + e.getMessage());
                this.available = false;
            }
        } else {
            this.available = false;
            logger.warning("CraftEngine 未检测到！");
        }
    }

    public boolean isAvailable() { return available; }
    public Plugin getCEPlugin() { return cePlugin; }

    // ==================== 反射初始化 ====================

    private void initReflection() throws Exception {
        // 获取真正的 CraftEngine 实例
        // CE 在 Paper 上使用 PaperCraftEnginePlugin 作为壳 → bootstrap.plugin → BukkitCraftEngine
        Object actualPlugin = cePlugin;
        try {
            actualPlugin.getClass().getMethod("recipeManager");
        } catch (NoSuchMethodException e) {
            // PaperCraftEnginePlugin 壳：反射获取 bootstrap.plugin
            try {
                java.lang.reflect.Field bf = actualPlugin.getClass().getDeclaredField("bootstrap");
                bf.setAccessible(true);
                Object bootstrap = bf.get(actualPlugin);
                java.lang.reflect.Field pf = bootstrap.getClass().getDeclaredField("plugin");
                pf.setAccessible(true);
                actualPlugin = pf.get(bootstrap);
                logger.info("  §a✓ §rCE 实例穿透 » " + actualPlugin.getClass().getSimpleName());
            } catch (Exception ex) {
                logger.info("非Paper壳: " + ex.getMessage());
            }
        }
        this.actualCEPlugin = actualPlugin;

        // 用实际 CraftEngine 实例的 ClassLoader
        this.ceLoader = actualPlugin.getClass().getClassLoader();

        // Key class — 多个地方用到，先缓存
        this.keyClass = Class.forName(
                "net.momirealms.craftengine.core.util.Key", true, ceLoader);

        // RecipeManager: actualPlugin → recipeManager()
        Method rmMethod = actualPlugin.getClass().getMethod("recipeManager");
        ceRecipeManager = rmMethod.invoke(actualPlugin);

        // RecipeManager.recipesByType(Object recipeType) → List
        Class<?> recipeTypeClass = Class.forName(
                "net.momirealms.craftengine.core.item.recipe.RecipeType", true, ceLoader);
        recipesByTypeMethod = ceRecipeManager.getClass().getMethod("recipesByType", recipeTypeClass);

        // RecipeManager.isDataPackRecipe(Key) → boolean
        isDataPackRecipeMethod = findMethod(ceRecipeManager.getClass(), "isDataPackRecipe", 1);

        // ItemManager
        Method imMethod = actualPlugin.getClass().getMethod("itemManager");
        ceItemManager = imMethod.invoke(actualPlugin);

        // ItemManager.getItemDefinition(Key) → Optional<ItemDefinition>
        getItemDefinitionMethod = findMethod(ceItemManager.getClass(), "getItemDefinition", 1);
        // ItemManager.getBuildableItem(Key) → Optional<BuildableItem>
        getBuildableItemMethod = findMethod(ceItemManager.getClass(), "getBuildableItem", 1);

        // ItemDefinition.material() → Key
        Class<?> itemDefClass = Class.forName(
                "net.momirealms.craftengine.core.item.ItemDefinition", true, ceLoader);
        getMaterialMethod = itemDefClass.getMethod("material");
        getIdMethod = itemDefClass.getMethod("id");

        // BuildableItem.buildItem(ItemBuildContext, int) → Item
        Class<?> buildableItemClass = Class.forName(
                "net.momirealms.craftengine.core.item.BuildableItem", true, ceLoader);
        buildItemMethod = buildableItemClass.getMethod("buildItem",
                Class.forName("net.momirealms.craftengine.core.item.ItemBuildContext", true, ceLoader),
                int.class);
        // Item.minecraftItem() → NMS ItemStack
        Class<?> itemClass = Class.forName(
                "net.momirealms.craftengine.core.item.Item", true, ceLoader);
        minecraftItemMethod = itemClass.getMethod("minecraftItem");
        // Item.hoverNameComponent() → Optional<Component>（default 方法，返回 GUI 显示名组件）
        try {
            itemHoverNameComponentMethod = itemClass.getMethod("hoverNameComponent");
        } catch (NoSuchMethodException e) {
            logger.info("Item.hoverNameComponent() 不可用（CE 版本较低）");
        }
        // Optional.orElse(null) — 用于解包 Optional 返回值
        optionalOrElseMethod = Class.forName("java.util.Optional").getMethod("orElse", Object.class);

        // ItemBuildContext.empty() → ItemBuildContext
        Class<?> ctxClass = Class.forName(
                "net.momirealms.craftengine.core.item.ItemBuildContext", true, ceLoader);
        emptyBuildContext = ctxClass.getMethod("empty").invoke(null);

        // CraftItemStack.asBukkitCopy(NMS ItemStack) → ItemStack
        // Paper 1.21+ 路径无版本号：org.bukkit.craftbukkit.inventory.CraftItemStack
        for (String cisPath : new String[]{
                "org.bukkit.craftbukkit.inventory.CraftItemStack",       // Paper 1.21+
                "org.bukkit.craftbukkit.v1_21_R3.inventory.CraftItemStack", // 旧版
                "org.bukkit.craftbukkit.v1_20_R4.inventory.CraftItemStack"  // 更旧
        }) {
            try {
                Class<?> cisClass = Class.forName(cisPath);
                asBukkitCopyMethod = cisClass.getMethod("asBukkitCopy",
                        Class.forName("net.minecraft.world.item.ItemStack"));
                logger.info("CraftItemStack 转换器就绪: " + cisPath);
                break;
            } catch (Exception ignored) {}
        }
        if (asBukkitCopyMethod == null) {
            logger.warning("CraftItemStack 转换器不可用，CE物品将显示为原版材质");
        }

        // 加载原版 zh_cn 翻译（确保搜索/排序使用与客户端一致的显示名）
        loadVanillaZhTranslations();
    }

    /** 从插件内置资源加载原版 zh_cn.json 翻译文件 */
    private void loadVanillaZhTranslations() {
        try {
            java.io.InputStream is = null;
            // 1. 插件内置资源（最高优先级，与插件一起发布，永远可用）
            is = getClass().getResourceAsStream("/vanilla_zh_cn.json");
            // 2. 回退：服务端 JAR 中的 assets（兼容旧版部署）
            if (is == null) {
                try {
                    Class<?> msClass = Class.forName("net.minecraft.server.MinecraftServer");
                    is = msClass.getResourceAsStream("/assets/minecraft/lang/zh_cn.json");
                } catch (Exception ignored) {}
            }
            if (is != null) {
                com.google.gson.Gson gson = new com.google.gson.Gson();
                java.io.InputStreamReader reader = new java.io.InputStreamReader(
                        is, java.nio.charset.StandardCharsets.UTF_8);
                @SuppressWarnings("unchecked")
                Map<String, String> translations = gson.fromJson(reader, Map.class);
                if (translations != null) {
                    vanillaZhTranslations.putAll(translations);
                    vanillaZhLoaded = true;
                    logger.info("  §a✓ §r原版 zh_cn 翻译 » " + translations.size() + " 条");
                }
                reader.close();
            } else {
                logger.warning("[原版翻译] 未找到 zh_cn.json，"
                        + "原版物品将显示英文名。");
            }
        } catch (Exception e) {
            logger.warning("[原版翻译] 加载失败: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    // ==================== 配方加载 ====================

    /**
     * 加载所有 CE 配方，按类型 ID 分组（使用字符串 key 避免跨 ClassLoader 的 enum 问题）
     */
    public Map<String, List<RecipeData>> loadAllRecipes() {
        Map<String, List<RecipeData>> result = new LinkedHashMap<>();
        if (!available) return result;

        // 获取 RecipeType.values()
        Object[] recipeTypes = null;
        try {
            Class<?> rtClass = Class.forName(
                    "net.momirealms.craftengine.core.item.recipe.RecipeType", true, ceLoader);
            recipeTypes = (Object[]) rtClass.getMethod("values").invoke(null);
        } catch (Exception e) {
            logger.severe("无法获取 RecipeType: " + e.getMessage());
            return result;
        }

        for (Object recipeType : recipeTypes) {
            try {
                // 使用 RecipeType.id() 获取类型ID（如 crafting, smelting），比 toString() 可靠
                String typeId;
                try {
                    typeId = (String) recipeType.getClass().getMethod("id").invoke(recipeType);
                } catch (Exception e) {
                    typeId = recipeType.toString().toLowerCase();
                }

                @SuppressWarnings("unchecked")
                List<Object> recipes = (List<Object>) recipesByTypeMethod.invoke(ceRecipeManager, recipeType);
                List<RecipeData> list = new ArrayList<>();
                for (Object recipe : recipes) {
                    if (isDataPackRecipe(recipe)) continue;
                    RecipeData data = extractRecipeData(recipe);
                    if (data != null) list.add(data);
                }
                if (!list.isEmpty()) {
                    result.put(typeId, list);
                }
            } catch (Exception e) {
                logger.info("加载配方类型失败: " + recipeType + " - " + e.getMessage());
            }
        }

        int total = result.values().stream().mapToInt(List::size).sum();
        logger.info("  §a✓ §r配方加载 » " + total + " 个 (" + result.size() + " 类型)");
        return result;
    }

    // ==================== 配方数据提取 ====================

    @SuppressWarnings("unchecked")
    private RecipeData extractRecipeData(Object recipe) {
        try {
            Class<?> rc = recipe.getClass();

            // id
            String id = callStr(recipe, "id");

            // type
            Object typeObj = rc.getMethod("type").invoke(recipe);
            String type = typeObj.toString().toLowerCase();

            // result
            String resultId = null;
            int resultCount = 1;
            try {
                Method resultMethod = findGetter(rc, "result");
                if (resultMethod != null) {
                    Object customResult = resultMethod.invoke(recipe);
                    if (customResult != null) {
                        // CustomRecipeResult.item() → BuildableItem
                        Object buildableItem = callGet(customResult, "item");
                        if (buildableItem != null) {
                            resultId = callStr(buildableItem, "id");
                        }
                        // count
                        try {
                            Object cnt = customResult.getClass().getMethod("count").invoke(customResult);
                            if (cnt instanceof Number n) resultCount = n.intValue();
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}

            // ingredients：每个 Ingredient 只取第一个代表物品（保证 GUI 槽位映射正确）
            List<String> ingredientIds = new ArrayList<>();
            List<Integer> ingredientCounts = new ArrayList<>();
            List<String> allIngredientIds = new ArrayList<>();
            try {
                List<Object> ings = (List<Object>) rc.getMethod("ingredientsInUse").invoke(recipe);
                for (Object ing : ings) {
                    List<String> keys = new ArrayList<>();
                    // Ingredient.items() → List<UniqueKey>
                    Object items = callGet(ing, "items");
                    if (items instanceof List<?> itemList) {
                        for (Object uk : itemList) {
                            // UniqueKey.key() → Key, Key.asString() → String
                            Object key = callGet(uk, "key");
                            keys.add(key != null ? key.toString() : uk.toString().replaceAll("[\\[\\]]", ""));
                        }
                    }
                    int count = 1;
                    try {
                        Object cnt = ing.getClass().getMethod("count").invoke(ing);
                        if (cnt instanceof Number n) count = n.intValue();
                    } catch (Exception ignored) {}
                    // GUI 显示：每个 Ingredient 只取第一个
                    if (!keys.isEmpty()) {
                        ingredientIds.add(keys.get(0));
                        ingredientCounts.add(count);
                    }
                    // 搜索匹配：保留全部 ID
                    allIngredientIds.addAll(keys);
                }
            } catch (Exception ignored) {}

            // 提取有序合成 pattern
            String[] shapedPattern = null;
            Map<String, String> patternKeyIds = null;
            try {
                // recipe.pattern() → CustomShapedRecipe$Pattern
                Object pattern = callGet(recipe, "pattern");
                if (pattern != null) {
                    // Pattern.pattern() → String[]
                    Method patMethod = pattern.getClass().getMethod("pattern");
                    String[] rows = (String[]) patMethod.invoke(pattern);
                    shapedPattern = rows;
                    // Pattern.ingredients() → Map<Character, Ingredient>
                    Object ingMap = callGet(pattern, "ingredients");
                    if (ingMap instanceof Map<?, ?> map) {
                        patternKeyIds = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            String ch = e.getKey().toString();
                            // Get first item from Ingredient
                            Object ing = e.getValue();
                            List<String> keys = extractIngredientKeys(ing);
                            String firstKey = keys.isEmpty() ? "?" : keys.get(0);
                            patternKeyIds.put(ch, firstKey);
                        }
                    }
                }
            } catch (Exception ignored) {}

            // cooking time & experience (for furnace-type recipes)
            int cookingTime = 0;
            float experience = 0;
            try {
                Object ct = rc.getMethod("cookingTime").invoke(recipe);
                if (ct instanceof Number n) cookingTime = n.intValue();
            } catch (Exception ignored) {}
            try {
                Object exp = rc.getMethod("experience").invoke(recipe);
                if (exp instanceof Number n) experience = n.floatValue();
            } catch (Exception ignored) {}

            return new RecipeData(id, type, resultId, resultCount,
                    ingredientIds, ingredientCounts, allIngredientIds,
                    shapedPattern, patternKeyIds,
                    cookingTime, experience);
        } catch (Exception e) {
            logger.info("提取配方数据失败: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractIngredientKeys(Object ing) {
        List<String> keys = new ArrayList<>();
        try {
            Object items = callGet(ing, "items");
            if (items instanceof List<?> list) {
                for (Object uk : list) {
                    Object key = callGet(uk, "key");
                    if (key != null) keys.add(key.toString());
                }
            }
        } catch (Exception ignored) {}
        return keys;
    }

    // ==================== 物品材质解析 ====================

    /**
     * 根据物品 ID 字符串解析 Material
     */
    public Material resolveMaterial(String itemId) {
        if (itemId == null || itemId.isEmpty()) return Material.PAPER;

        // 解析 namespace:value
        String namespace = "minecraft";
        String value = itemId;
        int colon = itemId.indexOf(':');
        if (colon > 0) {
            namespace = itemId.substring(0, colon);
            value = itemId.substring(colon + 1);
        }

        // 原版物品
        if ("minecraft".equals(namespace)) {
            try {
                return Material.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        // CE 自定义物品：通过 ItemManager.getItemDefinition 获取材质
        if (available && ceItemManager != null && getItemDefinitionMethod != null && keyClass != null) {
            try {
                // 构造 Key
                Object key = keyClass.getConstructor(String.class, String.class).newInstance(namespace, value);

                // 调用 getItemDefinition
                Object optDef = getItemDefinitionMethod.invoke(ceItemManager, key);
                // Optional → orElse(null)
                if (optDef != null) {
                    Method orElse = optDef.getClass().getMethod("orElse", Object.class);
                    Object def = orElse.invoke(optDef, (Object) null);
                    if (def != null) {
                        // ItemDefinition.material() → Key
                        Object matKey = getMaterialMethod.invoke(def);
                        if (matKey != null) {
                            String matStr = matKey.toString();
                            String matValue = matStr;
                            int c = matStr.indexOf(':');
                            if (c > 0) matValue = matStr.substring(c + 1);
                            try {
                                return Material.valueOf(matValue.toUpperCase());
                            } catch (IllegalArgumentException ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return Material.PAPER;
    }

    /**
     * 判断配方是否为原版数据包配方（非 CE 自定义）
     */
    private boolean isDataPackRecipe(Object recipe) {
        if (isDataPackRecipeMethod == null || keyClass == null) return false;
        try {
            // 获取配方 ID
            String idStr = callStr(recipe, "id");
            if (idStr == null) return false;
            // 解析 namespace:value
            int colon = idStr.indexOf(':');
            String ns = colon > 0 ? idStr.substring(0, colon) : "minecraft";
            String val = colon > 0 ? idStr.substring(colon + 1) : idStr;
            // 构造 Key
            Object key = keyClass.getConstructor(String.class, String.class).newInstance(ns, val);
            // 调用 isDataPackRecipe
            return (Boolean) isDataPackRecipeMethod.invoke(ceRecipeManager, key);
        } catch (Exception e) {
            return false; // 出错时保留配方
        }
    }

    /**
     * 构建 GUI 展示用的 ItemStack。
     * CE 自定义物品会构建完整 NBT 以保留纹理；原版物品直接用 Material。
     */
    public ItemStack buildItemStack(String itemId, int count) {
        if (itemId == null || itemId.isEmpty()) {
            return fallbackItem("未知物品", count);
        }
        count = Math.max(1, Math.min(count, 64));

        // 解析 namespace:value
        String ns = "minecraft";
        String val = itemId;
        int colon = itemId.indexOf(':');
        if (colon > 0) { ns = itemId.substring(0, colon); val = itemId.substring(colon + 1); }

        // 原版物品：直接用 Material
        if ("minecraft".equals(ns)) {
            try {
                return new ItemStack(Material.valueOf(val.toUpperCase()), count);
            } catch (IllegalArgumentException ignored) {}
        }

        // CE 自定义物品：通过 ItemManager 构建完整物品（保留纹理 NBT）
        if (available && ceItemManager != null && getBuildableItemMethod != null && buildItemMethod != null) {
            try {
                Object key = keyClass.getConstructor(String.class, String.class).newInstance(ns, val);
                Object optBuildable = getBuildableItemMethod.invoke(ceItemManager, key);
                if (optBuildable != null) {
                    Method orElse = optBuildable.getClass().getMethod("orElse", Object.class);
                    Object buildable = orElse.invoke(optBuildable, (Object) null);
                    if (buildable != null) {
                        // Build CE Item
                        Object ceItem = buildItemMethod.invoke(buildable, emptyBuildContext, count);
                        // Get NMS ItemStack
                        Object nmsStack = minecraftItemMethod.invoke(ceItem);
                        // Convert to Bukkit ItemStack
                        if (asBukkitCopyMethod != null && nmsStack != null) {
                            // 不覆盖 item_name 组件，让客户端按玩家语言解析名称
                            return (ItemStack) asBukkitCopyMethod.invoke(null, nmsStack);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warning("构建 CE 物品失败 " + itemId + ": " + e.getMessage());
            }
        }

        // 兜底：用解析出的 Material
        return new ItemStack(resolveMaterial(itemId), count);
    }

    /** 获取配方类型的显示名称 */
    public String getTypeDisplayName(String typeId) {
        CategoryMeta meta = CATEGORIES.get(typeId);
        return meta != null ? meta.name : typeId;
    }

    /** CE 物品名缓存：itemId → 名称（仅缓存成功结果，失败不缓存以支持延迟加载重试） */
    private final Map<String, String> itemNameCache = new LinkedHashMap<>();
    /** item_name 组件读取缓存（仅缓存成功结果） */
    private final Map<String, String> displayNameCache = new LinkedHashMap<>();

    /** 外部放入名字缓存（配方加载时从 YML 读回） */
    /** 外部放入名字缓存（配方加载时从 YML 读回） */
    public void putDisplayName(String itemId, String name) {
        if (itemId != null && name != null && !name.isEmpty()) {
            displayNameCache.put(itemId, name);
        }
    }

    /** 清除所有名称缓存，强制重新从 CE 解析（reload 时调用） */
    public void clearNameCaches() {
        displayNameCache.clear();
        itemNameCache.clear();
        ceTranslations.clear();
        ceTranslationsLoaded = false;
        logger.info("  §a✓ §r名称缓存 » 已清除");
    }

    /**
     * 【核心】通过 CE API 直接获取物品在 GUI 中显示的名字。
     * 构建 CE Item → 提取 hoverNameComponent() → 序列化为纯文本。
     * 这跟客户端 GUI 中看到的物品名字完全一致，因为 CE 的 ItemNameProcessor
     * 在构建过程中已经把 item_name 组件设置好了。
     */
    /**
     * 从 ItemDefinition.processors() 中读取 ItemNameProcessor 的原始 item-name。
     * 不构建 CE 物品，无纹理加载，极快。
     */
    private String readItemNameFromDefinition(Object buildable, java.util.Locale locale) {
        try {
            java.lang.reflect.Method processorsMethod = buildable.getClass()
                    .getMethod("processors");
            Object[] processors = (Object[]) processorsMethod.invoke(buildable);
            if (processors != null) {
                for (Object proc : processors) {
                    try {
                        java.lang.reflect.Method itemNameMethod = proc.getClass()
                                .getMethod("itemName");
                        String rawName = (String) itemNameMethod.invoke(proc);
                        if (rawName != null && !rawName.isEmpty()) {
                            // 检查是否有 <l10n:key> 翻译标签
                            String fromL10n = resolveL10n(rawName, locale);
                            if (fromL10n != null) return fromL10n;
                            // 纯文本 item-name：去除格式标签
                            String stripped = stripMiniMessageTags(rawName);
                            if (!stripped.isEmpty()) return stripped;
                        }
                    } catch (NoSuchMethodException ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 从 MiniMessage 字符串中提取 <l10n:key> 或 <lang:key> 并按玩家语言查翻译 */
    private String resolveL10n(String raw, java.util.Locale locale) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<(?:l10n|lang):([^>]+)>").matcher(raw);
        while (m.find()) {
            String key = m.group(1);
            ensureCETranslationsLoaded();
            // 按语言查：en_US → en_us:key → en:key → key（原始key回退）
            String fullLang = (locale != null ? locale.toString() : "zh_cn").replace('-', '_').toLowerCase();
            String shortLang = fullLang.contains("_") ? fullLang.substring(0, fullLang.indexOf('_')) : fullLang;
            for (String tryKey : new String[]{
                    fullLang + ":" + key,   // en_us:key
                    shortLang + ":" + key,   // en:key
                    key                      // key（clientLangData 的 zh_cn 回退）
            }) {
                String name = ceTranslations.get(tryKey);
                if (name != null && !name.isEmpty()) return name;
            }
        }
        return null;
    }

    /** 去除 MiniMessage 格式标签（<!i>, <bold>, </bold>, <#FF0000> 等），保留文本 */
    private static String stripMiniMessageTags(String miniMessage) {
        return miniMessage.replaceAll("<[^>]*>", "").trim();
    }

    private String readCEItemHoverName(String itemId, java.util.Locale locale) {
        int colon = itemId.indexOf(':');
        if (colon <= 0) return null;
        String ns = itemId.substring(0, colon);
        String val = itemId.substring(colon + 1);
        if ("minecraft".equals(ns)) return null;

        if (!available || keyClass == null || getBuildableItemMethod == null) {
            return null;
        }

        try {
            Object key = keyClass.getConstructor(String.class, String.class)
                    .newInstance(ns, val);
            Object optBuildable = getBuildableItemMethod.invoke(ceItemManager, key);
            if (optBuildable == null) return null;
            Object buildable = optionalOrElseMethod.invoke(optBuildable, (Object) null);
            if (buildable == null) return null;

            // 1. 快速路径：直接从 ItemDefinition.processors() 读 item-name（不构建物品）
            String fromDef = readItemNameFromDefinition(buildable, locale);
            if (fromDef != null && !fromDef.isEmpty()
                    && !fromDef.equalsIgnoreCase(ns)) {
                return fromDef;
            }

            // 2. 慢速回退：构建 CE 物品，调用 hoverNameComponent() / NMS getHoverName()
            //    仅在 fast path 失败时使用（如 item-name 为空、靠翻译键的物品）
            if (buildItemMethod != null) {
                Object ceItem = buildItemMethod.invoke(buildable, emptyBuildContext, 1);
                if (ceItem != null) {
                    // 2a. CE API: hoverNameComponent()
                    if (itemHoverNameComponentMethod != null) {
                        Object optComp = itemHoverNameComponentMethod.invoke(ceItem);
                        if (optComp != null) {
                            Object component = optionalOrElseMethod.invoke(optComp, (Object) null);
                            String plain = serializeAdventureComponent(component);
                            if (plain != null && !plain.isEmpty()
                                    && !plain.equalsIgnoreCase(ns)) {
                                return plain;
                            }
                        }
                    }
                    // 2b. NMS ItemStack.getHoverName()
                    Object nmsStack = minecraftItemMethod.invoke(ceItem);
                    if (nmsStack != null) {
                        try {
                            java.lang.reflect.Method getHoverName = nmsStack.getClass()
                                    .getMethod("getHoverName");
                            Object component = getHoverName.invoke(nmsStack);
                            String plain = serializeAdventureComponent(component);
                            if (plain != null && !plain.isEmpty()
                                    && !plain.equalsIgnoreCase(ns)) {
                                return plain;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // 3. 最终回退：CE TranslationManager
            ensureCETranslationsLoaded();
            String tlKey = "item." + ns + "." + val;
            String name = ceTranslations.get(tlKey);
            if (name != null && !name.isEmpty()) return name;

        } catch (Exception e) {
            // 静默失败
        }
        return null;
    }

    /**
     * 将 Adventure Component 序列化为纯文本。
     * 同时支持原版 {@code net.kyori.adventure.text.Component}
     * 和 CE relocate 后的 {@code net.momirealms.craftengine.libraries.adventure.text.Component}。
     */
    private String serializeAdventureComponent(Object component) {
        if (component == null) return null;
        // 1. 原版 Adventure Component
        if (component instanceof net.kyori.adventure.text.Component advComp) {
            return net.kyori.adventure.text.serializer.plain
                    .PlainTextComponentSerializer.plainText().serialize(advComp);
        }
        // 2. CE relocate 后的 Adventure Component（通过反射调用 shaded 的序列化器）
        if (ceLoader != null) {
            try {
                Class<?> ceCompClass = Class.forName(
                        "net.momirealms.craftengine.libraries.adventure.text.Component",
                        true, ceLoader);
                if (ceCompClass.isInstance(component)) {
                    Class<?> ceSerializerClass = Class.forName(
                            "net.momirealms.craftengine.libraries.adventure.text.serializer.plain.PlainTextComponentSerializer",
                            true, ceLoader);
                    Object serializer = ceSerializerClass.getMethod("plainText").invoke(null);
                    java.lang.reflect.Method serializeMethod = serializer.getClass()
                            .getMethod("serialize", ceCompClass);
                    return (String) serializeMethod.invoke(serializer, component);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 读取物品在 GUI 中实际展示的显示名。
     * <p>
     * 解析优先级（与客户端行为对齐）：
     * 0. CE API hoverName 组件（CE 物品最优先，与 GUI 完全一致）
     * 1. CE 翻译（TranslationManager.clientLangData）
     * 2. 原版 zh_cn.json 翻译（从服务端 JAR 加载）
     * 3. GlobalTranslator 用玩家 locale 渲染（跟随客户端语言）
     * 4. NMS Language（服务端语言，最后回退）
     *
     * @param itemId 物品 ID
     * @param locale 玩家客户端语言（Player.getLocale()），用于原版物品翻译
     */
    public String readItemDisplayName(String itemId, java.util.Locale locale) {
        if (itemId == null) return null;
        String cached = displayNameCache.get(itemId);
        if (cached != null) return cached;

        // 0. CE API hoverName 方式（最准确，直接用 CE 构建的 Item 提取名字组件）
        if (itemId.contains(":") && !itemId.startsWith("minecraft:")) {
            String hoverName = readCEItemHoverName(itemId, locale);
            if (hoverName != null && !hoverName.isEmpty()) {
                displayNameCache.put(itemId, hoverName);
                return hoverName;
            }
        }

        // 构建实际物品（CE 物品走完整 NBT 构建，和 GUI 里看到的一致）
        ItemStack stack = buildItemStack(itemId, 1);
        Object component = getItemNameComponent(stack);

        String key = null;
        if (component != null) {
            key = getTranslationKey(component);

            // 1. CE 翻译（CE 物品的 clientLangData）
            if (key != null) {
                ensureCETranslationsLoaded();
                String ceName = ceTranslations.get(key);
                if (ceName != null && !isTranslationKey(ceName)) {
                    displayNameCache.put(itemId, ceName);
                    return ceName;
                }
            }

            // 2. 原版 zh_cn.json 翻译（与服务端 JAR 中的客户端翻译一致）
            if (key != null && vanillaZhLoaded) {
                String vanillaName = vanillaZhTranslations.get(key);
                if (vanillaName != null && !vanillaName.isEmpty()) {
                    displayNameCache.put(itemId, vanillaName);
                    return vanillaName;
                }
            }

            // 3. GlobalTranslator 用玩家客户端语言渲染
            try {
                net.kyori.adventure.text.Component rendered =
                        net.kyori.adventure.translation.GlobalTranslator.render(
                                (net.kyori.adventure.text.Component) component,
                                locale != null ? locale : java.util.Locale.US);
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(rendered);
                // 只接受真正的翻译文本，拒绝未翻译的 key
                if (plain != null && !plain.isEmpty() && !isTranslationKey(plain)) {
                    displayNameCache.put(itemId, plain);
                    return plain;
                }
            } catch (Exception ignored) {}
        } else {
            // component 为 null 的原版物品：用 Material 翻译键查 CE 翻译/原版语言文件
            Material mat = stack.getType();
            if (mat != Material.AIR && mat != Material.PAPER) {
                String transKey = mat.getTranslationKey();
                if (transKey != null && locale != null) {
                    // 优先查 CE clientLangData（含原版翻译）
                    ensureCETranslationsLoaded();
                    String ceName = ceTranslations.get(transKey);
                    if (ceName != null && !ceName.isEmpty() && !isTranslationKey(ceName)) {
                        displayNameCache.put(itemId, ceName);
                        return ceName;
                    }
                    // 回退：从服务端 JAR 提取的原版 zh_cn.json
                    if ("zh".equals(locale.getLanguage()) && vanillaZhLoaded) {
                        String vanillaName = vanillaZhTranslations.get(transKey);
                        if (vanillaName != null && !vanillaName.isEmpty()) {
                            displayNameCache.put(itemId, vanillaName);
                            return vanillaName;
                        }
                    }
                }
            }
        }

        // 4. 最后回退：NMS Language（仅原版物品使用，CE 物品 Material 可能不匹配）
        if (itemId.contains(":") && !itemId.startsWith("minecraft:")) {
            return null; // CE 物品走 formatItemName 兜底
        }
        Material mat = stack.getType();
        if (mat != Material.AIR && mat != Material.PAPER) {
            String name = getVanillaTranslation(mat);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }

        // 不缓存失败 —— CE 翻译可能延迟加载，下次可能成功
        return null;
    }

    /** 判断字符串是否看起来像翻译键（如 "block.minecraft.stone"、"item.xxx.yyy"） */
    private static boolean isTranslationKey(String text) {
        if (text == null || text.isEmpty()) return true;
        // 翻译键格式：category.namespace.path 或 namespace.category.path
        // 如 block.minecraft.stone、item.chessboard.block、enchantment.minecraft.sharpness
        if (text.startsWith("block.") || text.startsWith("item.")
                || text.startsWith("enchantment.") || text.startsWith("effect.")
                || text.startsWith("entity.") || text.startsWith("biome.")
                || text.startsWith("argument.") || text.startsWith("death.")
                || text.startsWith("advancements.") || text.startsWith("chat.")
                || text.startsWith("commands.") || text.startsWith("container.")
                || text.startsWith("filled_map.") || text.startsWith("flat_world_preset.")
                || text.startsWith("gameMode.") || text.startsWith("generator.")
                || text.startsWith("gui.") || text.startsWith("instrument.")
                || text.startsWith("itemGroup.") || text.startsWith("jukebox_song.")
                || text.startsWith("key.") || text.startsWith("language.")
                || text.startsWith("menu.") || text.startsWith("mirror.")
                || text.startsWith("mob.") || text.startsWith("narrator.")
                || text.startsWith("options.") || text.startsWith("potion.")
                || text.startsWith("record.") || text.startsWith("resource_pack.")
                || text.startsWith("sign.") || text.startsWith("sound_category.")
                || text.startsWith("stat.") || text.startsWith("subtitles.")
                || text.startsWith("team.") || text.startsWith("text.")
                || text.startsWith("tutorial.") || text.startsWith("trim_pattern.")
                || text.startsWith("trim_material.") || text.startsWith("painting.")
                || text.startsWith("selectWorld.") || text.startsWith("spectatorMenu.")
                || text.startsWith("connect.") || text.startsWith("disconnect.")
                || text.startsWith("multiplayer.") || text.startsWith("realms.")
                || text.startsWith("socialInteractions.") || text.startsWith("title.")
                || text.startsWith("credits_and_attribution.")) {
            return true;
        }
        // 通用检测：全小写+下划线的点分隔格式（如 category.namespace.path）
        return text.matches("^[a-z_]+\\.[a-z_]+(\\.[a-z_]+)*$") && !text.contains(" ");
    }

    /** CE 翻译缓存：translationKey → zh_cn 文本 */
    private Map<String, String> ceTranslations = new LinkedHashMap<>();
    private boolean ceTranslationsLoaded;

    /** 原版 Minecraft zh_cn 翻译缓存：translationKey → 中文文本 */
    private final Map<String, String> vanillaZhTranslations = new LinkedHashMap<>();
    private boolean vanillaZhLoaded;

    /**
     * 统一的 CE 翻译加载入口（幂等）。
     * resolveViaTranslationManager 和 loadCETranslations 都走这里，
     * 避免两个独立守卫互相覆盖 ceTranslations。
     */
    private synchronized void ensureCETranslationsLoaded() {
        if (ceTranslationsLoaded) return;
        ceTranslationsLoaded = true;
        if (!available) return;

        // 反射 CE TranslationManager.clientLangData()
        try {
            Class<?> ceMain = Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine", true, ceLoader);
            Object ceInstance = ceMain.getMethod("instance").invoke(null);
            Object tm = ceMain.getMethod("translationManager").invoke(ceInstance);
            @SuppressWarnings("unchecked")
            Map<String, Object> clientData = (Map<String, Object>) tm.getClass()
                    .getMethod("clientLangData").invoke(tm);
            logger.info("  §a✓ §r翻译系统 » " + clientData.size() + " 种语言可用");
            Object zhCN = clientData.get("zh_cn");
            if (zhCN != null) {
                @SuppressWarnings("unchecked")
                Map<String, String> translations = (Map<String, String>) zhCN.getClass()
                        .getField("translations").get(zhCN);
                ceTranslations.putAll(translations);
                // 不 return —— 继续加载 translations.yml（pack 翻译）
            } else {
                logger.warning("[CE翻译] zh_cn 数据为 null！可用语言: " + clientData.keySet());
            }
        } catch (Exception e) {
            logger.warning("[CE翻译] 反射 TranslationManager 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 回退：文件系统搜索（保留原有逻辑）
        loadCETranslationsFromFile();

        // 额外：解析 CE 资源包中的 translations.yml（farmersdelight 等 pack 用此格式）
        int oldCount = ceTranslations.size();
        loadPackTranslationsYml();
        if (ceTranslations.size() > oldCount) {
            logger.info("  §a✓ §r扩展翻译 » +" + (ceTranslations.size() - oldCount) + " 条");
        }
    }

    /** 从 CE 资源包的 configuration/translations.yml 加载翻译（所有语言） */
    private void loadPackTranslationsYml() {
        java.io.File resourcesDir = new java.io.File(cePlugin.getDataFolder(), "resources");
        if (!resourcesDir.exists()) return;
        java.io.File[] packs = resourcesDir.listFiles(java.io.File::isDirectory);
        if (packs == null) return;
        for (java.io.File pack : packs) {
            java.io.File tlFile = new java.io.File(pack, "configuration/translations.yml");
            if (!tlFile.exists()) continue;
            try {
                org.bukkit.configuration.file.YamlConfiguration yaml =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(tlFile);
                org.bukkit.configuration.ConfigurationSection tlSec =
                        yaml.getConfigurationSection("translations");
                if (tlSec == null) continue;
                // 加载所有语言，key 格式: "zh_cn:item.key" → "翻译", "en:item.key" → "Translation"
                for (String lang : tlSec.getKeys(false)) {
                    org.bukkit.configuration.ConfigurationSection langSec = tlSec.getConfigurationSection(lang);
                    if (langSec == null) continue;
                    String langPrefix = lang.toLowerCase() + ":";
                    for (String key : langSec.getKeys(true)) {
                        Object val = langSec.get(key);
                        if (val instanceof String s && !s.isEmpty()) {
                            ceTranslations.putIfAbsent(langPrefix + key, s);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /** 从 CE 的 clientLangData 查找翻译键 */
    private String resolveViaTranslationManager(String key) {
        ensureCETranslationsLoaded();
        return ceTranslations.get(key);
    }

    /**
     * 解析 CE 物品的显示名称。
     * 优先级：CE lang 文件 → GlobalTranslator → 格式化 key
     */
    public String resolveCEItemName(String itemId) {
        if (itemId == null || itemId.startsWith("minecraft:")) return null;
        String cached = itemNameCache.get(itemId);
        if (cached != null) return cached.isEmpty() ? null : cached;

        ItemStack stack = buildItemStack(itemId, 1);
        // 1. CE lang → 2. GlobalTranslator → 3. 格式化 key
        String name = readItemNameViaCELang(stack);
        if (name == null) name = readItemNameViaGlobalTranslator(stack);
        if (name == null) name = readItemNameKey(stack);

        // 只缓存成功结果，失败不缓存（CE 翻译可能延迟加载）
        if (name != null) itemNameCache.put(itemId, name);
        return name;
    }

    /** 通过 GlobalTranslator 解析 item_name */
    private String readItemNameViaGlobalTranslator(ItemStack stack) {
        Object component = getItemNameComponent(stack);
        if (component == null) return null;
        try {
            net.kyori.adventure.text.Component rendered = net.kyori.adventure.translation.GlobalTranslator
                    .render((net.kyori.adventure.text.Component) component, java.util.Locale.SIMPLIFIED_CHINESE);
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(rendered);
            if (plain != null && !plain.isEmpty()) return plain;
        } catch (Exception ignored) {}
        return null;
    }

    /** 从 CE lang 读取翻译 */
    private String readItemNameViaCELang(ItemStack stack) {
        Object component = getItemNameComponent(stack);
        if (component == null) return null;
        String key = getTranslationKey(component);
        if (key == null) return null;
        loadCETranslations();
        return ceTranslations.get(key);
    }

    /** 提取翻译键的尾部作为兜底名称 */
    private String readItemNameKey(ItemStack stack) {
        Object component = getItemNameComponent(stack);
        if (component == null) return null;
        String key = getTranslationKey(component);
        if (key == null) return null;
        // item.default.chessboard_block → chessboard_block → Chessboard Block
        int lastDot = key.lastIndexOf('.');
        String last = lastDot >= 0 ? key.substring(lastDot + 1) : key;
        return formatItemName(last);
    }

    /** 获取 item_name Adventure Component（反射） */
    private Object getItemNameComponent(ItemStack stack) {
        try {
            Class<?> dctClass = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
            java.lang.reflect.Field f = dctClass.getField("ITEM_NAME");
            Object type = f.get(null);
            java.lang.reflect.Method hasData = ItemStack.class.getMethod("hasData",
                    Class.forName("io.papermc.paper.datacomponent.DataComponentType"));
            if (!(boolean) hasData.invoke(stack, type)) return null;
            java.lang.reflect.Method getData = ItemStack.class.getMethod("getData",
                    Class.forName("io.papermc.paper.datacomponent.DataComponentType"));
            Object data = getData.invoke(stack, type);
            if (data == null) return null;
            return data.getClass().getMethod("name").invoke(data);
        } catch (Exception ignored) { return null; }
    }

    /** 从 Adventure Component 提取翻译键 */
    /** 原版物品：用 Minecraft Language 系统解析翻译（服务端语言） */
    private static String getVanillaTranslation(Material mat) {
        try {
            String key = mat.getTranslationKey();
            if (key == null) return null;
            Class<?> langClass = Class.forName("net.minecraft.locale.Language");
            Object lang = langClass.getMethod("getInstance").invoke(null);
            String result = (String) langClass.getMethod("getOrDefault", String.class).invoke(lang, key);
            if (result != null && !result.equals(key)) return result;
        } catch (Exception ignored) {}
        return null;
    }

    /** 从 ItemStack 提取 CE 物品 ID，多种方式回退 */
    public String getCEItemId(ItemStack stack) {
        if (stack == null) return null;
        // 使用 CE API: CraftEngineItems.getCustomItemId()
        try {
            Class<?> ceiClass = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            java.lang.reflect.Method m = ceiClass.getMethod("getCustomItemId", ItemStack.class);
            Object result = m.invoke(null, stack);
            if (result != null) {
                String id = result.toString(); // net.momirealms.craftengine.core.util.Key → "namespace:value"
                if (!id.isEmpty()) { logger.info("[CE ID] CE API: " + id); return id; }
            }
        } catch (Exception e) {
            logger.info("[CE ID] CE API 失败: " + e.getMessage());
        }
        // 回退：item_name 组件
        String key = getTranslationKeyFromItem(stack);
        if (key != null && key.startsWith("item.")) {
            String sub = key.substring(5);
            int lastDot = sub.lastIndexOf('.');
            if (lastDot > 0) return sub.substring(0, lastDot) + ":" + sub.substring(lastDot + 1);
        }
        try {
            Class<?> dctClass = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
            java.lang.reflect.Field f = dctClass.getField("ITEM_NAME");
            Object type = f.get(null);
            java.lang.reflect.Method hasData = ItemStack.class.getMethod("hasData",
                    Class.forName("io.papermc.paper.datacomponent.DataComponentType"));
            java.lang.reflect.Method getData = ItemStack.class.getMethod("getData",
                    Class.forName("io.papermc.paper.datacomponent.DataComponentType"));
            if ((boolean) hasData.invoke(stack, type)) {
                Object data = getData.invoke(stack, type);
                if (data != null) {
                    Object component = data.getClass().getMethod("name").invoke(data);
                    if (component instanceof net.kyori.adventure.text.TranslatableComponent tc) {
                        String tKey = tc.key();
                        if (tKey.startsWith("item.")) {
                            String sub = tKey.substring(5);
                            int d = sub.lastIndexOf('.');
                            if (d > 0) return sub.substring(0, d) + ":" + sub.substring(d + 1);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.fine("Paper DataComponent API failed: " + e.getMessage());
        }
        return null;
    }

    /** 从 ItemStack 直接提取翻译键 */
    public String getTranslationKeyFromItem(ItemStack stack) {
        Object c = getItemNameComponent(stack);
        return c != null ? getTranslationKey(c) : null;
    }

    private String getTranslationKey(Object component) {
        try {
            if (component instanceof net.kyori.adventure.text.TranslatableComponent tc)
                return tc.key();
        } catch (Exception ignored) {}
        return null;
    }

    /** 从 CE 的 zh_cn.yml 加载翻译（被 readItemNameViaCELang 使用，委托给统一入口） */
    private void loadCETranslations() {
        ensureCETranslationsLoaded();
    }

    /** 文件系统回退：从 CE JAR / 数据文件夹搜索并解析 zh_cn.yml */
    private void loadCETranslationsFromFile() {
        java.io.InputStream is = null;
        String[] jarPaths = {
                "resources/default_assets/configuration/langs/zh_cn.yml",
                "configuration/langs/zh_cn.yml",
                "langs/zh_cn.yml",
                "resources/langs/zh_cn.yml",
                "zh_cn.yml"
        };

        // a) CE ClassLoader（最高优先级，能访问 CE 所有内部资源）
        if (ceLoader != null) {
            for (String path : jarPaths) {
                is = ceLoader.getResourceAsStream(path);
                if (is != null) { logger.info("[CE翻译] ClassLoader找到: " + path); break; }
            }
        }
        // b) 真正的 CE Plugin 实例
        if (is == null && actualCEPlugin instanceof Plugin realCE) {
            for (String path : jarPaths) {
                is = realCE.getResource(path);
                if (is != null) { logger.info("[CE翻译] Plugin实例找到: " + path); break; }
            }
        }
        // c) Paper 壳
        if (is == null) {
            for (String path : jarPaths) {
                is = cePlugin.getResource(path);
                if (is != null) { logger.info("[CE翻译] Paper壳找到: " + path); break; }
            }
        }
        // d) 文件系统搜索 — CE 数据文件夹（资源包可能被解压到这里）
        if (is == null) {
            File dataFolder = cePlugin.getDataFolder();
            String[] fsPaths = {
                    "resources/default/configuration/langs/zh_cn.yml",
                    "resources/default_assets/configuration/langs/zh_cn.yml",
                    "contents/default_assets/configuration/langs/zh_cn.yml",
                    "default_assets/configuration/langs/zh_cn.yml"
            };
            for (String fp : fsPaths) {
                File f = new File(dataFolder, fp);
                if (f.exists()) {
                    try { is = new java.io.FileInputStream(f); logger.info("[CE翻译] 文件系统找到: " + f.getPath()); break; }
                    catch (Exception ignored) {}
                }
            }
            // 递归搜索兜底
            if (is == null) {
                File f = findCEFile(dataFolder, "zh_cn.yml");
                if (f != null) {
                    try { is = new java.io.FileInputStream(f); logger.info("[CE翻译] 递归搜索找到: " + f.getPath()); }
                    catch (Exception ignored) {}
                }
            }
        }

        if (is == null) {
            logger.warning("[CE翻译] 未找到 zh_cn.yml！");
            logger.warning("[CE翻译] JAR路径: " + String.join(", ", jarPaths));
            logger.warning("[CE翻译] 数据文件夹: " + cePlugin.getDataFolder().getAbsolutePath());
            logger.warning("[CE翻译] 搜索将回退到英文名，中文搜索无法匹配。请在 lang.yml custom-names 手动添加 CE 物品中文名。");
            return;
        }

        try {
            parseCELangFile(is);
            logger.info("[CE翻译] 从文件加载了 " + ceTranslations.size() + " 条物品翻译");
        } catch (Exception e) {
            logger.warning("[CE翻译] 解析 CE 翻译文件失败: " + e.getMessage());
        }
    }

    private File findCEFile(File dir, String name) {
        if (!dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals(name)) return f;
            if (f.isDirectory()) {
                File found = findCEFile(f, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 解析 CE 的 lang 文件（处理 lang#items 非标准 YAML 键） */
    private void parseCELangFile(java.io.InputStream is) throws Exception {
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        boolean inItems = false, inZhCN = false;
        while ((line = reader.readLine()) != null) {
            if (line.matches("^lang#items\\s*:.*")) { inItems = true; inZhCN = false; continue; }
            if (inItems && line.matches("^\\s+zh_cn\\s*:.*")) { inZhCN = true; continue; }
            if (inItems && inZhCN) {
                if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) break;
                if (line.matches("^\\s+[\\w.]+\\s*:.*")) {
                    int colon = line.indexOf(':');
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if (!value.isEmpty() && !value.startsWith("#")) ceTranslations.put(key, value);
                }
            }
        }
        reader.close();
    }

    private static String formatItemName(String name) {
        if (name == null || name.isEmpty()) return null;
        String[] words = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) sb.append(word.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    // ==================== 反射工具方法 ====================

    private String callStr(Object obj, String name) {
        if (obj == null) return null;
        try {
            Method m = findGetter(obj.getClass(), name);
            if (m != null) {
                Object r = m.invoke(obj);
                return r != null ? r.toString() : null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Object callGet(Object obj, String name) {
        if (obj == null) return null;
        try {
            Method m = findGetter(obj.getClass(), name);
            if (m != null) return m.invoke(obj);
        } catch (Exception ignored) {}
        return null;
    }

    private Method findGetter(Class<?> c, String name) {
        String g = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() == 0 && (m.getName().equals(name) || m.getName().equals(g))) {
                return m;
            }
        }
        return null;
    }

    private Method findMethod(Class<?> c, String name, int paramCount) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                return m;
            }
        }
        return null;
    }

    private ItemStack fallbackItem(String name, int count) {
        ItemStack stack = new ItemStack(Material.PAPER, Math.min(count, 64));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§7" + name);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ==================== 数据类 ====================

    /** 配方数据 */
    public static class RecipeData {
        public final String id;
        public final String type;
        public final String resultId;
        public final int resultCount;
        /** 每个 Ingredient 只取第一个代表物品（用于 GUI 显示），保证顺序与 ingredientsInUse() 一致 */
        public final List<String> ingredientIds;
        public final List<Integer> ingredientCounts;
        /** 全部原料 ID 展平列表（用于搜索匹配） */
        public final List<String> allIngredientIds;
        /** 有序合成配方 pattern */
        public final String[] shapedPattern;
        /** pattern 字符 → 物品ID 映射 */
        public final Map<String, String> patternKeyIds;
        /** 烹饪时间（ticks），仅熔炉类配方有效 */
        public final int cookingTime;
        /** 经验值，仅熔炉类配方有效 */
        public final float experience;

        public RecipeData(String id, String type, String resultId, int resultCount,
                          List<String> ingredientIds, List<Integer> ingredientCounts,
                          List<String> allIngredientIds,
                          String[] shapedPattern, Map<String, String> patternKeyIds,
                          int cookingTime, float experience) {
            this.id = id != null ? id : "unknown";
            this.type = type != null ? type : "unknown";
            this.resultId = resultId;
            this.resultCount = Math.max(1, resultCount);
            this.ingredientIds = ingredientIds != null ? ingredientIds : List.of();
            this.ingredientCounts = ingredientCounts != null ? ingredientCounts : List.of();
            this.allIngredientIds = allIngredientIds != null ? allIngredientIds : this.ingredientIds;
            this.shapedPattern = shapedPattern;
            this.patternKeyIds = patternKeyIds;
            this.cookingTime = cookingTime;
            this.experience = experience;
        }
    }

    /** 分类元数据 */
    public static class CategoryMeta {
        public final String name;
        public final Material icon;
        public final String description;
        public final int order;

        public CategoryMeta(String name, Material icon, String description, int order) {
            this.name = name;
            this.icon = icon;
            this.description = description;
            this.order = order;
        }
    }
}
