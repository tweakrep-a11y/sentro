package miku.moe.app;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.chip.Chip;
import java.util.Locale;

public final class MangaHomeV3Labels {
    private MangaHomeV3Labels() {}

    public static String typeText(MangaPost post) {
        if (post == null) return "";
        String raw = post.getTypeLabel();
        if (raw == null) return "";
        String clean = raw.trim();
        if (clean.isEmpty()) return "";
        String lower = clean.toLowerCase(Locale.ROOT);
        String name = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        String flag = typeFlag(lower);
        return flag.isEmpty() ? name : flag + " " + name;
    }

    private static String typeFlag(String lower) {
        if (lower.contains("manhwa") || lower.contains("webtoon")) return "\uD83C\uDDF0\uD83C\uDDF7";
        if (lower.contains("manhua")) return "\uD83C\uDDE8\uD83C\uDDF3";
        if (lower.contains("doujin") || lower.contains("manga")) return "\uD83C\uDDEF\uD83C\uDDF5";
        return "";
    }

    public static void bindType(TextView view, MangaPost post) {
        String text = typeText(post);
        if (text.isEmpty()) {
            view.setText("");
            view.setVisibility(View.INVISIBLE);
            return;
        }
        view.setText(text);
        view.setVisibility(View.VISIBLE);
    }

    public static String statusText(MangaPost post) {
        if (post == null || post.status == null) return "";
        return post.status.trim();
    }

    public static int statusColor(String status) {
        String s = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if (s.contains("ongoing") || s.contains("berjalan") || s.contains("publishing")) return 0xFF66BB6A;
        if (s.contains("complete") || s.contains("tamat") || s.contains("finished") || s.equals("end")) return 0xFF64B5F6;
        if (s.contains("hiatus") || s.contains("jeda")) return 0xFFFFB74D;
        if (s.contains("cancel") || s.contains("drop") || s.contains("batal")) return 0xFFEF5350;
        if (s.contains("upcoming") || s.contains("segera")) return 0xFFBA68C8;
        return 0xFF90A4AE;
    }

    public static void bindStatus(View group, Chip chip, MangaPost post) {
        String status = statusText(post);
        if (status.isEmpty()) {
            group.setVisibility(View.GONE);
            return;
        }
        int color = statusColor(status);
        chip.setText(status);
        chip.setTextColor(color);
        chip.setChipBackgroundColor(ColorStateList.valueOf(Color.argb(36, Color.red(color), Color.green(color), Color.blue(color))));
        chip.setChipStrokeColor(ColorStateList.valueOf(Color.argb(120, Color.red(color), Color.green(color), Color.blue(color))));
        group.setVisibility(View.VISIBLE);
    }
}
