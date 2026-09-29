# 1.1.0 更新说明

菜单动作体系重构（参照 ALFriends 的 triggers 系统），另含主菜单计数修复与菜单配置自动升级。

## 主菜单配方计数

- 普通主菜单的 `{count}` 现在只统计**未隐藏**的配方，与玩家实际能浏览到的数量一致（例：40 个配方隐藏 5 个 → 显示 35）。
- 管理端主菜单保持显示总数并附带「已隐藏: N 个配方」，便于管理。

## 菜单配置自动升级（config_version）

- `menu.yml` / `recipesmenu.yml` 新增 `config_version: 2`。
- 服务器更新插件后首次启动时自动迁移旧版本配置：
  - 旧文件先备份为 `menu.yml.bak` / `recipesmenu.yml.bak`；
  - 只把新版本缺失的键补进去（**连同注释**），自定义修改过的键一律保留原样；
  - 旧版 `action:` 写法继续兼容（自动映射为 triggers），无需手动改配置。

## 菜单配置（menu.yml / recipesmenu.yml）

- 按钮行为统一改为 `triggers` 动作列表写法，按点击类型（`left` / `right` / `shift_left` / `shift_right`）分发，列表内按顺序执行：

  ```yaml
  triggers:
    left:
      - "sound: block.note_block.pling"
      - "open_category: crafting"
    right:
      - "command: wiki open"
    shift_left:
      - "delay: 10"
      - "console: tell {player} done"
  ```

- 通用动作：`close`、`sound: 名-音量-音调`、`command:`/`op:`/`console:`、`message:`、`open: main|admin_main|creator_type`、`delay:`/`wait:`（支持 `20`、`20t`、`1s`、`500ms`，多条累加）。
- 业务动作改为小写下划线风格：`open_category: <分类>`、`prev_page`、`next_page`、`search` / `search_mode` / `search_clear`、`back`、`back_to_main`、`create_recipe`、`prev_recipe` / `next_recipe`、`open_creator: <类型>`、`creator_adjust: P|G|Y|E`、`creator_mode`、`creator_exp_input`、`save_recipe`。
- **旧版单值 `action: OPEN_CATEGORY` 等仍然兼容**（自动映射为新动作）；同时配置 `action` 与 `triggers` 时以 `triggers` 为准。
- 命令类动作沿用分号串多条 + 可省略开头 `/`；占位符 `{player}`/`%player_name%`、`{uuid}`/`%player_uuid%`、`{world}`/`%world%` 不变。

## 新增配置（config.yml）

```yaml
features:
  button-cooldown-seconds: 0.3   # 按钮点击冷却，防连点，0 = 关闭
  default-button-sound: block.note_block.pling
```

## 行为变化

- 按钮动作在渲染时写入物品 PDC，点击路径零配置查询；单个动作异常只记日志，不再中断同序列后续动作。
- 新增按钮点击冷却（默认 0.3s，可配置）。
- `sound:` 按钮配置支持 `名称-音量-音调` 与分号串多个；动作列表自带 `sound:` 时不再叠加按钮默认音。
- 纯装饰按钮（背景玻璃等）不再播默认点击音。
- 无效音效名/未知动作类型只在控制台警告一次。

## 内部重构

- 新增 `gui/MenuActionParser`（动作串解析，delay 累计 + 溢出钳制）、`MenuTriggerResolver`（triggers 优先 + 旧 action 兼容映射）、`MenuActionExecutor`（延迟执行 + 异常隔离，走 FoliaLib 统一调度器）、`MenuActionRouter`（注册表式动作分发，替代巨型 switch）、`MenuClickService`（PDC → 冷却 → 音效 → 执行统一点击链）、`ButtonCooldowns`、`MenuSoundService`。
- `GUIListener` 仅保留事件防护与路由，删除 5 个 per-菜单 switch 处理器与重复的 triggers 执行逻辑。
