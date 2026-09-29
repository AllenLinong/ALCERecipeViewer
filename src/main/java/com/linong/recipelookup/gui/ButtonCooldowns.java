package com.linong.recipelookup.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 按钮点击冷却（防连点触发两次），key = (玩家, 菜单类型, 槽位)。参照 ALFriends。 */
final class ButtonCooldowns {

    private final Map<Key, Long> lastClicks = new ConcurrentHashMap<>();

    synchronized boolean isCoolingDown(UUID playerId, String menuName, int slot, long now, long cooldownMillis) {
        if (cooldownMillis <= 0L) return false;
        Key key = new Key(playerId, menuName, slot);
        Long previous = lastClicks.get(key);
        if (previous != null && now - previous < cooldownMillis) return true;
        lastClicks.put(key, now);
        return false;
    }

    void clear(UUID playerId) {
        lastClicks.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    private record Key(UUID playerId, String menuName, int slot) {
    }
}
