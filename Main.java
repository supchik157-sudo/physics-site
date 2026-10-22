import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Сайт с конспектами по физике на чистой Java (без внешних библиотек).
 *
 * Запуск сервера (из папки проекта):   java Main.java
 * Другой порт:                         java Main.java 9000   (или переменная окружения PORT)
 * Выгрузка в готовые HTML-файлы:       java Main.java export          (папка dist)
 *                                      java Main.java export my-folder
 *
 * Конспекты лежат в папке notes/ обычными .html-файлами. В режиме сервера файлы читаются
 * при каждом запросе, так что после правки достаточно обновить страницу в браузере.
 */
public class Main {

    static final String SITE_NAME = "Конспекты по физике";
    static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    static final Path NOTES_DIR = ROOT.resolve("notes");
    static final Path STATIC_DIR = ROOT.resolve("static");
    static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]*");

    // ссылки на конспекты внутри текста: href="/note/имя" и href="/"
    static final Pattern NOTE_LINK = Pattern.compile("href=([\"'])/note/([a-z0-9-]+)\\1");
    static final Pattern HOME_LINK = Pattern.compile("href=([\"'])/\\1");

    /** icon: необязательная иконка конспекта (если пусто, берётся иконка раздела). */
    record Note(String slug, String title, String section, int order, String summary, String icon, String body) {}

    /** Оформление раздела: оттенок цвета (0-359) и иконка. */
    record Theme(int hue, String icon) {}

    /**
     * Как строить ссылки. Сервер отдаёт страницы по адресам /note/имя, а при выгрузке в файлы
     * все страницы лежат в одной папке и ссылки относительные: так сайт работает и в подпапке
     * (например, на GitHub Pages: ник.github.io/название-репозитория/).
     */
    record Urls(String home, String assets, Function<String, String> noteUrl) {
        String note(String slug) {
            return noteUrl.apply(slug);
        }
    }

    static final Urls SERVER = new Urls("/", "/static/", slug -> "/note/" + slug);
    static final Urls EXPORT = new Urls("index.html", "static/", slug -> slug + ".html");

    // ------------------------------------------------------------------ запуск

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("export")) {
            export(Path.of(args.length > 1 ? args[1] : "dist"));
            return;
        }

        String portText = args.length > 0 ? args[0] : System.getenv("PORT");
        int port = 8080;
        if (portText != null && !portText.isBlank()) {
            try {
                port = Integer.parseInt(portText.strip());
            } catch (NumberFormatException e) {
                System.err.println("Bad port: " + portText);
                System.exit(1);
            }
        }
        if (!Files.isDirectory(NOTES_DIR)) {
            System.err.println("Folder 'notes' not found. Run the server from the project folder: " + ROOT);
            System.exit(1);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", Main::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Site is running: http://localhost:" + port + "   (Ctrl+C to stop)");
    }

    // ------------------------------------------------------------------ экспорт

    /** Собирает весь сайт в папку обычных HTML-файлов, которую можно выложить на любой статический хостинг. */
    static void export(Path outArg) throws IOException {
        Path out = outArg.toAbsolutePath().normalize();
        if (!Files.isDirectory(NOTES_DIR)) {
            fail("Folder 'notes' not found. Run the command from the project folder: " + ROOT);
        }
        if (ROOT.startsWith(out) || out.startsWith(NOTES_DIR) || out.startsWith(STATIC_DIR)) {
            fail("Choose another output folder: " + out + " overlaps with the project itself.");
        }

        List<Note> notes = null;
        try {
            notes = loadNotes(true);
        } catch (IOException e) {
            fail("Export stopped. " + e.getMessage());
        }
        if (notes.isEmpty()) {
            fail("No notes found in " + NOTES_DIR);
        }
        for (Note n : notes) {
            if (n.slug().equals("index")) {
                fail("The file notes/index.html clashes with the home page. Rename it, e.g. to notes/intro.html");
            }
        }

        // чистим старый результат, но только если это точно наша выгрузка (в ней лежит .nojekyll)
        if (Files.exists(out)) {
            boolean empty;
            try (Stream<Path> s = Files.list(out)) {
                empty = s.findAny().isEmpty();
            }
            if (!empty && !Files.exists(out.resolve(".nojekyll"))) {
                fail("Refusing to overwrite " + out + ": the folder is not empty and was not created by export.");
            }
            try (Stream<Path> walk = Files.walk(out)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    if (!p.equals(out)) {
                        Files.delete(p);
                    }
                }
            }
        }

        Files.createDirectories(out);
        Files.writeString(out.resolve(".nojekyll"), ""); // GitHub Pages: не обрабатывать файлы через Jekyll
        write(out.resolve("index.html"), layout(EXPORT, SITE_NAME, notes, null, indexContent(EXPORT, notes)));
        for (int i = 0; i < notes.size(); i++) {
            Note note = notes.get(i);
            Note prev = i > 0 ? notes.get(i - 1) : null;
            Note next = i < notes.size() - 1 ? notes.get(i + 1) : null;
            write(out.resolve(note.slug() + ".html"),
                    layout(EXPORT, note.title() + " — " + SITE_NAME, notes, note.slug(), noteContent(EXPORT, note, prev, next)));
        }
        if (Files.isDirectory(STATIC_DIR)) {
            copyDir(STATIC_DIR, out.resolve("static"));
        }
        System.out.println("Exported " + notes.size() + " notes to " + out);
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }

    static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static void copyDir(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }

    // ----------------------------------------------------------------- маршруты

    static void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                send(ex, 405, "text/plain; charset=utf-8", "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }

            String path = ex.getRequestURI().getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            if (path.equals("/")) {
                List<Note> notes = loadNotes(false);
                sendHtml(ex, 200, layout(SERVER, SITE_NAME, notes, null, indexContent(SERVER, notes)));
            } else if (path.startsWith("/note/")) {
                serveNote(ex, path.substring("/note/".length()));
            } else if (path.startsWith("/static/")) {
                serveStatic(ex, path.substring("/static/".length()));
            } else {
                notFound(ex);
            }
        } catch (Exception e) {
            e.printStackTrace();
            String msg = "<h1>Ошибка сервера</h1><p>Не удалось собрать страницу. "
                    + "Чаще всего причина в файле конспекта: проверь, что он сохранён в кодировке UTF-8.</p>"
                    + "<p><code>" + esc(String.valueOf(e)) + "</code></p>";
            sendHtml(ex, 500, layout(SERVER, "Ошибка", List.of(), null, "<div class=\"note\">" + msg + "</div>"));
        }
    }

    static void serveNote(HttpExchange ex, String slug) throws IOException {
        if (!SLUG.matcher(slug).matches()) {
            notFound(ex);
            return;
        }
        List<Note> notes = loadNotes(false);
        int index = -1;
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).slug().equals(slug)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            notFound(ex);
            return;
        }
        Note note = notes.get(index);
        Note prev = index > 0 ? notes.get(index - 1) : null;
        Note next = index < notes.size() - 1 ? notes.get(index + 1) : null;
        String title = note.title() + " — " + SITE_NAME;
        sendHtml(ex, 200, layout(SERVER, title, notes, slug, noteContent(SERVER, note, prev, next)));
    }

    static void serveStatic(HttpExchange ex, String relative) throws IOException {
        Path file;
        try {
            file = STATIC_DIR.resolve(relative).normalize();
        } catch (InvalidPathException e) {
            notFound(ex);
            return;
        }
        if (!file.startsWith(STATIC_DIR) || !Files.isRegularFile(file)) {
            notFound(ex);
            return;
        }
        send(ex, 200, contentType(file.getFileName().toString()), Files.readAllBytes(file));
    }

    static void notFound(HttpExchange ex) throws IOException {
        List<Note> notes = loadNotes(false);
        String content = "<div class=\"note\"><h1>Страница не найдена</h1>"
                + "<p>Такой темы нет. Вернись на <a href=\"/\">главную</a> или выбери тему в меню слева.</p></div>";
        sendHtml(ex, 404, layout(SERVER, "Не найдено — " + SITE_NAME, notes, null, content));
    }

    // ------------------------------------------------------------------ конспекты

    /**
     * Читает все конспекты из папки notes. Если strict = false, непрочитанный файл (например, не в UTF-8)
     * показывается отдельной записью, чтобы не ронять весь сайт. Если strict = true, бросается исключение.
     */
    static List<Note> loadNotes(boolean strict) throws IOException {
        List<Note> result = new ArrayList<>();
        if (!Files.isDirectory(NOTES_DIR)) {
            return result;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(NOTES_DIR)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".html")).sorted().toList();
        }
        for (Path file : files) {
            String slug = file.getFileName().toString().replaceFirst("\\.html$", "");
            if (!SLUG.matcher(slug).matches()) {
                continue; // например, _template.html
            }
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                if (strict) {
                    throw new IOException("Cannot read notes/" + file.getFileName()
                            + " (save the file as UTF-8): " + e, e);
                }
                System.err.println("Cannot read " + file.getFileName() + ": " + e);
                result.add(new Note(slug, "Не читается: " + file.getFileName(), "Ошибки", 9999, "", "",
                        "<p>Файл <code>" + esc(file.getFileName().toString()) + "</code> не удалось прочитать. "
                                + "Скорее всего, он сохранён не в кодировке UTF-8. "
                                + "Пересохрани его как UTF-8 и обнови страницу.</p>"));
                continue;
            }
            result.add(parse(slug, text));
        }
        result.sort(Comparator.comparingInt(Note::order).thenComparing(Note::title));
        return result;
    }

    /** Файл конспекта: строки "ключ: значение", затем строка "---", затем HTML. */
    static Note parse(String slug, String text) {
        text = text.replace("\r\n", "\n");
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        Map<String, String> meta = new HashMap<>();
        String body = text;

        if (text.startsWith("title:")) {
            String[] lines = text.split("\n", -1);
            int sep = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].strip().equals("---")) {
                    sep = i;
                    break;
                }
            }
            if (sep >= 0) {
                for (int i = 0; i < sep; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon > 0) {
                        meta.put(lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).strip());
                    }
                }
                body = String.join("\n", Arrays.copyOfRange(lines, sep + 1, lines.length));
            }
        }

        int order = 1000;
        try {
            order = Integer.parseInt(meta.getOrDefault("order", "1000"));
        } catch (NumberFormatException ignored) {
            // оставляем значение по умолчанию
        }
        return new Note(
                slug,
                meta.getOrDefault("title", slug),
                meta.getOrDefault("section", "Без раздела"),
                order,
                meta.getOrDefault("summary", ""),
                meta.getOrDefault("icon", ""),
                body);
    }

    static Map<String, List<Note>> bySection(List<Note> notes) {
        Map<String, List<Note>> sections = new LinkedHashMap<>();
        for (Note n : notes) {
            sections.computeIfAbsent(n.section(), k -> new ArrayList<>()).add(n);
        }
        return sections;
    }

    /** Подгоняет ссылки внутри текста конспекта под выбранный режим (сервер или файлы). */
    static String fixLinks(Urls u, String body) {
        body = body.replace("\"/static/", "\"" + u.assets()).replace("'/static/", "'" + u.assets());
        body = NOTE_LINK.matcher(body).replaceAll(
                m -> Matcher.quoteReplacement("href=" + m.group(1) + u.note(m.group(2)) + m.group(1)));
        body = HOME_LINK.matcher(body).replaceAll(
                m -> Matcher.quoteReplacement("href=" + m.group(1) + u.home() + m.group(1)));
        return body;
    }

    // ------------------------------------------------------------ оформление разделов

    static final int[] PALETTE = {215, 18, 262, 190, 335, 295, 150, 35};

    /** Цвет и иконка раздела подбираются по названию, неизвестные разделы получают стабильный цвет. */
    static Theme themeOf(String section) {
        String s = section.toLowerCase(Locale.ROOT);
        if (s.contains("механик") || s.contains("кинемат") || s.contains("динамик")) return new Theme(215, "⚙️");
        if (s.contains("молекул") || s.contains("термо") || s.contains("тепл")) return new Theme(18, "🌡️");
        if (s.contains("электр") || s.contains("магнит")) return new Theme(262, "⚡");
        if (s.contains("колеб") || s.contains("волн")) return new Theme(190, "🌊");
        if (s.contains("оптик") || s.contains("свет")) return new Theme(335, "🔭");
        if (s.contains("атом") || s.contains("квант") || s.contains("ядер")) return new Theme(295, "⚛️");
        if (s.contains("ошибк")) return new Theme(0, "⚠️");
        return new Theme(PALETTE[Math.floorMod(s.hashCode(), PALETTE.length)], "📘");
    }

    static String iconOf(Note n) {
        return n.icon().isBlank() ? themeOf(n.section()).icon() : n.icon();
    }

    static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /** Склонение для чисел: 1 тема, 2 темы, 5 тем. */
    static String plural(int n, String one, String few, String many) {
        int m100 = n % 100;
        int m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) return many;
        if (m10 == 1) return one;
        if (m10 >= 2 && m10 <= 4) return few;
        return many;
    }

    // ------------------------------------------------------------------- HTML

    static String indexContent(Urls u, List<Note> notes) {
        Map<String, List<Note>> sections = bySection(notes);
        int formulas = 0;
        int tasks = 0;
        for (Note n : notes) {
            formulas += count(n.body(), "\\[");
            tasks += count(n.body(), "class=\"example\"");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"home\">");

        // --- главный экран
        sb.append("<header class=\"landing\">")
          .append("<div class=\"floaters\" aria-hidden=\"true\">")
          .append("<span style=\"--x:57%;--y:9%;--r:-6deg;--s:1.3\">E = mc²</span>")
          .append("<span style=\"--x:80%;--y:27%;--r:5deg\">F = ma</span>")
          .append("<span style=\"--x:72%;--y:46%;--r:-4deg;--d:1.5s\">pV = νRT</span>")
          .append("<span style=\"--x:83%;--y:66%;--r:7deg;--s:1.15;--d:.8s\">U = IR</span>")
          .append("<span style=\"--x:58%;--y:80%;--r:-5deg;--d:2s\">v = v₀ + at</span>")
          .append("<span style=\"--x:78%;--y:88%;--r:-3deg;--d:.4s\">Q = cmΔt</span>")
          .append("</div>")
          .append("<div class=\"landing-body\">")
          .append("<p class=\"eyebrow\">Формулы · задачи · разбор ошибок</p>")
          .append("<h1>").append(esc(SITE_NAME)).append("</h1>")
          .append("<p class=\"landing-text\">Коротко и по делу: определения, формулы и разобранные задачи. "
                  + "Всё, что нужно, чтобы быстро повторить тему.</p>")
          .append("<div class=\"landing-actions\">");
        if (!notes.isEmpty()) {
            sb.append("<a class=\"btn btn-main\" href=\"").append(u.note(notes.get(0).slug()))
              .append("\">Начать читать <span aria-hidden=\"true\">→</span></a>");
        }
        sb.append("<a class=\"btn btn-ghost\" href=\"#topics\">Все темы</a></div>");
        if (!notes.isEmpty()) {
            sb.append("<ul class=\"stats\">")
              .append("<li><b>").append(notes.size()).append("</b> ")
              .append(plural(notes.size(), "тема", "темы", "тем")).append("</li>")
              .append("<li><b>").append(sections.size()).append("</b> ")
              .append(plural(sections.size(), "раздел", "раздела", "разделов")).append("</li>");
            if (formulas > 0) {
                sb.append("<li><b>").append(formulas).append("</b> ")
                  .append(plural(formulas, "формула", "формулы", "формул")).append("</li>");
            }
            if (tasks > 0) {
                sb.append("<li><b>").append(tasks).append("</b> ")
                  .append(plural(tasks, "задача", "задачи", "задач")).append("</li>");
            }
            sb.append("</ul>");
        }
        sb.append("</div></header>");

        // --- что внутри
        sb.append("<section class=\"features\">")
          .append(feature("📐", "Формулы в рамках", "Главное выделено цветом и всегда под рукой.", "f-blue"))
          .append(feature("✏️", "Разобранные задачи", "К каждой теме есть пример с решением.", "f-green"))
          .append(feature("⚠️", "Частые ошибки", "На чём чаще всего теряют баллы.", "f-orange"))
          .append(feature("🔍", "Быстрый поиск", "Нажми «/» и найди нужную тему за секунду.", "f-purple"))
          .append("</section>");

        // --- все темы по разделам
        sb.append("<div class=\"topics-head\" id=\"topics\"><h2>Все темы</h2><p>Выбери раздел и тему.</p></div>");
        if (notes.isEmpty()) {
            sb.append("<p class=\"empty\">Пока нет ни одного конспекта. Добавь .html-файл в папку <code>notes</code> "
                    + "(шаблон лежит в <code>notes/_template.html</code>) и обнови страницу.</p>");
        }
        for (Map.Entry<String, List<Note>> e : sections.entrySet()) {
            Theme t = themeOf(e.getKey());
            int total = e.getValue().size();
            sb.append("<section class=\"section themed\" style=\"--hue:").append(t.hue()).append("\">")
              .append("<div class=\"sec-head\"><span class=\"sec-ico\" aria-hidden=\"true\">").append(t.icon()).append("</span>")
              .append("<h2>").append(esc(e.getKey())).append("</h2>")
              .append("<span class=\"sec-count\">").append(total).append(' ')
              .append(plural(total, "тема", "темы", "тем")).append("</span></div><div class=\"cards\">");
            int i = 0;
            for (Note n : e.getValue()) {
                i++;
                sb.append("<a class=\"card themed\" style=\"--hue:").append(t.hue()).append("\" href=\"")
                  .append(u.note(n.slug())).append("\">")
                  .append("<span class=\"card-top\"><span class=\"card-ico\" aria-hidden=\"true\">").append(esc(iconOf(n)))
                  .append("</span><span class=\"card-num\">").append(i < 10 ? "0" : "").append(i).append("</span></span>")
                  .append("<span class=\"card-title\">").append(esc(n.title())).append("</span>");
                if (!n.summary().isEmpty()) {
                    sb.append("<span class=\"card-text\">").append(esc(n.summary())).append("</span>");
                }
                sb.append("<span class=\"card-go\">Открыть <span aria-hidden=\"true\">→</span></span></a>");
            }
            sb.append("</div></section>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    static String feature(String icon, String title, String text, String cls) {
        return "<div class=\"feature " + cls + "\"><span class=\"feature-ico\" aria-hidden=\"true\">" + icon + "</span>"
                + "<h3>" + esc(title) + "</h3><p>" + esc(text) + "</p></div>";
    }

    static String noteContent(Urls u, Note note, Note prev, Note next) {
        String icon = iconOf(note);
        String plain = note.body().replaceAll("(?s)<!--.*?-->", " ").replaceAll("(?s)<[^>]*>", " ").strip();
        int words = plain.isEmpty() ? 0 : plain.split("\\s+").length;
        int minutes = Math.max(1, (int) Math.round(words / 120.0));
        int formulas = count(note.body(), "\\[");
        int tasks = count(note.body(), "class=\"example\"");

        StringBuilder sb = new StringBuilder();
        sb.append("<article class=\"note\">");

        // шапка конспекта
        sb.append("<header class=\"note-head\" data-icon=\"").append(esc(icon)).append("\">")
          .append("<span class=\"note-icon\" aria-hidden=\"true\">").append(esc(icon)).append("</span>")
          .append("<div class=\"note-head-text\">")
          .append("<p class=\"crumb\"><a href=\"").append(u.home()).append("\">Главная</a><span class=\"sep\">/</span>")
          .append("<span class=\"badge\">").append(esc(note.section())).append("</span></p>")
          .append("<h1>").append(esc(note.title())).append("</h1>")
          .append("<ul class=\"chips\"><li>⏱ ").append(minutes).append(" мин чтения</li>");
        if (formulas > 0) {
            sb.append("<li>📐 ").append(formulas).append(' ')
              .append(plural(formulas, "формула", "формулы", "формул")).append("</li>");
        }
        if (tasks > 0) {
            sb.append("<li>✏️ ").append(tasks).append(' ')
              .append(plural(tasks, "задача", "задачи", "задач")).append("</li>");
        }
        sb.append("</ul></div></header>");

        sb.append(fixLinks(u, note.body()));
        sb.append("</article>");

        sb.append("<nav class=\"pager\">");
        if (prev != null) {
            sb.append("<a class=\"pager-prev\" href=\"").append(u.note(prev.slug())).append("\"><small>← Назад</small>")
              .append(esc(prev.title())).append("</a>");
        } else {
            sb.append("<span></span>");
        }
        if (next != null) {
            sb.append("<a class=\"pager-next\" href=\"").append(u.note(next.slug())).append("\"><small>Дальше →</small>")
              .append(esc(next.title())).append("</a>");
        }
        sb.append("</nav>");
        return sb.toString();
    }

    static String layout(Urls u, String pageTitle, List<Note> notes, String currentSlug, String content) {
        int hue = 215;
        if (currentSlug != null) {
            for (Note n : notes) {
                if (n.slug().equals(currentSlug)) {
                    hue = themeOf(n.section()).hue();
                    break;
                }
            }
        }

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!doctype html>\n<html lang=\"ru\">\n<head>\n")
          .append("<meta charset=\"utf-8\">\n")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
          .append("<meta name=\"theme-color\" content=\"#2a4fb0\">\n")
          .append("<title>").append(esc(pageTitle)).append("</title>\n")
          .append("<link rel=\"icon\" href=\"data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>"
                  + "<text y='.9em' font-size='90'>⚛️</text></svg>\">\n")
          .append("<link rel=\"stylesheet\" href=\"").append(u.assets()).append("style.css\">\n")
          .append("<script async id=\"MathJax-script\" src=\"https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js\"></script>\n")
          .append("<script defer src=\"").append(u.assets()).append("app.js\"></script>\n")
          .append("</head>\n<body style=\"--hue:").append(hue).append("\">\n")
          .append("<div class=\"progress\" id=\"progress\"></div>\n");

        // верхняя панель для телефонов
        sb.append("<header class=\"topbar\"><button class=\"menu-btn\" id=\"menu-btn\" type=\"button\" aria-label=\"Меню\">☰</button>")
          .append("<a class=\"topbar-title\" href=\"").append(u.home()).append("\">").append(esc(SITE_NAME)).append("</a></header>\n");
        sb.append("<div class=\"scrim\" id=\"scrim\"></div>\n");

        // боковое меню
        sb.append("<aside class=\"sidebar\" id=\"sidebar\">")
          .append("<a class=\"brand\" href=\"").append(u.home()).append("\"><span class=\"brand-mark\">φ</span><span>")
          .append(esc(SITE_NAME)).append("</span></a>")
          .append("<input id=\"search\" class=\"search\" type=\"search\" placeholder=\"Поиск по темам  ( / )\" autocomplete=\"off\">")
          .append("<nav class=\"nav\">");
        for (Map.Entry<String, List<Note>> e : bySection(notes).entrySet()) {
            Theme t = themeOf(e.getKey());
            sb.append("<div class=\"nav-section themed\" style=\"--hue:").append(t.hue()).append("\"><h3>")
              .append("<span class=\"nav-ico\" aria-hidden=\"true\">").append(t.icon()).append("</span>")
              .append(esc(e.getKey())).append("</h3>");
            for (Note n : e.getValue()) {
                sb.append("<a href=\"").append(u.note(n.slug())).append("\"");
                if (n.slug().equals(currentSlug)) {
                    sb.append(" class=\"active\" aria-current=\"page\"");
                }
                sb.append(">").append(esc(n.title())).append("</a>");
            }
            sb.append("</div>");
        }
        sb.append("</nav></aside>\n");

        sb.append("<main class=\"content\">\n").append(content)
          .append("\n<footer class=\"footer\"><span class=\"footer-mark\">φ</span><span>").append(esc(SITE_NAME))
          .append("</span></footer>\n</main>\n")
          .append("<button class=\"to-top\" id=\"to-top\" type=\"button\" aria-label=\"Наверх\">↑</button>\n")
          .append("</body>\n</html>\n");
        return sb.toString();
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------ ответы

    static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        send(ex, status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        if (ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    static String contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }
}
