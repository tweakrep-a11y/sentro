package miku.moe.app;

import java.util.ArrayList;

public final class MangaHomeV3Sorts {
    public static final String DEFAULT = "popular";

    private MangaHomeV3Sorts() {}

    public static ArrayList<String[]> forSource(String sourceId) {
        ArrayList<String[]> out = new ArrayList<>();
        out.add(new String[]{DEFAULT, "Populer"});
        String[] extras = extras(sourceId);
        for (int i = 0; i + 1 < extras.length; i += 2) {
            String key = extras[i];
            if ("latest".equals(key) || "popular".equals(key) || "popularity".equals(key)) continue;
            out.add(new String[]{key, extras[i + 1]});
        }
        return out;
    }

    private static String[] extras(String sourceId) {
        if (sourceId == null) return new String[0];
        switch (sourceId) {
            case MangaSettingsManager.MANGA_SOURCE_KOMIKCAST:
                return new String[]{"project", "Project", "mirror", "Miror", "bookmark", "Most Bookmark", "newseries", "New Series", "rating", "Rating", "az", "A-Z"};
            case MangaSettingsManager.MANGA_SOURCE_KOMIKTAP:
                return new String[]{"added", "Added", "az", "A-Z", "za", "Z-A"};
            case MangaSettingsManager.MANGA_SOURCE_MANHWAINDO:
                return new String[]{"added", "Added", "az", "A-Z", "completed", "Completed", "manga", "Manga", "manhwa", "Manhwa", "manhua", "Manhua"};
            case MangaSettingsManager.MANGA_SOURCE_SOULSCANS:
                return new String[]{"added", "Added", "az", "A-Z", "za", "Z-A", "project", "Project", "completed", "Completed", "manga", "Manga", "manhwa", "Manhwa", "manhua", "Manhua"};
            case MangaSettingsManager.MANGA_SOURCE_KUROMANGA:
                return new String[]{"added", "Added", "az", "A-Z", "za", "Z-A"};
            case MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK:
                return new String[]{"added", "Added", "az", "A-Z", "completed", "Completed"};
            case MangaSettingsManager.MANGA_SOURCE_MGKOMIK:
                return new String[]{"new", "New Manga", "views", "Most Views", "az", "A-Z", "project", "Project", "completed", "Completed", "manga", "Manga", "manhwa", "Manhwa", "manhua", "Manhua"};
            case MangaSettingsManager.MANGA_SOURCE_COMICASO:
                return new String[]{"new", "New", "completed", "Completed", "manga", "Manga", "manhwa", "Manhwa", "manhua", "Manhua"};
            case MangaSettingsManager.MANGA_SOURCE_CROTPEDIA:
                return new String[]{"rating", "Rating", "added", "Latest Added", "az", "A-Z", "za", "Z-A", "manga", "Manga", "image-set", "Image-set", "manhwa", "Manhwa", "one-shot", "One-shot", "doujinshi", "Doujinshi"};
            case MangaSettingsManager.MANGA_SOURCE_NGOMIK:
                return new String[]{"added", "Added", "az", "A-Z", "za", "Z-A"};
            case MangaSettingsManager.MANGA_SOURCE_IKIRU:
                return new String[]{"updated", "Terbaru", "rating", "Rating", "project", "Project", "added", "Baru Ditambahkan", "az", "A-Z", "manga", "Manga", "manhwa", "Manhwa", "manhua", "Manhua"};
            case MangaSettingsManager.MANGA_SOURCE_LUVYAA:
                return new String[]{"added", "Baru Ditambahkan", "project", "Project", "az", "A-Z", "za", "Z-A", "completed", "Selesai", "ongoing", "Berjalan", "hiatus", "Hiatus"};
            case MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN:
                return new String[]{"added", "Baru Ditambahkan", "az", "A-Z", "za", "Z-A"};
            case MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG:
                return new String[]{"title_latest", "Judul Terbaru"};
            case MangaSettingsManager.MANGA_SOURCE_KOMIKU:
                return new String[]{"rating", "Rating", "ongoing", "Ongoing", "completed", "Completed"};
            case MangaSettingsManager.MANGA_SOURCE_MANGASUSU:
                return new String[]{"added", "Baru ditambahkan"};
            case MangaSettingsManager.MANGA_SOURCE_COSMICSCANS:
                return new String[]{"added", "New Added", "az", "A-Z", "za", "Z-A", "project", "Project"};
            case MangaSettingsManager.MANGA_SOURCE_NATSU:
            case MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL:
                return new String[]{"project", "Project"};
            case MangaSettingsManager.MANGA_SOURCE_AINZSCANSS:
                return new String[]{"views", "Top Views", "bookmark", "Top Favorite", "rate", "Top Rate"};
            case MangaSettingsManager.MANGA_SOURCE_APKOMIK:
                return new String[]{"project", "Project", "manga", "Manga", "manhwa", "Manhwa", "manhua", "Manhua"};
            case MangaSettingsManager.MANGA_SOURCE_DOUJINDESU:
                return new String[]{"newest", "Newest", "title_asc", "A-Z", "oldest", "Oldest", "manga", "Manga", "manhwa", "Manhwa", "manhua", "Manhua", "doujinshi", "Doujinshi"};
            default:
                return new String[0];
        }
    }

    public static boolean isDefault(String sort) {
        return sort == null || sort.isEmpty() || DEFAULT.equals(sort);
    }

    public static String labelFor(String sourceId, String sort) {
        if (isDefault(sort)) return "Populer";
        for (String[] item : forSource(sourceId)) if (item[0].equals(sort)) return item[1];
        return "Populer";
    }
}
