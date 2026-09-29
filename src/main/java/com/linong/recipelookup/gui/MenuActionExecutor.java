package com.linong.recipelookup.gui;

import com.linong.recipelookup.ALCERecipeViewer;
import org.bukkit.entity.Player;

import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * 菜单动作执行器（参照 ALFriends）。
 * <p>
 * {@code delay=0} 直接执行；否则经 FoliaLib 统一调度器延迟执行（执行前校验玩家在线）。
 * 单个动作抛异常只记日志，不影响同序列后续动作。
 */
final class MenuActionExecutor {

    private final ALCERecipeViewer plugin;

    MenuActionExecutor(ALCERecipeViewer plugin) {
        this.plugin = plugin;
    }

    void execute(Player player, String encodedActions, BiConsumer<Player, String> actionHandler) {
        for (MenuActionParser.PlannedAction plannedAction : MenuActionParser.parse(encodedActions)) {
            if (plannedAction.delayTicks() == 0L) {
                dispatch(player, plannedAction.action(), actionHandler);
                continue;
            }
            plugin.getFoliaLib().getScheduler().runAtEntityLater(player, () -> {
                if (player.isOnline()) dispatch(player, plannedAction.action(), actionHandler);
            }, plannedAction.delayTicks());
        }
    }

    boolean containsSound(String encodedActions) {
        return MenuActionParser.containsSound(encodedActions);
    }

    private void dispatch(Player player, String action, BiConsumer<Player, String> actionHandler) {
        try {
            actionHandler.accept(player, action);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "执行菜单动作失败 (" + player.getName() + "): " + action, exception);
        }
    }
}
