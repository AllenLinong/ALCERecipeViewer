# 🎯 ALCERecipeViewer - CraftEngine 合成表查看器

## 📖 概述

**ALCERecipeViewer** 是一款面向 Minecraft Paper/Spigot 1.21.x 服务器的 GUI 合成表查看插件。它通过反射桥接 **CraftEngine** 的自定义配方系统，将所有 CE 配方分类展示在箱子 GUI 中，支持搜索、分类浏览、配方详情查看，并提供内置的配方编辑器。

插件完全兼容 **Folia** 多线程调度器，所有界面由 `menu.yml` shape 布局系统驱动，显示文本由 `lang.yml` 统一管理。

## 📅 版本历史

### v1.0.0 (2026-06)

**核心功能**：
- 从 CraftEngine 自动加载全部自定义配方，按类型分类
- 三级 GUI 导航：主菜单 → 配方列表 → 配方详情
- 聊天栏搜索（支持中文名 + 英文 ID 匹配）
- A-Z 拼音排序（`Collator.getInstance(Locale.CHINESE)` + 锚点字覆盖全部 Unicode 汉字）
- CE 物品纹理渲染（反射 BuildableItem → NMS → CraftItemStack.asBukkitCopy）
- CE 翻译查找（`TranslationManager.clientLangData() → zh_cn`）
- 配方详情展示（工作台/熔炉/锻造台/切石机/酿造台 GUI）
- 内置配方编辑器（管理员创建 CE 配方，支持 8 种配方类型）
- 配方缓存（YAML 文件 + 内存，减少 CE 反射调用）
- 防刷物品（详情 GUI 全部使用箱子，杜绝 recipe book 按钮漏洞）

**兼容性**：
- 支持 Minecraft 1.21.x（Paper/Spigot/Folia）
- 兼容 CraftEngine v26.5+
- 通过 FoliaLib v0.5.2 实现 Folia/Paper/Spigot 调度器兼容

---

## ✨ 主要特性

| 功能模块 | 描述 |
|---------|------|
| 🔍 **分类浏览** | 8 大配方分类：工作台、熔炉、高炉、烟熏炉、营火、切石机、锻造台、酿造台 |
| 🔎 **智能搜索** | 聊天栏输入搜索词，匹配中文名 + 英文 ID + 原料物品 |
| 📝 **拼音排序** | 中文名按拼音首字母 A-Z 分组排序，英文和数字自动分组 |
| 🖼️ **CE 纹理渲染** | 反射构建 CE 物品完整 NBT，客户端显示原始纹理 |
| 🌐 **CE 翻译查找** | 从 CE `clientLangData` 获取 zh_cn 翻译，兜底 GlobalTranslator + NMS Language |
| 📋 **配方详情** | 工作台（3x3 含 pattern）、熔炉（时间+经验）、锻造台、切石机、酿造台 |
| ✏️ **配方编辑器** | 管理员通过 GUI 创建并保存 CE 配方，支持 8 种类型，自动生成 YAML |
| 💾 **配方缓存** | 首次加载后缓存到 `plugins/ALCERecipeViewer/recipes/`，重启秒开 |
| 🛡️ **防刷物品** | 全部使用箱子 GUI，无原版 recipe book 漏洞 |
| ⚡ **Folia 兼容** | 通过 FoliaLib shade 重定位实现全局/区域/异步调度器兼容 |
| 🏷️ **自定义名称** | `lang.yml custom-names` 支持手动配置 CE 物品中文名 |

---

## 🚀 安装指南

### 📋 前置要求

| 项目 | 要求 |
|------|------|
| **Minecraft 服务器版本** | 1.21.x |
| **Java 版本** | **17+**（推荐 21） |
| **服务器类型** | Paper / Spigot / Folia |
| **必需依赖** | **CraftEngine** v26.5+ |
| **可选依赖** | PlaceholderAPI（暂未集成） |

### 📦 安装步骤

1. 确保服务器已安装 **CraftEngine** 插件
2. 将 `ALCERecipeViewer-1.0.0.jar` 放入 `plugins` 文件夹
3. 重启服务器（或使用 `/alcerecipes reload`）
4. 插件会在启动 5 秒后自动加载 CE 配方（CE 初始化较晚）
5. 输入 `/alcerecipes` 打开配方浏览器

### ⚡ 服务器类型支持

| 服务器类型 | 支持状态 | 性能表现 |
|-----------|---------|---------|
| ✅ **Folia** | 完全支持 | 🚀 最佳性能 |
| ✅ **Paper** | 完全支持 | ⚡ 优秀性能 |
| ✅ **Spigot** | 完全支持 | ⚡ 良好性能 |

