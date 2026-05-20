# Instant Payments

## Summary

Instant Payments is an application that models a focused slice of a real-time payment system backed entirely by a single **Amazon DynamoDB** table. It does not attempt to reproduce every concern of a production payment platform, instead, it isolates the core transactional heart of the flow: **idempotent payment creation**, **event-sourced state management**, **conditional fund reservation and completion**, and **query access patterns** that a merchant or operations team would need. The goal is to demonstrate how the **DynamoDB** feature set can support these critical steps with correctness guarantees, using a simplified, illustrative implementation of a real-time payment flow.

The application demonstrates key DynamoDB capabilities, including multi-item atomicity with **TransactWriteItems**, **conditional writes** for idempotency and state transitions, and asynchronous event processing with **DynamoDB Streams** (**NEW_IMAGE**). It uses **Global Secondary Indexes** for query access patterns, **Time to Live (TTL)** for idempotency record expiry, and **optimistic locking** for safe concurrent updates. The system follows a **single-table design**, organizing entities with **composite keys**, and provides interchangeable implementations using both a **low-level DynamoDB client** and a **high-level document or enhanced client** (configurable at application startup).

---

## Why DynamoDB?

An instant payment flow is a short-lived state machine (accept a command, reserve funds, complete or reject) where every step must be safe under retries and concurrency, reads must return in single-digit milliseconds to meet real-time SLAs, and the full history must be auditable. The access patterns are narrow and predictable: point reads on a known payment or account, ordered scans within a single partition for event replay, and indexed lookups by merchant. DynamoDB fits because its core primitives line up directly with these requirements.

Each payment's stream head and events live under one partition key, and each account's balance, reservations, and ledger entries live under another, so a single `Query` retrieves an entire aggregate without cross-table joins. Events are stored with sorted keys (`EVENT#0001`, `EVENT#0002`, ...) that give an append-only, replayable log per payment - no separate event store needed. Each lifecycle step bundles two to five items across these entity types into one `TransactWriteItems` call, and condition expressions inside that transaction enforce the state machine: sequence checks on the stream head, version guards on the account, status gates on reservations, and write-once constraints on ledger entries. Concurrent processors that lose a race receive an immediate conditional failure rather than corrupting state, which is exactly what an at-least-once delivery model (HTTP retries, DynamoDB Streams) needs.

Beyond correctness, DynamoDB Streams triggers the processing lifecycle automatically when the first payment event is inserted, removing the need for a separate message broker. Idempotency records are created atomically alongside the payment and expire via TTL after a configurable window, so deduplication cleanup requires no scheduled jobs. On-demand capacity absorbs payment volume spikes without throughput planning, and single-partition `GetItem` reads stay in the low single-digit milliseconds regardless of table size.

---

## Endpoints

### POST /api/v1/payments/outbound

Creates a new outbound payment using an idempotent request. The service atomically writes three items in a single **TransactWriteItems** operation: the payment state, initial event, and idempotency record. The idempotency record enforces uniqueness via a **conditional write** (**attribute_not_exists** on its partition key). Duplicate requests return the original response if identical or are rejected if conflicting. Idempotency records expire automatically using **Time to Live (TTL)**.

### GET /api/v1/payments/outbound/{paymentId}

Retrieves the full state and event history of a payment. The service uses a single-partition access pattern to read the payment head (**GetItem**) and associated events (**Query**) in parallel, then reconstructs the current state from the ordered event stream and validates it against the stored aggregate. No **secondary index** is required, as all payment-related items are accessed via the same partition key.

### POST /api/v1/payments/outbound/{paymentId}/process

Manually triggers payment processing, primarily for operational use and testing. Normally, processing is initiated automatically via **DynamoDB Streams** on new payment events. The processor loads the payment state, replays events to determine the status, and advances the lifecycle through validation, fund reservation, and completion. Each step executes as a **TransactWriteItems** operation with **conditional writes** to ensure correctness, prevent duplicates, and maintain consistency under concurrent execution. The process is idempotent and safe to retry.

### GET /api/v1/accounts/{accountId}

Retrieves an account’s balances and active reservations in a single read. The service uses a single-partition access pattern to read the account (**GetItem**) and associated reservations (**Query** with sort-key prefix) in parallel. The response includes current and available balances, reflecting posted transactions and pending reservations. No **FilterExpression** or **secondary indexes** are required, as all relevant items are co-located under the same partition key.

### POST /api/v1/accounts/{accountId}/batch-get-reservations

Retrieves specific reservations for a single account by their ids. The caller supplies between **1** and **100** reservation identifiers in JSON. Duplicate ids in the list are merged while preserving first-seen order. Requests with no ids, more than **100** ids, or blank identifiers are rejected. For valid requests, the response includes found reservations and lists any missing ids separately, so callers can reconcile partial results against the identifiers they sent.

### GET /api/v1/merchants/{merchantId}/payments

Lists a merchant's payments ordered by creation time. The service queries a **Global Secondary Index (GSI)** using the merchant identifier as the partition key and a **composite sort key** to ensure chronological ordering and uniqueness. The index **projects** full payment data, avoiding additional reads from the **base table**. Results are returned newest first by default, with optional sort direction and page size controls.

### GET /api/v1/merchants/{merchantId}/payments/state/{state}

Lists a merchant's payments filtered by lifecycle state, ordered by creation time. The service queries a **Global Secondary Index (GSI)** with a **composite partition key** (merchant and state) to directly retrieve only matching items without a **FilterExpression**. The index returns full payment data, sorted newest first by default with optional sort direction and page size controls.
