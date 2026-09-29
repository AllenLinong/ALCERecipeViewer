package com.linong.recipelookup.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MenuActionParser 行为锁定测试（纯逻辑，无 Bukkit 依赖）。
 * 重点：close 类动作自带隐式 1 tick 延迟——关闭菜单后，后续动作
 * 至少隔 1 tick 执行；显式 delay: 在此基础上累加。
 */
class MenuActionParserTest {

    private static final String SEP = MenuActionParser.ACTION_SEPARATOR;

    private static String encode(String... actions) {
        return String.join(SEP, actions);
    }

    private static List<MenuActionParser.PlannedAction> parse(String... actions) {
        return MenuActionParser.parse(encode(actions));
    }

    // ==================== close 隐式 1 tick ====================

    @Test
    void closeAloneExecutesImmediately() {
        List<MenuActionParser.PlannedAction> planned = parse("close");
        assertEquals(1, planned.size());
        assertEquals("close", planned.get(0).action());
        assertEquals(0L, planned.get(0).delayTicks());
    }

    @Test
    void actionAfterCloseWaitsOneTick() {
        List<MenuActionParser.PlannedAction> planned = parse("close", "command: spawn");
        assertEquals(2, planned.size());
        assertEquals(0L, planned.get(0).delayTicks());
        assertEquals(1L, planned.get(1).delayTicks());
    }

    @Test
    void explicitDelayAccumulatesOnCloseImplicitTick() {
        // 锁定核心语义：close 的隐式 1 tick + 显式 delay: 5 = 6 tick
        List<MenuActionParser.PlannedAction> planned = parse("close", "delay: 5", "command: spawn");
        assertEquals(2, planned.size());
        assertEquals(0L, planned.get(0).delayTicks());
        assertEquals(6L, planned.get(1).delayTicks());
    }

    @Test
    void delayBeforeCloseAppliesToCloseItself() {
        // close 前面的延迟作用于 close 本身，close 的隐式 1 tick 只影响后续动作
        List<MenuActionParser.PlannedAction> planned = parse("delay: 5", "close", "command: spawn");
        assertEquals(2, planned.size());
        assertEquals(5L, planned.get(0).delayTicks());
        assertEquals(6L, planned.get(1).delayTicks());
    }

    @Test
    void closeVariantsAndCaseInsensitivity() {
        for (String variant : new String[]{"close", "CLOSE", "Close", "silent-close", "FORCE-CLOSE"}) {
            List<MenuActionParser.PlannedAction> planned = parse(variant, "message: hi");
            assertEquals(1L, planned.get(1).delayTicks(),
                    "close 类动作 " + variant + " 应给后续动作 +1 tick");
        }
    }

    @Test
    void closeWithActionPrefixAlsoDelays() {
        List<MenuActionParser.PlannedAction> planned = parse("action:close", "command: spawn");
        assertEquals(1L, planned.get(1).delayTicks());
    }

    @Test
    void closeAsLastActionProducesNoPhantomEntries() {
        List<MenuActionParser.PlannedAction> planned = parse("command: spawn", "close");
        assertEquals(2, planned.size());
        assertEquals("command: spawn", planned.get(0).action());
        assertEquals("close", planned.get(1).action());
    }

    @Test
    void nonCloseActionsDoNotDelayFollowers() {
        List<MenuActionParser.PlannedAction> planned = parse("sound: X-1-1", "message: hi");
        assertEquals(0L, planned.get(1).delayTicks());
    }

    // ==================== delay 基础语义 ====================

    @Test
    void delayAccumulatesAcrossEntries() {
        List<MenuActionParser.PlannedAction> planned =
                parse("command: a", "delay: 10", "console: b", "wait: 500ms", "close");
        assertEquals(3, planned.size());
        assertEquals(0L, planned.get(0).delayTicks());
        assertEquals(10L, planned.get(1).delayTicks());
        assertEquals(20L, planned.get(2).delayTicks()); // 10 + 500ms(=10tick)
    }

    @Test
    void delayUnitsAreParsed() {
        assertEquals(10L, MenuActionParser.parseDelayTicks("500ms"));
        assertEquals(30L, MenuActionParser.parseDelayTicks("1.5s"));
        assertEquals(20L, MenuActionParser.parseDelayTicks("20t"));
        assertEquals(20L, MenuActionParser.parseDelayTicks("20"));
        assertEquals(0L, MenuActionParser.parseDelayTicks("-5"));
        assertEquals(0L, MenuActionParser.parseDelayTicks("bad"));
        assertEquals(0L, MenuActionParser.parseDelayTicks(""));
    }

    @Test
    void delayOverflowIsClamped() {
        String max = "9223372036854775807";
        List<MenuActionParser.PlannedAction> planned = parse("delay: " + max, "delay: " + max, "close");
        assertEquals(Long.MAX_VALUE, planned.get(0).delayTicks());
    }

    // ==================== 其他工具方法 ====================

    @Test
    void containsSoundDetectsOnlySoundEntries() {
        assertTrue(MenuActionParser.containsSound(encode("sound: X-1-1", "close")));
        assertTrue(MenuActionParser.containsSound(encode("SOUND: x")));
        assertFalse(MenuActionParser.containsSound(encode("command: sound: fake")));
        assertFalse(MenuActionParser.containsSound(encode("close")));
    }

    @Test
    void splitCommandsTrimsAndDropsBlanks() {
        assertEquals(List.of("/spawn", "tell x", "give y z"),
                MenuActionParser.splitCommands(" /spawn; tell x ;;give y z "));
    }

    @Test
    void blankEntriesAreSkipped() {
        List<MenuActionParser.PlannedAction> planned = parse("", "  ", "close", "");
        assertEquals(1, planned.size());
    }
}