---

## ⚙️ 配置说明

### 🔧 主配置文件 (config.yml)

```yaml
# 功能开关
features:
  # 调试模式：点击配方物品时在聊天栏显示名称解析信息
  debug: false
```

### 🖥️ 菜单配置 (menu.yml)

菜单采用 **shape 布局** 系统，用字符定义每个槽位：

- `#` = 背景填充（玻璃板）
- `A-Z` = 功能按钮
- `I` = 动态物品区（配方列表/原料槽）
- `R` = 结果物品槽

#### 主菜单示例

```yaml
main_menu:
  title: "&8ALCE合成表 - 分类浏览"
  shape:
    - "#########"
    - "#A#B#C#D#"
    - "#########"
    - "#E#F#G#H#"
    - "#########"
  buttons:
    A:
      material: "CRAFTING_TABLE"
      name: "&6&l工作台配方"
      lore:
        - "&7有序合成 / 无序合成"
        - "&7共有 &f{count} &7个配方"
        - "&e▶ 点击浏览"
      action: "OPEN_CATEGORY"
      category: "crafting"
```

#### 按钮动作类型

| 动作 | 描述 |
|------|------|
| `OPEN_CATEGORY` | 打开指定配方分类 |
| `PREV_PAGE` / `NEXT_PAGE` | 翻页 |
| `SEARCH` | 聊天栏搜索（Shift+左键清空） |
| `BACK_TO_MAIN` | 返回主菜单 |
| `BACK` | 返回上一级（配方列表） |
| `CLOSE` | 关闭菜单 |

#### CE 物品材质

按钮 `material` 支持 CE 自定义物品 ID：
```yaml
H:
  material: "internal:cooking_info"   # CE 内部物品，自动渲染纹理
  name: "&c&l火焰"
```

### 🌐 语言文件 (lang.yml)

```yaml
# CE 物品自定义中文名（用于搜索和排序）
custom-names:
  "myplugin:custom_sword": "自定义剑"
  "diamond_sword": "钻石剑"

# 配方类型显示名称
recipe-types:
  crafting: "工作台"
  smelting: "熔炉烧炼"

# 分类默认名称
default-categories:
  crafting: "工作台配方"
  smelting: "熔炉烧炼"
```

### ✏️ 新增配方菜单 (recipesmenu.yml)

管理员通过 `/alcerecipes create` 打开配方编辑器，支持：
- 有序合成 / 无序合成
- 熔炉/高炉烧炼（时间+经验）
- 烟熏炉 / 营火
- 锻造台 / 切石机 / 酿造台

---

## 🎮 使用方法

### 👤 玩家指南

1. 输入 `/alcerecipes` 打开合成表主菜单
2. 点击分类按钮浏览对应配方
3. 点击「搜索」按钮在聊天栏输入关键词
4. 点击配方物品查看合成详情
5. 在详情界面按「返回」回到配方列表

### 🔧 命令列表

#### 玩家命令

| 命令 | 权限 | 描述 |
|------|------|------|
| `/alcerecipes` | `alcerecipeviewer.use` | 打开合成表主菜单 |
| `/alcer` | `alcerecipeviewer.use` | 同上（简写） |
| `/alcerecipe` | `alcerecipeviewer.use` | 同上（简写） |
| `/alcebook` | `alcerecipeviewer.use` | 同上（简写） |

#### 管理员命令

| 命令 | 权限 | 描述 |
|------|------|------|
| `/alcerecipes reload` | `alcerecipeviewer.admin` | 重新加载 CE 配方数据 |
| `/alcerecipes clear` | `alcerecipeviewer.admin` | 清空配方缓存 |
| `/alcerecipes create` | `alcerecipeviewer.admin` | 打开配方编辑器 |

#### 权限节点

| 权限 | 默认 | 描述 |
|------|------|------|
| `alcerecipeviewer.use` | true | 使用查看器 |
| `alcerecipeviewer.admin` | op | 管理命令 |

---

## 🔌 技术架构

### 项目结构

