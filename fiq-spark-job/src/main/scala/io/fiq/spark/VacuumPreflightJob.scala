package io.fiq.spark

import io.fiq.spark.FiqSparkSupport._
import org.apache.spark.sql.SparkSession

import java.time.Instant

object VacuumPreflightJob {
  def main(args: Array[String]): Unit = {
    val options = parseArgs(args)
    val preflightId = required(options, "preflight-id")
    val target = resolveTarget(options)
    val expectedVersion = required(options, "expected-version").toLong
    val retentionHours = required(options, "retention-hours").toLong
    val allowUnsafe = options.getOrElse("allow-unsafe-retention", "false").toBoolean
    val spark = SparkSession.builder().appName(s"FIQ VACUUM preflight $preflightId")
      .enableHiveSupport().getOrCreate()
    try {
      val actualVersion = currentVersion(spark, target)
      if (actualVersion != expectedVersion)
        throw new IllegalStateException(
          s"FIQ_PLAN_STALE: expected Delta version $expectedVersion but found $actualVersion")
      val candidates = vacuumCandidates(spark, target, retentionHours, allowUnsafe)
      val result = Map(
        "schemaVersion" -> 1,
        "runId" -> preflightId,
        "operationType" -> "VACUUM_FULL_PREFLIGHT",
        "executionTarget" -> target.asEvidence,
        "resolvedThrough" -> target.targetType,
        "plannedVersion" -> actualVersion,
        "retentionHours" -> retentionHours,
        "candidateCount" -> candidates.uris.size,
        "candidateHash" -> candidates.hash,
        "candidateBytes" -> candidates.bytes.map(java.lang.Long.valueOf).orNull,
        "plannedAt" -> Instant.now().toString)
      val written = writeResult(spark, required(options, "result-prefix"), result)
      println(s"FIQ_RESULT_MANIFEST:${written.manifestUri}")
    } finally spark.stop()
  }
}
