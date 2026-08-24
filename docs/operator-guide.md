# Operator guide

## Local stack

Requirements are Java 21, Docker with Compose, and Node 24. Build the Spark artifact first because
the Livy container mounts it read-only:

```bash
./gradlew :fiq-spark-job:shadowJar
docker compose up --build
```

Open `http://localhost:9091`. Development auth is explicitly disabled by the Compose profile.
PostgreSQL is on 5432, MinIO on 9000/9001, and Livy on 8998. These credentials are development
only. Do not reuse the Compose values outside an isolated workstation.

The sample initializer creates PATH small files, an HMS small-file table, deletion-vector and
history/checkpoint fixtures, and an isolated VACUUM fixture. The server automatically registers
the sample PATH/HMS connections and discovers them. The initializer and discovery upserts are
idempotent; `FIQ_SAMPLE_ENABLED` is false outside Compose by default.

The first clean startup can take several minutes because Spark resolves Delta, Hadoop AWS, and
Hive client artifacts into the `fiq-ivy` volume. Keep that volume for normal restarts; subsequent
starts reuse it. Wait for `sample-init` to exit successfully and `server` to start:

```bash
docker compose ps -a
docker compose logs -f sample-init server
```

To keep the stack running after closing the terminal:

```bash
docker compose up --build -d
docker compose ps
docker compose logs -f server
```

`Ctrl+C` only stops log following in the last command. Stop services with `docker compose down`;
add `--volumes` only when you intentionally want to erase local sample databases and objects.

## End-user workflow

1. **Connections**: create PATH/HMS, test the qualified runtime, then discover. PATH always needs
   an explicit bounded root; HMS enumerates Delta-provider tables through Spark.
2. **Tables**: confirm PATH or CATALOG execution target and the Sample label. Refresh assessment.
3. **Health**: inspect completeness/provenance for all six dimensions. UNKNOWN means not measured,
   never zero.
4. **Policies**: choose only OPTIMIZE BINPACK or VACUUM FULL and configure thresholds. These values
   evaluate the assessment; they do not affect measurement.
5. **Table → Policies**: create a plan. Review the exact target, observations, threshold
   comparisons, blocker reasons, and VACUUM preflight identity.
6. **Operations**: queue or approve, then inspect ASSESS, PREFLIGHT, EXECUTION, and VERIFICATION.
   A Spark job alone is not success; verified before/after evidence is required.

Run `./scripts/acceptance-core.sh` after startup for the destructive isolated sample acceptance.
It proves PATH `forPath`, HMS `forName`, measurable OPTIMIZE reduction, and VACUUM FULL
dry-run/approval/apply/verify. Never point that script at non-sample data.

The upstream Livy 0.9 distribution is built for Scala 2.12 while Spark 4 uses Scala 2.13. FIQ uses
Livy's batch endpoint, where the submitted application owns its Scala 2.13 classpath, and probes
reachability before use. Production must use a vendor-tested Livy/Spark 4 deployment; do not infer
support from the Livy server version alone.

## Production configuration

Use external PostgreSQL and Spark/Livy. Set `FIQ_DATABASE_*`, `FIQ_LIVY_URL`, and the OIDC values.
The OIDC client uses Authorization Code + PKCE and a secure SameSite cookie. Keep
`FIQ_AUTH_ENABLED=true` and `FIQ_COOKIE_SECURE=true`. Connections store only a secret reference;
credentials arrive through workload identity, cloud credential chains, or Kubernetes Secrets.

Run `/q/health/ready` for readiness, `/q/health/live` for liveness, and scrape `/q/metrics`.
Back up PostgreSQL with PITR. Restore into a separate instance, run Flyway validation, then admit
one FIQ replica before scaling out. Spark jobs already accepted by Livy must be reconciled by job
ID; never replay a run by changing its database state.

## Incident actions

- Livy unavailable: queued submissions back off exponentially and fail after the configured cap.
- Lost server replica: leases expire and another replica resumes polling without resubmission.
- Stale table version: the run becomes `SKIPPED`; refresh and create a new plan.
- Unknown Delta feature: the table remains observable but maintenance is read-only.
- VACUUM LITE or another retained operation type: capability is
  `NOT_QUALIFIED_IN_PHASE_1`; it has no Phase 1 executor.
