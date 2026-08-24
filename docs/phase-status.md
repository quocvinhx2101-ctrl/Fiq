# Delivery status

## Phase 1

Implemented foundations include the six-module build, Delta 4.0.1 Kernel inspection, typed
Spark/Livy execution for all eight operation commands, safety planning, PostgreSQL/Flyway state,
workspace isolation, OIDC and hashed API-key RBAC, policy CRUD/validation/simulation, connection
capability tests, approvals, safe retry, idempotency, leased SKIP LOCKED scheduling, SSE, OpenAPI,
metrics, production SPA, Docker development services, and operational docs.

Before a production 1.0 claim, the remaining Phase 1 qualification work is the Spark metadata
assessment that makes log/retention dimensions complete, HMS/Glue/path discovery jobs, guided UI
forms for policy/connection/API-key management, webhook delivery, full Testcontainers fixture
coverage, and measured scale/accessibility/security acceptance. Playwright currently covers the
primary responsive flow and dark theme at 390/768/1440. Controls for unavailable endpoints are
disabled with an explicit reason; no sample data is shown as real data.

## Phase 2

Unity Catalog 0.3.1 authoritative discovery, catalog-managed execution/permissions, quotas,
provider secret adapters, signed webhooks, traces/dashboards, DR exercises, and the declared Delta
4.x compatibility matrix remain Phase 2. Catalog-managed tables are fail-closed in the current
code, including the Delta 4.0.1 managed-table vacuum prohibition.
