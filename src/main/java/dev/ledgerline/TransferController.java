package dev.ledgerline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TransferController {
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    record TransferRequest(@NotNull UUID fromAccountId, @NotNull UUID toAccountId,
                           @NotNull @Positive @Max(AccountController.MAX_AMOUNT) Long amount,
                           @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    private final TransferService transfers;

    TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping("/transfers")
    ResponseEntity<TransferService.Transfer> create(@RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody TransferRequest req) {
        return respond(transfers.transfer(ApiException.requireIdempotencyKey(key),
                req.fromAccountId(), req.toAccountId(), req.amount(), req.currency()));
    }

    @GetMapping("/transfers/{id}")
    TransferService.Transfer get(@PathVariable UUID id) {
        return transfers.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "transfer_not_found"));
    }

    static ResponseEntity<TransferService.Transfer> respond(TransferService.Outcome outcome) {
        var response = ResponseEntity.status(outcome.httpStatus());
        if (outcome.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(outcome.transfer());
    }
}
