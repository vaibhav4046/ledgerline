package dev.ledgerline.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerline.IntegrationTest;
import dev.ledgerline.StripeStub;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

class StripeTest extends IntegrationTest {

    @Test
    void createsPaymentIntentWithBearerKeyFormFieldsAndForwardedIdempotencyKey() {
        UUID account = createAccount("USD");

        var res = post("/payments/intents", "pi-key-1", Map.of("accountId", account, "amount", 2_500, "currency", "USD"));

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat((String) res.getBody().get("stripeId")).startsWith("pi_test_");
        assertThat((String) res.getBody().get("clientSecret")).contains("_secret_");
        var sent = StripeStub.last();
        assertThat(sent.authorization()).isEqualTo("Bearer " + StripeStub.SECRET_KEY);
        assertThat(sent.idempotencyKey()).isEqualTo("pi-key-1");
        assertThat(sent.contentType()).startsWith("application/x-www-form-urlencoded");
        assertThat(sent.decodedBody().split("&")).contains("amount=2500", "currency=usd",
                "metadata[account_id]=" + account, "automatic_payment_methods[enabled]=true");

        var replay = post("/payments/intents", "pi-key-1", Map.of("accountId", account, "amount", 2_500, "currency", "USD"));
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(res.getBody());
    }

    @Test
    void refusesLiveOrNonTestKeys() {
        assertThatThrownBy(() -> new StripeClient(RestClient.builder(), "http://localhost", "sk_live_abc"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sk_test_");
        assertThatThrownBy(() -> new StripeClient(RestClient.builder(), "http://localhost", "rk_live_abc"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new StripeClient(RestClient.builder(), "http://localhost", "").enabled()).isFalse();
        assertThat(new StripeClient(RestClient.builder(), "http://localhost", "sk_test_x").enabled()).isTrue();
    }

    @Test
    void succeededWebhookCreditsExactlyOnceAcrossReplaysAndDuplicateEvents() {
        UUID account = createAccount("USD");
        String stripeId = (String) post("/payments/intents", "pi-key-2",
                Map.of("accountId", account, "amount", 4_200, "currency", "USD")).getBody().get("stripeId");

        var first = webhook(event("evt_1", stripeId), Instant.now().getEpochSecond(), WEBHOOK_SECRET);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody()).containsEntry("credited", true);
        assertThat(balance(account)).isEqualTo(4_200);

        var replay = webhook(event("evt_1", stripeId), Instant.now().getEpochSecond(), WEBHOOK_SECRET);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).containsEntry("duplicate", true);

        var otherEvent = webhook(event("evt_2", stripeId), Instant.now().getEpochSecond(), WEBHOOK_SECRET);
        assertThat(otherEvent.getStatusCode().value()).isEqualTo(200);
        assertThat(otherEvent.getBody()).containsEntry("credited", false);

        assertThat(balance(account)).isEqualTo(4_200);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_intents WHERE stripe_id = ?", String.class, stripeId))
                .isEqualTo("succeeded");
        assertThat(audit()).containsEntry("transfersWithNonZeroSum", 0).containsEntry("globalBalanceSum", 0);
    }

    @Test
    void rejectsBadSignatureAndStaleTimestamp() {
        UUID account = createAccount("USD");
        String stripeId = (String) post("/payments/intents", "pi-key-3",
                Map.of("accountId", account, "amount", 100, "currency", "USD")).getBody().get("stripeId");
        String body = event("evt_bad", stripeId);

        assertThat(webhook(body, Instant.now().getEpochSecond(), "whsec_wrong").getStatusCode().value())
                .isEqualTo(400);
        assertThat(webhook(body, Instant.now().getEpochSecond() - 301, WEBHOOK_SECRET).getStatusCode().value())
                .isEqualTo(400);
        assertThat(balance(account)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stripe_events", Long.class)).isZero();
    }

    private static String event(String eventId, String stripeId) {
        return """
                {"id":"%s","type":"payment_intent.succeeded","data":{"object":{"id":"%s","object":"payment_intent"}}}"""
                .formatted(eventId, stripeId);
    }

    private ResponseEntity<Map<String, Object>> webhook(String body, long timestamp, String secret) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Stripe-Signature", "t=" + timestamp + ",v1=" + WebhookSignature.sign(secret, timestamp, bytes));
        return http.exchange("/webhooks/stripe", HttpMethod.POST, new HttpEntity<>(bytes, headers), JSON_MAP);
    }
}
