# 1.1.2 更新说明

配方排序改为独立的排序菜单（`/alcerecipes admin order`），不再占用管理列表的 Shift 点击；新增聊天输入多格移动。

## 新指令：/alcerecipes admin order（管理员）

```
/alcerecipes admin order            打开排序分类选择（复用主菜单布局）
/alcerecipes admin order <分类ID>   直接打开该分类的排序列表（支持 Tab 补全）
```

- 排序列表的内容与普通玩家看到的**完全一致**：已隐藏的配方不显示、多配方去重、CE 分类排序 + 自定义顺序，管理员所见即玩家所得。
- **左键点击物品 = 上移一位，右键 = 下移一位**，调整即时保存到 `recipe_order.yml` 并同步到所有玩家菜单。
- 每个物品的 lore 标注了当前位置与操作提示；菜单底部带说明按钮与翻页按钮。
- 同义写法：`/alcer admin order`、`/alcerecipes manage order`、`/alcerecipes admin sort`。

## 排序菜单：输入格数一次移动多格

- **Shift+左键**物品 → 聊天栏输入数字 N，一次前进（上移）N 格；**Shift+右键** → 后退（下移）N 格。
- 输入 `cancel` 取消；支持负数反向；移动后菜单自动跳到物品落地的页并提示新位置。

## 跨服务器同步排序配置

直接复制文件即可（两台服务器 CraftEngine 配方包一致时效果完全相同）：

1. 复制 A 服 `plugins/ALCERecipeViewer/recipe_order.yml`（自定义顺序）
   和 `hidden_recipes.yml`（隐藏列表）到 B 服同名位置；
2. B 服执行 `/alcerecipes reload`（或重启）生效。

## 行为变化

- 移除了管理列表（`/alcerecipes admin`）里 Shift+左/右键调整顺序的功能，管理列表恢复为单纯左键切换显示/隐藏。
- 排序持久化逻辑重写：直接以「玩家可见列表」的完整顺序保存，移动操作不会与隐藏配方错位交换；未排序的分类仍按 CE 分类顺序显示。
- 菜单动作新增 `open: order_main`（打开排序主菜单）；`open_category` 在排序主菜单中打开排序列表，`back` / `back_to_main` 在排序列表中返回排序主菜单。

## 菜单配置自动升级（menu.yml → config_version: 3）

- 服务器更新插件后首次启动自动迁移：
  - 旧 `menu.yml` 备份为 `menu.yml.bak`；
  - 新增 `order_list` 菜单节（连同注释）自动补入，自定义修改过的键一律保留原样；
  - `recipesmenu.yml` 版本不变，不产生多余备份。
- 语言文件（`lang/zh_cn.yml` / `lang/en_us.yml`）新增的排序菜单文本同样自动补入。
