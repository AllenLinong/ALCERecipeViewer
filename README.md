# [1.21.x][Folia] ALCERecipeViewer —— CraftEngine 合成表 GUI 查看器 | 分类浏览 | 配方编辑 | 中文搜索

> **让 CE 自定义配方一目了然！**  


---

## 📖 插件简介

**ALCERecipeViewer** 是一款专为 **CraftEngine** 设计的合成表 GUI 查看插件，通过反射桥接 CE 自定义配方系统，将全部 CE 配方按 8 大分类展示在箱子 GUI 中。

玩家无需记忆复杂指令，只需 `/alcerecipes` 即可直观浏览所有自定义合成配方，支持中文搜索、A-Z 拼音排序、CE 物品纹理渲染，管理员更可通过内置编辑器直接创建 CE 配方。

---

## ✨ 主要特性

| 功能 | 描述 |
|------|------|
| 🔍 **8 大分类浏览** | 工作台、熔炉、高炉、烟熏炉、营火、切石机、锻造台、酿造台 |
| 🔎 **智能搜索** | 聊天栏输入关键词，同时匹配中文名 + 英文 ID + 原料物品 |
| 📝 **拼音排序** | 中文名按拼音首字母 A-Z 分组，英文数字自动归类 |
| 🖼️ **CE 纹理渲染** | 通过反射构建物品完整 NBT，客户端显示 CE 物品原始纹理 |
| 🌐 **翻译查找** | 自动读取 CE `clientLangData` 获取 zh_cn 翻译，原生物品兜底 |
| 📋 **配方详情** | 工作台（3×3 pattern）、熔炉（时间+经验）、锻造台、切石机、酿造台 |
| ✏️ **内置配方编辑器** | 管理员通过 GUI 可视化创建 CE 配方，支持 8 种配方类型 |
| 💾 **配方缓存** | 首次加载后缓存到本地，重启秒开 |
| 🛡️ **防刷物品** | 全部使用箱子 GUI，无原版 recipe book 漏洞 |
| ⚡ **Folia 兼容** | FoliaLib shade 重定位，全局/区域/异步调度器全兼容 |
| 🏷️ **自定义名称** | `lang.yml` 支持手动配置 CE 物品中文名用于搜索 |
| 🧩 **自定义菜单按钮** | `menu.yml` 支持按点击类型执行玩家、临时 OP 或控制台命令，并支持多命令与延时动作 |
| 👁️ **配方可见性管理** | 管理员可单独隐藏配方，普通玩家不会在浏览或搜索结果中看到 |

---

## 🚀 安装方法

### 前置要求

| 项目 | 要求 |
|------|------|
| Minecraft 版本 | **1.21.x**（Paper / Spigot / Folia） |
| Java 版本 | **21+** |
| 必需前置 | **CraftEngine** v26.5+ |

### 安装步骤

1. 确保服务器已安装 **CraftEngine** 插件
2. 将 `ALCERecipeViewer-1.0.0.jar` 放入 `plugins` 文件夹
3. 重启服务器（或输入 `/alcerecipes reload`）
4. 插件启动 5 秒后自动加载 CE 配方（CE 初始化较晚）
5. 输入 `/alcerecipes` 打开配方浏览器

---

## 🎮 使用方法

### 玩家命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/alcerecipes` | `alcerecipeviewer.use` | 打开合成表主菜单 |
| `/alcer` | 同上 | 简写 |
| `/alcerecipe` | 同上 | 简写 |
| `/alcebook` | 同上 | 简写 |

### 管理员命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/alcerecipes reload` | `alcerecipeviewer.admin` | 重新加载 CE 配方数据 |
| `/alcerecipes clear` | `alcerecipeviewer.admin` | 清空配方缓存 |
| `/alcerecipes create` | `alcerecipeviewer.admin` | 打开配方编辑器（GUI） |
| `/alcerecipes admin` / `manage` | `alcerecipeviewer.admin` | 打开配方可见性管理菜单 |

### 权限节点

| 权限 | 默认 | 说明 |
|------|------|------|
| `alcerecipeviewer.use` | 所有玩家 | 使用合成表查看器 |
| `alcerecipeviewer.admin` | OP | 管理命令 + 配方编辑器 |

---

## ⚙️ 配置说明

### `config.yml` —— 主配置

```yaml
features:
  debug: false  # 调试模式：点击配方显示名称解析信息
```

---

## 📝 更新日志

### v1.0.7

- **配方可见性管理**：新增管理员菜单，可显示/隐藏指定配方；隐藏状态保存至 `hidden_recipes.yml`，重启与 `/alcerecipes reload` 后仍会保留。
- **管理员浏览**：`/alcerecipes admin`（或 `manage`）可查看全部配方及各分类隐藏数量；点击配方即可切换可见性并刷新页面。
- **玩家配方过滤**：被隐藏的配方不会出现在普通玩家的分类浏览或搜索结果中；管理员仍可查看全部配方。
- **CraftEngine 兼容性**：改进核心实例识别与 CE 类加载，兼容官方核心、Paper bootstrap 和 Bukkit/Spigot 插件外壳。
- **CraftEngine 重载同步**：支持 CE 26.7 的重载事件；CE 完成加载或重载后自动刷新配方、翻译及名称缓存，并避免并发刷新覆盖数据。
- **多语言与错误处理**：名称缓存按“语言 + 物品 ID”隔离，改善中英文切换；物品转换失败时减少重复日志并提供更准确的降级处理。
