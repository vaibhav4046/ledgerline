# ledgerline

A payments ledger service in **Java 21 + Spring Boot 3**, backed by **PostgreSQL**.

- **Double-entry bookkeeping.** Every transfer writes two entries that sum to zero. PostgreSQL enforces this itself, so a bug in the Java code still cannot leave the ledger out of balance.
- **Idempotent transfers.** Retries and concurrent duplicates with the same `Idempotency-Key` move money exactly once.
- **Stripe test-mode PaymentIntents and webhooks.** Webhooks are signature-verified and safe to replay.
- **Transactional outbox** for events.
- **Tests.** JUnit 5 and Testcontainers, including concurrency tests. CI runs on GitHub Actions and also builds the Docker image.
- **Load test.** k6, with correctness checked after every run.

[![ci](https://github.com/vaibhav4046/ledgerline/actions/workflows/ci.yml/badge.svg)](https://github.com/vaibhav4046/ledgerline/actions/workflows/ci.yml)

## Architecture

```
            Idempotency-Key                     Stripe-Signature (HMAC-SHA256, 300 s tolerance)
 client ───────────────┐                     ┌──────────────── Stripe (test mode)
                       ▼                     ▼                        ▲
              ┌───────────────────────────────────────┐               │ POST /v1/payment_intents
              │ Spring Boot (JdbcTemplate, no ORM)     │───────────────┘ (Bearer sk_test_…, Idempotency-Key forwarded)
              │  TransferController / AccountController│
              │  PaymentIntentController               │
              │  StripeWebhookController               │
              │  TransferService  ── one DB transaction per money movement
              │  OutboxPublisher  ── @Scheduled poller │
              └───────────────┬───────────────────────┘
                              ▼
 ┌──────────────────────────── PostgreSQL 16 (Flyway V1__ledger.sql) ───────────────────────────┐
 │ accounts        balance bigint, CHECK (allow_negative OR balance >= 0)                         │
 │ transfers       idempotency_key UNIQUE, request_hash, status POSTED|REJECTED                   │
 │ entries         signed amounts; DEFERRED constraint trigger: SUM(amount) per transfer = 0;    │
 │                 UPDATE/DELETE blocked (append-only)                                            │
 │ outbox_events   written in the same transaction; poller uses FOR UPDATE SKIP LOCKED           │
 │ payment_intents / stripe_events (event_id PK → replayed webhooks are no-ops)                   │
 └───────────────────────────────────────────────────────────────────────────────────────────────┘
```

### How a transfer works (one transaction)
1. `INSERT INTO transfers … ON CONFLICT (idempotency_key) DO NOTHING`. If a concurrent duplicate is mid-flight, the unique index makes this insert wait for the first transaction to commit.
2. If the key already exists, the service compares a SHA-256 hash of the request.
   - Same hash: it returns the stored result, with header `Idempotent-Replayed: true`.
   - Different hash: it returns `409`.
3. It locks both account rows with `SELECT … FOR UPDATE`, in ascending id order so two transfers cannot deadlock. Then it checks the currency and the funds.
4. If funds are short, the transfer is stored as `REJECTED` (`422`), with no entries. A retry with the same key replays the same `422`.
5. Otherwise it writes 2 entries, updates the cached balances, and inserts a `transfer.posted` outbox event, then commits. At commit, the deferred trigger re-checks that the entries sum to zero.

### Stripe (test mode only)
- `STRIPE_SECRET_KEY` is read from the environment. The service refuses any key that does not start with `sk_test_`. Without a key, `/payments/intents` returns `503`.
- `POST /webhooks/stripe` checks the signature itself: HMAC-SHA256 over `t.payload`, a constant-time comparison, and a 300 s tolerance on the timestamp.
- It then inserts the event id into `stripe_events`. A replayed event is a no-op.
- `payment_intent.succeeded` credits the account through the normal deposit path, with idempotency key `stripe:<pi_id>`. So even two *different* event ids for the same PaymentIntent cannot credit twice.
- No secrets are in this repo. `.env.example` holds placeholders only.

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/accounts` | `{name, currency}` |
| GET | `/accounts/{id}` | balance in minor units |
| POST | `/accounts/{id}/deposits` | `Idempotency-Key` required; funds come from a per-currency system account |
| POST | `/transfers` | `Idempotency-Key` required; `{fromAccountId, toAccountId, amount, currency}` → 201 / 422 / 409 / 400 / 404 |
| GET | `/transfers/{id}` | |
| POST | `/payments/intents` | `Idempotency-Key` required; creates a Stripe test-mode PaymentIntent |
| POST | `/webhooks/stripe` | signed Stripe events |
| GET | `/admin/audit` | SQL invariant checks (used by the load test) |

## Tests (CI)

There are 17 tests, run in CI against PostgreSQL 16 via Testcontainers. Latest result: `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`.

- **ConcurrencyTest**
  - 32 threads send the same key at once → exactly 1 transfer, 2 entries, and the balance moves once.
  - An account holding 100 receives 50 concurrent transfers of 10 → exactly 10 post, 40 are rejected, and the balance never goes below 0.
  - About 1,000 random transfers across 10 accounts from 16 threads, including opposite-direction pairs → the audit is clean.
- **LedgerConstraintsTest:** an unbalanced entry pair fails at commit, entries cannot be updated or deleted, and a normal account cannot go negative. These go straight to SQL, bypassing the Java code.
- **TransferApiTest:** the happy path, insufficient funds, currency mismatch, replay, a reused key with a different body (409), validation and not-found.
- **StripeTest:** PaymentIntent creation against a stub server (checks the Bearer key, the forwarded Idempotency-Key and the form fields); non-`sk_test_` keys are refused; a valid webhook credits once; a replayed event and a second event for the same PaymentIntent do not credit again; a bad signature or a stale timestamp gets `400`.
- **OutboxTest:** one event per transfer, published exactly once.

## Load test results

Script: [`loadtest/transfers.js`](loadtest/transfers.js). It uses k6's constant-arrival-rate executor (preAllocatedVUs 50, maxVUs 100).

Setup creates 200 accounts with 1,000,000 each, plus 10 "hot" accounts with only 5,000 each. Then each iteration does one of three things:
- **Most iterations:** a random transfer with a fresh key.
- **About 10%:** the transfer takes money from a hot account. This forces overdraft contention.
- **About 5%:** the same key is sent twice at once (`http.batch`), and the script checks that both responses carry the same transfer id.

After the run, `GET /admin/audit` checks the database invariants.

**Where it ran:** a GitHub Actions `ubuntu-latest` runner. The app, PostgreSQL 16 and k6 all ran on the same VM: AMD EPYC 7763, 4 vCPU, 15 GiB RAM, OpenJDK 21.0.13, k6 v0.54.0, Hikari pool 20. These are not dedicated benchmark machines, so expect variance between runs.

| Run | Target rate | Iterations/s achieved | HTTP req/s | p50 | p95 | p99 | max | Dropped iterations |
|---|---|---|---|---|---|---|---|---|
| [run 45042704725](https://github.com/vaibhav4046/ledgerline/actions/runs/45042704725) | 200/s × 60 s | 193.9 | 210.8 | 2.71 ms | 4.78 ms | 8.36 ms | 120.78 ms | 0 |
| [run 45042908015](https://github.com/vaibhav4046/ledgerline/actions/runs/45042908015) | 800/s × 60 s | 764.4 | 809.2 | 10.93 ms | 169.45 ms | 271.99 ms | 718.55 ms | 647 |

At 800/s the runner is saturated. k6 hit its 100-VU cap and dropped 647 iterations (about 1.3%), and tail latency rises sharply. So about 760 transfers/s is near the ceiling of this 4-vCPU box with everything co-located. HTTP req/s includes the 420 setup requests and the duplicate-key pairs.

**Correctness after each run** (`GET /admin/audit`, raw output in `results/*/audit.json`):

| Check | 200/s run | 800/s run |
|---|---|---|
| Posted transfers / rejected (overdraft) transfers | 11,082 / 1,129 | 42,879 / 4,685 |
| Transfers whose entries don't sum to 0 | 0 | 0 |
| Accounts whose balance ≠ sum of their entries | 0 | 0 |
| Non-system accounts with negative balance (double-spends) | 0 | 0 |
| Global balance sum | 0 | 0 |
| Duplicate idempotency keys | 0 | 0 |
| Same-key pairs that returned different transfer ids (`idem_mismatch`) | 0 | 0 |
| k6 checks passed | 12,626 / 12,626 | 49,707 / 49,707 |

k6 reports `http_req_failed` as 9.13% and 9.75%. That figure is the intentional `422` overdraft rejections on the hot accounts: k6 counts every non-2xx as "failed". No request failed for any other reason. Every check (status in 201/422, or 200 for a replay) passed.

Each `results/run-*/` folder holds the files below. All numbers above are copied from them.
- `machine.txt`: specs, commit and date
- `command.txt`: the exact k6 command
- `k6-output.txt` and `k6-summary.txt`
- `k6-summary.json`: full metrics
- `audit.json`
- `app.log.gz`

## Reproduce

Locally (needs Java 21, Maven, PostgreSQL 16 and k6):

```bash
mvn -B verify                                   # tests; uses Testcontainers (Docker) by default
# or point tests at an existing Postgres instead of Docker:
LEDGER_TEST_DB_URL='jdbc:postgresql://127.0.0.1:5432/postgres?currentSchema=ledger_test' mvn -B verify

mvn -B -DskipTests package
LEDGER_DB_URL='jdbc:postgresql://127.0.0.1:5432/postgres?currentSchema=ledger' \
LEDGER_DB_USER=postgres LEDGER_DB_PASSWORD=postgres java -jar target/ledgerline-0.1.0.jar
k6 run -e RATE=200 -e DURATION=60s -e BASE_URL=http://localhost:8080 loadtest/transfers.js
curl -s localhost:8080/admin/audit
```

The load-test numbers above were produced the same way, by the `loadtest` workflow: `gh workflow run loadtest.yml -f rate=800 -f duration=60s`.

Docker: `docker build -t ledgerline . && docker run -p 8080:8080 -e LEDGER_DB_URL=… ledgerline`.

## Limitations (honest list)

- **No authentication or authorization.** In particular, `/admin/audit` and account creation are open. A real deployment needs auth and rate limits.
- **Not tested against live Stripe.** No real Stripe test-mode keys were used here. PaymentIntent creation is tested against a local stub that checks the exact request shape, and webhook handling is tested with signatures generated in-test. Running against Stripe needs `STRIPE_SECRET_KEY=sk_test_…` and `STRIPE_WEBHOOK_SECRET`.
- **The outbox publisher only logs** events. There is no broker (Kafka, SQS and so on) wired in. Delivery is at-least-once: a crash after publishing but before `published_at` is set causes a re-send, so consumers must dedupe by event id.
- **Single currency per transfer.** There is no FX.
- **Hot accounts serialize.** Every transfer touching one account takes that row lock, so a single very hot account is a throughput ceiling. Sharded or bucketed balances would fix that.
- **The load test is a single co-located VM.** These are not distributed or production-scale numbers. They show behaviour under concurrency on 4 vCPUs.
- **Not run on the author's laptop.** The tests and the load test could not run there, because loopback TCP on that Windows machine stalled after the first message, even for a plain Java echo test. All results come from GitHub-hosted runners, as linked above.
