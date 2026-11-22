package dev.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxTest extends IntegrationTest {

    @Autowired
    OutboxPublisher publisher;

    @Test
    void transferEnqueuesOneEventThatIsPublishedExactlyOnce() {
        UUID a = fundedAccount(1_000);
        UUID b = createAccount("USD");
        publisher.publishBatch(); // flush the deposit's event

        var res = transfer("outbox-1", a, b, 10);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE published_at IS NULL AND event_type = 'transfer.posted' AND aggregate_id = ?""",
                Long.class, res.getBody().get("id"))).isEqualTo(1);
        assertThat(unpublished()).isEqualTo(1);
        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(unpublished()).isZero();
        assertThat(publisher.publishBatch()).isZero();
    }

    private long unpublished() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
    }
}
