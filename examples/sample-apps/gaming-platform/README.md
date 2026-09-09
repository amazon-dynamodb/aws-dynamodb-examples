# Gaming Platform

## Summary

Gaming Platform is an application that models a focused slice of a cross-platform gaming backend backed entirely by **Amazon DynamoDB**. It does not attempt to reproduce every concern of a production game backend, instead, it isolates the patterns that matter for correctness and scale: **idempotent player registration**, **profile and progression updates**, **player settings management**, **wallet-based currency tracking**, **atomic in-game purchases**, **lobby-style batch reads**, **append-only game events with TTL**, **leaderboard queries**, and **asynchronous leaderboard maintenance from DynamoDB Streams**. The goal is to demonstrate how the **DynamoDB** feature set can support these critical steps with correctness guarantees, using a simplified, illustrative implementation of a live game backend.

The application demonstrates key DynamoDB capabilities, including multi-item atomicity with **TransactWriteItems** for purchases and currency grants, **conditional writes** for registration and progression, and asynchronous event processing with **DynamoDB Streams** (**NEW_IMAGE**). It uses **Global Secondary Indexes** for browsing players by platform, **Time to Live (TTL)** for game event expiry, and **optimistic locking** on a `version` attribute for safe concurrent updates, applied through explicit condition expressions on the low-level path and **VersionedRecordExtension** on the high-level path. The module follows a **multi-table design** that organizes entities with **composite keys** across the **PlayerState**, **GameEvent**, and **Leaderboard** tables, and within **PlayerState** each player keeps separate profile, settings, and wallet items so those writes never conflict on the same version. It provides interchangeable persistence implementations behind a shared interface, selectable at application startup, covering a low-level DynamoDB client and, where the SDK offers one, a high-level document or enhanced client.

For simplicity, the stream-driven leaderboard projection tracks its progress in memory, so if the application restarts a match result recorded during that brief window may not be projected automatically. No event is lost: the game event is still stored, and the application can be configured to replay from the beginning of the stream history, which catches missed results but reprocesses everything in the retention window.

---

## Why DynamoDB?

A live game backend needs fast reads for matchmaking and lobby hydration, safe writes when currency and progression change under concurrency, and a place to land high-volume per-player telemetry without unbounded storage growth. DynamoDB keeps hot player profiles and lobby lookups in the single-digit-millisecond range with **GetItem**, **BatchGetItem**, and **Query**.

Purchases that debit a wallet and append an audit event must succeed or fail together. **TransactWriteItems** bundles the wallet update and event insert so retries cannot leave half-finished state. Registration and progression rely on **conditional writes** so concurrent clients see predictable conflicts instead of silent last-writer-wins skew.

High-volume event history is paired with **TTL** so retention is automatic. **DynamoDB Streams** triggers leaderboard projection whenever a qualifying event is inserted, removing the need for a separate message broker or batch job. A dedicated **Leaderboard** table holds denormalized rows keyed for top-N reads. A single descending **Query** on a padded score sort key returns ranks in one round trip.

GSI-backed discovery by platform, batch reads for lobby hydration, transactional purchases, expiring events, and stream-driven projection show how DynamoDB's building blocks compose for interactive workloads.

One cost trade-off comes with the stream-driven leaderboard: a raw DynamoDB stream carries every change to the **GameEvent** table and offers no server-side filter, so the listener reads all records and acts only on **PVP_MATCH** inserts, paying `GetRecords` cost on purchase and currency-grant events it then discards. The per-match volume here is small, but on a busy table this adds up. A workload that needs server-side filtering can use Kinesis Data Streams for DynamoDB, which supports consumer-side stream filters, instead of raw DynamoDB Streams.

---

## Endpoints

### POST /api/v1/players

Registers a new player or returns the existing profile on an idempotent replay. The service uses **TransactWriteItems** to atomically create the profile, default settings, and default wallet items in a single operation. Duplicate requests with the same platform identity are accepted as replays. Conflicting duplicates are rejected. Returns a full player snapshot with a `created` flag that distinguishes new registrations from replays.

### GET /api/v1/players/{playerId}/profile

Retrieves a player's profile. The service reads it with a single **GetItem**.

### GET /api/v1/players/{playerId}/settings

Returns the current player settings (profile visibility, notifications flag, preferred language). Reads the settings item with **GetItem** in one round trip.

### PATCH /api/v1/players/{playerId}/settings

Applies a partial update to player settings with optimistic locking on `version`. Only the supplied fields are overwritten. Version conflicts are returned as a conflict error so the client can retry after a fresh read.

### PATCH /api/v1/players/{playerId}/progression

Applies an experience update with optimistic locking. The profile advances only if the expected version still matches, otherwise a conflict error lets the client retry after a fresh read. When the gain crosses a level threshold a soft-currency bonus is automatically credited to the wallet.

### GET /api/v1/players/{playerId}/wallet

Returns the player's current soft currency balance, read with **GetItem** in one round trip.

### POST /api/v1/players/{playerId}/wallet/earn

Credits soft currency to the player wallet. The wallet balance and a currency grant audit event are written atomically in a single **TransactWriteItems** call, keeping the economy trail consistent with the balance. Duplicate submissions are detected and return the current balance without crediting the player twice.

### POST /api/v1/players/{playerId}/purchases

Executes an in-game purchase that debits the wallet and records a purchase audit event. A single **TransactWriteItems** operation updates the wallet balance and appends the purchase record to **GameEvent**, so both succeed or both roll back. Duplicate submissions return the original outcome without charging twice.

### POST /api/v1/players/{playerId}/events

Records a game event for an existing player. The event is persisted with **PutItem** on **GameEvent** and carries a **TTL** attribute for automatic expiry. **DynamoDB Streams** on the table feeds the leaderboard listener for qualifying event types such as **PVP_MATCH**.

### GET /api/v1/players/{playerId}/events

Lists paginated game event history. The service queries the player partition in **GameEvent** with **Query**. Results are newest first by default, with an option to request oldest first. Each response includes a pagination token when more results are available.

### GET /api/v1/leaderboards/{scope}

Returns the top-ranked entries for a given leaderboard scope. The service reads them from **Leaderboard** with a single **Query** that returns them already ordered by rank. The number of entries returned defaults to **10** and can be set from **1** up to **100**.

### POST /api/v1/lobbies/summaries

Batch-loads lobby summaries for multiple player identifiers using **BatchGetItem**. The response includes matched summaries and any ids that had no matching profile, so callers can handle partial results.

### GET /api/v1/lobbies/platform/{platform}

Lists players on a platform via a **Global Secondary Index (GSI)** on **PlayerState**. Results are ordered by most recently active first, with configurable page size.
