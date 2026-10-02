package dev.conduit.mcp.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lets agents that only speak MCP over stdio reach Conduit's IDE server, which speaks HTTP.
 * The agent starts this class as its MCP server; every JSON-RPC line read from stdin is posted
 * to the IDE and the response, if any, is written back as one line on stdout.
 *
 * The endpoint and the session token come from the environment, not the command line, so the
 * token does not show up in process listings. Plain Java with no dependencies because it runs
 * as a separate process on the IDE's bundled runtime.
 */
public final class StdioBridge {
    static final String URL_VARIABLE = "CONDUIT_MCP_URL";
    static final String TOKEN_VARIABLE = "CONDUIT_MCP_TOKEN";

    private final URI endpoint;
    private final String token;

    StdioBridge(URI endpoint, String token) {
        this.endpoint = endpoint;
        this.token = token;
    }

    public static void main(String[] args) throws Exception {
        String url = System.getenv(URL_VARIABLE);
        String token = System.getenv(TOKEN_VARIABLE);
        if (url == null || token == null) {
            System.err.println("Conduit MCP bridge: " + URL_VARIABLE + " and " + TOKEN_VARIABLE + " must be set");
            System.exit(2);
        }
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        new StdioBridge(URI.create(url), token).run(System.in, out);
    }

    /** Pumps messages until [in] ends. Requests run concurrently; responses are written whole. */
    void run(InputStream in, PrintStream out) throws IOException, InterruptedException {
        ExecutorService pool = Executors.newCachedThreadPool();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String message = line;
            pool.execute(() -> {
                String response = forward(message);
                if (response != null) {
                    synchronized (out) {
                        out.println(response);
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);
    }

    /** Returns the response line, or null when the server sent no body (notifications). */
    String forward(String message) {
        try {
            HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream body = connection.getOutputStream()) {
                body.write(message.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                    System.err.println("Conduit MCP bridge: IDE answered HTTP " + status);
                }
                return null;
            }
            try (InputStream response = connection.getInputStream()) {
                String text = new String(response.readAllBytes(), StandardCharsets.UTF_8);
                return text.isBlank() ? null : text.replace("\r", "").replace("\n", " ");
            }
        } catch (IOException e) {
            System.err.println("Conduit MCP bridge: " + e.getMessage());
            return null;
        }
    }
}
