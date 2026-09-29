package com.linong.recipelookup.gui;

import com.linong.recipelookup.MenuConfig.ButtonDef;
import org.bukkit.event.inventory.ClickType;

import java.util.List;
import java.util.Locale;

/**
 * 按钮动作解析（参照 ALFriends 的 MenuTriggerResolver）。
 * <p>
 * 解析优先级：
 * <ol>
 *   <li>{@code triggers.<click>} 动作列表（新格式，推荐）——命中后不再看旧 action；</li>
 *   <li>旧版单值 {@code action:} 兼容映射为统一动作串（部分动作按点击类型展开不同行为）。</li>
 * </ol>
 * 返回用 {@link MenuActionParser#ACTION_SEPARATOR} 拼好的编码串，null 表示该按钮此点击无动作。
 */
public final class MenuTriggerResolver {

    public static final String CLICK_LEFT = "left";
    public static final String CLICK_RIGHT = "right";
    public static final String CLICK_SHIFT_LEFT = "shift_left";
    public static final String CLICK_SHIFT_RIGHT = "shift_right";

    private MenuTriggerResolver() {
    }

    public static String clickName(ClickType click) {
        return switch (click) {
            case SHIFT_LEFT -> CLICK_SHIFT_LEFT;
            case SHIFT_RIGHT -> CLICK_SHIFT_RIGHT;
            case RIGHT -> CLICK_RIGHT;
            default -> CLICK_LEFT;
        };
    }

    /** 解析按钮在指定点击类型下的动作编码串；无动作返回 null */
    public static String resolve(ButtonDef button, String click) {
        if (button == null || click == null) return null;

        List<String> triggers = button.triggers().get(click);
        if (triggers != null && !triggers.isEmpty()) {
            return String.join(MenuActionParser.ACTION_SEPARATOR, triggers);
        }
        return legacyAction(button, click);
    }

    /** 旧版单值 action 兼容：映射为统一动作串。RUN_COMMAND/SEARCH/CREATOR_EXP 等按配置或点击类型展开 */
    private static String legacyAction(ButtonDef button, String click) {
        String action = button.action();
        if (action == null || action.isEmpty()) return null;
        String normalized = action.trim().toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "OPEN_CATEGORY" -> button.category().isEmpty() ? null : "open_category:" + button.category();
            case "PREV_PAGE" -> "prev_page";
            case "NEXT_PAGE" -> "next_page";
            case "PREV_RECIPE" -> "prev_recipe";
            case "NEXT_RECIPE" -> "next_recipe";
            case "BACK" -> "back";
            case "BACK_TO_MAIN" -> "back_to_main";
            case "CLOSE" -> "close";
            case "CREATE_RECIPE" -> "create_recipe";
            // 搜索按钮旧行为：左键输入 / 右键切模式 / Shift 清空
            case "SEARCH" -> switch (click) {
                case CLICK_RIGHT -> "search_mode";
                case CLICK_SHIFT_LEFT, CLICK_SHIFT_RIGHT -> "search_clear";
                default -> "search";
            };
            case "RUN_COMMAND" -> legacyCommandAction(button);
            // 经验按钮旧行为：Shift+左键聊天输入，其余点击微调
            case "CREATOR_EXP" -> CLICK_SHIFT_LEFT.equals(click) ? "creator_exp_input" : "creator_adjust:E";
            case "CREATOR_SHAPED" -> "open_creator:shaped";
            case "CREATOR_SHAPELESS" -> "open_creator:shapeless";
            case "CREATOR_FURNACE" -> "open_creator:furnace";
            case "CREATOR_SMITHING" -> "open_creator:smithing";
            case "CREATOR_STONECUTTER" -> "open_creator:stonecutting";
            case "CREATOR_CAMPFIRE" -> "open_creator:campfire";
            case "CREATOR_SMOKING" -> "open_creator:smoking";
            case "CREATOR_BREWING" -> "open_creator:brewing";
            case "CREATOR_FURNACE_MODE" -> "creator_mode";
            case "CREATOR_FURNACE_TIME" -> "creator_adjust:P";
            case "CREATOR_BLAST_TIME" -> "creator_adjust:G";
            case "CREATOR_SMOKING_TIME" -> "creator_adjust:Y";
            case "CREATOR_CAMPFIRE_TIME" -> "creator_adjust:G";
            case "SAVE_RECIPE" -> "save_recipe";
            default -> null;
        };
    }

    private static String legacyCommandAction(ButtonDef button) {
        String command = button.command();
        if (command == null || command.isEmpty()) return null;
        return button.asPlayer() ? "command:" + command : "console:" + command;
    }
}
