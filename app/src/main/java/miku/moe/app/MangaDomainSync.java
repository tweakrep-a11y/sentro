package miku.moe.app;

import java.io.IOException;
import java.util.Locale;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Response;

public final class MangaDomainSync {
    public static final Interceptor INTERCEPTOR = new Interceptor() {
        @Override public Response intercept(Chain chain) throws IOException {
            okhttp3.Request request = chain.request();
            Response response = chain.proceed(request);
            try {
                inspect(request.url(), request.method(), response);
            } catch (Exception ignored) {
            }
            return response;
        }
    };

    private MangaDomainSync() {}

    public static void onDomainChanged(String sourceId) {
        if (sourceId == null || sourceId.trim().isEmpty()) return;
        MangaSourceFactory.invalidateSourceCaches(sourceId);
        BaseMangaGridFragment.clearPageCacheForSource(sourceId);
    }

    private static void inspect(HttpUrl original, String method, Response response) {
        if (response.priorResponse() == null || !"GET".equalsIgnoreCase(method)) return;
        HttpUrl target = response.request().url();
        if (!target.encodedPath().equals(original.encodedPath())) return;
        if (isAssetResponse(response)) return;
        String oldHost = original.host().toLowerCase(Locale.ROOT);
        String newHost = target.host().toLowerCase(Locale.ROOT);
        if (oldHost.equals(newHost) || !sameFamily(oldHost, newHost)) return;
        String newDomain = target.scheme() + "://" + newHost;
        for (String source : MangaSourceFactory.allSourceIds()) {
            if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(source)) continue;
            HttpUrl current = HttpUrl.parse(MangaSettingsManager.getSourceDomain(source));
            if (current == null || !current.host().equalsIgnoreCase(oldHost)) continue;
            MangaSettingsManager.setSourceDomain(source, newDomain);
        }
    }

    private static boolean isAssetResponse(Response response) {
        String type = response.header("Content-Type");
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.startsWith("image/") || lower.startsWith("video/") || lower.startsWith("audio/") || lower.startsWith("font/");
    }

    private static boolean sameFamily(String oldHost, String newHost) {
        String oldName = siteName(oldHost);
        return !oldName.isEmpty() && oldName.equals(siteName(newHost));
    }

    private static String siteName(String host) {
        if (host == null) return "";
        String[] labels = host.split("\\.");
        int n = labels.length;
        if (n < 2) return "";
        String name = labels[n - 2];
        if (n >= 3 && (name.equals("co") || name.equals("com") || name.equals("net") || name.equals("org") || name.equals("or") || name.equals("go") || name.equals("ac"))) name = labels[n - 3];
        return name;
    }
}
