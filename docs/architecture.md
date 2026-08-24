# Architecture

FIQ is an independent Delta Lake maintenance control plane. It borrows control-plane ideas from
Floe but has no Floe build or runtime dependency and contains no Iceberg, Trino, Qute, or HTMX
code. All source is under the `io.fiq` package.

## Runtime boundaries

`fiq-server` owns workspace-scoped APIs, authorization, audit, schedules, and durable operation
state. PostgreSQL is the source of truth. Claims use `FOR UPDATE SKIP LOCKED`; a partial unique
index guarantees that only one queued/running/cancelling operation exists for a table.

Classic tables are read by Delta Kernel in `fiq-delta`. Mutations are expressed as typed
`SparkMaintenanceRequest` values and sent by `fiq-engine-spark` to Livy. The Scala 2.13
`fiq-spark-job` verifies the planned Delta version, performs a vacuum dry run before apply, and
uses only supported Delta/Spark SQL or `DeltaTable` APIs. It never writes `_delta_log` files.

Catalog-managed tables are fail-closed. They are never routed through Kernel or a path fallback.
Their catalog-qualified discovery and execution adapter belongs to Phase 2. Unknown table
features produce read-only capabilities.

## Operation lifecycle

`PLANNED → AWAITING_APPROVAL → QUEUED → RUNNING → CANCELLING → terminal` is enforced in the
domain and by compare-and-set database updates. Immediately before submission the scheduler
refreshes table health. A version change skips the plan and requires reassessment. Livy job IDs,
command previews, errors, approvals, and post-run assessment events remain auditable.

## Honest completeness

Kernel-only health is marked `PARTIAL` when log coverage, tombstones, or catalog-authoritative
state is unavailable. The planner may use that assessment for layout and DV work, but blocks
vacuum decisions that require retention/log completeness. FIQ never upgrades LITE to FULL.
