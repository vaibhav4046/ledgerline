package dev.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrencyTest extends IntegrationTest {

    @Test
    void thirtyTwoConcurrentDuplicatesProduceExactlyOneTransfer() throws Exception {
        UUID a = fundedAccount(1_000);
        UUID b = createAccount("USD");

        var responses = concurrently(32, () -> transfer("dup-key", a, b, 100));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(200, 201));
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 201)).hasSize(1);
        assertThat(responses.stream().map(r -> r.getBody().get("id")).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE idempotency_key = 'dup-key'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE transfer_id = ?::uuid", Long.class,
                responses.get(0).getBody().get("id"))).isEqualTo(2);
        assertThat(balance(a)).isEqualTo(900);
        assertThat(balance(b)).isEqualTo(100);
    }

    @Test
    void doubleSpendUnderConcurrencyPostsExactlyWhatTheBalanceAllows() throws Exception {
        UUID a = fundedAccount(100);
        UUID b = createAccount("USD");

        var responses = concurrently(50, () -> transfer(UUID.randomUUID().toString(), a, b, 10));

        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 201)).hasSize(10);
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 422)).hasSize(40);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isEqualTo(100);
        assertThat(audit()).containsEntry("negativeNonSystemAccounts", 0);
    }

    @Test
    void randomTransfersAcrossTenAccountsKeepTheLedgerBalanced() throws Exception {
        int accounts = 10;
        int threads = 16;
        int perThread = 63; // 1008 transfers
        long funding = 10_000;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            ids.add(fundedAccount(funding));
        }
        AtomicInteger threadNo = new AtomicInteger();

        List<List<Integer>> statuses = concurrently(threads, () -> {
            int me = threadNo.getAndIncrement();
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            List<Integer> mine = new ArrayList<>();
            for (int i = 0; i < perThread; i++) {
                UUID from;
                UUID to;
                if (i % 5 == 0) {
                    // Opposite-direction pair on the same two accounts across threads: deadlock bait.
                    from = ids.get(me % 2);
                    to = ids.get(1 - me % 2);
                } else {
                    int x = rnd.nextInt(accounts);
                    int y = (x + 1 + rnd.nextInt(accounts - 1)) % accounts;
                    from = ids.get(x);
                    to = ids.get(y);
                }
                mine.add(transfer(UUID.randomUUID().toString(), from, to, 1 + rnd.nextInt(500))
                        .getStatusCode().value());
            }
            return mine;
        });

        List<Integer> all = statuses.stream().flatMap(List::stream).toList();
        assertThat(all).hasSize(threads * perThread).allSatisfy(s -> assertThat(s).isIn(201, 422));
        Map<String, Object> audit = audit();
        assertThat(audit)
                .containsEntry("transfersWithNonZeroSum", 0)
                .containsEntry("accountsWhereBalanceNeEntrySum", 0)
                .containsEntry("negativeNonSystemAccounts", 0)
                .containsEntry("duplicateIdempotencyKeys", 0)
                .containsEntry("globalBalanceSum", 0);
        long posted = ((Number) audit.get("postedTransfers")).longValue();
        long rejected = ((Number) audit.get("rejectedTransfers")).longValue();
        assertThat(posted + rejected).isEqualTo(threads * perThread + accounts);
        assertThat(((Number) audit.get("entries")).longValue()).isEqualTo(2 * posted);
        assertThat(ids.stream().mapToLong(this::balance).sum()).isEqualTo(accounts * funding);
    }
}
