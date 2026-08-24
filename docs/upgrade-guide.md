# Upgrade guide

1. Back up PostgreSQL and retain the current container image and Spark job artifact.
2. Run `./gradlew ci` and the Delta compatibility suite for every declared Delta 4.x minor.
3. Restore a production snapshot into staging and start one server so Flyway applies migrations.
4. Validate `/q/health/ready`, `/q/openapi`, policy simulation, and one non-production maintenance
   flow through post-run assessment.
5. Roll servers while keeping at least one available replica. Deploy the matching Spark job jar
   before admitting new queued operations.
6. Roll back the application image only when its schema compatibility says so. Never run Flyway
   clean or edit migration history.

Delta capabilities are detected from protocol and table features, not a version string. New or
unknown features remain read-only until the compatibility matrix and safety tests pass.
