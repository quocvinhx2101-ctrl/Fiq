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
- Incomplete LITE coverage: LITE is blocked; FIQ does not silently run FULL.
