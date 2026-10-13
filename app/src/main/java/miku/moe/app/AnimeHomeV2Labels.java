package miku.moe.app;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.chip.Chip;
import java.util.Locale;

public final class AnimeHomeV2Labels {
    private AnimeHomeV2Labels() {}

    public static String statusText(AnimePost post) {
        if (post == null) return "";
        String raw = post.statusVideo == null ? "" : post.statusVideo.trim();
        if (!raw.isEmpty() && !raw.equalsIgnoreCase("null")) {
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.contains("complete") || lower.contains("finished") || lower.equals("selesai") || lower.equals("tamat")) return "Completed";
            if (lower.contains("ongoing") || lower.contains("on going") || lower.contains("currently") || lower.contains("berjalan")) return "Ongoing";
            return raw;
        }
        return post.ongoing ? "Ongoing" : "";
    }

    public static void bindStatus(View group, Chip chip, AnimePost post) {
        String status = statusText(post);
        if (status.isEmpty()) {
            group.setVisibility(View.GONE);
            return;
        }
        int color = MangaHomeV3Labels.statusColor(status);
        chip.setText(status);
        chip.setTextColor(color);
        chip.setChipBackgroundColor(ColorStateList.valueOf(Color.argb(36, Color.red(color), Color.green(color), Color.blue(color))));
        chip.setChipStrokeColor(ColorStateList.valueOf(Color.argb(120, Color.red(color), Color.green(color), Color.blue(color))));
        group.setVisibility(View.VISIBLE);
    }

    public static String rankText(AnimePost post) {
        // 2026-10-08: chip bintang rating dihapus untuk SEMUA source anime
        // di "Populer minggu ini" atas permintaan user — hanya tampil status.
        if (post == null) return "";
        return statusText(post);
    }

    public static void bindRank(TextView view, AnimePost post) {
        String text = rankText(post);
        if (text.isEmpty()) {
            view.setText("");
            view.setVisibility(View.INVISIBLE);
            return;
        }
        view.setText(text);
        view.setVisibility(View.VISIBLE);
    }

    public static String genreText(AnimePost post) {
        if (post == null) return "";
        String value = post.genre == null ? "" : post.genre.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("null")) return "";
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String part : value.split("[,/|]")) {
            String clean = part.trim();
            if (clean.isEmpty()) continue;
            if (out.length() > 0) out.append(" \u00B7 ");
            out.append(clean);
            count++;
            if (count >= 3) break;
        }
        return out.toString();
    }

    public static String episodeText(android.content.Context context, AnimePost post) {
        // 2026-10-08: permintaan user — di Home Anime V2 chip episode terbaru
        // SELALU tampil, tidak mengikuti toggle "Sembunyikan Label Episode".
        // Kelas pengaturan (AnimeSettingsManager) tidak disentuh.
        return AnimeEpisodeLabelUtils.latestLabel(post);
    }

    public static String itemKey(AnimePost post) {
        if (post == null) return "";
        String source = post.sourceId == null ? "" : post.sourceId.trim();
        String slug = post.slug == null ? "" : post.slug.trim().replaceAll("^/+|/+$", "");
        if (!slug.isEmpty()) return source + ":slug:" + slug;
        if (post.categoryId > 0) return source + ":category:" + post.categoryId + ":" + post.channelId;
        String title = post.categoryName == null ? "" : post.categoryName.trim().toLowerCase(Locale.ROOT);
        return source + ":title:" + title;
    }
}
