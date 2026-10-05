import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Archives one month of the weekly schedule into archive/<month><year>.html.
 *
 * Weeks are collected from two places:
 *   - the collapsed "Earlier weeks" blocks of index.html (they are removed from index.html afterwards);
 *   - the export files js/schedule/YYYY_WW.js (they stay where they are, the site only loads the current month).
 * A week belongs to the month of its Thursday, so 28 Sep - 4 Oct is an October week.
 * Then the Archives list of index.html is updated and the new page gets its own list.
 *
 * Run from the repository root (archive-schedule.bat does it):
 *   java ArchiveSchedule.java [YYYY-MM] [--force] [--dry-run]
 * Without a month, the previous month is archived. Output is plain ASCII on purpose (Windows console).
 */
public class ArchiveSchedule {

    static final Path ROOT = Paths.get("").toAbsolutePath();
    static final Path INDEX = ROOT.resolve("index.html");
    static final Path ARCHIVE = ROOT.resolve("archive");
    static final Path DATA = ROOT.resolve("js").resolve("schedule");
    static final Locale EN = Locale.ENGLISH;
    static final String CLIPBOARD = "\uD83D\uDCCB";
    static final String NEW = "\uD83C\uDD95";
    static final List<String> TOP = List.of("\u2B50", "\uD83D\uDD1D", "\uD83D\uDC4D", "\uD83D\uDD25");

    static boolean dryRun;

    public static void main(String[] args) throws IOException {
        boolean force = false;
        YearMonth month = null;
        for (String a : args) {
            if (a.equals("--force")) force = true;
            else if (a.equals("--dry-run")) dryRun = true;
            else {
                try {
                    month = YearMonth.parse(a);
                } catch (DateTimeParseException e) {
                    die("Unknown argument '" + a + "'. Usage: ArchiveSchedule [YYYY-MM] [--force] [--dry-run]");
                }
            }
        }
        YearMonth current = YearMonth.from(LocalDate.now(ZoneId.of("Europe/Moscow")));
        if (month == null) month = current.minusMonths(1);
        if (!month.isBefore(current) && !force) {
            die("Month " + month + " is not over yet. Use --force to archive it anyway.");
        }
        if (!Files.exists(INDEX) || !Files.isDirectory(ARCHIVE)) {
            die("Run it from the repository root: index.html and archive/ were not found in " + ROOT);
        }

        Path page = ARCHIVE.resolve(pageName(month));
        if (Files.exists(page) && !force) {
            die(page.getFileName() + " already exists. Use --force to rebuild it (only weeks that are still in index.html or js/schedule/ are kept).");
        }

        String index = read(INDEX);
        Map<LocalDate, String> weeks = new TreeMap<>(Comparator.reverseOrder());   // Monday -> article

        // 1) hand-made weeks folded in index.html
        Matcher fold = Pattern.compile("[ \\t]*<details class=\"fold\">\\s*<summary>(.*?)</summary>(.*?)</details>\\s*", Pattern.DOTALL).matcher(index);
        StringBuilder rest = new StringBuilder();
        int last = 0, folded = 0;
        while (fold.find()) {
            Matcher date = Pattern.compile("data-date=\"(\\d{4}-\\d{2}-\\d{2})\"").matcher(fold.group(2));
            if (!date.find()) continue;
            LocalDate monday = mondayOf(LocalDate.parse(date.group(1)));
            if (!YearMonth.from(monday.plusDays(3)).equals(month)) continue;
            weeks.putIfAbsent(monday, foldedWeek(fold.group(1), fold.group(2)));
            rest.append(index, last, fold.start());
            last = fold.end();
            folded++;
        }
        rest.append(index.substring(last));
        index = rest.toString();

        // 2) export files
        int files = 0;
        if (Files.isDirectory(DATA)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(DATA, "*.js")) {
                for (Path file : stream) {
                    if (!file.getFileName().toString().matches("\\d{4}_\\d+\\.js")) continue;
                    Map<String, Object> data = parseExport(read(file));
                    List<Object> days = list(data.get("days"));
                    if (days.isEmpty()) continue;
                    LocalDate monday = mondayOf(LocalDate.parse(str(map(days.get(0)).get("date"))));
                    if (!YearMonth.from(monday.plusDays(3)).equals(month)) continue;
                    if (weeks.putIfAbsent(monday, exportedWeek(data)) == null) files++;
                }
            }
        }
        if (weeks.isEmpty()) die("No weeks of " + month + " found in index.html or js/schedule/.");

        // the new page
        String template = newestArchivePage(month);
        String html = buildPage(template, month, new ArrayList<>(weeks.values()));
        index = updateIndexList(index, month, current);

