# Transaction processing service

Spring Boot 4 / Java 21 / Gradle service implementing the attached L2 assessment. REST accepts requests; a scheduled worker processes them asynchronously. H2 persists transactions, request IDs, balances and ledger entries across restarts.

## Run

Install Java 21 and set `JAVA_HOME`, then:

```sh
./gradlew test
./gradlew bootRun
```

On this machine Java 21 is available at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. If the shell cannot find Java:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

Run the demonstration with a simulated failure for `TXN-2005`:

```sh
./gradlew bootRun --args='--spring.profiles.active=demo'
# In another terminal, create the accounts first (requires jq):
jq -c '.[]' examples/accounts.json | while IFS= read -r account; do
  curl -s -X POST http://localhost:8080/api/v1/accounts \
    -H 'Content-Type: application/json' -d "$account"
done
curl -s -X POST http://localhost:8080/api/v1/transactions/batch \
  -H 'Content-Type: application/json' --data-binary @examples/transactions.json
# Allow a few seconds for background processing and retry.
curl -s http://localhost:8080/api/v1/transactions/summary
curl -s http://localhost:8080/api/v1/accounts/ACC-201/USD
curl -s http://localhost:8080/api/v1/accounts/ACC-201/USD/ledger
curl -s http://localhost:8080/api/v1/transactions/TXN-2005
```

Alternatively, build with `./gradlew bootJar` and run `java -jar build/libs/transaction-processing-service-0.0.1-SNAPSHOT.jar`.

The default database is `./data/transactions`. Use a different file for an isolated demonstration, e.g. `DB_URL='jdbc:h2:file:./data/demo'`. Tests use independent in-memory databases and disable the scheduler. The service is an assessment implementation without authentication; restrict access locally. Protect write, recovery and management endpoints before exposing it to a network.

## REST API

| Method | Endpoint | Result |
| --- | --- | --- |
| POST | `/api/v1/accounts` | 201; create an account/currency with opening balance and optional starting sequence |
| GET | `/api/v1/accounts/{accountId}?currency=USD` | Account details; currency optional when unambiguous |
| GET | `/api/v1/requests/{requestId}` | Original accepted/duplicate outcome, replay count and current transaction |
| GET | `/api/v1/transactions/{id}/attempts` | Persistent attempt and recovery history; limit/offset pagination |
| GET | `/api/v1/processing/summary` | Business status counts, request outcome counts, replay counts and blocked streams |
| POST | `/api/v1/transactions` | 202 with current transaction and Location; 200 for an identical replay |
| POST | `/api/v1/transactions/batch` | 200 with one outcome per row, in input order; maximum 100 rows |
| GET | `/api/v1/transactions/{id}` | Current status, attempts, error code and timestamps |
| POST | `/api/v1/transactions/{id}/retry` | 202; explicitly recover a FAILED transaction after resolving its cause |
| POST | `/api/v1/transactions/{id}/cancel` | 200; explicitly cancel a FAILED blocking transaction with a reason code |
| GET | `/api/v1/transactions/summary` | Persisted transaction counts by status and blocked streams |
| GET | `/api/v1/accounts/{id}/{currency}` | Balance and next required sequence |
| GET | `/api/v1/accounts/{id}/{currency}/ledger?limit=100&offset=0` | Ledger entries; maximum limit 1000 |
| GET | `/actuator/health` | Application and database health |
| GET | `/actuator/metrics` | JVM, HTTP and custom metrics |
| GET | `/actuator/prometheus` | Prometheus scrape endpoint |

A single submission looks like:

```json
{"transactionId":"TXN-1","requestId":"REQ-1","accountId":"ACC-1","transactionType":"CREDIT","amount":100.00,"currency":"USD","sequenceNumber":1}
```

Required fields are validated in the service, so another ingestion adapter can reuse the same rules. IDs contain 1–100 ASCII letters, digits, underscores or hyphens. Amounts are positive, have at most 17 integer digits and 2 decimal places, and use `BigDecimal`. Currencies must be uppercase three-letter codes recognized by Java. The simplified ledger uses two decimals for all supported currencies. Types are CREDIT and DEBIT; reversals are not implemented. Accounts must be created independently; the nonnegative opening balance is immutable after creation. Overdrafts are rejected. Unknown account/currency submissions return 422 and do not create a transaction. Opening balances are stored separately; balance reconciles as opening balance plus ledger deltas.

