package dev.ledgerline.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Minimal Stripe REST client (no SDK). Only test-mode keys are accepted. */
@Component
public class StripeClient {

    public record Intent(String id, String clientSecret, String status) {
    }

    private final RestClient http;
    private final String secretKey;

    public StripeClient(RestClient.Builder builder,
                        @Value("${ledgerline.stripe.base-url:https://api.stripe.com}") String baseUrl,
                        @Value("${ledgerline.stripe.secret-key:}") String secretKey) {
        if (!secretKey.isEmpty() && !secretKey.startsWith("sk_test_")) {
            throw new IllegalStateException("Refusing to start: STRIPE_SECRET_KEY must be a test-mode key (sk_test_...)");
        }
        this.secretKey = secretKey;
        this.http = builder.baseUrl(baseUrl).build();
    }

    public boolean enabled() {
        return !secretKey.isEmpty();
    }

    public Intent createPaymentIntent(String idempotencyKey, UUID accountId, long amount, String currency) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("amount", Long.toString(amount));
        form.add("currency", currency.toLowerCase(Locale.ROOT));
        form.add("metadata[account_id]", accountId.toString());
        form.add("automatic_payment_methods[enabled]", "true");

        JsonNode body = http.post().uri("/v1/payment_intents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (body == null || body.path("id").asText("").isEmpty()) {
            throw new IllegalStateException("Stripe returned no payment intent id");
        }
        return new Intent(body.path("id").asText(), body.path("client_secret").asText(null),
                body.path("status").asText());
    }
}
