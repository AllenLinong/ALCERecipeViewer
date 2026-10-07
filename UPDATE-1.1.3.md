# 1.1.3 更新说明

## 新增：配方排序菜单（管理员）

```
/alcerecipes admin order            打开排序分类选择（复用主菜单布局）
/alcerecipes admin order <分类ID>   直接打开该分类的排序列表（支持 Tab 补全）
```

- 排序列表的内容与普通玩家看到的**完全一致**：已隐藏的配方不显示、多配方去重、CE 分类排序 + 自定义顺序，管理员所见即玩家所得。
- **左键点击物品 = 上移一位，右键 = 下移一位**，调整即时保存到 `recipe_order.yml` 并同步到所有玩家菜单。
- **Shift+左键 / Shift+右键**：在聊天栏输入格数，一次前进/后退多格（输入 `cancel` 取消，支持负数反向），移动后菜单自动跳到物品落地的页面。

## 其他

- 管理列表（`/alcerecipes admin`）恢复为单纯左键切换显示/隐藏，不再占用 Shift 键。
- `menu.yml` 配置版本升级到 v3：旧配置自动备份为 `.bak` 并补入新的 `order_list` 菜单节（带注释），自定义修改全部保留，无需手动改配置。
- 跨服务器同步排序：复制 `plugins/ALCERecipeViewer/recipe_order.yml`（自定义顺序）与 `hidden_recipes.yml`（隐藏列表）到目标服务器，执行 `/alcerecipes reload` 生效。
