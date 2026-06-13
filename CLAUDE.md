# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build the plugin JAR (skip tests)
mvn clean package -DskipTests
# Output: target/ALCERecipeViewer-1.0.0.jar

# Deploy to test server
cp target/ALCERecipeViewer-1.0.0.jar /path/to/server/plugins/
```

- **Java 17+** required (recommended 21), **Maven 3.6+**
- Only compile-scope dependency is **FoliaLib** (shade-relocated to `com.linong.recipelookup.lib.folialib`). All others are `provided` by the server runtime.

## Project Overview

A Minecraft **Paper/Spigot 1.21.x** plugin (Folia-compatible) that acts as a graphical recipe viewer for **CraftEngine** custom recipes. It reads CE recipes via reflection and displays them in chest GUIs with a three-tier navigation system. All menu layouts are driven by `menu.yml` shape definitions; all display text comes from `lang.yml`.

### Source files (8 total)

```
src/main/java/com/linong/recipelookup/
├── ALCERecipeViewer.java         — Plugin main class; delayed retry loading (5s→20s); recipe caching
├── ConfigManager.java            — Loads config.yml + lang.yml; all user-facing text getters
├── MenuConfig.java               — Shape layout parser (menu.yml + recipesmenu.yml)
├── bridge/
│   └── CEBridge.java             — Reflection-based CE API access + translation lookup + item building
├── gui/
│   └── RecipeGUI.java            — 3-tier GUI (main→list→detail) + search + A-Z sort + recipe creator
├── listener/
│   ├── GUIListener.java          — Click/drag cancellation + navigation routing + anti-dupe
│   └── ChatSearchListener.java   — Chat input interception for search + creator exp input
└── command/
    └── ViewRecipeCommand.java    — /alcerecipes command + tab completion
```

## Three-Tier GUI Navigation

```
Main Menu (TYPE_MAIN) → Recipe List (TYPE_LIST) → Recipe Detail (TYPE_DETAIL)
       ↑                        ↑                        │
       └────────────────────────┴──── BACK ──────────────┘
```

- **Main**: 8 category buttons (A-H), each with `OPEN_CATEGORY` action + `category` field. Counts shown via `{count}` variable.
- **List**: Dynamic `I` slots filled from `getSortedRecipes()`. Navigation: PREV_PAGE, NEXT_PAGE, SEARCH, BACK_TO_MAIN. Clicking an `I` slot opens the recipe detail.
- **Detail**: Uses chest inventory (never native workbench/furnace types) to prevent recipe book duping. Only navigation is BACK → recipe list.

**Critical rule**: Both display and click handling for `I` slots MUST use the same sorted list from `getSortedRecipes()`. The click handler calculates `recipeIdx = page * pageSize + itemIndex` against this sorted list.

## Shape Menu System (`MenuConfig.java` + `menu.yml`)

- `#` = background (glass pane), `A-Z` = action buttons, `I` = dynamic item area, `R` = result slot
- Each button defines: `material` (Minecraft material or `namespace:id` for CE items), `name`, `lore`, `action`, `category`
- CE item materials (containing `:`) trigger `buildButtonOrCE()` → `CEBridge.buildItemStack()` for full NBT texture rendering
- Color codes use `&` format, converted by `ChatColor.translateAlternateColorCodes`
- Title color translation happens in `MenuConfig.parseMenu()`
- Menus are parsed once at load into `MenuDef` records; `buttonAt(MenuDef, slot)` resolves clicks

### Actions

| Action | Context | Behavior |
|--------|---------|----------|
| `OPEN_CATEGORY` | Main menu | Opens recipe list for `category` |
| `PREV_PAGE` / `NEXT_PAGE` | List | Page navigation |
| `SEARCH` | List | Left-click: chat search; Shift+click: clear search |
| `BACK_TO_MAIN` | List | Returns to main menu |
| `BACK` | Detail | Returns to recipe list (preserving page) |
| `CLOSE` | Any | Closes inventory |

## CE Bridge (`CEBridge.java`) — Reflection Architecture

### Paper shell penetration
`Bukkit.getPlugin("CraftEngine")` returns `PaperCraftEnginePlugin` (a shell). To reach the real `BukkitCraftEngine`:
```
PaperCraftEnginePlugin → reflect "bootstrap" field → Bootstrap → reflect "plugin" field → BukkitCraftEngine
```
The real instance's ClassLoader (`ceLoader`) is used for all `Class.forName(name, true, ceLoader)` calls.

### Key reflection chains
- **Load recipes**: `RecipeManager.recipesByType(RecipeType)` → filter with `isDataPackRecipe(Key)` (excludes vanilla datapack recipes)
- **Recipe type ID**: Use `RecipeType.id()` method (string), NOT `toString()` — more reliable
- **Build CE items**: `ItemManager.getBuildableItem(Key)` → `BuildableItem.buildItem(ItemBuildContext.empty(), count)` → `Item.minecraftItem()` → `CraftItemStack.asBukkitCopy(NMS ItemStack)`
- **Translations**: `TranslationManager.instance().clientLangData()` → get `zh_cn` → `translations` field. `clientLangData` ≠ `serverLangData` — item translations are in the former only.