```
src/main/java/com/linong/recipelookup/
├── ALCERecipeViewer.java        — 主类，延迟重试加载配方（5s→20s）
├── ConfigManager.java           — 加载 config.yml + lang.yml
├── MenuConfig.java              — Shape 布局解析器（menu.yml + recipesmenu.yml）
├── bridge/
│   └── CEBridge.java            — 反射访问 CE API + 翻译查找
├── gui/
│   └── RecipeGUI.java           — 三级 GUI + 搜索 + A-Z 排序 + 中文名映射
├── listener/
│   ├── GUIListener.java         — 点击/拖拽取消 + 导航逻辑 + 防刷物品
│   └── ChatSearchListener.java  — 聊天栏搜索输入拦截
└── command/
    └── ViewRecipeCommand.java   — /alcerecipes + tab补全
```

### CE 桥接层 (CEBridge.java)

- **Paper 壳穿透**：`Bukkit.getPlugin("CraftEngine")` → 反射 `bootstrap.plugin` → `BukkitCraftEngine`
- **ClassLoader 隔离**：`Class.forName(name, true, ceLoader)` 使用 CE 类加载器
- **只加载 CE 自定义配方**：`RecipeManager.isDataPackRecipe(Key)` 过滤原版
- **物品构建**：`ItemManager.getBuildableItem()` → `buildItem(ItemBuildContext.empty())` → `minecraftItem()` → `CraftItemStack.asBukkitCopy()`
- **翻译查找**：`TranslationManager.clientLangData() → zh_cn`（`clientLangData` ≠ `serverLangData`）

### 搜索系统

`matchItem()` → `toChineseName()` 优先级：
1. `lang.yml custom-names`（手动配置）
2. `bridge.readItemDisplayName()` — 构建物品 → 读 `item_name` DataComponent → CE `clientLangData` 翻译 → GlobalTranslator 兜底
3. 内置 `CN_MAP`（~120 条原版中文名）
4. `formatItemName()` 兜底格式化

### 防刷物品机制

配方详情 GUI 全部使用**箱子**（`Bukkit.createInventory(null, size, title)`），不使用原生工作台/熔炉类型。原因是原生类型自带 recipe book 按钮，点击 recipe book 会绕过 `InventoryClickEvent` 取消逻辑导致物品可被取走。

### Folia 兼容性

通过 **FoliaLib** v0.5.2（shade 重定位到 `com.linong.recipelookup.lib.folialib`）实现：

- `foliaLib.getScheduler().runLaterAsync()` — 延迟异步（配方加载）
- `foliaLib.getScheduler().runNextTick()` — 下一 tick（GUI 切换）
- `plugin.yml` 声明 `folia-supported: true`

FoliaLib 自动检测服务器类型，在 Folia 上使用区域调度器，在 Paper/Spigot 上使用 Bukkit 调度器。

---

## 🛠️ 配方缓存机制

1. 启动时优先从 `plugins/ALCERecipeViewer/recipes/*.yml` 加载缓存
2. 缓存不存在则从 CE 反射加载，并自动保存缓存
3. `/alcerecipes reload` 强制从 CE 重新加载并更新缓存
4. `/alcerecipes clear` 清空内存 + 文件缓存

---

## 🐛 故障排除

### 插件无法启动

- 检查 Java 版本是否为 17+
- 检查 CraftEngine 是否已安装且启用
- 查看控制台错误信息（CE 桥接失败会有 warning）

### 配方显示为空

- CE 配方数据初始化较晚（~5-8秒），插件会延迟重试加载
- 等待几秒后使用 `/alcerecipes reload` 手动加载
- 检查 CE 是否有自定义配方

### 搜索不到物品

- 检查 `lang.yml custom-names` 是否添加了 CE 物品中文名
- 启用 `config.yml features.debug: true` 查看点击时的名称解析
- CE 翻译文件可能未正确加载，查看控制台 `[CE翻译]` 日志

### CE 物品显示为纸张

- CE 纹理渲染依赖 CraftItemStack 转换器，检查控制台是否有 warning
- 确认 CE 版本兼容（v26.5+）

---

## 📈 性能优化

- **配方缓存**：避免每次启动反射扫描全部 CE 配方
- **翻译缓存**：`itemNameCache` / `displayNameCache` / `ceTranslations` 三级缓存
- **ClassLoader 复用**：反射 Class 对象缓存，避免重复 `Class.forName`
- **异步加载**：配方获取全部在异步线程执行
- **搜索缓存**：成功结果缓存，减少重复查询

---

## 🤝 社区支持

### 📚 相关链接

- **GitHub 仓库**: https://github.com/AllenLinong/ALCERecipeViewer
- **问题反馈**: 在 GitHub 提交 Issue

---

## 📄 许可证

本项目采用 MIT 许可证。

---

**ALCERecipeViewer** - 让 CE 配方一目了然！ 🎉
