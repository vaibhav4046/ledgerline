package dev.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/** The database itself refuses to corrupt the ledger, even if application code is bypassed. */
class LedgerConstraintsTest extends IntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Test
    void unbalancedEntriesFailAtCommit() {
        UUID a = createAccount("USD");
        UUID b = createAccount("USD");
        UUID t = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO transfers (id, idempotency_key, request_hash, from_account, to_account, amount, currency, status)
                    VALUES (?, 'raw', 'x', ?, ?, 100, 'USD', 'POSTED')""", t, a, b);
            jdbc.update("INSERT INTO entries (transfer_id, account_id, amount) VALUES (?, ?, -100), (?, ?, 99)",
                    t, a, t, b);
        })).hasStackTraceContaining("unbalanced transfer");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE id = ?", Long.class, t)).isZero();
    }

    @Test
    void entriesAreAppendOnly() {
        UUID a = fundedAccount(500);

        assertThatThrownBy(() -> jdbc.update("UPDATE entries SET amount = amount * 2 WHERE account_id = ?", a))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM entries WHERE account_id = ?", a))
                .hasStackTraceContaining("append-only");
        assertThat(balance(a)).isEqualTo(500);
    }

    @Test
    void normalAccountCannotGoNegative() {
        UUID a = fundedAccount(10);

        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance = -1 WHERE id = ?", a))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("accounts_no_overdraft");
    }
}
