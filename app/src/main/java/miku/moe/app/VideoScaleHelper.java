package miku.moe.app;

import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;

public final class VideoScaleHelper {

    private VideoScaleHelper() {
    }

    public static void apply(PlayerView view, Player player, String scale) {
        if (view == null) return;
        view.setResizeMode(AnimeSettingsManager.getVideoResizeMode(scale));
        view.setScaleX(1f);
        view.setScaleY(1f);
    }
}