Single-request validation returns 400, key/payload or sequence conflicts return 409, and missing resources return 404 using problem details. Batch rows with invalid fields, unknown enum values or conflicting keys receive individual INVALID/CONFLICT results; valid rows continue. Syntactically invalid JSON or a non-array batch body is rejected as a whole. Invalid records are returned to the caller and are not inserted into the business transaction store.

## Expected demonstration outcome

The sample retains the assessment's sequence 4 and 5 values. `examples/accounts.json` explicitly creates ACC-202 with starting sequence 4 and ACC-203 with starting sequence 5. Other accounts default to 1. Initializing a starting sequence is account setup, not automatic skipping: any later sequence gap still blocks processing.

Batch outcomes on a new database:

```json
["ACCEPTED", "ACCEPTED", "DUPLICATE", "ACCEPTED", "INVALID", "ACCEPTED"]
```

After the worker completes:

- ACC-201/USD balance is 85.00, with three ledger entries and next sequence 4.
- ACC-203/USD balance is 500.00, with one ledger entry; TXN-2005 has two attempts in the demo profile.
- TXN-2004 is rejected for its negative amount; no ACC-202 transaction is created and its explicitly created balance remains zero.
- Summary counts are PROCESSED=4 and zero for other business states, with no blocked streams.
- Reposting the sample creates no additional ledger entries or balance effects. Previously accepted rows return DUPLICATE; the invalid row remains INVALID.

The first API response represents ingestion, not completion. Depending on polling timing, a duplicate can refer to a RECEIVED or already PROCESSED transaction.

## Architecture and guarantees

Packages separate `controller` (HTTP), `dto` (API contracts), `service` (ingestion and processing), `repository` (SQL), `model` (business states), `config` and `exception` (safe errors). `TransactionService.submit` and `submitBatch` are reusable by a future file or queue adapter. JDBC is used explicitly for SQL and lock control; the project's existing JPA dependency supplies the transaction infrastructure, but there are no JPA entities.

**Idempotency.** `transactionId` is the unique business key. Matching business fields are a duplicate even with a new `requestId`. The request-key table permanently associates every accepted request ID with one transaction. Reusing either key with different business meaning is a conflict. Amount comparisons ignore decimal scale. Duplicate is a submission outcome, not a replacement for the canonical transaction's state. The ledger also has a unique transaction ID as a database-level safeguard.

**Ordering.** Sequence numbers are scoped to `(accountId, currency)` and default to 1. Account creation may explicitly establish another starting sequence; new transactions below the next required sequence are rejected. Future sequences become PENDING. A unique stream/sequence constraint rejects two transactions claiming the same position. A successful transaction advances the sequence. An explicit operator cancellation of the FAILED transaction at the current sequence also advances it, without applying balance or ledger changes. Missing or failed predecessors block subsequent events; the summary reports expected sequence and the first outstanding sequence per stream. These streams may also be awaiting a retry, so inspect the corresponding transaction for its reason. Ready streams are prioritized over gaps so missing sequences cannot starve unrelated ready accounts. A failed debit is not silently skipped. An operator may explicitly cancel the failed transaction to unblock the stream; cancellation preserves the original request and failure history. There is no direct balance-adjustment API.

**Atomicity and concurrency.** Every write takes one database row lock inside a transaction, including ingestion and manual recovery. This deliberately serializes writers and protects races on keys that do not yet exist. Balance, ledger, sequence and PROCESSED state commit together. A crash before commit rolls everything back, leaving the record eligible for another poll; a crash after commit leaves a durable terminal state. PROCESSING is an internal state in that same transaction and is normally not externally visible. H2 file mode supports this single application instance; the lock design is conservative, not a claim of distributed throughput or external exactly-once delivery.

