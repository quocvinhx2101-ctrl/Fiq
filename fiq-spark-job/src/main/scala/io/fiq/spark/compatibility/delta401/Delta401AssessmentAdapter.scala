package io.fiq.spark.compatibility.delta401

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.delta.DeltaLog
import org.apache.spark.sql.functions.{coalesce, col, count, lit, max, min, sum, when}

import scala.jdk.CollectionConverters._

/**
 * Delta Spark 4.0.1 compatibility boundary. DeltaLog, Snapshot and action datasets are internal
 * Delta APIs used here because public DeltaTable APIs do not expose tombstones or checkpoint state.
 */
object Delta401AssessmentAdapter {
  private val ageBoundsHours = Seq(1L, 24L, 168L, 720L, 2160L)

  def inspect(spark: SparkSession, options: Map[String, String]): Map[String, Any] = {
    val (resolvedThrough, location) = options("target-type") match {
      case "PATH" => "PATH" -> options("path")
      case "CATALOG" =>
        val qualified = Seq(options("catalog"), options("namespace"), options("table"))
          .flatMap(_.split("\\.")).map(quote).mkString(".")
        val location = spark.sql(s"DESCRIBE DETAIL $qualified").head().getAs[String]("location")
        "CATALOG" -> location
      case other => throw new IllegalArgumentException(s"Unsupported target type: $other")
    }
    val deltaLog = DeltaLog.forTable(spark, new Path(location))
    val snapshot = deltaLog.update()
    val tombstones = snapshot.tombstones
    val nowMillis = System.currentTimeMillis()
    val ageHours = (lit(nowMillis) - col("deletionTimestamp")) / lit(3600000L)
    val baseAggregates = Seq(
      count(lit(1)).as("tombstoneCount"),
      count(col("size")).as("measuredSizeCount"),
      sum(col("size")).as("tombstoneBytes"),
      min(col("deletionTimestamp")).as("oldestDeletionTimestamp"),
      max(col("deletionTimestamp")).as("newestDeletionTimestamp"))
    val bucketAggregates = ageBoundsHours.zipWithIndex.map { case (upper, index) =>
      val lower = if (index == 0) lit(true) else ageHours >= lit(ageBoundsHours(index - 1))
      coalesce(sum(when(lower && ageHours < lit(upper), 1L).otherwise(0L)), lit(0L))
        .as(s"ageBucket$index")
    } :+ coalesce(sum(when(ageHours >= lit(ageBoundsHours.last), 1L).otherwise(0L)), lit(0L))
      .as(s"ageBucket${ageBoundsHours.size}")
    val retention = tombstones.agg((baseAggregates ++ bucketAggregates).head,
      (baseAggregates ++ bucketAggregates).tail: _*).head()
    val tombstoneCount = retention.getAs[Long]("tombstoneCount")
    val measuredSizeCount = retention.getAs[Long]("measuredSizeCount")
    val tombstoneBytes =
      if (tombstoneCount == measuredSizeCount && tombstoneCount > 0)
        retention.getAs[java.lang.Long]("tombstoneBytes")
      else if (tombstoneCount == 0) java.lang.Long.valueOf(0L)
      else null
    val checkpoint = snapshot.logSegment.checkpointProvider
    val checkpointVersion: java.lang.Long =
      if (checkpoint.isEmpty) null else java.lang.Long.valueOf(checkpoint.version)
    val checkpointType =
      if (checkpoint.isEmpty) null
      else if (checkpoint.getClass.getSimpleName.toUpperCase.contains("V2")) "V2"
      else "V1"
    val checkpointAt = if (checkpoint.isEmpty || checkpoint.topLevelFiles.isEmpty) null
      else java.lang.Long.valueOf(checkpoint.topLevelFiles.map(_.getModificationTime).max)
    val protocol = snapshot.protocol
    val features = (protocol.getReaderFeatures.asScala ++ protocol.getWriterFeatures.asScala).toSeq.sorted
    val deltaVersion = classOf[DeltaLog].getPackage.getImplementationVersion
    Map(
      "schemaVersion" -> 1,
      "resolvedThrough" -> resolvedThrough,
      "location" -> location,
      "version" -> snapshot.version,
      "snapshotTimestamp" -> snapshot.timestamp,
      "tombstoneCount" -> tombstoneCount,
      "tombstoneBytes" -> tombstoneBytes,
      "oldestDeletionTimestamp" -> nullableLong(retention, "oldestDeletionTimestamp"),
      "newestDeletionTimestamp" -> nullableLong(retention, "newestDeletionTimestamp"),
      "tombstoneSizeComplete" -> (tombstoneCount == measuredSizeCount),
      "tombstoneAgeBucketHours" -> ageBoundsHours,
      "tombstoneAgeBucketCounts" -> (0 to ageBoundsHours.size).map(index =>
        retention.getAs[Long](s"ageBucket$index")),
      "checkpointVersion" -> checkpointVersion,
      "checkpointType" -> checkpointType,
      "checkpointTimestamp" -> checkpointAt,
      "commitsSinceCheckpoint" -> (if (checkpointVersion == null) null
        else java.lang.Long.valueOf(snapshot.version - checkpointVersion.longValue())),
      "logFileCountSinceCheckpoint" -> snapshot.logSegment.deltas.size,
      "logBytesSinceCheckpoint" -> snapshot.logSegment.deltas.map(_.getLen).sum,
      "minReaderVersion" -> protocol.minReaderVersion,
      "minWriterVersion" -> protocol.minWriterVersion,
      "tableFeatures" -> features,
      "sparkVersion" -> spark.version,
      "deltaVersion" -> deltaVersion,
      "mutationQualified" -> (spark.version == "4.0.1" && deltaVersion == "4.0.1"))
  }

  private def nullableLong(row: org.apache.spark.sql.Row, name: String): java.lang.Long =
    if (row.isNullAt(row.fieldIndex(name))) null else java.lang.Long.valueOf(row.getAs[Long](name))

  private def quote(value: String): String = "`" + value.replace("`", "``") + "`"
}
