package com.linong.recipelookup.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 菜单动作串解析器（参照 ALFriends）。
 * <p>
 * 动作串用不可见分隔符 {@link #ACTION_SEPARATOR} 拼接，{@code delay:/wait:}
 * 条目累计后续动作的延迟 tick 数（多条累加、溢出钳制），其余条目按顺序执行。
 * 纯逻辑、无 Bukkit 依赖。
 */
final class MenuActionParser {

    /** Record Separator，避免与动作内容里的 ":"、";"、空格冲突 */
    static final String ACTION_SEPARATOR = "\u001E";

    private MenuActionParser() {
    }

    static List<PlannedAction> parse(String encodedActions) {
        List<PlannedAction> plannedActions = new ArrayList<>();
        long delayTicks = 0L;
        for (String part : encodedActions.split(ACTION_SEPARATOR, -1)) {
            String action = part.trim();
            if (action.isBlank()) continue;

            String delayValue = delayValue(action);
            if (delayValue != null) {
                delayTicks = addWithoutOverflow(delayTicks, parseDelayTicks(delayValue));
            } else {
                plannedActions.add(new PlannedAction(action, delayTicks));
            }
        }
        return List.copyOf(plannedActions);
    }

    static List<String> splitCommands(String commands) {
        return Arrays.stream(commands.split(";"))
                .map(String::trim)
                .filter(command -> !command.isBlank())
                .toList();
    }

    static boolean containsSound(String encodedActions) {
        return Arrays.stream(encodedActions.split(ACTION_SEPARATOR, -1))
                .map(String::trim)
                .anyMatch(action -> action.regionMatches(true, 0, "sound:", 0, 6));
    }

    /** 延迟值解析：20t=20tick、500ms、1.5s、裸数字按 tick；解析失败返回 0 */
    static long parseDelayTicks(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return 0L;
        try {
            if (normalized.endsWith("ms")) {
                return positiveCeil(Double.parseDouble(normalized.substring(0, normalized.length() - 2)) / 50.0D);
            }
            if (normalized.endsWith("t")) {
                return positiveCeil(Double.parseDouble(normalized.substring(0, normalized.length() - 1)));
            }
            if (normalized.endsWith("s")) {
                return positiveCeil(Double.parseDouble(normalized.substring(0, normalized.length() - 1)) * 20.0D);
            }
            return Math.max(0L, Long.parseLong(normalized));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static String delayValue(String action) {
        int separator = action.indexOf(':');
        if (separator < 0) return null;
        String type = action.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        return type.equals("delay") || type.equals("wait") ? action.substring(separator + 1).trim() : null;
    }

    private static long positiveCeil(double value) {
        if (!Double.isFinite(value) || value <= 0.0D) return 0L;
        if (value >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) Math.ceil(value);
    }

    private static long addWithoutOverflow(long first, long second) {
        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }

    record PlannedAction(String action, long delayTicks) {
    }
}
