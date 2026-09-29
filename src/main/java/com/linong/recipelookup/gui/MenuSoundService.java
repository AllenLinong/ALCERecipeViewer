package com.linong.recipelookup.gui;

import com.linong.recipelookup.ALCERecipeViewer;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 菜单音效服务（参照 ALFriends）。
 * <p>
 * 格式：{@code 名称-音量-音调}，可用 {@code ;} 串多个；
 * 名称支持原版声音键（如 block.note_block.pling）与 CE 自定义声音 ID（namespace:sound_id）。
 * 无效名称收集去重后只警告一次。
 */
public final class MenuSoundService {

    private final ALCERecipeViewer plugin;
    private final Set<String> invalidSounds = ConcurrentHashMap.newKeySet();

    public MenuSoundService(ALCERecipeViewer plugin) {
        this.plugin = plugin;
    }

    /** 播放按钮配置音效；未配置时使用默认点击音 */
    public void playButton(Player player, String configured) {
        String sound = configured;
        if (sound == null || sound.isBlank()) {
            sound = plugin.getConfigManager().getDefaultButtonSound();
        }
        play(player, sound);
    }

    public void play(Player player, String configured) {
        if (configured == null || configured.isBlank()) return;
        for (SoundSpec spec : parse(configured)) {
            try {
                // 字符串键重载同时接受原版键名与 CE 自定义声音 ID
                player.playSound(player.getLocation(), spec.name(), SoundCategory.MASTER, spec.volume(), spec.pitch());
            } catch (Exception exception) {
                if (invalidSounds.add(spec.name())) {
                    plugin.getLogger().warning("无效的菜单音效: " + spec.name());
                }
            }
        }
    }

    static List<SoundSpec> parse(String configured) {
        List<SoundSpec> sounds = new ArrayList<>();
        for (String part : configured.split(";")) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            String[] components = value.split("-", 3);
            String name = components[0].trim();
            if (name.isEmpty()) continue;
            sounds.add(new SoundSpec(name, parseFloat(components, 1, 1.0F), parseFloat(components, 2, 1.0F)));
        }
        return List.copyOf(sounds);
    }

    private static float parseFloat(String[] values, int index, float fallback) {
        if (index >= values.length) return fallback;
        try {
            float value = Float.parseFloat(values[index].trim());
            return Float.isFinite(value) ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    record SoundSpec(String name, float volume, float pitch) {
    }
}
