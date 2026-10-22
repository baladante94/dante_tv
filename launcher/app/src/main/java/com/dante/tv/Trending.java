package com.dante.tv;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Trending rows from the services on this TV: Netflix's own weekly Top 10 for the TV's country
 * (top10.netflix.com), and what is popular on Apple TV and Crunchyroll there (TMDB, availability from JustWatch).
 * Fetched in the background at most every few hours and kept on disk, so Home never waits for the network.
 */
final class Trending {

    static final class Item {
        final String section, title, pkg, meta;
        final File backdrop, poster;
        /** A series (Shows tab) rather than a film (Movies tab). */
        final boolean series;
        /** The title's own ID in its app, when Wikidata knows it ("" otherwise), and the shelf's country. */
        final String netflixId, appleId, crunchyrollId, hotstarUrl, region;

        Item(String section, String title, String pkg, String meta, File backdrop, File poster, boolean series,
             String netflixId, String appleId, String crunchyrollId, String hotstarUrl, String region) {
            this.series = series;
            this.hotstarUrl = hotstarUrl;
            this.netflixId = netflixId;
            this.crunchyrollId = crunchyrollId;
            this.appleId = appleId;
            this.region = region;
            this.section = section;
            this.title = title;
            this.pkg = pkg;
            this.meta = meta;
            this.backdrop = backdrop;
            this.poster = poster;
        }
    }

    private static final String NETFLIX = "com.netflix.ninja", APPLE = "com.apple.atve.androidtv.appletv",
            CRUNCHYROLL = "com.crunchyroll.crunchyroid", HOTSTAR = "in.startv.hotstar";
    /** Shows and films kept per service, so the Movies and Shows tabs have enough of each. */
    private static final int PER_KIND = 6;
    private static final long TMDB_EVERY_MS = 12 * 60 * 60_000L, NETFLIX_EVERY_MS = 3 * 24 * 60 * 60_000L;
    private static final String TOP10 = "https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv";
    private static volatile boolean running;

