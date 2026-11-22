package dev.ledgerline;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** In-process fake of POST /v1/payment_intents that records the last request. */
public final class StripeStub {
    public static final String SECRET_KEY = "sk_test_stub_key";

    public record Request(String authorization, String idempotencyKey, String contentType, String decodedBody) {
    }

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static volatile Request last;
    private static final HttpServer SERVER;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SERVER.createContext("/v1/payment_intents", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var h = exchange.getRequestHeaders();
            last = new Request(h.getFirst("Authorization"), h.getFirst("Idempotency-Key"),
                    h.getFirst("Content-Type"), URLDecoder.decode(body, StandardCharsets.UTF_8));
            String id = "pi_test_" + SEQ.incrementAndGet();
            byte[] response = ("{\"id\":\"" + id + "\",\"object\":\"payment_intent\",\"client_secret\":\"" + id
                    + "_secret_x\",\"status\":\"requires_payment_method\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        SERVER.start();
    }

    private StripeStub() {
    }

    public static String baseUrl() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    public static Request last() {
        return last;
    }
}
