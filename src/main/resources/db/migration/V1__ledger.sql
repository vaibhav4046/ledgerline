CREATE TABLE accounts (
    id             uuid PRIMARY KEY,
    name           text        NOT NULL,
    currency       char(3)     NOT NULL,
    balance        bigint      NOT NULL DEFAULT 0,
    allow_negative boolean     NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT accounts_no_overdraft CHECK (allow_negative OR balance >= 0)
);

CREATE TABLE transfers (
    id              uuid PRIMARY KEY,
    idempotency_key text        NOT NULL UNIQUE,
    request_hash    text        NOT NULL,
    from_account    uuid        NOT NULL REFERENCES accounts (id),
    to_account      uuid        NOT NULL REFERENCES accounts (id),
    amount          bigint      NOT NULL CHECK (amount > 0),
    currency        char(3)     NOT NULL,
    status          text        NOT NULL CHECK (status IN ('POSTED', 'REJECTED')),
    reject_reason   text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT transfers_distinct_accounts CHECK (from_account <> to_account)
);

CREATE TABLE entries (
    id          bigserial PRIMARY KEY,
    transfer_id uuid        NOT NULL REFERENCES transfers (id),
    account_id  uuid        NOT NULL REFERENCES accounts (id),
    amount      bigint      NOT NULL CHECK (amount <> 0),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX entries_account_id_idx ON entries (account_id);
CREATE INDEX entries_transfer_id_idx ON entries (transfer_id);

-- Double-entry guard: checked at COMMIT, so both legs can be inserted first.
CREATE FUNCTION entries_must_balance() RETURNS trigger AS $$
BEGIN
    IF (SELECT coalesce(sum(amount), 0) FROM entries WHERE transfer_id = NEW.transfer_id) <> 0 THEN
        RAISE EXCEPTION 'unbalanced transfer %: entries do not sum to zero', NEW.transfer_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER entries_balanced
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION entries_must_balance();

-- Ledger is append-only: corrections are new transfers, never edits.
CREATE FUNCTION entries_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'entries are append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER entries_no_update_delete
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION entries_append_only();

CREATE TABLE outbox_events (
    id             bigserial PRIMARY KEY,
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);
CREATE INDEX outbox_unpublished_idx ON outbox_events (id) WHERE published_at IS NULL;

CREATE TABLE payment_intents (
    id              uuid PRIMARY KEY,
    stripe_id       text        NOT NULL UNIQUE,
    account_id      uuid        NOT NULL REFERENCES accounts (id),
    amount          bigint      NOT NULL CHECK (amount > 0),
    currency        char(3)     NOT NULL,
    status          text        NOT NULL,
    client_secret   text,
    idempotency_key text        NOT NULL UNIQUE,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE stripe_events (
    event_id    text PRIMARY KEY,
    type        text        NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
