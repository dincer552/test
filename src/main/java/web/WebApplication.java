package web;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth1AccessToken;
import com.github.scribejava.core.model.OAuth1RequestToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth10aService;
import core.net.HattrickAPI;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public final class WebApplication {

    private static final Map<String, OAuth1RequestToken> REQUEST_TOKENS = new ConcurrentHashMap<>();
    private static final Map<String, OAuth1AccessToken> ACCESS_TOKENS = new ConcurrentHashMap<>();
    private static final String TEAM_DETAILS_URL =
            "https://chpp.hattrick.org/chppxml.ashx?file=teamdetails&version=3.0";

    private WebApplication() {}

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "10000"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", WebApplication::handleRoot);
        server.createContext("/health", WebApplication::handleHealth);
        server.createContext("/oauth/start", WebApplication::handleOAuthStart);
        server.createContext("/oauth/callback", WebApplication::handleOAuthCallback);
        server.createContext("/team", WebApplication::handleTeamPage);
        server.createContext("/api/team", WebApplication::handleTeamApi);
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
            exchange.getResponseHeaders().add("Set-Cookie",
                    "HO_SESSION=" + sessionId + "; Path=/; HttpOnly; Secure; SameSite=Lax");
            redirect(exchange, service.getAuthorizationUrl(requestToken));
        } catch (Exception e) {
            e.printStackTrace();
            send(exchange, 500, "CHPP OAuth başlatılamadı: " + e.getMessage(), "text/plain; charset=UTF-8");
        }
    }

    private static void handleOAuthCallback(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String verifier = query.get("oauth_verifier");
            String sessionId = getCookie(exchange, "HO_SESSION");
            if (verifier == null || sessionId == null) {
                send(exchange, 400, "OAuth callback eksik parametre içeriyor.", "text/plain; charset=UTF-8");
                return;
            }
            OAuth1RequestToken requestToken = REQUEST_TOKENS.remove(sessionId);
            if (requestToken == null) {
                send(exchange, 400, "OAuth oturumu bulunamadı veya süresi doldu.", "text/plain; charset=UTF-8");
                return;
            }
            OAuth1AccessToken accessToken = oauthService().getAccessToken(requestToken, verifier);
            ACCESS_TOKENS.put(sessionId, accessToken);
            redirect(exchange, "/team");
        } catch (Exception e) {
            e.printStackTrace();
            send(exchange, 500, "CHPP OAuth callback hatası: " + e.getMessage(), "text/plain; charset=UTF-8");
        }
    }

    private static void handleTeamPage(HttpExchange exchange) throws IOException {
        if (getCookie(exchange, "HO_SESSION") == null || !ACCESS_TOKENS.containsKey(getCookie(exchange, "HO_SESSION"))) {
            redirect(exchange, "/oauth/start");
            return;
        }
        String html = """
                <!doctype html><html lang="tr"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Hattrick Organizer - Takım</title>
                <style>
                body{font-family:system-ui,sans-serif;margin:0;padding:48px;background:#f5f7fb;color:#18202a}
                main{max-width:760px;margin:auto;background:#fff;padding:32px;border-radius:16px;box-shadow:0 8px 30px rgba(0,0,0,.08)}
                .card{margin-top:20px;padding:20px;background:#f7f9fc;border-radius:12px}
                pre{white-space:pre-wrap;word-break:break-word}
                </style></head><body><main>
                <h1>Hattrick Organizer Web</h1><h2>Takım Bilgileri</h2>
                <div id="team" class="card">CHPP'den takım bilgileri alınıyor...</div>
                <p><a href="/api/team">Ham takım verisini göster</a></p>
                </main><script>
                fetch('/api/team').then(r=>r.json()).then(t=>{
                  document.getElementById('team').innerHTML =
                    '<b>'+escapeHtml(t.teamName || 'Takım')+'</b><br>Takım ID: '+escapeHtml(t.teamId || '-')+
                    '<br>Kısa ad: '+escapeHtml(t.shortName || '-')+
                    '<br>Lig: '+escapeHtml(t.countryName || '-');
                }).catch(e=>document.getElementById('team').textContent='CHPP verisi alınamadı: '+e);
                function escapeHtml(s){return String(s).replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[c]));}
                </script></body></html>
                """;
        send(exchange, 200, html, "text/html; charset=UTF-8");
    }

    private static void handleTeamApi(HttpExchange exchange) throws IOException {
        try {
            String sessionId = getCookie(exchange, "HO_SESSION");
            OAuth1AccessToken accessToken = sessionId == null ? null : ACCESS_TOKENS.get(sessionId);
            if (accessToken == null) {
                send(exchange, 401, "{\"error\":\"CHPP bağlantısı yok\"}", "application/json; charset=UTF-8");
                return;
            }

            OAuth10aService service = oauthService();
            OAuthRequest request = new OAuthRequest(Verb.GET, TEAM_DETAILS_URL);
            service.signRequest(accessToken, request);
            Response response = service.execute(request);
            String xml = response.getBody();

            if (!response.isSuccessful()) {
                send(exchange, 502, "{\"error\":\"CHPP API HTTP " + response.getCode() + "\"}", "application/json; charset=UTF-8");
                return;
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

            String teamId = text(doc, "TeamID");
            String teamName = text(doc, "TeamName");
            String shortName = text(doc, "ShortTeamName");
            String countryName = text(doc, "LeagueName");

            String json = "{\"teamId\":\"" + json(teamId) + "\",\"teamName\":\"" + json(teamName)
                    + "\",\"shortName\":\"" + json(shortName) + "\",\"countryName\":\"" + json(countryName) + "\"}";
            send(exchange, 200, json, "application/json; charset=UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
            send(exchange, 502, "{\"error\":\"CHPP takım verisi alınamadı: " + json(e.getMessage()) + "\"}",
                    "application/json; charset=UTF-8");
        }
    }

    private static String text(Document document, String tagName) {
        NodeList elements = document.getElementsByTagName(tagName);
        return elements.getLength() == 0 ? "" : elements.item(0).getTextContent();
    }

    private static String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void handleRoot(HttpExchange exchange) throws IOException {
        String html = """
                <!doctype html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Hattrick Organizer Web</title><style>
                body{font-family:system-ui,sans-serif;margin:0;padding:48px;background:#f5f7fb}.main{max-width:720px;margin:auto;background:#fff;padding:32px;border-radius:16px;box-shadow:0 8px 30px rgba(0,0,0,.08)}
                a{display:inline-block;margin-top:16px;padding:12px 18px;background:#1677ff;color:white;text-decoration:none;border-radius:8px}
                </style></head><body><main class="main"><h1>Hattrick Organizer Web</h1><p>Server: OK</p><p>CHPP bağlantısı hazır.</p><a href="/oauth/start">Hattrick hesabını bağla</a></main></body></html>
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
            String value = parts.length > 1 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
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

    private static void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
