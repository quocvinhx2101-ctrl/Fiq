# FIQ Phase 1 — Classic Delta Maintenance Core

FIQ discovers Classic Delta tables, measures their maintenance state independently of policy,
explains policy decisions, safely runs qualified maintenance, and verifies the result.

```text
DISCOVER → ASSESS FACTS → EVALUATE POLICY → EXPLAIN → PREFLIGHT
         → OPTIMIZE / VACUUM FULL → VERIFY BEFORE/AFTER
```

## Compatibility baseline

- Java 21
- Apache Spark 4.0.1 / Scala 2.13
- Delta Lake and Delta Kernel 4.0.1
- PostgreSQL 16+

The only Phase 1 mutation-qualified runtime is Spark 4.0.1 + Delta Lake 4.0.1. PATH and HMS are
supported sources. FIQ never edits `_delta_log`; PATH mutation uses `DeltaTable.forPath`, while
HMS mutation uses `DeltaTable.forName`, with no fallback between them.

## Deployment boundary

FIQ itself consists of only the FIQ server/UI and PostgreSQL. Delta Kernel is embedded in the
server; it is a library, not another service. Storage, catalog, and execution infrastructure are
integrations supplied by the user's platform.

```text
FIQ runtime                  External integrations
┌──────────────────┐        ┌──────────────────────────────┐
│ FIQ server + UI  │───────►│ Object storage              │
│ Delta Kernel     │        │ Hive Metastore              │
│ PostgreSQL       │        │ Spark 4.0.1 + Delta 4.0.1   │
└──────────────────┘        │ Livy execution endpoint     │
                            └──────────────────────────────┘
```

## Modules

- `fiq-domain` — policy, health, capability, operation, and security model
- `fiq-delta` — classic Delta metadata assessment
- `fiq-engine-spark` — Livy execution adapter
- `fiq-spark-job` — Spark/Delta maintenance job
- `fiq-server` — REST API, scheduler, persistence, authentication, and SPA hosting
- `fiq-ui` — React production interface

## Run FIQ

```bash
make up
```

This starts only `fiq-server` and PostgreSQL. Open <http://localhost:9091>; the empty Connections
screen is the expected initial state. Add a PATH or HMS connection for the storage, catalog, and
Spark/Livy already operated by your platform. FIQ does not contact those systems until a
connection is tested, discovery runs, an assessment needs it, or an operation executes.

Use `make ps`, `make logs`, and `make down` to inspect or stop this minimal runtime.

## Run the local demo

```bash
make demo-up
```

The demo overlay adds MinIO, Hive Metastore, HMS PostgreSQL, Spark/Delta through Livy, and
idempotent sample generation. These services are evaluation infrastructure, **not FIQ runtime
dependencies**. The demo automatically creates PATH/HMS connections and discovers its fixtures.
Open <http://localhost:9091> after `make demo-ps` shows `server` running and `sample-init` exited
successfully.

In the UI:

1. Open **Connections** to test PATH/HMS access or run discovery.
2. Open **Tables**, choose a sample table, and refresh its six assessment dimensions.
3. Create an **OPTIMIZE BINPACK** or **VACUUM FULL** policy under **Policies**.
4. In the table's **Policies** tab, create a plan and review its target and decision evidence.
5. Queue or approve it under **Operations**, then inspect verification before/after evidence.

To exercise both PATH/HMS OPTIMIZE and isolated sample VACUUM FULL from the API:

```bash
make demo-test
```

The script requires `curl` and `jq`. Zero-hour retention is enabled only for the isolated sample
VACUUM fixture and still requires approval; it is never a global setting.

## Development checks

```bash
make build
make test
```

Run `make help` for all build, runtime, demo, log, restart, and scoped cleanup commands.

## Qualification boundary

- Supported: bounded PATH discovery, HMS discovery, policy-independent six-dimension assessment,
  explainable policies, `OPTIMIZE_BINPACK`, and `VACUUM_FULL` with post-run verification.
- Retained but not Phase 1 mutation-qualified: VACUUM LITE, Z-order, liquid-clustering
  maintenance, REORG, inventory vacuum, and Glue.
- Deferred: Unity Catalog/catalog-managed mutation, generic multi-format support, HA/DR, and
  enterprise integration infrastructure.

This branch is the **Phase 1 Classic Delta Maintenance Core**, not a general production-readiness
claim. See [`docs/phase-status.md`](docs/phase-status.md) for tested and unresolved boundaries.

See [`docs/architecture.md`](docs/architecture.md),
[`docs/operator-guide.md`](docs/operator-guide.md),
[`docs/admin-guide.md`](docs/admin-guide.md), and
[`docs/upgrade-guide.md`](docs/upgrade-guide.md) for safety and deployment details.

Development changes follow the sustainable Git workflow in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Acknowledgements

FIQ is an independent project inspired by [Floe](https://github.com/nssalian/floe), created by
[nssalian](https://github.com/nssalian). Floe's approach to table-maintenance control planes
provided important ideas and a practical foundation for FIQ's Delta Lake–focused design. We are
grateful to its author and contributors for making their work available to the open-source
community.

Where FIQ adapts material from Floe, that work remains acknowledged under the Apache License 2.0.
See [`NOTICE`](NOTICE) and [`LICENSE`](LICENSE) for attribution and licensing details. FIQ is not
affiliated with or endorsed by the Floe project.
