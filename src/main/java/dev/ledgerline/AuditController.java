package dev.ledgerline;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ledger invariants computed straight from SQL. Every "bad" counter must be 0 and globalBalanceSum must be 0
 * (system accounts carry the negative side of every deposit).
 * ponytail: unauthenticated and full-table scans; put behind auth and run off a replica in production.
 */
@RestController
class AuditController {
    private static final Map<String, String> CHECKS = new LinkedHashMap<>();

    static {
        CHECKS.put("transfersWithNonZeroSum",
                "SELECT count(*) FROM (SELECT transfer_id FROM entries GROUP BY transfer_id HAVING sum(amount) <> 0) t");
        CHECKS.put("accountsWhereBalanceNeEntrySum", """
                SELECT count(*) FROM accounts a
                LEFT JOIN (SELECT account_id, sum(amount) AS s FROM entries GROUP BY account_id) e ON e.account_id = a.id
                WHERE a.balance <> coalesce(e.s, 0)""");
        CHECKS.put("negativeNonSystemAccounts", "SELECT count(*) FROM accounts WHERE NOT allow_negative AND balance < 0");
        CHECKS.put("globalBalanceSum", "SELECT coalesce(sum(balance), 0) FROM accounts");
        CHECKS.put("duplicateIdempotencyKeys",
                "SELECT count(*) FROM (SELECT idempotency_key FROM transfers GROUP BY 1 HAVING count(*) > 1) d");
        CHECKS.put("postedTransfers", "SELECT count(*) FROM transfers WHERE status = 'POSTED'");
        CHECKS.put("rejectedTransfers", "SELECT count(*) FROM transfers WHERE status = 'REJECTED'");
        CHECKS.put("entries", "SELECT count(*) FROM entries");
    }

    private final JdbcTemplate jdbc;

    AuditController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/admin/audit")
    Map<String, Long> audit() {
        Map<String, Long> result = new LinkedHashMap<>();
        CHECKS.forEach((name, sql) -> result.put(name, jdbc.queryForObject(sql, Long.class)));
        return result;
    }
}
