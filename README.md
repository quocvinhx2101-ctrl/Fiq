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

## Modules

- `fiq-domain` — policy, health, capability, operation, and security model
- `fiq-delta` — classic Delta metadata assessment
- `fiq-engine-spark` — Livy execution adapter
- `fiq-spark-job` — Spark/Delta maintenance job
- `fiq-server` — REST API, scheduler, persistence, authentication, and SPA hosting
- `fiq-ui` — React production interface

## Run the complete local sample

```bash
./gradlew :fiq-spark-job:shadowJar
docker compose up --build
```

Leave Compose running and open <http://localhost:9091>. It starts PostgreSQL, MinIO, Hive
Metastore, Spark/Delta through Livy, FIQ, and idempotent sample generation. FIQ then creates the
sample PATH/HMS connections and discovers the fixtures automatically—no SQL or setup curl is
required. Run in the background with `docker compose up --build -d`; stop with
`docker compose down`.

In the UI:

1. Open **Connections** to test PATH/HMS access or run discovery.
2. Open **Tables**, choose a sample table, and refresh its six assessment dimensions.
3. Create an **OPTIMIZE BINPACK** or **VACUUM FULL** policy under **Policies**.
4. In the table's **Policies** tab, create a plan and review its target and decision evidence.
5. Queue or approve it under **Operations**, then inspect verification before/after evidence.

To exercise both PATH/HMS OPTIMIZE and isolated sample VACUUM FULL from the API:

```bash
./scripts/acceptance-core.sh
```

The script requires `curl` and `jq`. Zero-hour retention is enabled only for the isolated sample
VACUUM fixture and still requires approval; it is never a global setting.

## Development checks

```bash
./gradlew ci
cd fiq-ui
npm ci
npm run typecheck
npm test
npm run test:e2e
npm run build
```

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