### Item ID resolution
`buildItemStack(itemId, count)`:
1. `minecraft:xxx` → direct `new ItemStack(Material, count)`
2. CE custom (`namespace:value`) → full reflection build chain for NBT texture preservation
3. Fallback: `resolveMaterial()` via `ItemDefinition.material()` → Material enum

## Search & Name Resolution

`toChineseName(itemId)` priority chain:
1. `lang.yml custom-names` (manual overrides)
2. `bridge.readItemDisplayName()` — builds item → reads `item_name` DataComponent → CE `clientLangData` translation → GlobalTranslator → NMS Language fallback
3. Built-in `CN_MAP` (~120 vanilla Chinese names)
4. `formatItemName()` — `underscore_name` → `Underscore Name`

Search matches **display names only** (not `namespace:id`). Name cache stores only successful results — nulls are not cached because CE translations may load late.

## A-Z Pinyin Sorting

`sortGroup()` uses `Collator.getInstance(Locale.CHINESE)` with anchor characters covering all Unicode CJK range (U+4E00–U+9FFF). Each anchor character maps to a pinyin initial (啊→A, 巴→B, etc.). Non-Chinese characters are grouped as: A-Z, a-z, 0-9, or `#`. Recipes are sorted by `CN_COLLATOR` comparator applied to `toChineseName(resultId)`.

## Anti-Dupe Mechanism

All detail GUIs use **chest inventories** (`Bukkit.createInventory(null, size, title)`), never native workbench/furnace/smithing types. Native types have a recipe book button whose click bypasses `InventoryClickEvent` cancellation, allowing players to take items. Chest GUIs have no recipe book.

## Click Routing (`GUIListener.java`)

- `onInventoryClick`: Checks `getRawSlot()` vs `topInventory.getSize()` to distinguish GUI slots from player inventory. Player inventory clicks pass through.
- **Detail**: Always cancelled. Only `BACK` action button is handled.
- **Creator**: Only buttons with non-empty `action` are cancelled; `I`/`R` dynamic slots pass through for native Paper drag handling.
- **Main/List**: All top-inventory clicks cancelled. `I` slot clicks calculate recipe index from sorted list. Navigation buttons dispatch by `action` string.
- `onInventoryDrag`: For creator GUI, checks each raw slot — if any touches a non-dynamic button, cancels the entire drag. For browse GUIs, cancels if any raw slot is in the top inventory.
- `onInventoryClose`: Detail → reopens recipe list; otherwise → `removePlayer()` cleanup.

## Recipe Creator (Admin)

`/alcerecipes create` → `recipe_creator_type` menu → select type → type-specific creator GUI.

Creator GUIs have native drag support for `I` (ingredient) and `R` (result) slots. Time/exp controls use left-click (+1) / right-click (-1). Shift+left-click on exp button triggers chat input mode.

Saves recipes as YAML to `plugins/CraftEngine/resources/alcerecipeviewer/configuration/custom_recipes.yml` in CE's expected format. After saving, admin must run `ce reload all` then `/alcerecipes clear && /alcerecipes reload`.

## Recipe Caching

- On first load, recipes saved to `plugins/ALCERecipeViewer/recipes/<typeId>.yml`
- Subsequent starts: load from cache first; if empty, fall back to CE reflection load
- `/alcerecipes reload` — force re-load from CE, update cache
- `/alcerecipes clear` — wipe memory + file cache

## Folia Compatibility

Uses **FoliaLib** v0.5.2 (shade-relocated to `com.linong.recipelookup.lib.folialib`). Scheduler calls:
- `runLaterAsync(task, ticks)` — delayed async (recipe loading)
- `runNextTick(task)` — next tick (GUI transitions, reopen after close)
- `plugin.yml` declares `folia-supported: true`

## Config Files

| File | Purpose |
|------|---------|
| `config.yml` | Feature toggles (`features.debug`) |
| `lang.yml` | All user-facing text, custom item names, recipe type names, search messages |
| `menu.yml` | Shape layouts for all browse GUIs (main, list, 5 detail types) |
| `recipesmenu.yml` | Shape layouts for creator GUIs (merged into MenuConfig at load) |
| `plugin.yml` | Plugin metadata, commands, permissions |

## Key Data Classes

- `CEBridge.RecipeData` — immutable recipe snapshot (id, type, resultId, resultCount, ingredientIds, ingredientCounts, allIngredientIds, shapedPattern, patternKeyIds, cookingTime, experience)
- `CEBridge.CategoryMeta` — category display metadata (name, icon Material, description, sort order)
- `MenuConfig.MenuDef` — parsed menu (title, shape[], Map<Character, ButtonDef>)
- `MenuConfig.ButtonDef` — button definition (material, name, lore, action, category, dynamic, ceItem)
