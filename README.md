# FIQ

FIQ is a policy-driven Delta Lake maintenance control plane. It assesses table health,
plans safe maintenance, routes approval, and executes one auditable Delta operation at a time.

## Compatibility baseline

- Java 21
- Apache Spark 4.0.1 / Scala 2.13
- Delta Lake and Delta Kernel 4.0.1
- PostgreSQL 16+

FIQ never edits `_delta_log` directly. Classic tables are inspected through Delta Kernel and
maintained through Spark. Catalog-managed tables are accessed only through their managing catalog.

## Modules

- `fiq-domain` — policy, health, capability, operation, and security model
- `fiq-delta` — classic Delta metadata assessment
- `fiq-engine-spark` — Livy execution adapter
- `fiq-spark-job` — Spark/Delta maintenance job
- `fiq-server` — REST API, scheduler, persistence, authentication, and SPA hosting
- `fiq-ui` — React production interface

## Build

```bash
./gradlew ci
cd fiq-ui && npm ci && npm run build
```

For a local stack, first build the Spark job then launch Compose:

```bash
./gradlew :fiq-spark-job:shadowJar
docker compose up --build
```

See `docs/architecture.md`, `docs/operator-guide.md`, `docs/admin-guide.md`,
`docs/upgrade-guide.md`, and `docs/phase-status.md` for safety, deployment, and the explicit
qualification boundary.

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
