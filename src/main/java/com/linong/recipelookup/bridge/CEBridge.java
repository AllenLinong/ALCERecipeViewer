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
                logger.info("CraftEngine v" + cePlugin.getDescription().getVersion() + " 桥接就绪（反射模式）");
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
                logger.info("CE Paper壳检测到，已穿透到: " + actualPlugin.getClass().getSimpleName());
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
                    // 日志：每种类型前 3 个配方的 resultId
                    List<String> samples = list.stream().limit(3)
                            .map(r -> r.id + "→" + r.resultId).toList();
                    logger.info("[配方加载] " + typeId + ": " + list.size() + "个 " + samples);
                }
            } catch (Exception e) {
                logger.info("加载配方类型失败: " + recipeType + " - " + e.getMessage());
            }
        }

        int total = result.values().stream().mapToInt(List::size).sum();
        logger.info("从 CraftEngine 加载了 " + total + " 个配方 (" + result.size() + " 种类型)");
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

    /** CE 物品名缓存：itemId → 名称 */
    private final Map<String, String> itemNameCache = new LinkedHashMap<>();
    /** item_name 组件读取缓存 */
    private final Map<String, String> displayNameCache = new LinkedHashMap<>();

    /**
     * 读取物品 item_name 组件的纯文本显示名。
     * 对所有物品有效（CE + 原版），返回客户端实际显示的名称。
     */
    public String readItemDisplayName(String itemId) {
        if (itemId == null) return null;
        String cached = displayNameCache.get(itemId);
        if (cached != null) return cached.isEmpty() ? null : cached;

        // CE 物品：直接用 ItemDefinition.translationKey() 查 clientLangData
        if (itemId.contains(":") && !itemId.startsWith("minecraft:")) {
            String ceName = resolveViaItemDefinition(itemId);
            if (ceName != null) { displayNameCache.put(itemId, ceName); return ceName; }
        }

        ItemStack stack = buildItemStack(itemId, 1);
        Object component = getItemNameComponent(stack);

        if (component != null) {
            // 1. CE 的 TranslationManager（CE 物品翻译）
            String key = getTranslationKey(component);
            if (key != null) {
                String ceName = resolveViaTranslationManager(key);
                if (ceName != null) { displayNameCache.put(itemId, ceName); return ceName; }
            }
            // 2. GlobalTranslator（服务端 Adventure 翻译）
            try {
                net.kyori.adventure.text.Component rendered =
                        net.kyori.adventure.translation.GlobalTranslator.render(
                                (net.kyori.adventure.text.Component) component,
                                java.util.Locale.SIMPLIFIED_CHINESE);
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(rendered);
                if (plain != null && !plain.isEmpty()) { displayNameCache.put(itemId, plain); return plain; }
            } catch (Exception ignored) {}
        }

        // 3. 原版物品：用 Minecraft 内置翻译（服务端语言）
        Material mat = stack.getType();
        if (mat != Material.AIR && mat != Material.PAPER) {
            String name = getVanillaTranslation(mat);
            if (name != null && !name.isEmpty()) { displayNameCache.put(itemId, name); return name; }
        }

        displayNameCache.put(itemId, "");
        return null;
    }

    /** 通过 CE ItemDefinition.translationKey() 查 clientLangData */
    private String resolveViaItemDefinition(String itemId) {
        try {
            String[] parts = itemId.split(":", 2);
            if (parts.length != 2) return null;
            // 构造 translation key: item.<namespace>.<value>
            String key = "item." + parts[0] + "." + parts[1];
            return resolveViaTranslationManager(key);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 CE 的 clientLangData（物品翻译）查找（首次调用后缓存 TM 引用） */
    private Object cachedTranslationManager;

    private String resolveViaTranslationManager(String key) {
        try {
            if (cachedTranslationManager == null) {
                Class<?> tmClass = Class.forName(
                        "net.momirealms.craftengine.core.plugin.locale.TranslationManager", true, ceLoader);
                cachedTranslationManager = tmClass.getMethod("instance").invoke(null);
                @SuppressWarnings("unchecked")
                Map<String, Object> clientData = (Map<String, Object>) tmClass
                        .getMethod("clientLangData").invoke(cachedTranslationManager);
                // 一次性加载所有 zh_cn 翻译到 ceTranslations
                Object langData = clientData.get("zh_cn");
                if (langData != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> translations = (Map<String, String>) langData.getClass()
                            .getField("translations").get(langData);
                    ceTranslations.putAll(translations);
                    logger.info("[CE翻译] 加载 " + ceTranslations.size() + " 条 zh_cn 翻译");
                }
            }
            return ceTranslations.get(key);
        } catch (Exception e) {
            logger.warning("[CE翻译] 失败: " + e.getMessage());
            return null;
        }
    }
    /** CE 翻译缓存：translationKey → zh_cn 文本 */
    private Map<String, String> ceTranslations = new LinkedHashMap<>();
    private boolean ceTranslationsLoaded;

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

    /** 加载 CE 的 zh_cn.yml 翻译文件（从 CE JAR 或数据文件夹） */
    /** 从 CE 内存翻译系统加载 zh_cn（反射 TranslationManager，不需要文件系统） */
    private void loadCETranslations() {
        if (ceTranslationsLoaded) return;
        ceTranslationsLoaded = true;
        ceTranslations = new LinkedHashMap<>();
        if (!available) return;

        // 反射 CE TranslationManager.clientLangData()
        try {
            Class<?> ceMain = Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine", true, ceLoader);
            Object ceInstance = ceMain.getMethod("instance").invoke(null);
            Object tm = ceMain.getMethod("translationManager").invoke(ceInstance);
            @SuppressWarnings("unchecked")
            Map<String, Object> clientData = (Map<String, Object>) tm.getClass()
                    .getMethod("clientLangData").invoke(tm);
            logger.info("[CE翻译] TranslationManager 获取成功，可用语言: " + clientData.keySet());
            Object zhCN = clientData.get("zh_cn");
            if (zhCN != null) {
                @SuppressWarnings("unchecked")
                Map<String, String> translations = (Map<String, String>) zhCN.getClass()
                        .getField("translations").get(zhCN);
                ceTranslations.putAll(translations);
                logger.info("[CE翻译] 内存加载 " + ceTranslations.size() + " 条 (TranslationManager)");
                // 打印前3个样例
                int count = 0;
                for (Map.Entry<String, String> e : ceTranslations.entrySet()) {
                    if (count++ < 3) logger.info("[CE翻译] 样例: " + e.getKey() + " → " + e.getValue());
                }
                return;
            } else {
                logger.warning("[CE翻译] zh_cn 数据为 null！可用语言: " + clientData.keySet());
            }
        } catch (Exception e) {
            logger.warning("[CE翻译] 反射 TranslationManager 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 回退：文件系统搜索
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
            // CE 的资源包内容可能解压到 contents/ 或直接放在 dataFolder 下
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
            logger.info("从 CE 加载了 " + ceTranslations.size() + " 条物品翻译");
        } catch (Exception e) {
            logger.warning("解析 CE 翻译文件失败: " + e.getMessage());
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
