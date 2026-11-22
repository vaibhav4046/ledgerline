package dev.ledgerline;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox relay. SKIP LOCKED lets several instances poll without double-publishing a row.
 * ponytail: publish() only logs; swap in a Kafka/SNS producer when there is a consumer.
 * Delivery is at-least-once if the process dies between publish and commit.
 */
@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;

    OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx,
                    @Value("${ledgerline.outbox.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${ledgerline.outbox.poll-ms:1000}")
    public void poll() {
        if (enabled) {
            publishBatch();
        }
    }

    /** Publishes up to one batch and marks it published, in one transaction. Returns the number published. */
    public int publishBatch() {
        Integer count = tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload
                    FROM outbox_events WHERE published_at IS NULL
                    ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""", BATCH_SIZE);
            for (Map<String, Object> row : rows) {
                publish(row);
            }
            jdbc.batchUpdate("UPDATE outbox_events SET published_at = now() WHERE id = ?",
                    rows.stream().map(r -> new Object[] {r.get("id")}).toList());
            return rows.size();
        });
        return count == null ? 0 : count;
    }

    private void publish(Map<String, Object> row) {
        log.info("outbox.publish id={} type={} {}={} payload={}", row.get("id"), row.get("event_type"),
                row.get("aggregate_type"), row.get("aggregate_id"), row.get("payload"));
    }
}
