package dev.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferApiTest extends IntegrationTest {

    @Test
    void postsTransferAndMovesBalances() {
        UUID a = fundedAccount(1_000);
        UUID b = createAccount("USD");

        var res = transfer("t-1", a, b, 300);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody()).containsEntry("status", "POSTED");
        assertThat(balance(a)).isEqualTo(700);
        assertThat(balance(b)).isEqualTo(300);
        var fetched = get("/transfers/" + res.getBody().get("id"));
        assertThat(fetched.getBody()).isEqualTo(res.getBody());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE transfer_id = ?::uuid",
                Long.class, res.getBody().get("id"))).isEqualTo(2);
    }

    @Test
    void insufficientFundsIsRejectedAndStoredWithoutEntries() {
        UUID a = fundedAccount(100);
        UUID b = createAccount("USD");

        var res = transfer("t-poor", a, b, 101);

        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody()).containsEntry("status", "REJECTED").containsEntry("rejectReason", "insufficient_funds");
        assertThat(balance(a)).isEqualTo(100);
        assertThat(balance(b)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE transfer_id = ?::uuid",
                Long.class, res.getBody().get("id"))).isZero();

        var replay = transfer("t-poor", a, b, 101);
        assertThat(replay.getStatusCode().value()).isEqualTo(422);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(res.getBody());
    }

    @Test
    void currencyMismatchIsRejected() {
        UUID usd = fundedAccount(1_000);
        UUID eur = createAccount("EUR");

        var res = transfer("t-fx", usd, eur, 10);

        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody()).containsEntry("error", "currency_mismatch");
        assertThat(balance(usd)).isEqualTo(1_000);
    }

    @Test
    void sameKeySameBodyReplaysIdenticalResponse() {
        UUID a = fundedAccount(1_000);
        UUID b = createAccount("USD");

        var first = transfer("t-replay", a, b, 250);
        var second = transfer("t-replay", a, b, 250);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(balance(a)).isEqualTo(750);
    }

    @Test
    void sameKeyDifferentBodyIsConflict() {
        UUID a = fundedAccount(1_000);
        UUID b = createAccount("USD");
        transfer("t-reuse", a, b, 100);

        var res = transfer("t-reuse", a, b, 999);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).containsEntry("error", "idempotency_key_reused");
        assertThat(balance(a)).isEqualTo(900);
    }

    @Test
    void validationAndNotFound() {
        UUID a = fundedAccount(1_000);
        UUID b = createAccount("USD");

        assertThat(transfer(null, a, b, 10).getStatusCode().value()).isEqualTo(400);
        assertThat(transfer("t-neg", a, b, -5).getStatusCode().value()).isEqualTo(400);
        assertThat(transfer("t-self", a, a, 5).getStatusCode().value()).isEqualTo(400);
        assertThat(post("/transfers", "t-bad", Map.of("fromAccountId", "nope")).getStatusCode().value()).isEqualTo(400);
        assertThat(transfer("t-404", a, UUID.randomUUID(), 5).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/accounts/" + UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/transfers/" + UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
        assertThat(balance(a)).isEqualTo(1_000);
    }
}
