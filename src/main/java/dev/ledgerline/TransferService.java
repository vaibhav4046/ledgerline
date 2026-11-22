package dev.ledgerline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves money. Every transfer runs in one transaction: lock both accounts in id order,
 * claim the idempotency key, write two entries, update balances, enqueue an outbox event.
 */
@Service
public class TransferService {

    public record Transfer(UUID id, UUID fromAccountId, UUID toAccountId, long amount, String currency,
                           String status, String rejectReason, Instant createdAt) {
    }

    public record Outcome(Transfer transfer, boolean replayed) {
        public HttpStatus httpStatus() {
            if ("REJECTED".equals(transfer.status())) {
                return HttpStatus.UNPROCESSABLE_ENTITY;
            }
            return replayed ? HttpStatus.OK : HttpStatus.CREATED;
        }
    }

    private static final RowMapper<Transfer> TRANSFER = (rs, i) -> new Transfer(
            rs.getObject("id", UUID.class), rs.getObject("from_account", UUID.class),
            rs.getObject("to_account", UUID.class), rs.getLong("amount"), rs.getString("currency"),
            rs.getString("status"), rs.getString("reject_reason"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public TransferService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Outcome transfer(String key, UUID from, UUID to, long amount, String currency) {
        if (from.equals(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "same_account");
        }
        return move(key, hash("transfer", from, to, amount, currency), from, to, amount, currency);
    }

    /** Credits an account from the per-currency external system account. */
    @Transactional
    public Outcome deposit(String key, UUID accountId, long amount) {
        String currency = jdbc.query("SELECT currency FROM accounts WHERE id = ?",
                        (rs, i) -> rs.getString(1), accountId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
        UUID system = systemAccount(currency);
        if (system.equals(accountId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_deposit_to_system_account");
        }
        return move(key, hash("deposit", accountId, amount), system, accountId, amount, currency);
    }

    public Optional<Transfer> find(UUID id) {
        return jdbc.query("SELECT * FROM transfers WHERE id = ?", TRANSFER, id).stream().findFirst();
    }

    private Outcome move(String key, String requestHash, UUID from, UUID to, long amount, String currency) {
        Optional<Outcome> replay = replay(key, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        // ORDER BY id + FOR UPDATE locks rows in a global order, so opposite-direction transfers can't deadlock.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, currency, balance, allow_negative FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                from, to);
        if (rows.size() < 2) {
            throw new ApiException(HttpStatus.NOT_FOUND, "account_not_found");
        }
        Map<String, Object> source = null;
        for (Map<String, Object> row : rows) {
            if (!currency.equals(row.get("currency"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "currency_mismatch");
            }
            if (from.equals(row.get("id"))) {
                source = row;
            }
        }
        boolean funded = (Boolean) source.get("allow_negative") || (Long) source.get("balance") >= amount;

        UUID id = UUID.randomUUID();
        // A concurrent request with the same key blocks here on the unique index until the first commits.
        int inserted = jdbc.update("""
                        INSERT INTO transfers (id, idempotency_key, request_hash, from_account, to_account,
                                               amount, currency, status, reject_reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (idempotency_key) DO NOTHING""",
                id, key, requestHash, from, to, amount, currency,
                funded ? "POSTED" : "REJECTED", funded ? null : "insufficient_funds");
        if (inserted == 0) {
            return replay(key, requestHash).orElseThrow();
        }
        if (funded) {
            post(id, from, to, amount, currency);
        }
        return new Outcome(find(id).orElseThrow(), false);
    }

    private void post(UUID id, UUID from, UUID to, long amount, String currency) {
        jdbc.update("INSERT INTO entries (transfer_id, account_id, amount) VALUES (?, ?, ?), (?, ?, ?)",
                id, from, -amount, id, to, amount);
        jdbc.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", amount, from);
        jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", amount, to);
        jdbc.update("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                        VALUES ('transfer', ?, 'transfer.posted', jsonb_build_object(
                            'transferId', ?::text, 'fromAccountId', ?::text, 'toAccountId', ?::text,
                            'amount', ?::bigint, 'currency', ?::text))""",
                id.toString(), id, from, to, amount, currency);
    }

    private Optional<Outcome> replay(String key, String requestHash) {
        return jdbc.query("SELECT id, request_hash FROM transfers WHERE idempotency_key = ?",
                (rs, i) -> {
                    if (!requestHash.equals(rs.getString("request_hash"))) {
                        throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused");
                    }
                    return rs.getObject("id", UUID.class);
                }, key).stream().findFirst().map(id -> new Outcome(find(id).orElseThrow(), true));
    }

    private UUID systemAccount(String currency) {
        UUID id = UUID.nameUUIDFromBytes(("external:" + currency).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO accounts (id, name, currency, allow_negative) VALUES (?, ?, ?, true)
                ON CONFLICT (id) DO NOTHING""", id, "external:" + currency, currency);
        return id;
    }

    static String hash(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            canonical.append(part).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
