# Architecture

FIQ is an independent Classic Delta maintenance core. It borrows control-plane ideas from
Floe but has no Floe build or runtime dependency and contains no Iceberg, Trino, Qute, or HTMX
code. All source is under the `io.fiq` package.

## Runtime boundaries

`fiq-server` owns workspace-scoped APIs, authorization, audit, schedules, and durable operation
state. PostgreSQL is the source of truth. Claims use `FOR UPDATE SKIP LOCKED`; a partial unique
index guarantees that only one queued/running/cancelling operation exists for a table.

The deployable FIQ runtime is only `fiq-server + PostgreSQL`. Delta Kernel stays embedded in the
server. Object storage, HMS, Spark, and Livy are connection-scoped integrations and are not
startup dependencies. The repository's `compose.demo.yaml` supplies those systems only for a
self-contained evaluation environment.

```text
          user-provided platform
  storage / catalog / Spark / Livy
                 ▲
                 │ connection adapters
        ┌────────┴─────────┐
        │ FIQ server + UI  │
        │ embedded Kernel  │
        │ planner/scheduler│
        └────────┬─────────┘
                 │
            PostgreSQL
```

Classic PATH tables are read by Delta Kernel in `fiq-delta`; HMS discovery and log/retention
evidence use the qualified Spark runtime. Mutations are expressed as typed
`SparkMaintenanceRequest` values and sent by `fiq-engine-spark` to Livy. The Scala 2.13
`fiq-spark-job` verifies the planned Delta version and uses only supported Delta/Spark mutation
APIs. `PathTarget` always resolves through `DeltaTable.forPath`; `CatalogTarget` always resolves
through `DeltaTable.forName`. It never writes `_delta_log` files.

Assessment stores immutable facts for FILE_LAYOUT, DELETION_VECTORS, TRANSACTION_LOG, RETENTION,
CLUSTERING, and PROTOCOL. Each dimension carries its own completeness, provenance, version,
timestamp, and incomplete reason. A bounded 1 MiB histogram measures file sizes without loading
and sorting every active file. Policies query that histogram later; assessment has no small-file
threshold and does not create maintenance recommendations.

Catalog-managed tables and unknown features are fail-closed/read-only. Glue and Unity Catalog are
outside the Phase 1 mutation qualification.

## Operation lifecycle

`PLANNED → AWAITING_APPROVAL → QUEUED → RUNNING → CANCELLING → terminal` is enforced in the
domain and by compare-and-set database updates. Durable policy fires evaluate cron, timezone,
window, selectors, concurrency, capabilities, and current assessment facts idempotently.

The job writes structured `result.json`, then a SHA-256 manifest under the FIQ system prefix.
FIQ does not use Livy logs as the canonical operation result. Spark command success is only the
mutation outcome: FIQ marks success after a fresh assessment verifies version/history and the
expected OPTIMIZE improvement or VACUUM candidate removal. A stale version is `SKIPPED` with
`FIQ_PLAN_STALE`; a possible mutation followed by failed verification is `FAILED` with
`maintenanceApplied=true`.

## Honest completeness

Incomplete facts remain `PARTIAL`/`UNKNOWN`, never fabricated as zero. The planner requires only
the dimensions needed for a requested operation. Phase 1 has executors only for OPTIMIZE BINPACK
and VACUUM FULL; unsupported operations return `NOT_QUALIFIED_IN_PHASE_1`.
