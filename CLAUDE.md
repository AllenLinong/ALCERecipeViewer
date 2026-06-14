# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
"/c/maven/apache-maven-3.9.9/bin/mvn" clean package -DskipTests
# Output: target/ALCERecipeViewer-1.0.1.jar
```

- **Java 17+** required (Java 21 at `D:\JAVA21`), **Maven 3.9.9** at `C:\maven\apache-maven-3.9.9`
- Git Bash PATH 不包含 mvn，必须使用绝对路径 `"/c/maven/apache-maven-3.9.9/bin/mvn"`
- Only compile-scope dependency is **FoliaLib** (shade-relocated to `com.linong.recipelookup.lib.folialib`). All others are `provided`.

## Project Overview

Minecraft **Paper/Spigot 1.21.x** plugin (Folia-compatible) — graphical recipe viewer for **CraftEngine** custom recipes. Reads CE recipes via reflection, displays in chest GUIs with three-tier navigation.

## Architecture

```
ACLERecipeViewer.java          — Plugin lifecycle, recipe loading/caching
ConfigManager.java             — config.yml + language file loading
MenuConfig.java                — Shape menu parser + button builder with {@key} lang resolution
bridge/CEBridge.java           — Reflection-based CE API: recipes, item building, translations, name resolution
gui/RecipeGUI.java             — 3-tier GUI, search, sort, recipe creator
listener/GUIListener.java      — Click/drag routing, anti-dupe
listener/ChatSearchListener.java — Chat-based search
command/ViewRecipeCommand.java — /alcerecipes command
```

## Language System

- `config.yml` → `language: zh_cn` (or `en_us`)
- `lang/zh_cn.yml` and `lang/en_us.yml` — all UI text including `menu:` node for menu text
- `RecipeGUI.resolveLocale()` → returns `Locale.SIMPLIFIED_CHINESE` or `Locale.US`
- All locale resolution goes through this one method

### Menu i18n

Menu YML files (`menu.yml`, `recipesmenu.yml`) use `{@key}` references that resolve to `lang.yml` → `menu.key`:
```yaml
name: "{@category_crafting}"
lore:
  - "{@recipe_count}"
```
`MenuConfig.resolveLang()` handles `{@key}` → language file lookup. Resolution order: `{@key}` first, then `{var}` replacement.

### Recipe cache per-language names

Recipe cache YML stores names per language: `name_zh_cn`, `name_en_us`. On load, reads the name matching current config language into `displayNameCache`.

## Name Resolution Chain

`RecipeGUI.toChineseName(itemId, locale)`:
1. `lang.yml custom-names`
2. CN_MAP (zh only)
3. `bridge.readItemDisplayName()`:
   - `readCEItemHoverName()`:
     - **Fast path**: `ItemDefinition.processors()` → `ItemNameProcessor.itemName()` → resolve `<l10n:key>` / `<lang:key>` tags via `ceTranslations` (looks up `en_us:key` → `en:key` → `key`)
     - **Slow path** (only if fast path fails): `buildItem()` → `hoverNameComponent()` / NMS `getHoverName()` → serialized via `serializeAdventureComponent()` (handles CE-shaded Adventure)
   - Component-based: `getItemNameComponent()` → CE translations → vanilla zh_cn → GlobalTranslator
   - NMS Language only for **vanilla items** (CE items skip to avoid wrong Material names)
4. `formatItemName()` — `underscore_name` → `Underscore Name`

## CE Translation Loading

`ensureCETranslationsLoaded()`:
1. `TranslationManager.clientLangData()` → zh_cn translations
2. `loadCETranslationsFromFile()` — file system fallback
3. `loadPackTranslationsYml()` — parses `configuration/translations.yml` from ALL CE resource packs, stores as `locale:key` (e.g. `en:item.farmersdelight.cooking_pot`)

## Search & Sorting

- Search matches **result item name only** (not ingredients)
- Sort: pre-computes all names into HashMap (O(N)), then sorts with Collator
- `playerRecipes` map caches sorted list per player; GUIListener uses cached list on click

## Recipe Creator

- `/alcerecipes create` → admin GUI for creating CE recipes
- Furnace mode switching (blast/furnace/both) with `creatorFurnaceMode` map
- File I/O is async via `FoliaLib.runAsync()` to avoid main thread blocking
- Saves to `plugins/CraftEngine/resources/alcerecipeviewer/configuration/`

## Recipe Caching

- Recipes cached at `plugins/ALCERecipeViewer/recipes/<typeId>.yml`
- On reload: clears caches → loads from CE → pre-warms all item names → saves recipes + per-language names
- `/alcerecipes clear` → wipes memory + file cache

## Anti-Dupe

All detail GUIs use **chest inventories** (`Bukkit.createInventory(null, size, title)`), never native workbench/furnace types which have recipe books that bypass click cancellation.

## FoliaLib Scheduler

- `runLaterAsync(task, ticks)` — delayed async
- `runNextTick(task)` — next tick (GUI transitions)
- `runAsync(task) ` — immediate async (file I/O)

## Key Data Classes

- `CEBridge.RecipeData` — recipe snapshot (id, type, resultId, resultCount, ingredientIds, ingredientCounts, allIngredientIds, shapedPattern, patternKeyIds, cookingTime, experience)
- `MenuConfig.MenuDef` — parsed menu (title, shape[], Map<Character, ButtonDef>)
- `MenuConfig.ButtonDef` — button definition (material, name, lore, action, category, dynamic, ceItem)