**Retries and recovery.** Simulated failures occur after balance and ledger writes, causing rollback. Transient database exceptions also trigger bounded retry handling when the database is reachable. Failure state is recorded in a separate transaction with a persisted version guard to avoid overwriting another worker's progress, including a fresh manual retry cycle that resets the attempt count. Backoff doubles from `processing.retry-delay-ms`, capped at 60 seconds; `processing.max-attempts` includes the initial attempt. Exhaustion becomes FAILED. A recovery request starts a new attempt budget on the existing transaction and preserves its business fields, deduplication keys and append-only attempt history. Recovery itself is recorded as a history event. The simulator is disabled unless transaction IDs are explicitly configured; the demo profile fails TXN-2005 once and TXN-FAIL ten times to show exhaustion; remove a persistent simulation before recovery. Database outages can also prevent failure bookkeeping; the durable pending row is retried on subsequent polls when the database returns. Unexpected non-transient errors after a transaction is selected for processing roll back its effects and mark that record FAILED with a safe error code, allowing other records to continue. Failures before a record can be processed, or while recording the failure, are logged without changing its business outcome; subsequent polls can retry when storage recovers.

**Observability.** Structured key/value logs include transaction IDs, states and attempts, without account IDs, amounts or request bodies. Operators should use opaque transaction IDs. Metrics `transactions.submissions` (outcome), `transactions.processing` (outcome), and `transactions.processing.duration` expose ingestion, duplicate detection, failures, retries and timing. Labels have bounded cardinality; IDs are never metric labels. Counters reset on restart; the summary reads durable business states and request outcome/replay counts. Request lookup preserves the first outcome associated with that request ID; repeating that same ID increments its replay count. A new request ID for the same business transaction records DUPLICATE. Rejected or malformed requests do not reserve request IDs and are returned immediately; lookup only covers admitted requests. Validation errors from malformed JSON are also observable through HTTP metrics. Built-in Actuator health covers the database. Failure codes and retry times are visible through transaction lookup. Failed and pending records remain persisted for investigation.

## Configuration

| Setting | Default | Meaning |
| --- | --- | --- |
| `DB_URL` | `jdbc:h2:file:./data/transactions;DB_CLOSE_ON_EXIT=FALSE` | Database location |
| `DB_USERNAME` / `DB_PASSWORD` | `sa` / empty | Local H2 credentials; override through environment |
| `processing.enabled` | true | Enable scheduled worker |
| `processing.poll-delay-ms` | 500 | Delay after a poll completes |
| `processing.batch-size` | 100 | Maximum candidates per poll |
| `processing.max-attempts` | 3 | Attempts per automatic retry cycle |
| `processing.retry-delay-ms` | 1000 | Initial backoff |
| `processing.simulated-failures.<transactionId>` | absent | Number of attempts to fail after balance update |

## Verification and production considerations

Tests exercise business-key and request-key duplicates, conflicting payloads and sequences, concurrent submissions/workers, out-of-order debit handling, missing-sequence visibility, fairness between streams, invalid-batch isolation, rollback after side effects, retry exhaustion, manual recovery, insufficient funds, HTTP statuses and Actuator endpoints. Additional tests cover explicit account setup, unknown accounts/currencies, opening balances, configured starting sequences, stale sequences, persistent request outcomes and recovery history. Run `./gradlew test`; the HTML report is `build/reports/tests/test/index.html`.

AI assistance was used to interpret the requirements, implement the packages and generate tests and this design note. The implementation is checked through compilation, service tests and HTTP tests, including a failure injected after writes to verify rollback rather than assuming it. The candidate should review and explain these design decisions before submission.

Production work would include database migrations and PostgreSQL integration tests; per-stream locking and bounded queue polling; queue acknowledgement and a transactional outbox for downstream effects; authentication/authorization and authenticated recovery audit history; request-size/rate limits; currency-specific precision; ledger reconciliation and reversal policy; alerting on old gaps and exhausted retries; retention for deduplication records; and secure operational configuration. The global lock, unbounded summary of blocked streams, H2 storage, and absence of authenticated operator identities in the recovery history are deliberate assessment limitations.


## Account setup and Postman

Import `examples/assessment.postman_collection.json` into Postman. Set `baseUrl` (default `http://localhost:8080`) and start the app with the demo profile on a fresh database. The six folders cover account creation, ordinary submission, idempotency, ordering, retries/failures and monitoring. The collection's POST assertions expect first-run HTTP statuses; rerunning against the same database intentionally produces account conflicts and duplicate responses. Use a new database path for another clean first run.