    private Trending() {
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "trending");
        d.mkdirs();
        return d;
    }

    /** What is on disk now, in row order; empty until the first fetch has finished. */
    static List<Item> cached(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(new File(dir(ctx), "trending.json").toPath()),
                    StandardCharsets.UTF_8));
            String region = root.optString("region", "");
            for (String key : new String[]{"netflix", "apple", "crunchyroll", "hotstar"}) {
                JSONArray arr = root.optJSONArray(key);
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    File backdrop = new File(o.getString("image")), poster = new File(o.optString("poster", ""));
                    if (backdrop.isFile() && poster.isFile() && installed(ctx, o.getString("pkg"))) {
                        out.add(new Item(o.getString("section"), o.getString("title"), o.getString("pkg"),
                                o.optString("meta", ""), backdrop, poster, o.optBoolean("series"),
                                o.optString("nf", ""), o.optString("atv", ""), o.optString("cr", ""), o.optString("hs", ""), region));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Forgets the rows on disk, so the next refresh fetches everything (a new key or region). */
    static void reset(Context ctx) {
        new File(dir(ctx), "trending.json").delete();
    }

    /** Refreshes in the background when the rows are old; {@code done} runs on that thread if anything changed. */
    static void refreshIfStale(Context ctx, String token, String region, Runnable done) {
        if (token == null || token.isEmpty() || running) return;
        final Context app = ctx.getApplicationContext();
        running = true;
        new Thread(() -> {
            try {
                if (refresh(app, token, region)) done.run();
            } catch (Exception e) {
                android.util.Log.w("TvHome.Trending", "refresh failed", e);
            } finally {
                running = false;
            }
        }, "trending").start();
    }

    private static boolean refresh(Context ctx, String token, String region) throws Exception {
        File file = new File(dir(ctx), "trending.json");
        JSONObject root = file.isFile()
                ? new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
        long now = System.currentTimeMillis();
        boolean changed = false;
        // An older cache (no posters or film/series split) or another country starts over.
        // v5: JioHotstar, and each title's TMDB and app IDs (for opening the title in its app).
        if (root.optInt("v") != 6 || !region.equals(root.optString("region"))) {
            root = new JSONObject().put("v", 6).put("region", region);
        }
        loadGenres(token);
        if (now - root.optLong("netflixAt") > NETFLIX_EVERY_MS && installed(ctx, NETFLIX)) {
            JSONArray rows = netflix(ctx, token, region);
            if (rows.length() > 0) {
                root.put("netflix", rows).put("netflixAt", now);
                changed = true;
            }
        }
        if (now - root.optLong("tmdbAt") > TMDB_EVERY_MS) {
            if (installed(ctx, APPLE)) root.put("apple", popular(ctx, token, region, 350, "Popular on Apple TV", APPLE));
            if (installed(ctx, CRUNCHYROLL)) root.put("crunchyroll", popular(ctx, token, region, 283, "Popular on Crunchyroll", CRUNCHYROLL));
            if (installed(ctx, HOTSTAR)) root.put("hotstar", popular(ctx, token, region, 2336, "Popular on JioHotstar", HOTSTAR));
            root.put("tmdbAt", now);
            changed = true;
        }
        // App IDs come from Wikidata, which rate-limits at times: a failed lookup is tried again after half an hour
        // instead of waiting for the next 12-hour refresh (until then those cards open the app).
        if (changed || (!root.optBoolean("idsOk", false) && now - root.optLong("idsAt") > IDS_RETRY_MS)) {
            root.put("idsOk", appIds(root)).put("idsAt", now);
            changed = true;
        }
        if (changed) {
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            cleanImages(ctx, root);
        }
        return changed;
    }

    /**
     * Netflix's weekly Top 10 for one country: the published file is sorted by country with the newest week first,
     * so reading stops as soon as that country's latest week has gone by.
     */
    private static JSONArray netflix(Context ctx, String token, String region) throws Exception {
        List<String> films = new ArrayList<>(), shows = new ArrayList<>();
        HttpURLConnection c = open(TOP10, null);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line, week = null;
            while ((line = in.readLine()) != null) {
                String[] f = line.split("\t");
                if (f.length < 6 || !region.equals(f[1])) {
                    if (week != null) break;
                    continue;
                }
                if (week == null) week = f[2];
                if (!week.equals(f[2])) break;
                ("TV".equals(f[3]) ? shows : films).add(f[5].replace("​", "").trim());
            }
        } finally {
            c.disconnect();
        }
        JSONArray out = new JSONArray();
        int tv = 0, mv = 0;
        for (int i = 0; i < 10; i++) {
            if (i < shows.size() && tv < PER_KIND && addSearched(ctx, token, region, out, shows.get(i), true, "Top 10 on Netflix", NETFLIX)) tv++;
            if (i < films.size() && mv < PER_KIND && addSearched(ctx, token, region, out, films.get(i), false, "Top 10 on Netflix", NETFLIX)) mv++;
        }
        return out;
    }

    private static boolean addSearched(Context ctx, String token, String region, JSONArray out, String title, boolean tv,
                                       String section, String pkg) throws Exception {
        JSONObject r = tmdb(token, "/search/" + (tv ? "tv" : "movie") + "?region=" + region + "&query="
                + URLEncoder.encode(title, "UTF-8"));
        JSONArray results = r.optJSONArray("results");
        if (results == null || results.length() == 0) return false;
        return add(ctx, out, results.getJSONObject(0), title, section, pkg, tv);
    }

    /** The most popular series and films on one service in the country this week, interleaved. */
    private static JSONArray popular(Context ctx, String token, String region, int provider, String section, String pkg)
            throws Exception {
        String q = "?watch_region=" + region + "&with_watch_providers=" + provider + "&sort_by=popularity.desc";
        JSONArray tv = tmdb(token, "/discover/tv" + q).optJSONArray("results");
        JSONArray mv = tmdb(token, "/discover/movie" + q).optJSONArray("results");
        JSONArray out = new JSONArray();
        int shows = 0, films = 0;
        for (int i = 0; i < 20; i++) {
            if (tv != null && i < tv.length() && shows < PER_KIND && add(ctx, out, tv.getJSONObject(i), null, section, pkg, true)) shows++;
            if (mv != null && i < mv.length() && films < PER_KIND && add(ctx, out, mv.getJSONObject(i), null, section, pkg, false)) films++;
        }
        return out;
    }

    private static final java.util.Map<Integer, String> GENRES = new java.util.HashMap<>();

    private static void loadGenres(String token) {
        if (!GENRES.isEmpty()) return;
        for (String kind : new String[]{"tv", "movie"}) {
            try {
                JSONArray g = tmdb(token, "/genre/" + kind + "/list").optJSONArray("genres");
                for (int i = 0; g != null && i < g.length(); i++) {
                    GENRES.put(g.getJSONObject(i).getInt("id"), g.getJSONObject(i).getString("name"));
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** One card: the wide backdrop and the poster, saved at TV size, and a details line for under the card. */
    private static boolean add(Context ctx, JSONArray out, JSONObject t, String title, String section, String pkg,
                               boolean tv) throws Exception {
        String back = t.optString("backdrop_path", ""), post = t.optString("poster_path", "");
        if (back.isEmpty() || "null".equals(back) || post.isEmpty() || "null".equals(post)) return false;
        String name = title != null ? title : t.optString("name", t.optString("title", ""));
        File backdrop = image(ctx, "w780", back), poster = image(ctx, "w342", post);
        StringBuilder meta = new StringBuilder(tv ? "Series" : "Film");
        JSONArray ids = t.optJSONArray("genre_ids");
        StringBuilder genres = new StringBuilder();
        for (int i = 0, n = 0; ids != null && i < ids.length() && n < 2; i++) {
            String g = GENRES.get(ids.optInt(i));
            if (g == null) continue;
            if (n++ > 0) genres.append(", ");
            genres.append(g.replace("Action & Adventure", "Action").replace("Sci-Fi & Fantasy", "Sci-Fi"));
        }
        if (genres.length() > 0) meta.append(" \u00B7 ").append(genres);
        meta.append(" \u00B7 ").append(section.replace("Top 10 on ", "").replace("Popular on ", ""));
        double rating = t.optDouble("vote_average", 0);
        if (rating > 0) meta.append(String.format(java.util.Locale.ROOT, " \u00B7 \u2605 %.1f", rating));
        out.put(new JSONObject().put("section", section).put("title", name).put("pkg", pkg).put("id", t.optInt("id"))
                .put("meta", meta.toString()).put("image", backdrop.getPath()).put("poster", poster.getPath())
                .put("series", tv));
        return true;
    }

    private static File image(Context ctx, String size, String path) throws Exception {
        File img = new File(dir(ctx), size + path.replace("/", ""));
        if (img.isFile()) return img;
        HttpURLConnection c = open("https://image.tmdb.org/t/p/" + size + path, null);
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(img)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        return img;
    }

    /** Removes pictures no row uses any more. */
    private static void cleanImages(Context ctx, JSONObject root) {
        String json = root.toString();
        File[] files = dir(ctx).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().endsWith(".jpg") && !json.contains(f.getName())) f.delete();
        }
    }

    private static final long IDS_RETRY_MS = 30 * 60_000L;
    private static final String[] ROWS = {"netflix", "apple", "crunchyroll", "hotstar"};

    /**
     * Each card's title in its own app, so a card opens the title rather than the app. TMDB has no such IDs.
     * JustWatch (which feeds TMDB's "where to watch") has each title's link on every service, new releases
     * included: one batched request for all cards gives the Netflix ID, the Apple TV show or film ID and the
     * JioHotstar page. Its Crunchyroll links point at episodes, which the app ignores, so Crunchyroll series IDs
     * come from Wikidata (P11330), which also fills any Netflix or Apple TV ID JustWatch missed.
     * @return false when a lookup failed (rate limit, network), so refresh() tries again later.
     */
    private static boolean appIds(JSONObject root) {
        List<JSONObject> items = new ArrayList<>();
        try {
            for (String key : ROWS) {
                JSONArray arr = root.optJSONArray(key);
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (o.optInt("id") != 0 && !o.optBoolean("looked")) items.add(o);
                }
            }
        } catch (Exception e) {
            return false;
        }
        if (items.isEmpty()) return true;
        boolean ok = justWatch(items) & wikidata(items);
        if (ok) {
            for (JSONObject o : items) {
                try {
                    o.put("looked", true);
                } catch (Exception ignored) {
                }
            }
        }
        return ok;
    }

    private static boolean justWatch(List<JSONObject> items) {
        try {
            StringBuilder q = new StringBuilder("query {");
            for (int i = 0; i < items.size(); i++) {
                q.append(" t").append(i).append(": popularTitles(country: IN, first: 3, filter: {searchQuery: ")
                        .append(JSONObject.quote(items.get(i).getString("title")))
                        .append("}) { edges { node { objectType content(country: IN, language: \"en\") { externalIds { tmdbId } }")
                        .append(" offers(country: IN, platform: WEB) { standardWebURL package { clearName } } } } }");
            }
            q.append(" }");
            HttpURLConnection c = open("https://apis.justwatch.com/graphql", null);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(new JSONObject().put("query", q.toString()).toString().getBytes(StandardCharsets.UTF_8));
            }
            JSONObject data;
            try (InputStream in = c.getInputStream()) {
                data = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8)).getJSONObject("data");
            } finally {
                c.disconnect();
            }
            for (int i = 0; i < items.size(); i++) {
                JSONObject o = items.get(i);
                JSONObject hits = data.optJSONObject("t" + i);
                JSONArray edges = hits == null ? null : hits.optJSONArray("edges");
                for (int e = 0; edges != null && e < edges.length(); e++) {
                    JSONObject node = edges.getJSONObject(e).getJSONObject("node");
                    boolean series = "SHOW".equals(node.optString("objectType"));
                    String tmdb = node.getJSONObject("content").getJSONObject("externalIds").optString("tmdbId");
                    if (series != o.optBoolean("series") || !tmdb.equals(String.valueOf(o.optInt("id")))) continue;
                    JSONArray offers = node.optJSONArray("offers");
                    for (int f = 0; offers != null && f < offers.length(); f++) {
                        JSONObject offer = offers.getJSONObject(f);
                        String name = offer.getJSONObject("package").optString("clearName").toLowerCase(java.util.Locale.ROOT);
                        String url = offer.optString("standardWebURL");
                        if (name.contains("channel")) continue;   // the Amazon channel of a service, not the service's app
                        java.util.regex.Matcher m;
                        if (name.startsWith("netflix") && !o.has("nf")
                                && (m = java.util.regex.Pattern.compile("netflix\\.com/title/(\\d+)").matcher(url)).find()) {
                            o.put("nf", m.group(1));
                        } else if (name.startsWith("apple tv") && !o.has("atv")) {
                            // A show's link is to an episode and names the show in showId; a film's ends in its own ID.
                            m = java.util.regex.Pattern.compile(series ? "showId=(umc\\.cmc\\.[a-z0-9]+)" : "/(umc\\.cmc\\.[a-z0-9]+)").matcher(url);
                            if (m.find()) o.put("atv", m.group(1));
                        } else if (name.contains("hotstar") && !o.has("hs") && url.contains("hotstar.com/")) {
                            o.put("hs", url);
                        }
                    }
                    break;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean wikidata(List<JSONObject> items) {
        try {
            StringBuilder tv = new StringBuilder(), mv = new StringBuilder();
            List<JSONObject> asked = new ArrayList<>();
            for (JSONObject o : items) {
                boolean need = "com.crunchyroll.crunchyroid".equals(o.optString("pkg")) && o.optBoolean("series") ? !o.has("cr")
                        : "com.netflix.ninja".equals(o.optString("pkg")) ? !o.has("nf")
                        : "com.apple.atve.androidtv.appletv".equals(o.optString("pkg")) && !o.has("atv");
                if (!need) continue;
                asked.add(o);
                (o.optBoolean("series") ? tv : mv).append(" \"").append(o.optInt("id")).append('"');
            }
            if (asked.isEmpty()) return true;
            String query = "SELECT ?tv ?mv ?nf ?atvs ?atvm ?cr WHERE {"
                    + " { VALUES ?tv {" + tv + " } ?item wdt:P4983 ?tv. } UNION { VALUES ?mv {" + mv + " } ?item wdt:P4947 ?mv. }"
                    + " OPTIONAL { ?item wdt:P1874 ?nf } OPTIONAL { ?item wdt:P9751 ?atvs } OPTIONAL { ?item wdt:P9586 ?atvm }"
                    + " OPTIONAL { ?item wdt:P11330 ?cr } }";
            HttpURLConnection c = open("https://query.wikidata.org/sparql?format=json&query="
                    + URLEncoder.encode(query, "UTF-8"), null);
            c.setRequestProperty("Accept", "application/sparql-results+json");
            JSONArray rows;
            try (InputStream in = c.getInputStream()) {
                rows = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8))
                        .getJSONObject("results").getJSONArray("bindings");
            } finally {
                c.disconnect();
            }
            for (int r = 0; r < rows.length(); r++) {
                JSONObject b = rows.getJSONObject(r);
                boolean series = b.has("tv");
                String id = b.getJSONObject(series ? "tv" : "mv").getString("value");
                String atvKey = series ? "atvs" : "atvm";
                for (JSONObject o : asked) {
                    if (o.optBoolean("series") != series || !id.equals(String.valueOf(o.optInt("id")))) continue;
                    if (b.has("nf") && !o.has("nf")) o.put("nf", b.getJSONObject("nf").getString("value"));
                    if (b.has(atvKey) && !o.has("atv")) o.put("atv", b.getJSONObject(atvKey).getString("value"));
                    if (b.has("cr") && !o.has("cr")) o.put("cr", b.getJSONObject("cr").getString("value"));
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when TMDB accepts this key (the setup screen checks before keeping it). */
    static boolean check(String token) {
        try {
            return tmdb(token, "/configuration").has("images");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * TMDB accepts two kinds of key: the API Read Access Token (sent as a bearer header) and the short API Key
     * (sent as api_key in the address). api.tmdb.org, because some Indian ISPs block api.themoviedb.org.
     */
    private static JSONObject tmdb(String token, String path) throws Exception {
        boolean bearer = token.startsWith("eyJ");
        String url = "https://api.tmdb.org/3" + path
                + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(token, "UTF-8"));
        HttpURLConnection c = open(url, bearer ? token : null);
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "DanteTV/1.1 (github.com/baladante94/dante_tv)");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
