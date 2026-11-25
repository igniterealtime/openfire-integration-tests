package org.igniterealtime.openfire.integration.federation;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The admin console of one of the servers, used as an administrator's browser uses it: logged in as the environment's
 * administrator, it submits forms with the CSRF token that each page carries.
 */
final class AdminConsole {

    // The administrator of each server in the federated environment.
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "admin";

    // A server-to-server connection test waits for each step of the connection in turn, up to the server's timeouts.
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final URI base;
    private final HttpClient client;

    private AdminConsole(URI base) {
        this.base = base;
        this.client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Logs in to the admin console of a server.
     *
     * @param domain the domain of the server
     * @return the console, logged in
     */
    static AdminConsole loginTo(String domain) throws IOException, InterruptedException {
        final AdminConsole console = new AdminConsole(FederatedTestEnvironment.adminConsole(domain));
        final Document form = console.get("login.jsp");
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("login", "true");
        fields.put("username", ADMIN_USER);
        fields.put("password", ADMIN_PASSWORD);
        fields.put("csrf", csrf(form));
        final Document page = console.post("login.jsp", fields);
        if (page.getElementById("loginForm") != null || page.location().contains("login.jsp")) {
            throw new IllegalStateException("Could not log in to the admin console at " + console.base);
        }
        return console;
    }

    /**
     * Runs the server-to-server connection test of the admin console against a domain, as its form does.
     *
     * @param domain the domain to test
     * @return the checklist that the page shows
     */
    S2SConnectionTest testConnection(String domain) throws IOException, InterruptedException {
        final Document form = get("server-connectiontest.jsp");
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("csrf", csrf(form));
        fields.put("server2server-testing-domain", domain);
        fields.put("s2s-test", "Test");
        return S2SConnectionTest.of(domain, post("server-connectiontest.jsp", fields));
    }

    private static String csrf(Document page) {
        final Element field = page.selectFirst("input[name=csrf]");
        if (field == null) {
            throw new IllegalStateException("No CSRF token on " + page.location());
        }
        return field.attr("value");
    }

    private Document get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(base.resolve(path)).GET());
    }

    private Document post(String path, Map<String, String> fields) throws IOException, InterruptedException {
        final String body = fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private Document send(HttpRequest.Builder request) throws IOException, InterruptedException {
        final HttpResponse<String> response = client.send(request.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + response.uri());
        }
        return Jsoup.parse(response.body(), response.uri().toString());
    }

    /**
     * The outcome of one check of the connection test, by the label that the page shows for it in English, the
     * language of the environment's admin console.
     */
    enum Status {
        PASS("Pass"), WARNING("Warning"), FAIL("Failed"), NOT_REACHED("Not reached"), NOT_OBSERVED("Not observed");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        static Status ofLabel(String label) {
            for (final Status status : values()) {
                if (status.label.equals(label)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown status: " + label);
        }
    }

    /**
     * One row of the checklist.
     */
    record Check(int number, String title, Status status, String detail, boolean firstFailure) {
        @Override
        public String toString() {
            return number + ". " + title + ": " + status + (firstFailure ? " (first failure)" : "") + ": " + detail;
        }
    }

    /**
     * The checklist that the connection test page shows for a domain.
     */
    record S2SConnectionTest(String domain, List<Check> checks) {

        static S2SConnectionTest of(String domain, Document page) {
            final List<Check> checks = new ArrayList<>();
            for (final Element row : page.select(".s2s-checklist tbody tr")) {
                final List<Element> cells = row.select("td");
                checks.add(new Check(Integer.parseInt(cells.get(0).text()), cells.get(1).text(),
                        Status.ofLabel(cells.get(2).text()), cells.get(3).text(), row.hasClass("jive-highlight")));
            }
            return new S2SConnectionTest(domain, checks);
        }

        Check check(int number) {
            return checks.stream().filter(c -> c.number() == number).findFirst()
                    .orElseThrow(() -> new AssertionError("No check " + number + " in " + this));
        }

        @Override
        public String toString() {
            return "the connection test of " + domain + ":\n" + checks.stream().map(Check::toString).collect(Collectors.joining("\n"));
        }
    }
}
