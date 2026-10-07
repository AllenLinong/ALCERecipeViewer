# 1.1.3 更新说明

## 排序菜单 lore 修正

- Shift 多格移动的两行提示改为 `⇧↑ Shift+左键: 输入前进格数` / `⇧↓ Shift+右键: 输入后退格数`，上下方向一目了然（原来两行都是 `⇧`，看起来像两个"上"）。
- 已运行过旧版的服务器需手动更新 `lang/zh_cn.yml` 中 `order.move_jump_up` / `order.move_jump_down` 两行（或删除后 `/alcerecipes reload` 自动补入新写法）。

## 移除排序配置导入导出功能

- 删除 `admin order export / import` 分享码及全部相关实现（跨服同步改回后台复制文件方式）。
- 跨服同步排序：复制 `plugins/ALCERecipeViewer/recipe_order.yml`（自定义顺序）与 `hidden_recipes.yml`（隐藏列表）到目标服，执行 `/alcerecipes reload` 即可。
