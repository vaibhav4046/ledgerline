package dev.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the app on a random port against real PostgreSQL: the DB in LEDGER_TEST_DB_URL when set,
 * otherwise a Testcontainers postgres:16-alpine (CI). Every test class shares one Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {
    static final String SCHEMA = "ledger_test";
    protected static final String WEBHOOK_SECRET = "whsec_test_only";
    protected static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {
    };

    private static final String EXTERNAL_URL = System.getenv("LEDGER_TEST_DB_URL");
    private static final PostgreSQLContainer<?> CONTAINER;

    static {
        if (EXTERNAL_URL == null) {
            CONTAINER = new PostgreSQLContainer<>("postgres:16-alpine");
            CONTAINER.start();
        } else {
            CONTAINER = null;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (CONTAINER != null) {
            String url = CONTAINER.getJdbcUrl();
            registry.add("spring.datasource.url",
                    () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
            registry.add("spring.datasource.username", CONTAINER::getUsername);
            registry.add("spring.datasource.password", CONTAINER::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("LEDGER_TEST_DB_USER", "postgres"));
            registry.add("spring.datasource.password",
                    () -> System.getenv().getOrDefault("LEDGER_TEST_DB_PASSWORD", ""));
        }
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("ledgerline.outbox.enabled", () -> "false");
        registry.add("ledgerline.stripe.base-url", StripeStub::baseUrl);
        registry.add("ledgerline.stripe.secret-key", () -> StripeStub.SECRET_KEY);
        registry.add("ledgerline.stripe.webhook-secret", () -> WEBHOOK_SECRET);
    }

    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    protected void cleanDatabase() {
        jdbc.execute("TRUNCATE entries, transfers, outbox_events, payment_intents, stripe_events, accounts CASCADE");
    }

    protected ResponseEntity<Map<String, Object>> post(String path, String idempotencyKey, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JSON_MAP);
    }

    protected ResponseEntity<Map<String, Object>> get(String path) {
        return http.exchange(path, HttpMethod.GET, null, JSON_MAP);
    }

    protected UUID createAccount(String currency) {
        var res = post("/accounts", null, Map.of("name", "acct", "currency", currency));
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        return UUID.fromString((String) res.getBody().get("id"));
    }

    protected UUID fundedAccount(long amount) {
        UUID id = createAccount("USD");
        var res = post("/accounts/" + id + "/deposits", UUID.randomUUID().toString(), Map.of("amount", amount));
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        return id;
    }

    protected long balance(UUID account) {
        return ((Number) get("/accounts/" + account).getBody().get("balance")).longValue();
    }

    protected ResponseEntity<Map<String, Object>> transfer(String key, UUID from, UUID to, long amount) {
        return post("/transfers", key,
                Map.of("fromAccountId", from, "toAccountId", to, "amount", amount, "currency", "USD"));
    }

    protected Map<String, Object> audit() {
        return get("/admin/audit").getBody();
    }

    /** Runs {@code threads} tasks released together by a start gate; returns their results in order. */
    protected static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
