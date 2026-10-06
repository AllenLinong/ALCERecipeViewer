package com.linong.recipelookup;

import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 排序配置分享码（参照 LuckPerms 的 exportcode / importcode）。
 * <p>
 * 导出：把「各分类自定义顺序 + 隐藏列表」打包为 gzip + Base64 码，
 * 按聊天栏 256 字符限制切段输出（点击每段复制完整导入指令）。
 * 导入：逐段接收，乱序/中断自动续传，收齐后解码并整体应用。
 * 全程游戏内完成，无需到后台复制文件。
 */
public class OrderShareManager {

    /** 每段数据长度：预留 "alcerecipes admin order import i/n:" 前缀后仍在 256 字符内 */
    private static final int CHUNK_DATA_SIZE = 180;
    private static final int MAX_CHUNKS = 500;

    /** 导入会话：总段数 + 已收数据（下标 = 段号-1） */
    private record ImportSession(int total, String[] parts) {}

    private final ALCERecipeViewer plugin;
    private final Map<UUID, ImportSession> sessions = new ConcurrentHashMap<>();

    public OrderShareManager(ALCERecipeViewer plugin) {
        this.plugin = plugin;
    }

    // ==================== 导出 ====================

    /**
     * 导出分享码：指定分类的自定义顺序（null = 全部）+ 隐藏列表。
     * 没有任何可导出内容时返回空列表。
     */
    public List<String> exportCode(String category) {
        StringBuilder payload = new StringBuilder();
        for (Map.Entry<String, List<String>> e : plugin.getRecipeOrderManager().snapshot().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (category != null && !category.equals(e.getKey())) continue;
            if (payload.length() > 0) payload.append('\n');
            payload.append("O|").append(e.getKey()).append('=')
                    .append(String.join(",", e.getValue()));
        }
        Set<String> hidden = plugin.getVisibilityManager().getHiddenItemIds();
        if (!hidden.isEmpty()) {
            if (payload.length() > 0) payload.append('\n');
            payload.append("H=").append(String.join(",", hidden.stream().sorted().toList()));
        }
        if (payload.length() == 0) return List.of();
        return splitChunks(encode(payload.toString()));
    }

    private static String encode(String raw) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(raw.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> splitChunks(String data) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < data.length(); i += CHUNK_DATA_SIZE) {
            chunks.add(data.substring(i, Math.min(data.length(), i + CHUNK_DATA_SIZE)));
        }
        return chunks;
    }

    // ==================== 导入 ====================

    /**
     * 接收一段 {@code i/n:数据}。收齐自动解码应用；
     * 中途收到不同总段数的新码时，旧会话自动作废重来。
     */
    public void receiveChunk(Player player, String chunkArg) {
        int sep = chunkArg.indexOf(':');
        if (sep <= 0) {
            player.sendMessage(plugin.getConfigManager().getShareBadChunk());
            return;
        }
        String header = chunkArg.substring(0, sep);
        String data = chunkArg.substring(sep + 1);
        int slash = header.indexOf('/');
        int index;
        int total;
        try {
            index = Integer.parseInt(header.substring(0, slash));
            total = Integer.parseInt(header.substring(slash + 1));
        } catch (Exception e) {
            index = 0;
            total = 0;
        }
        if (total < 1 || total > MAX_CHUNKS || index < 1 || index > total) {
            player.sendMessage(plugin.getConfigManager().getShareBadChunk());
            return;
        }

        UUID uuid = player.getUniqueId();
        ImportSession session = sessions.get(uuid);
        if (session == null || session.total() != total) {
            session = new ImportSession(total, new String[total]);
            sessions.put(uuid, session);
        }
        session.parts()[index - 1] = data;

        int received = 0;
        for (String part : session.parts()) {
            if (part != null) received++;
        }
        if (received < total) {
            player.sendMessage(plugin.getConfigManager().getShareProgress(received, total));
            return;
        }

        sessions.remove(uuid);
        String payload = decode(String.join("", session.parts()));
        int[] result = payload != null ? applyPayload(payload) : null;
        if (result == null) {
            player.sendMessage(plugin.getConfigManager().getShareBadChunk());
            return;
        }
        player.sendMessage(plugin.getConfigManager().getShareDone(result[0], result[1]));
    }

    private static String decode(String code) {
        try {
            byte[] bytes = Base64.getDecoder().decode(code);
            try (ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                 GZIPInputStream gzip = new GZIPInputStream(in)) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 应用载荷行（O|分类=id,id / H=id,id），返回 {分类数, 隐藏数}；格式非法返回 null */
    private int[] applyPayload(String payload) {
        int categories = 0;
        int hidden = 0;
        for (String line : payload.split("\n")) {
            if (line.isEmpty()) continue;
            if (line.startsWith("O|")) {
                int eq = line.indexOf('=');
                if (eq < 0) return null;
                String category = line.substring(2, eq);
                List<String> ids = new ArrayList<>();
                for (String id : line.substring(eq + 1).split(",")) {
                    if (!id.isBlank()) ids.add(id);
                }
                plugin.getRecipeOrderManager().importOrder(category, ids);
                categories++;
            } else if (line.startsWith("H=")) {
                List<String> ids = new ArrayList<>();
                for (String id : line.substring(2).split(",")) {
                    if (!id.isBlank()) ids.add(id);
                }
                plugin.getVisibilityManager().setHiddenItems(ids);
                hidden = ids.size();
            } else {
                return null;
            }
        }
        return new int[]{categories, hidden};
    }

    // ==================== 会话管理 ====================

    public boolean hasSession(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    public void clearSession(UUID uuid) {
        sessions.remove(uuid);
    }
}
