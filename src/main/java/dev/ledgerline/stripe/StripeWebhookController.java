package dev.ledgerline.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerline.ApiException;
import dev.ledgerline.TransferService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stripe webhooks. Raw bytes are verified before parsing. The event-id insert and the credit share one
 * transaction; the credit uses idempotency key "stripe:{pi}" so a second event for the same intent can't
 * double credit either.
 */
@RestController
class StripeWebhookController {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransferService transfers;
    private final ObjectMapper json;
    private final String secret;

    StripeWebhookController(JdbcTemplate jdbc, TransactionTemplate tx, TransferService transfers, ObjectMapper json,
                            @Value("${ledgerline.stripe.webhook-secret:}") String secret) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.transfers = transfers;
        this.json = json;
        this.secret = secret;
    }

    @PostMapping("/webhooks/stripe")
    Map<String, Object> receive(@RequestHeader(value = "Stripe-Signature", required = false) String signature,
                                @RequestBody byte[] payload) {
        if (secret.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "webhook_not_configured");
        }
        if (!WebhookSignature.isValid(signature, payload, secret, Instant.now().getEpochSecond())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_signature");
        }
        JsonNode event = parse(payload);
        String eventId = event.path("id").asText("");
        String type = event.path("type").asText("");
        if (eventId.isEmpty() || type.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_event");
        }
        return tx.execute(status -> {
            if (jdbc.update("INSERT INTO stripe_events (event_id, type) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    eventId, type) == 0) {
                return Map.of("duplicate", true);
            }
            if (type.equals("payment_intent.succeeded")) {
                return Map.of("received", true, "credited", credit(event.path("data").path("object").path("id").asText("")));
            }
            return Map.of("received", true);
        });
    }

    private boolean credit(String stripeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT account_id, amount FROM payment_intents WHERE stripe_id = ? FOR UPDATE", stripeId);
        if (rows.isEmpty()) {
            return false;
        }
        // The amount comes from our own record, never from the event body.
        TransferService.Outcome outcome = transfers.deposit("stripe:" + stripeId,
                (UUID) rows.get(0).get("account_id"), (Long) rows.get(0).get("amount"));
        jdbc.update("UPDATE payment_intents SET status = 'succeeded' WHERE stripe_id = ?", stripeId);
        return !outcome.replayed();
    }

    private JsonNode parse(byte[] payload) {
        try {
            return json.readTree(payload);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_json");
        }
    }
}
