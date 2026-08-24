package io.fiq.spark

import io.fiq.spark.FiqSparkSupport._
import org.apache.spark.sql.SparkSession

import java.time.Instant

object MaintenanceJob {
  def main(args: Array[String]): Unit = {
    val options = parseArgs(args)
    val runId = required(options, "operation-id")
    val operation = required(options, "operation")
    if (operation != "OPTIMIZE_BINPACK" && operation != "VACUUM_FULL")
      throw new IllegalArgumentException(s"$operation is not qualified in FIQ Phase 1")
    val target = resolveTarget(options)
    val expectedVersion = required(options, "expected-version").toLong
    val retentionHours = options.getOrElse("retention-hours", "168").toLong
    val predicate = options.getOrElse("predicate", "")
    rejectSqlControlCharacters(predicate)
    val resultPrefix = required(options, "result-prefix")
    val startedAt = Instant.now().toString
    val spark = SparkSession.builder().appName(s"FIQ $runId").enableHiveSupport().getOrCreate()
    try {
      val preVersion = currentVersion(spark, target)
      if (preVersion != expectedVersion)
        throw new IllegalStateException(
          s"FIQ_PLAN_STALE: expected Delta version $expectedVersion but found $preVersion")

      val (metrics, vacuumEvidence, maintenanceApplied) = operation match {
        case "OPTIMIZE_BINPACK" =>
          val where = if (predicate.isBlank) "" else s" WHERE $predicate"
          val frame = spark.sql(s"OPTIMIZE ${target.sql}$where")
          (rowEvidence(frame), Map.empty[String, Any], true)
        case "VACUUM_FULL" =>
          val allowUnsafe = options.getOrElse("allow-unsafe-retention", "false").toBoolean
          val candidates = vacuumCandidates(spark, target, retentionHours, allowUnsafe)
          val approvedHash = required(options, "approved-candidate-hash")
          val approvedCount = required(options, "approved-candidate-count").toLong
          if (candidates.hash != approvedHash || candidates.uris.size != approvedCount)
            throw new IllegalStateException(
              s"FIQ_VACUUM_PREFLIGHT_CHANGED: approved $approvedCount/$approvedHash but observed " +
                s"${candidates.uris.size}/${candidates.hash}")
          val frame = spark.sql(s"VACUUM ${target.sql} FULL RETAIN $retentionHours HOURS")
          val metrics = rowEvidence(frame)
          val after = vacuumCandidates(spark, target, retentionHours, allowUnsafe)
          val remaining = candidates.uris.toSet.intersect(after.uris.toSet).size
          (metrics, Map(
            "candidateCount" -> candidates.uris.size,
            "candidateHash" -> candidates.hash,
            "candidateBytes" -> candidates.bytes.map(java.lang.Long.valueOf).orNull,
            "remainingApprovedCandidates" -> remaining,
            "remainingCandidateCount" -> after.uris.size,
            "remainingCandidateHash" -> after.hash), candidates.uris.nonEmpty)
      }
      val postVersion = currentVersion(spark, target)
      val result = Map(
        "schemaVersion" -> 1,
        "runId" -> runId,
        "operationType" -> operation,
        "executionTarget" -> target.asEvidence,
        "resolvedThrough" -> target.targetType,
        "startedAt" -> startedAt,
        "completedAt" -> Instant.now().toString,
        "preVersion" -> preVersion,
        "postVersion" -> postVersion,
        "commandSucceeded" -> true,
        "maintenanceApplied" -> maintenanceApplied,
        "operationMetrics" -> metrics,
        "vacuum" -> vacuumEvidence)
      val written = writeResult(spark, resultPrefix, result)
      println(s"FIQ_RESULT_MANIFEST:${written.manifestUri}")
    } finally spark.stop()
  }
}
