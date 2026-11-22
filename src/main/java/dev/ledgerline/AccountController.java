package dev.ledgerline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {
    static final long MAX_AMOUNT = 1_000_000_000_000L;

    record CreateAccount(@NotBlank @Size(max = 200) String name,
                         @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    record Deposit(@NotNull @Positive @Max(MAX_AMOUNT) Long amount) {
    }

    record Account(UUID id, String name, String currency, long balance, boolean allowNegative, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final TransferService transfers;

    AccountController(JdbcTemplate jdbc, TransferService transfers) {
        this.jdbc = jdbc;
        this.transfers = transfers;
    }

    @PostMapping("/accounts")
    ResponseEntity<Account> create(@Valid @RequestBody CreateAccount req) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, name, currency) VALUES (?, ?, ?)", id, req.name(), req.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(load(id));
    }

    @GetMapping("/accounts/{id}")
    Account get(@PathVariable UUID id) {
        return load(id);
    }

    @PostMapping("/accounts/{id}/deposits")
    ResponseEntity<TransferService.Transfer> deposit(@PathVariable UUID id,
                                                     @RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody Deposit req) {
        return TransferController.respond(
                transfers.deposit(ApiException.requireIdempotencyKey(key), id, req.amount()));
    }

    private Account load(UUID id) {
        return jdbc.query("SELECT * FROM accounts WHERE id = ?", (rs, i) -> new Account(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("currency"),
                        rs.getLong("balance"), rs.getBoolean("allow_negative"),
                        rs.getTimestamp("created_at").toInstant()), id)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
    }
}
