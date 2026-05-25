# DynamoDB.InstantPayments.Processor

Self-hosted worker that consumes DynamoDB Streams from the payments table and processes payment events.

## What it does

- Consumes `OUTBOUND_PAYMENT_EVENT` entries with `EventName=INITIATED` from DynamoDB Streams.
- Applies predefined processing rule:
  - `Amount <= AutoAcceptMaxAmount` => reserves funds and appends `ACCEPTED` event.
  - otherwise appends `REJECTED` event with reason code.
- Periodically expires stale initiated payments by appending `EXPIRED` if no terminal event exists in configured timeout window.

## Configuration

Set values in `appsettings.json` under `Processor`:

- `Region`
- `PaymentsTableName`
- `AccountsTableName`
- `PaymentsStreamArn` (required for stream consumption)
- `AutoAcceptMaxAmount`
- `ExpireAfterMinutes`
- `StreamPollDelaySeconds`
- `ExpirationSweepIntervalSeconds`

## Run

```powershell
cd DynamoDB.InstantPayments.Processor
dotnet run
```

## Notes

- DynamoDB Streams must be enabled on `PaymentsTableName`.
- The worker uses conditional writes for idempotent reservation and event append operations.
- Shard progress is currently in-memory only.
