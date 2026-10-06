package com.linong.recipelookup;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {
    private static final String API = "https://api.github.com/repos/AllenLinong/ALCERecipeViewer/releases/latest";
    private static final String PAGE = "https://github.com/AllenLinong/ALCERecipeViewer/releases/latest";
    private static final Pattern TAG = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private final ALCERecipeViewer plugin;
    private volatile String latestVersion;

    public UpdateChecker(ALCERecipeViewer plugin) {
        this.plugin = plugin;
    }

    public void checkAsync() {
        plugin.getFoliaLib().getScheduler().runAsync(task -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(API))
                        .timeout(Duration.ofSeconds(8))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "ALCERecipeViewer")
                        .GET().build();
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) return;
                Matcher matcher = TAG.matcher(response.body());
                if (!matcher.find()) return;
                latestVersion = normalize(matcher.group(1));
                String current = normalize(plugin.getDescription().getVersion());
                if (isNewer(latestVersion, current)) {
                    plugin.getLogger().warning("发现 ALCERecipeViewer 新版本 " + latestVersion
                            + "，当前版本 " + current + "，下载: " + PAGE);
                }
            } catch (Exception ignored) {
            }
        });
    }

    public void notifyAdmin(org.bukkit.entity.Player player) {
        if (!plugin.getConfigManager().isUpdateCheckEnabled()) return;
        if (latestVersion == null || !isNewer(latestVersion, normalize(plugin.getDescription().getVersion()))) return;
        if (!player.hasPermission("alcerecipeviewer.admin")) return;
        player.sendMessage(org.bukkit.ChatColor.GOLD + "[ALCERecipeViewer] " + org.bukkit.ChatColor.YELLOW
                + "发现新版本 " + latestVersion + "，下载: " + PAGE);
    }

    private static String normalize(String version) {
        return version == null ? "0" : version.trim().replaceFirst("^[vV]", "");
    }

    private static boolean isNewer(String candidate, String current) {
        String[] left = candidate.split("[.-]");
        String[] right = current.split("[.-]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? number(left[i]) : 0;
            int b = i < right.length ? number(right[i]) : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int number(String value) {
        String digits = value.replaceAll("[^0-9].*", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
