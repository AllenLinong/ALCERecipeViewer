package com.linong.recipelookup.gui;

import com.linong.recipelookup.ALCERecipeViewer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 菜单点击门面：统一「读物品 PDC 动作 → 按钮冷却 → 音效 → 动作执行」点击链（参照 ALFriends）。
 * Executor / Router / Cooldowns / SoundService 均为包内实现细节，Listener 只面对本类。
 */
public final class MenuClickService {

    private final ALCERecipeViewer plugin;
    private final RecipeGUI gui;
    private final MenuSoundService sounds;
    private final MenuActionExecutor executor;
    private final MenuActionRouter router;
    private final ButtonCooldowns buttonCooldowns = new ButtonCooldowns();

    public MenuClickService(ALCERecipeViewer plugin, RecipeGUI gui) {
        this.plugin = plugin;
        this.gui = gui;
        this.sounds = new MenuSoundService(plugin);
        this.executor = new MenuActionExecutor(plugin);
        this.router = new MenuActionRouter(plugin, gui, sounds);
    }

    /**
     * 按钮点击：读物品 PDC 中该点击类型的动作编码串并执行。
     * 物品无动作（背景/装饰）返回 false，由调用方决定后续。
     */
    public boolean handleButton(Player player, ItemStack clicked, ClickType click,
                                String guiType, int rawSlot, int slot) {
        String encoded = gui.readTriggerActions(clicked, click);
        if (encoded == null || encoded.isBlank()) return false;
        if (isCoolingDown(player, guiType, rawSlot)) return true;
        // 动作串自带 sound: 时不再叠加按钮默认音
        if (!executor.containsSound(encoded)) {
            sounds.playButton(player, gui.readButtonSound(clicked));
        }
        dispatch(player, encoded, click, guiType, slot);
        return true;
    }

    /** 动态区合成动作（如配方列表条目 recipe_entry）：同样走冷却与执行器，但不播按钮音 */
    public void handleSynthesized(Player player, String action, ClickType click,
                                  String guiType, int rawSlot, int slot) {
        if (isCoolingDown(player, guiType, rawSlot)) return;
        dispatch(player, action, click, guiType, slot);
    }

    private void dispatch(Player player, String encoded, ClickType click, String guiType, int slot) {
        MenuActionRouter.MenuClickContext ctx = new MenuActionRouter.MenuClickContext(click, guiType, slot);
        executor.execute(player, encoded, (p, action) -> router.dispatch(p, action, ctx));
    }

    private boolean isCoolingDown(Player player, String guiType, int rawSlot) {
        long cooldownMillis = pluginCooldownMillis();
        if (cooldownMillis <= 0L) return false;
        return buttonCooldowns.isCoolingDown(player.getUniqueId(), guiType, rawSlot,
                System.currentTimeMillis(), cooldownMillis);
    }

    private long pluginCooldownMillis() {
        return Math.max(0L, Math.round(
                plugin.getConfigManager().getButtonCooldownSeconds() * 1000.0D));
    }

    /** 关菜单/退出时清理该玩家的点击冷却 */
    public void clearCooldowns(UUID uuid) {
        buttonCooldowns.clear(uuid);
    }
}
