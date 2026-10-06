package com.linong.recipelookup;

import org.bukkit.command.CommandSender;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
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
 * 导出内容 = 「相对 CE 默认顺序的增量（最少移动集合）+ 隐藏列表」，两边服务器 CE 包一致时
 * 默认顺序相同，只传差异，码通常只有一两百字符 —— 聊天栏一段即可粘完。
 * <ul>
 *   <li>短载荷（≤170 字符）用 {@code R} 原文编码，Base64 都不需要；</li>
 *   <li>长载荷用 {@code Z} = gzip + Base64，按聊天栏 256 字符限制切段；</li>
 *   <li>另附「完整指令」一段式复制，粘贴到目标服务器控制台或命令方块一次导入。</li>
 * </ul>
 * 导入逐段接收，乱序/中断自动续传，收齐后解码应用。
 */
public class OrderShareManager {

    /** 每段数据长度：预留 "alcerecipes admin order import 999/999:" 前缀后仍在 256 字符内 */
    private static final int CHUNK_DATA_SIZE = 210;
    /** 原文编码的载荷上限（更长的走 gzip+Base64） */
    private static final int RAW_PAYLOAD_LIMIT = 170;
    /** 完整指令的长度上限（命令方块约 32.5k） */
    private static final int FULL_COMMAND_LIMIT = 30000;
    private static final int MAX_CHUNKS = 500;

    /** 导出结果：逐段码（i/n:数据）+ 可选的完整一段式指令 */
    public record ExportResult(List<String> parts, String fullCommand) {}

    /** 导入会话：总段数 + 已收数据（下标 = 段号-1） */
    private record ImportSession(int total, String[] parts) {}

    /** 控制台导入的会话键 */
    public static final UUID CONSOLE_KEY = UUID.nameUUIDFromBytes("console".getBytes(StandardCharsets.UTF_8));

    private final ALCERecipeViewer plugin;
    private final Map<UUID, ImportSession> sessions = new ConcurrentHashMap<>();

    public OrderShareManager(ALCERecipeViewer plugin) {
        this.plugin = plugin;
    }

    // ==================== 导出 ====================

