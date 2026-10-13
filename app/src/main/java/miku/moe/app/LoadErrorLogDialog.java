package miku.moe.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LoadErrorLogDialog {
    public static final String SUMMARY_MESSAGE = "Terjadi kesalahan saat memuat klik pesan ini untuk menampilkan masalah lengkapnya";

    private static final int MAX_STACK_LINES = 60;
    private static final int MAX_CAUSES = 6;
    private static final Pattern HTTP_CODE = Pattern.compile("HTTP\\s*(\\d{3})", Pattern.CASE_INSENSITIVE);

    private LoadErrorLogDialog() {}

    public static boolean isNoConnectionMessage(String message) {
        if (message == null) return false;
        return message.toLowerCase(Locale.ROOT).contains("tidak ada koneksi");
    }

    public static boolean isCloudflareMessage(String message) {
        if (message == null) return false;
        return CloudflareHelper.isCloudflareRequiredMessage(message);
    }

    public static String rootMessage(Throwable error, String fallback) {
        if (error == null) return fallback;
        Throwable cause = error;
        HashSet<Throwable> seen = new HashSet<>();
        while (cause.getCause() != null && cause.getCause() != cause && seen.add(cause)) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty() ? fallback : message.trim();
    }

    public static void show(Context context, String report) {
        if (context == null) return;
        final String text = report == null || report.trim().isEmpty() ? "Log kesalahan tidak tersedia." : report;
        try {
            new MaterialAlertDialogBuilder(context)
                    .setTitle("Log Kesalahan")
                    .setMessage(text)
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Salin Log", (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Log Kesalahan Miku Moe", text));
                            Toast.makeText(context, "Log disalin", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        } catch (Exception ignored) {
        }
    }

    public static String buildReport(Context context, String area, String sourceId, String sourceLabel, String message, Throwable error, boolean emptyData, String extraTrace) {
        StringBuilder out = new StringBuilder();
        out.append("LOG KESALAHAN - MIKU MOE\n");
        out.append("Waktu: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())).append('\n');
        out.append("Aplikasi: ").append(appVersion(context)).append('\n');
        out.append("Perangkat: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Lokasi error: ").append(clean(area, "-")).append('\n');
        out.append("Source: ").append(clean(sourceLabel, "-")).append(" (").append(clean(sourceId, "-")).append(")\n");
        out.append("Pesan: ").append(clean(message, "-")).append("\n\n");

        out.append("DUGAAN PENYEBAB\n");
        for (String line : diagnose(message, error, emptyData)) out.append("- ").append(line).append('\n');

        out.append("\nDETAIL EXCEPTION\n");
        if (error == null) {
            out.append(emptyData ? "Tidak ada exception. Request selesai tanpa error tetapi hasil yaitu daftar manga kosong.\n" : "Tidak ada exception yang tercatat, hanya pesan error dari source.\n");
        } else {
            out.append(describeThrowable(error)).append('\n');
        }

        out.append("\nJEJAK JARINGAN MANGA TERAKHIR\n");
        out.append(MangaNetworkTrace.format(14)).append('\n');
        if (extraTrace != null && !extraTrace.trim().isEmpty()) {
            out.append("\nJEJAK TAMBAHAN\n");
            out.append(extraTrace.trim()).append('\n');
        }
        return out.toString().trim();
    }

    public static String buildAnimeReport(Context context, String area, String sourceId, String sourceLabel, String message, Throwable error, boolean emptyData, String details) {
        StringBuilder out = new StringBuilder();
        out.append("LOG KESALAHAN - MIKU MOE\n");
        out.append("Waktu: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())).append('\n');
        out.append("Aplikasi: ").append(appVersion(context)).append('\n');
        out.append("Perangkat: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Lokasi error: ").append(clean(area, "-")).append('\n');
        out.append("Source: ").append(clean(sourceLabel, "-")).append(" (").append(clean(sourceId, "-")).append(")\n");
        out.append("Pesan: ").append(clean(message, "-")).append("\n\n");
        out.append("DUGAAN PENYEBAB\n");
        for (String line : diagnose(message, error, emptyData, false)) out.append("- ").append(line).append('\n');
        out.append("\nDETAIL\n");
        out.append(details == null || details.trim().isEmpty() ? "Tidak ada detail tercatat." : details.trim()).append('\n');
        return out.toString().trim();
    }

    public static ArrayList<String> diagnose(String message, Throwable error, boolean emptyData) {
        return diagnose(message, error, emptyData, true);
    }

    public static ArrayList<String> diagnose(String message, Throwable error, boolean emptyData, boolean includeMangaTrace) {
        ArrayList<String> result = new ArrayList<>();
        String text = message == null ? "" : message;
        String lower = text.toLowerCase(Locale.ROOT);
        ArrayList<String> classes = new ArrayList<>();
        HashSet<Throwable> seen = new HashSet<>();
        Throwable cursor = error;
        while (cursor != null && seen.add(cursor)) {
            classes.add(cursor.getClass().getName());
            String cursorMessage = cursor.getMessage();
            if (cursorMessage != null) lower = lower + " " + cursorMessage.toLowerCase(Locale.ROOT);
            cursor = cursor.getCause();
        }
        StringBuilder classBuilder = new StringBuilder();
        for (String name : classes) classBuilder.append(name).append(' ');
        String classLine = classBuilder.toString();

        if (hasAny(lower, classLine, "unknownhost", "unable to resolve host", "no address associated")) {
            result.add("Nama domain source tidak bisa di-resolve (DNS gagal). Kemungkinan domain source sudah berganti atau mati, DNS diblokir, atau internet bermasalah.");
        }
        if (hasAny(lower, classLine, "connectexception", "failed to connect", "connection refused", "econnrefused")) {
            result.add("Server source tidak bisa dihubungi atau menolak koneksi. Kemungkinan server mati, domain pindah, atau IP diblokir.");
        }
        if (hasAny(lower, classLine, "sockettimeout", "timeout", "timed out")) {
            result.add("Server source tidak merespons tepat waktu. Kemungkinan server lambat atau down, atau koneksi tidak stabil.");
        }
        if (hasAny(lower, classLine, "sslexception", "sslhandshake", "sslpeer", "certpath", "certificate")) {
            result.add("Masalah SSL atau sertifikat. Kemungkinan domain berganti, sertifikat situs kedaluwarsa, atau jaringan memodifikasi koneksi.");
        }
        if (hasAny(lower, classLine, "cloudflare")) {
            result.add("Source meminta verifikasi Cloudflare.");
        }
        if (lower.contains("terlalu banyak request")) {
            result.add("Source membatasi jumlah request (rate limit, HTTP 429). Tunggu beberapa saat lalu coba lagi.");
        }

        HashSet<Integer> codes = new HashSet<>();
        Matcher matcher = HTTP_CODE.matcher(text);
        while (matcher.find()) {
            try { codes.add(Integer.parseInt(matcher.group(1))); } catch (Exception ignored) { }
        }
        for (int code : codes) result.add(describeHttp(code));

        if (hasAny(lower, classLine, "json", "jsonexception", "jsonsyntax", "malformedjson", "unexpected character", "of type org.json")) {
            result.add("Respons source tidak bisa dibaca sebagai JSON yang diharapkan. Kemungkinan struktur API berubah (parser perlu diperbarui), atau server mengirim halaman error atau proteksi anti-bot sebagai ganti data.");
        }
        if (hasAny(lower, classLine, "gagal dibaca", "nullpointer", "indexoutofbounds", "classcast", "numberformat", "nosuchelement", "illegalstate", "illegalargument", "selector")) {
            result.add("Parser gagal membaca data. Kemungkinan struktur HTML atau JSON situs berubah sehingga selector atau field yang dipakai parser tidak ditemukan.");
        }
        if (emptyData) {
            result.add("Request selesai tanpa error tetapi parser menghasilkan 0 item. Kemungkinan struktur situs berubah (selector tidak cocok), halaman yang diterima bukan daftar manga, atau domain mengalihkan ke halaman lain.");
        }

        MangaNetworkTrace.Entry redirect = includeMangaTrace ? MangaNetworkTrace.lastDomainRedirect() : null;
        if (redirect != null) {
            result.add("Terdeteksi pengalihan domain: " + MangaNetworkTrace.hostOf(redirect.url) + " menjadi " + MangaNetworkTrace.hostOf(redirect.finalUrl) + ". Domain source kemungkinan sudah pindah dan base URL source perlu diperbarui.");
        }

        MangaNetworkTrace.Entry failure = includeMangaTrace ? MangaNetworkTrace.lastFailure() : null;
        if (failure != null) {
            String detail = failure.errorClass.length() > 0 ? failure.errorClass + (failure.errorMessage.isEmpty() ? "" : ": " + failure.errorMessage) : "HTTP " + failure.code + (failure.message.isEmpty() ? "" : " " + failure.message);
            result.add("Request gagal terakhir di jaringan: " + failure.method + " " + failure.url + " -> " + detail + ".");
        }

        if (result.isEmpty()) result.add("Penyebab pasti tidak bisa disimpulkan dari pesan error. Periksa detail exception dan jejak jaringan di bawah.");
        return result;
    }

    private static String describeHttp(int code) {
        if (code == 401) return "HTTP 401: source meminta autentikasi atau login.";
        if (code == 403) return "HTTP 403: akses ditolak server. Kemungkinan proteksi anti-bot atau Cloudflare, atau IP atau region diblokir.";
        if (code == 404) return "HTTP 404: alamat yang diminta tidak ada. Kemungkinan domain atau path API berubah sehingga URL di parser sudah tidak valid.";
        if (code == 410) return "HTTP 410: alamat yang diminta sudah dihapus permanen oleh source.";
        if (code == 429) return "HTTP 429: terlalu banyak request, source membatasi akses sementara.";
        if (code >= 500 && code <= 599) return "HTTP " + code + ": server source sedang bermasalah atau down. Biasanya sementara, coba lagi nanti.";
        if (code >= 300 && code <= 399) return "HTTP " + code + ": source mengalihkan alamat. Kemungkinan domain atau path berubah.";
        return "HTTP " + code + ": respons tidak sukses dari server source.";
    }

    private static boolean hasAny(String lowerText, String classLine, String... needles) {
        String classes = classLine == null ? "" : classLine.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lowerText.contains(needle) || classes.contains(needle)) return true;
        }
        return false;
    }

    private static String describeThrowable(Throwable error) {
        StringBuilder out = new StringBuilder();
        HashSet<Throwable> seen = new HashSet<>();
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && seen.add(cursor) && depth < MAX_CAUSES) {
            out.append(depth == 0 ? "Exception: " : "Penyebab " + depth + ": ").append(cursor.getClass().getName()).append('\n');
            out.append("Pesan: ").append(cursor.getMessage() == null ? "<tanpa pesan>" : cursor.getMessage()).append('\n');
            cursor = cursor.getCause();
            depth++;
        }
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        String[] lines = writer.toString().split("\n");
        out.append("Stack trace:\n");
        int limit = Math.min(lines.length, MAX_STACK_LINES);
        for (int i = 0; i < limit; i++) out.append(lines[i]).append('\n');
        if (lines.length > limit) out.append("... ").append(lines.length - limit).append(" baris lagi dipotong\n");
        return out.toString().trim();
    }

    private static String appVersion(Context context) {
        if (context == null) return "-";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "-" : info.versionName;
        } catch (Exception ignored) {
            return "-";
        }
    }

    private static String clean(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