Use a 1500 ms Collection Runner delay, or wait between submitting transactions and inspecting their final state. The status code tests verify API behavior; the Java tests verify asynchronous business invariants. The missing-sequence example intentionally leaves sequence 4 pending until sequence 3 arrives. After retries finish, TXN-FAIL is FAILED; the subsequent cancellation requests in the collection move it to CANCELLED. To demonstrate retry recovery instead, stop before those cancellation requests, restart with its failure simulation disabled while preserving the database, then POST `/api/v1/transactions/TXN-FAIL/retry`. Inspect `/api/v1/transactions/TXN-FAIL/attempts` to see previous failures and the recovery event.

Create an account:

```json
{"accountId":"ACC-201","currency":"INR","openingBalance":0,"startingSequence":1}
```

The account ID/currency pair is unique. Creating it again returns 409 and cannot reset its balance or sequence. For accounts with multiple currencies, use `GET /api/v1/accounts/ACC-201?currency=INR`; omitting the currency is allowed only when there is one match. Existing unversioned account/transaction paths remain aliases, but explicit account creation is required on both versions.

The service provides API and Actuator monitoring; an HTML dashboard is not included.

## Database model and relationships

The existing JDBC approach is retained; these are relational entities with SQL foreign keys rather than JPA entity classes. `schema.sql` defines the following relationships:

- `accounts`: composite account/currency key, immutable opening balance and starting sequence, current balance and next sequence.
- `transactions`: unique business ID, foreign key to account/currency, unique sequence within that stream, processing state and current retry budget.
- `request_keys`: many requests to one transaction. Each request stores its first ACCEPTED/DUPLICATE outcome, received timestamp and replay count.
- `ledger`: at most one entry per transaction, linked by its primary/foreign key. The transaction determines the account relationship.
- `processing_attempts`: many append-only processing outcomes/recovery events to one transaction, including error codes and timestamps. Successful history commits with the ledger; retry history commits after the failed business transaction rolls back.

The database lock table coordinates writers. Additive `IF NOT EXISTS` statements preserve databases from the initial version. Historical request outcomes cannot be reconstructed: pre-upgrade request rows are backfilled as ACCEPTED, and their migration timestamp is used as received time. Existing account opening balances are backfilled as zero, matching the original implementation. Use a migration tool before production schema evolution.


## Failure recovery and mandatory edge cases

For an insufficient-funds failure or another permanently rejected business transaction, an operator can explicitly abandon the transaction:

```sh
curl -X POST http://localhost:8080/api/v1/transactions/TXN-FAILED/cancel \
  -H 'Content-Type: application/json' \
  -d '{"reasonCode":"CUSTOMER_WITHDREW"}'
```

Cancellation is only allowed for a FAILED transaction at the account's next sequence. It atomically records CANCELLED, the reason code and audit event, then advances that sequence without writing a ledger entry or changing the balance. Repeating cancellation returns the original cancelled record and does not advance again. Replaying the original business request remains a duplicate; cancelled transactions cannot be retried. If a retry and cancellation race, the database lock allows only one transition. Cancelling a processed transaction is forbidden and does not provide reversal functionality. Recovery endpoints remain operator-oriented assessment APIs; authenticate and authorize them before deployment.

The worker now isolates unexpected record-level failures and failure-bookkeeping errors. A persisted version increases with each state change, preventing a delayed failure callback from consuming attempts from a newer recovery cycle. Retry backoff is verified with a controlled clock, including duplicates arriving during the retry delay. Strict JSON settings reject fractional sequence numbers and numeric enum ordinals instead of coercing them into valid-looking requests.

Additional regression tests cover the exact six-record sample, cross-currency isolation, unexpected errors after balance/ledger writes, continued processing of independent accounts, cancellation/audit rollback, concurrent recovery versus cancellation, and stale failure callbacks after manual recovery. Rejected requests remain immediate API results rather than persisted business transactions. CREDIT and DEBIT remain the supported business types; reversal and external downstream delivery are not part of this implementation.
