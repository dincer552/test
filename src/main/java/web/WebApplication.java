package web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class WebApplication {

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

        server.setExecutor(null);
        server.start();

        System.out.println("HO Web server started on port " + port);
    }

    private static void handleRoot(HttpExchange exchange) throws IOException {

        String html = """
                <!doctype html>
                <html lang="tr">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport"
                          content="width=device-width, initial-scale=1">

                    <title>Hattrick Organizer Web</title>

                    <style>
                        body {
                            font-family: system-ui, sans-serif;
                            margin: 0;
                            padding: 48px;
                            background: #f5f7fb;
                            color: #18202a;
                        }

                        main {
                            max-width: 720px;
                            margin: auto;
                            background: white;
                            padding: 32px;
                            border-radius: 16px;
                            box-shadow: 0 8px 30px rgba(0,0,0,.08);
                        }

                        .ok {
                            display: inline-block;
                            padding: 6px 10px;
                            border-radius: 999px;
                            background: #e7f7ed;
                            color: #176b3a;
                            font-weight: 600;
                        }
                    </style>
                </head>

                <body>
                    <main>
                        <h1>Hattrick Organizer Web</h1>

                        <p class="ok">Server: OK</p>

                        <p>
                            Web altyapısı başarıyla çalışıyor.
                        </p>

                        <p>
                            Bir sonraki aşamada CHPP ve HO analiz
                            katmanları eklenecek.
                        </p>
                    </main>
                </body>
                </html>
                """;

        send(
                exchange,
                200,
                html,
                "text/html; charset=UTF-8"
        );
    }

    private static void handleHealth(HttpExchange exchange)
            throws IOException {

        send(
                exchange,
                200,
                "OK\n",
                "text/plain; charset=UTF-8"
        );
    }

    private static void send(
            HttpExchange exchange,
            int status,
            String body,
            String contentType
    ) throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders()
                .set("Content-Type", contentType);

        exchange.sendResponseHeaders(
                status,
                bytes.length
        );

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}