        System.out.println("Month: " + title(month) + ", weeks: " + weeks.size() + " (from index.html: " + folded + ", from files: " + files + ")");
        write(page, html, template);
        write(INDEX, index, null);
        System.out.println(dryRun ? "Dry run: nothing was written." : "Done: " + ROOT.relativize(page) + " created, index.html updated.");
    }

    // ---------- weeks ----------

    static String foldedWeek(String summary, String body) {
        Matcher m = Pattern.compile("<article class=\"prose\">(.*?)</article>", Pattern.DOTALL).matcher(body);
        String inner = m.find() ? m.group(1).strip() : body.strip();
        inner = retarget(inner);
        return "<article class=\"prose\">\n  <h2>" + summary.strip() + "</h2>\n  " + inner + "\n</article>";
    }

    /** index.html paths -> archive/ paths */
    static String retarget(String s) {
        s = s.replaceAll("([\"'(])(img|metro|css|js|fonts|clubs)/", "$1../$2/");
        s = s.replaceAll("([\"'])(index|clubs|teachers|qr-code|tg-post)\\.html", "$1../$2.html");
        return s.replaceAll("([\"'])archive/", "$1");
    }

    static String exportedWeek(Map<String, Object> data) {
        List<Object> days = list(data.get("days"));
        int total = 0;
        for (Object d : days) total += list(map(d).get("events")).size();
        StringBuilder sb = new StringBuilder();
        sb.append("<article class=\"prose\">\n");
        sb.append("  <h2>This Week in Moscow: ").append(esc(str(data.get("weekRange")))).append("</h2>\n");
        LocalDate generated = LocalDate.parse(str(data.get("generatedAt")).substring(0, 10));
        sb.append("  <p class=\"meta\">").append(generated.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", EN)))
                .append(" by <a href=\"#contacts\">Alexey Leonov</a></p>\n");
        sb.append("<p>This week we have <strong>").append(total).append(" events</strong> across the city and online.</p>\n");
        sb.append("<h2>").append(CLIPBOARD).append(" Weekly Schedule</h2>\n");
        sb.append("<p class=\"legend\"><span><b class=\"tag\">New</b> recently added</span><span><b class=\"tag tag--gold\">Top</b> our pick</span></p>\n");
        for (Object d : days) sb.append(day(map(d)));
        return sb.append("</article>").toString();
    }

    static String day(Map<String, Object> day) {
        LocalDate date = LocalDate.parse(str(day.get("date")));
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object e : list(day.get("events"))) events.add(map(e));
        events.sort(Comparator.comparing(e -> str(e.get("time"))));
        List<String> offline = new ArrayList<>(), online = new ArrayList<>();
        for (Map<String, Object> e : events) (Boolean.TRUE.equals(e.get("online")) ? online : offline).add(row(e));
        StringBuilder sb = new StringBuilder();
        sb.append(" <section class=\"day\" data-date=\"").append(date).append("\" id=\"day-").append(date).append("\">\n");
        sb.append("<h3>").append(date.getDayOfWeek().getDisplayName(TextStyle.FULL, EN)).append(", ")
                .append(date.getMonth().getDisplayName(TextStyle.FULL, EN)).append(' ').append(date.getDayOfMonth())
                .append(" <small>").append(events.size()).append(events.size() == 1 ? " meetup" : " meetups").append("</small></h3>\n");
        if (!offline.isEmpty()) sb.append("<ul class=\"route\">\n").append(String.join("\n", offline)).append("\n</ul>\n");
        if (!online.isEmpty()) {
            sb.append("<p class=\"route-label\">Online</p>\n<ul class=\"route route--alt\">\n")
                    .append(String.join("\n", online)).append("\n</ul>\n");
        }
        return sb.append("</section>\n\n").toString();
    }

    static String row(Map<String, Object> e) {
        String place = str(e.get("place"));
        boolean online = Boolean.TRUE.equals(e.get("online"));
        String where = "";
        if (!online && !place.isEmpty()) {
            where = place.matches(".*[\\u0400-\\u04FF].*")
                    ? " <span class=\"where\"><span lang=\"ru\">" + esc(place) + "</span></span>"
                    : " <span class=\"where\">" + esc(place) + "</span>";
        }
        String emoji = str(e.get("emoji"));
        String tag = emoji.equals(NEW) ? " <b class=\"tag\">New</b>" : TOP.contains(emoji) ? " <b class=\"tag tag--gold\">Top</b>" : "";
        return "<li><time>" + esc(str(e.get("time"))) + "</time> <a href=\"" + esc(href(str(e.get("link")))) + "\" target=\"_blank\">"
                + esc(str(e.get("club"))) + "</a>" + where + tag + "</li>";
    }

    /** a bare page name from the export (viva-lingua-club.html) lives in clubs/; archive pages are one level deeper */
    static String href(String link) {
        if (link.matches("[\\w\\-]+\\.html")) return "../clubs/" + link;
        if (link.startsWith("clubs/")) return "../" + link;
        return link;
    }

    // ---------- pages ----------

    static String pageName(YearMonth m) {
        return m.getMonth().getDisplayName(TextStyle.FULL, EN).toLowerCase(EN) + m.getYear() + ".html";
    }

    static String title(YearMonth m) {
        return m.getMonth().getDisplayName(TextStyle.FULL, EN) + " " + m.getYear();
    }

    /** the latest existing archive page is the template: head, header, sidebar and footer are copied from it */
    static String newestArchivePage(YearMonth target) throws IOException {
        Path best = null;
        YearMonth bestMonth = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(ARCHIVE, "*.html")) {
            for (Path p : stream) {
                Matcher m = Pattern.compile("([a-z]+)(\\d{4})\\.html").matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                YearMonth ym = YearMonth.of(Integer.parseInt(m.group(2)), Month.valueOf(m.group(1).toUpperCase(EN)));
                if (ym.equals(target)) continue;
                if (bestMonth == null || ym.isAfter(bestMonth)) { best = p; bestMonth = ym; }
            }
        }
        if (best == null) die("No archive page to use as a template in archive/.");
        return read(best);
    }

    static String buildPage(String template, YearMonth month, List<String> articles) {
        String t = template.replace("\r\n", "\n");
        t = t.replaceFirst("<title>[^<]*</title>", Matcher.quoteReplacement("<title>" + title(month) + " \u2014 Time to Learn English</title>"));
        t = t.replaceFirst("<h1>[^<]*</h1>", Matcher.quoteReplacement("<h1>" + title(month) + "</h1>"));

        int main = t.indexOf("<div class=\"layout__main\">");
        int aside = t.indexOf("<aside class=\"layout__aside\">");
        if (main < 0 || aside < 0) die("The template page has no layout__main / layout__aside.");
        int close = t.lastIndexOf("</div>", aside);
        int from = main + "<div class=\"layout__main\">".length();
        t = t.substring(0, from) + "\n" + String.join("\n\n", articles) + "\n    " + t.substring(close);

        // own list: this month (no link) and the older ones, as the other archive pages do
        Matcher ol = ARCHIVES.matcher(t);
        if (!ol.find()) die("The template page has no Archives list.");
        TreeMap<YearMonth, String> months = new TreeMap<>(Comparator.reverseOrder());
        Matcher li = LI.matcher(ol.group(2));
        while (li.find()) {
            YearMonth ym = YearMonth.parse(li.group(2), MONTH_YEAR);
            if (!ym.isAfter(month)) months.put(ym, pageName(ym));
        }
        months.put(month, "#");
        t = t.substring(0, ol.start(2)) + listHtml(months) + t.substring(ol.end(2));
        return t;
    }

    static final Pattern ARCHIVES = Pattern.compile("(id=\"archives\">\\s*<h4>Archives</h4>\\s*<ol>)(.*?)(</ol>)", Pattern.DOTALL);
    static final Pattern LI = Pattern.compile("<li><a href=\"([^\"]*)\">([A-Za-z]+ \\d{4})</a></li>");
    static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", EN);

    static String listHtml(Map<YearMonth, String> months) {
        StringBuilder sb = new StringBuilder("\n");
        months.forEach((ym, href) -> sb.append("            <li><a href=\"").append(href).append("\">").append(title(ym)).append("</a></li>\n"));
        return sb.append("          ").toString();
    }

    /** the new month gets its link; the current month stays on top without one */
    static String updateIndexList(String index, YearMonth month, YearMonth current) {
        Matcher ol = ARCHIVES.matcher(index);
        if (!ol.find()) die("index.html has no Archives list.");
        TreeMap<YearMonth, String> months = new TreeMap<>(Comparator.reverseOrder());
        Matcher li = LI.matcher(ol.group(2));
        while (li.find()) months.put(YearMonth.parse(li.group(2), MONTH_YEAR), li.group(1));
        months.put(month, "archive/" + pageName(month));
        months.putIfAbsent(current, "#");
        return index.substring(0, ol.start(2)) + listHtml(months) + index.substring(ol.end(2));
    }

    static LocalDate mondayOf(LocalDate d) {
        return d.with(DayOfWeek.MONDAY);
    }

    // ---------- files ----------

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** keeps the line endings of the file the text came from (the template for new pages) */
    static void write(Path p, String text, String sameEndingsAs) throws IOException {
        String ref = sameEndingsAs;
        if (ref == null && Files.exists(p)) ref = read(p);
        String out = text.replace("\r\n", "\n");
        if (ref != null && ref.contains("\r\n")) out = out.replace("\n", "\r\n");
        if (dryRun) return;
        Files.writeString(p, out, StandardCharsets.UTF_8);
    }

    static void die(String message) {
        System.err.println("Error: " + message);
        System.exit(1);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------- export file: "const schedule_2026_41 = {...};" ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o == null ? List.of() : (List<Object>) o; }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static Map<String, Object> parseExport(String js) {
        int from = js.indexOf('{'), to = js.lastIndexOf('}');
        return map(new Json(js.substring(from, to + 1)).value());
    }

    /** a minimal JSON reader, enough for the export (no dependencies, so the script runs with plain `java`) */
    static class Json {
        final String s;
        int i;

        Json(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++; ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws(); i++;   // ':'
                    m.put(k, value());
                    ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                i++; ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.valueOf(s.substring(start, i));
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;   // opening quote
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c != '\\') { sb.append(c); continue; }
                char n = s.charAt(i++);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(n);
                }
            }
            i++;
            return sb.toString();
        }
    }
}
