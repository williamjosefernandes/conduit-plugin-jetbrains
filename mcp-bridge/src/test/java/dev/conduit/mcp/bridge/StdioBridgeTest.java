package dev.conduit.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StdioBridgeTest {
    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/mcp", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"id\"")) {
                byte[] response = ("{\"echo\":\n" + body + "}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else {
                exchange.sendResponseHeaders(202, -1);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void forwardsRequestsAndSkipsNotifications() throws Exception {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        String input = "{\"id\":1,\"method\":\"ping\"}\n\n{\"method\":\"notifications/initialized\"}\n";
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        new StdioBridge(endpoint, "secret")
            .run(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(captured, true, StandardCharsets.UTF_8));

        String[] lines = captured.toString(StandardCharsets.UTF_8).strip().split("\\R");
        assertEquals(1, lines.length, "one response line, kept on a single line");
        assertTrue(lines[0].contains("\"method\":\"ping\""));
        assertEquals(List.of("Bearer secret", "Bearer secret"), authorizations);
    }
}
