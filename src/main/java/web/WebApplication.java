package web;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth1AccessToken;
import com.github.scribejava.core.model.OAuth1RequestToken;
import com.github.scribejava.core.oauth.OAuth10aService;
import core.net.HattrickAPI;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WebApplication {

    private static final Map<String, OAuth1RequestToken> REQUEST_TOKENS =
            new ConcurrentHashMap<>();

    private static final Map<String, OAuth1AccessToken> ACCESS_TOKENS =
            new ConcurrentHashMap<>();

    private WebApplication() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "10000")
        );

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", port),
                0
        );

        server.createContext("/", WebApplication::handleRoot);
        server.createContext("/health", WebApplication::handleHealth);
        server.createContext("/oauth/start", WebApplication::handleOAuthStart);
        server.createContext("/oauth/callback", WebApplication::handleOAuthCallback);

        server.setExecutor(null);
        server.start();

        System.out.println("HO Web server started on port " + port);
    }

    private static OAuth10aService oauthService() {
        String key = System.getenv("CHPP_CONSUMER_KEY");
        String secret = System.getenv("CHPP_CONSUMER_SECRET");
        String callback = System.getenv("CHPP_CALLBACK_URL");

        if (key == null || secret == null || callback == null) {
            throw new IllegalStateException("CHPP environment variables are missing");
        }

        return new ServiceBuilder(key)
                .apiSecret(secret)
                .callback(callback)
                .build(HattrickAPI.instance());
    }

    private static void handleOAuthStart(HttpExchange exchange) throws IOException {
        try {
            OAuth10aService service = oauthService();
            OAuth1RequestToken requestToken = service.getRequestToken();
            String sessionId = UUID.randomUUID().toString();

            REQUEST_TOKENS.put(sessionId, requestToken);

            exchange.getResponseHeaders().add(
                    "Set-Cookie",
                    "HO_SESSION=" + sessionId + "; Path=/; HttpOnly; Secure; SameSite=Lax"
            );

            redirect(exchange, service.getAuthorizationUrl(requestToken));
        } catch (Exception e) {
            e.printStackTrace();
            send(exchange, 500,
                    "CHPP OAuth başlatılamadı: " + e.getMessage(),
                    "text/plain; charset=UTF-8");
        }
    }

    private static void handleOAuthCallback(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String verifier = query.get("oauth_verifier");
            String sessionId = getCookie(exchange, "HO_SESSION");

            if (verifier == null || sessionId == null) {
                send(exchange, 400,
                        "OAuth callback eksik parametre içeriyor.",
                        "text/plain; charset=UTF-8");
                return;
            }

            OAuth1RequestToken requestToken = REQUEST_TOKENS.remove(sessionId);
            if (requestToken == null) {
                send(exchange, 400,
                        "OAuth oturumu bulunamadı veya süresi doldu.",
                        "text/plain; charset=UTF-8");
                return;
            }

            OAuth10aService service = oauthService();
            OAuth1AccessToken accessToken = service.getAccessToken(requestToken, verifier);
            ACCESS_TOKENS.put(sessionId, accessToken);

            send(exchange, 200,
                    "<!doctype html><html lang=\"tr\"><head><meta charset=\"UTF-8\"><title>CHPP Bağlandı</title></head>"
                            + "<body><h1>CHPP bağlantısı başarılı</h1>"
                            + "<p>Hattrick hesabınız başarıyla bağlandı.</p></body></html>",
                    "text/html; charset=UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
            send(exchange, 500,
                    "CHPP OAuth callback hatası: " + e.getMessage(),
                    "text/plain; charset=UTF-8");
        }
    }

    private static void handleRoot(HttpExchange exchange) throws IOException {
        String html = """
                <!doctype html>
                <html lang="tr">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Hattrick Organizer Web</title>
                    <style>
                        body { font-family: system-ui, sans-serif; margin: 0; padding: 48px; background: #f5f7fb; color: #18202a; }
                        main { max-width: 720px; margin: auto; background: white; padding: 32px; border-radius: 16px; box-shadow: 0 8px 30px rgba(0,0,0,.08); }
                        .ok { display: inline-block; padding: 6px 10px; border-radius: 999px; background: #e7f7ed; color: #176b3a; font-weight: 600; }
                        a { display: inline-block; margin-top: 16px; padding: 12px 18px; background: #1677ff; color: white; text-decoration: none; border-radius: 8px; }
                    </style>
                </head>
                <body>
                    <main>
                        <h1>Hattrick Organizer Web</h1>
                        <p class="ok">Server: OK</p>
                        <p>Web altyapısı başarıyla çalışıyor.</p>
                        <a href="/oauth/start">Hattrick hesabını bağla</a>
                    </main>
                </body>
                </html>
                """;

        send(exchange, 200, html, "text/html; charset=UTF-8");
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        send(exchange, 200, "OK\n", "text/plain; charset=UTF-8");
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null) return result;

        for (String parameter : query.split("&")) {
            String[] parts = parameter.split("=", 2);
            String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1
                    ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            result.put(key, value);
        }
        return result;
    }

    private static String getCookie(HttpExchange exchange, String name) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) return null;

        for (String cookie : cookieHeader.split(";")) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) return parts[1];
        }
        return null;
    }

    private static void send(
            HttpExchange exchange,
            int status,
            String body,
            String contentType
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
