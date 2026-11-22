package dev.ledgerline.stripe;

import dev.ledgerline.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PaymentIntentController {
    private static final long MAX_AMOUNT = 99_999_999L; // Stripe's per-charge ceiling

    record CreateIntent(@NotNull UUID accountId, @NotNull @Positive @Max(MAX_AMOUNT) Long amount,
                        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    record PaymentIntent(UUID id, String stripeId, String clientSecret, String status) {
    }

    private final JdbcTemplate jdbc;
    private final StripeClient stripe;

    PaymentIntentController(JdbcTemplate jdbc, StripeClient stripe) {
        this.jdbc = jdbc;
        this.stripe = stripe;
    }

    @PostMapping("/payments/intents")
    ResponseEntity<PaymentIntent> create(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody CreateIntent req) {
        ApiException.requireIdempotencyKey(key);
        if (!stripe.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "stripe_not_configured");
        }
        Optional<PaymentIntent> existing = find(key);
        if (existing.isPresent()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(existing.get());
        }
        String currency = jdbc.query("SELECT currency FROM accounts WHERE id = ? AND NOT allow_negative",
                        (rs, i) -> rs.getString(1), req.accountId()).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
        if (!currency.equals(req.currency())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "currency_mismatch");
        }

        // Our key is forwarded, so concurrent retries get the same intent back from Stripe.
        StripeClient.Intent intent = stripe.createPaymentIntent(key, req.accountId(), req.amount(), req.currency());
        jdbc.update("""
                        INSERT INTO payment_intents (id, stripe_id, account_id, amount, currency, status,
                                                     client_secret, idempotency_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                UUID.randomUUID(), intent.id(), req.accountId(), req.amount(), req.currency(), intent.status(),
                intent.clientSecret(), key);
        return ResponseEntity.status(HttpStatus.CREATED).body(find(key).orElseThrow());
    }

    private Optional<PaymentIntent> find(String key) {
        return jdbc.query("SELECT id, stripe_id, client_secret, status FROM payment_intents WHERE idempotency_key = ?",
                (rs, i) -> new PaymentIntent(rs.getObject("id", UUID.class), rs.getString("stripe_id"),
                        rs.getString("client_secret"), rs.getString("status")), key).stream().findFirst();
    }
}