    /**
     * 导出分享码：指定分类的自定义顺序（null = 全部）+ 隐藏列表。
     * 没有任何可导出内容时返回 null。
     */
    public ExportResult exportCode(String category) {
        StringBuilder payload = new StringBuilder();
        for (Map.Entry<String, List<String>> e : plugin.getRecipeOrderManager().snapshot().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (category != null && !category.equals(e.getKey())) continue;
            List<String> custom = new ArrayList<>();
            for (String id : e.getValue()) {
                if (id != null && !id.isBlank()) custom.add(id);
            }
            if (custom.isEmpty()) continue;

            String delta = deltaFor(e.getKey(), custom);
            if (delta == null) {
                // 无法用增量表达（自定义列表与可见集不一致）→ 退回全量
                if (payload.length() > 0) payload.append('\n');
                payload.append("O|").append(e.getKey()).append('=').append(String.join(",", custom));
            } else if (!delta.isEmpty()) {
                if (payload.length() > 0) payload.append('\n');
                payload.append("M|").append(e.getKey()).append('=').append(delta);
            }
            // delta 为空串 = 与默认顺序一致，无需导出
        }
        Set<String> hidden = plugin.getVisibilityManager().getHiddenItemIds();
        if (!hidden.isEmpty()) {
            if (payload.length() > 0) payload.append('\n');
            payload.append("H=").append(String.join(",", hidden.stream().sorted().toList()));
        }
        if (payload.length() == 0) return null;

        String code = encode(payload.toString());
        List<String> chunks = splitChunks(code);
        List<String> parts = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            parts.add((i + 1) + "/" + chunks.size() + ":" + chunks.get(i));
        }
        String fullCommand = null;
        String oneShot = "1/1:" + code;
        if (oneShot.length() + 32 <= FULL_COMMAND_LIMIT) { // 32 ≈ "/alcerecipes admin order import ".length()
            fullCommand = "/alcerecipes admin order import " + oneShot;
        }
        return new ExportResult(parts, fullCommand);
    }

    /**
     * 计算某分类相对 CE 默认顺序的最小移动集合（LIS 最长递增子序列）。
     * 返回 "目标位置:id,..."；与默认无差异返回 ""；无法增量表达返回 null。
     */
    private String deltaFor(String category, List<String> custom) {
        List<String> def = plugin.getRecipeGUI().defaultVisibleOrder(category);
        // 只保留当前可见的物品，且必须覆盖全部可见物品（否则位置语义对不上）
        List<String> c = new ArrayList<>();
        for (String id : custom) {
            if (def.contains(id)) c.add(id);
        }
        if (c.size() != def.size()) return null;

        Map<String, Integer> posInDef = new HashMap<>();
        for (int i = 0; i < def.size(); i++) posInDef.put(def.get(i), i);
        int n = c.size();
        int[] s = new int[n];
        for (int i = 0; i < n; i++) s[i] = posInDef.get(c.get(i));

        boolean[] onLis = lisMask(s);
        List<int[]> moves = new ArrayList<>(); // {目标位置, 序号}
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (!onLis[i]) {
                moves.add(new int[]{i, s[i]});
                any = true;
            }
        }
        if (!any) return "";

        // 自检：按目标位置升序 remove+insert 必须能精确重建 custom，否则退回全量
        List<String> recon = new ArrayList<>(def);
        for (int[] move : moves) {
            String id = def.get(move[1]);
            recon.remove(id);
            recon.add(Math.min(move[0], recon.size()), id);
        }
        if (!recon.equals(c)) return null;

        StringBuilder sb = new StringBuilder();
        for (int[] move : moves) {
            if (sb.length() > 0) sb.append(',');
            sb.append(move[0]).append(':').append(def.get(move[1]));
        }
        return sb.toString();
    }

    /** 最长严格递增子序列，返回「在子序列上」的掩码（O(n log n)，带前驱回溯） */
    private static boolean[] lisMask(int[] s) {
        int n = s.length;
        int[] tailsIdx = new int[n];
        int[] prev = new int[n];
        int len = 0;
        for (int i = 0; i < n; i++) {
            int lo = 0;
            int hi = len;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (s[tailsIdx[mid]] < s[i]) lo = mid + 1;
                else hi = mid;
            }
            prev[i] = lo > 0 ? tailsIdx[lo - 1] : -1;
            tailsIdx[lo] = i;
            if (lo == len) len++;
        }
        boolean[] mask = new boolean[n];
        for (int i = tailsIdx[len - 1]; i >= 0; i = prev[i]) mask[i] = true;
        return mask;
    }

    /** 短载荷原文（R 前缀），长载荷 gzip+Base64（Z 前缀） */
    private static String encode(String raw) {
        if (raw.length() <= RAW_PAYLOAD_LIMIT) return "R" + raw;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(raw.getBytes(StandardCharsets.UTF_8));
            }
            return "Z" + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            return "R" + raw; // 极端情况退回原文（导入端会按段解析）
        }
    }

    private static String decode(String code) {
        if (code.startsWith("R")) return code.substring(1);
        if (!code.startsWith("Z")) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(code.substring(1));
            try (ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                 GZIPInputStream gzip = new GZIPInputStream(in)) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
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
     * 接收一段 {@code i/n:码}。收齐自动解码应用；
     * 中途收到不同总段数的新码时，旧会话自动作废重来。
     */
    public void receiveChunk(CommandSender sender, UUID sessionKey, String chunkArg) {
        int sep = chunkArg.indexOf(':');
        if (sep <= 0) {
            sender.sendMessage(plugin.getConfigManager().getShareBadChunk());
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
            sender.sendMessage(plugin.getConfigManager().getShareBadChunk());
            return;
        }

        ImportSession session = sessions.get(sessionKey);
        if (session == null || session.total() != total) {
            session = new ImportSession(total, new String[total]);
            sessions.put(sessionKey, session);
        }
        session.parts()[index - 1] = data;

        int received = 0;
        for (String part : session.parts()) {
            if (part != null) received++;
        }
        if (received < total) {
            sender.sendMessage(plugin.getConfigManager().getShareProgress(received, total));
            return;
        }

        sessions.remove(sessionKey);
        String payload = decode(String.join("", session.parts()));
        int[] result = payload != null ? applyPayload(payload) : null;
        if (result == null) {
            sender.sendMessage(plugin.getConfigManager().getShareBadChunk());
            return;
        }
        sender.sendMessage(plugin.getConfigManager().getShareDone(result[0], result[1]));
    }

    /** 应用载荷行；先应用隐藏（H），再应用顺序（M 增量 / O 全量），返回 {分类数, 隐藏数}；格式非法返回 null */
    private int[] applyPayload(String payload) {
        int categories = 0;
        int hidden = 0;
        // 第一遍：H（增量顺序的位置基准依赖隐藏状态，必须先应用）
        for (String line : payload.split("\n")) {
            if (line.startsWith("H=")) {
                List<String> ids = new ArrayList<>();
                for (String id : line.substring(2).split(",")) {
                    if (!id.isBlank()) ids.add(id);
                }
                plugin.getVisibilityManager().setHiddenItems(ids);
                hidden = ids.size();
            }
        }
        // 第二遍：M / O
        for (String line : payload.split("\n")) {
            if (line.isEmpty() || line.startsWith("H=")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) return null;
            String category = line.substring(line.startsWith("M|") ? 2 : line.startsWith("O|") ? 2 : 0, eq);
            if (category.isEmpty() || (!line.startsWith("M|") && !line.startsWith("O|"))) return null;
            String body = line.substring(eq + 1);
            if (line.startsWith("M|")) {
                List<String> order = new ArrayList<>(plugin.getRecipeGUI().defaultVisibleOrder(category));
                List<int[]> moves = new ArrayList<>(); // {目标位置, def 中的下标}
                List<String> def = plugin.getRecipeGUI().defaultVisibleOrder(category);
                for (String token : body.split(",")) {
                    int colon = token.indexOf(':');
                    if (colon <= 0) return null;
                    int target;
                    try {
                        target = Integer.parseInt(token.substring(0, colon));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    int defIdx = def.indexOf(token.substring(colon + 1));
                    if (defIdx < 0) continue; // 目标服没有该物品，跳过
                    moves.add(new int[]{target, defIdx});
                }
                moves.sort(java.util.Comparator.comparingInt(m -> m[0]));
                for (int[] move : moves) {
                    String id = def.get(move[1]);
                    order.remove(id);
                    order.add(Math.min(move[0], order.size()), id);
                }
                plugin.getRecipeOrderManager().importOrder(category, order);
                categories++;
            } else { // O| 全量
                List<String> ids = new ArrayList<>();
                for (String id : body.split(",")) {
                    if (!id.isBlank()) ids.add(id);
                }
                plugin.getRecipeOrderManager().importOrder(category, ids);
                categories++;
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
