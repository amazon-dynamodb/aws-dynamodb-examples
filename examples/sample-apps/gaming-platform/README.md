# Gaming Platform

## Summary

Gaming Platform is a Spring Boot application that models a focused slice of a cross-platform gaming backend backed entirely by **Amazon DynamoDB**. It isolates patterns that matter for correctness and scale: **idempotent player registration**, **profile and progression updates**, **player settings management**, **wallet-based currency tracking**, **atomic in-game purchases**, **lobby-style batch reads**, **append-only game events with TTL**, **leaderboard queries**, and **asynchronous leaderboard maintenance from DynamoDB Streams**.

The application demonstrates **conditional writes** for registration and progression, **optimistic locking** via a `version` attribute (explicit condition expressions on the low-level path and **VersionedRecordExtension** on the high-level path), **multi-item atomicity** with **TransactWriteItems** for purchases and currency grants that span the **PlayerState** and **GameEvents** tables, **Global Secondary Indexes** for browsing players by platform, **Time to Live (TTL)** on game events, and **DynamoDB Streams** (**NEW_IMAGE**) consumed by a polling listener that projects **PVP_MATCH** results into a separate **LeaderboardAggregate** table with score-padded sort keys.

Unlike the Instant Payments sample's **single-table** layout, this module splits data across **three tables** so each access pattern maps to a clear physical home. Within the **PlayerState** table each player has up to three items: a profile row, a settings row, and a wallet row. This isolation means XP writes, currency writes, and settings writes never conflict on the same optimistic-lock version.

It provides interchangeable repository implementations using both the **low-level DynamoDbAsyncClient** and the **high-level DynamoDbEnhancedAsyncClient** (selected at startup via `dynamodb.client-type`).

For simplicity, the stream-driven leaderboard projection tracks its progress in memory, so if the application restarts a match result recorded during that brief window may not be projected automatically. No event is lost: the game event is still stored, and the application can be configured to replay from the beginning of the stream history, which catches missed results but reprocesses everything in the retention window. The listener reads the whole **GameEvents** stream and acts only on **PVP_MATCH** inserts, a simple approach that trades some read cost for fewer moving parts. Kinesis Data Streams for DynamoDB adds server-side filtering when that cost matters.

---

## Why DynamoDB?

A live game backend needs fast reads for matchmaking and lobby hydration, safe writes when currency and progression change under concurrency, and a place to land high-volume per-player telemetry without unbounded storage growth. DynamoDB keeps hot player profiles and lobby lookups in the single-digit-millisecond range with **GetItem**, **BatchGetItem**, and **Query**.

Purchases that debit a wallet and append an audit event must succeed or fail together. **TransactWriteItems** bundles the wallet update and event insert so retries cannot leave half-finished state. Registration and progression rely on **conditional writes** so concurrent clients see predictable conflicts instead of silent last-writer-wins skew.

High-volume event history is paired with **TTL** so retention is automatic. **DynamoDB Streams** triggers leaderboard projection whenever a qualifying event is inserted, removing the need for a separate message broker or batch job. A dedicated **LeaderboardAggregate** table holds denormalized rows keyed for top-N reads. A single descending **Query** on a padded score sort key returns ranks in one round trip.

GSI-backed discovery by platform, batch reads for lobby hydration, transactional purchases, expiring events, and stream-driven projection show how DynamoDB's building blocks compose for interactive workloads.

---

## Endpoints

The endpoints below follow a natural player journey, from first registration through profile setup, earning and spending currency, playing a match, and finally checking the leaderboard.

### POST /api/v1/players

Registers a new player or returns the existing profile on an idempotent replay. The service uses **TransactWriteItems** to atomically create the profile, default settings, and default wallet items in a single operation. Duplicate requests with the same platform identity are accepted as replays. Conflicting duplicates are rejected. Returns a full player snapshot with a `created` flag that distinguishes new registrations from replays.

### GET /api/v1/players/{playerId}/profile

Retrieves the profile slice for a player. The service reads the PROFILE item with a single **GetItem**.

### GET /api/v1/players/{playerId}/settings

Returns the current player settings (profile visibility, notifications flag, preferred language). Reads the settings item with **GetItem** in one round trip.

### PATCH /api/v1/players/{playerId}/settings

Applies a partial update to player settings with optimistic locking on `version`. Only the supplied fields are overwritten. Version conflicts are returned as a conflict error so the client can retry after a fresh read.

### PATCH /api/v1/players/{playerId}/progression

Applies an XP delta with optimistic locking on `version`. The profile advances only if the expected version still matches. Conflicts surface as domain errors so the client can retry after a fresh read. When the XP delta crosses a level threshold a soft-currency bonus is automatically credited through the wallet earn path.

### GET /api/v1/players/{playerId}/wallet

Returns the player's current soft currency balance and wallet version, read with **GetItem** in one round trip.

### POST /api/v1/players/{playerId}/wallet/earn

Credits soft currency to the player wallet. The wallet balance and a currency grant audit event are written atomically in a single **TransactWriteItems** call, keeping the economy trail consistent with the balance. Duplicate submissions are detected and return the current balance without crediting the player twice.

### POST /api/v1/players/{playerId}/purchases

Executes an in-game purchase that debits the wallet and records a purchase audit event. A single **TransactWriteItems** operation updates the wallet balance and appends the purchase record to **GameEvents**, so both succeed or both roll back. Duplicate submissions return the original outcome without charging twice.

### POST /api/v1/players/{playerId}/events

Records a game event for an existing player. The event is persisted with **PutItem** on **GameEvents** and carries a **TTL** attribute for automatic expiry. **DynamoDB Streams** on the table feeds the leaderboard listener for qualifying event types such as **PVP_MATCH**.

### GET /api/v1/players/{playerId}/events

Lists paginated game event history. The service queries the player partition in **GameEvents** with **Query**. Results are newest first by default, with an option to request oldest first. Each response includes a pagination token when more results are available.

### GET /api/v1/leaderboards/{scope}

Returns top-ranked leaderboard entries for a given scope such as `SEASON#default#MODE#ranked`. The service queries **LeaderboardAggregate** with **Query** using a score-padded sort key so ranks are resolved in one keyed range read. The number of entries returned is configurable.

### POST /api/v1/lobbies/summaries

Batch-loads lobby summaries for multiple player identifiers using **BatchGetItem**. The response includes matched summaries and any ids that had no matching profile, so callers can handle partial results.

### GET /api/v1/lobbies/platform/{platform}

Lists players on a platform via the **GSI_PLATFORM_PLAYERS** global secondary index over **PlayerState**. Results are ordered by most recently active first, with configurable page size.
