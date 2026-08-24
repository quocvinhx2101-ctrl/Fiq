package io.fiq.spark

import io.delta.tables.DeltaTable
import org.apache.spark.sql.SparkSession

import scala.collection.mutable

/** Idempotent Classic Delta fixtures. This entrypoint is used only by local Compose and tests. */
object SampleDataJob {
  def main(args: Array[String]): Unit = {
    val options = parseArgs(args)
    val root = options.getOrElse("root", "s3a://fiq-samples")
    val spark = SparkSession.builder().appName("FIQ sample Classic Delta tables").enableHiveSupport().getOrCreate()
    try {
      seedSmallFiles(spark, s"$root/path/small_files", 96)
      seedHms(spark, s"$root/hms")
      seedDeletionVectors(spark, s"$root/path/deletion_vectors")
      seedHistory(spark, s"$root/path/history_checkpoint")
      seedVacuum(spark, s"$root/path/vacuum")
    } finally spark.stop()
  }

  private def seedSmallFiles(spark: SparkSession, path: String, partitions: Int): Unit = {
    if (DeltaTable.isDeltaTable(spark, path)) return
    spark.range(0, partitions.toLong * 100)
      .withColumnRenamed("id", "event_id")
      .repartition(partitions)
      .write.format("delta").mode("overwrite").save(path)
    spark.sql(s"ALTER TABLE delta.`$path` SET TBLPROPERTIES ('fiq.sample'='true')")
  }

  private def seedHms(spark: SparkSession, root: String): Unit = {
    val databaseLocation = s"$root/fiq_sample.db"
    val path = s"$databaseLocation/hms_small_files"
    // The metastore stores metadata only. Keeping both the namespace and table location on
    // shared object storage prevents Spark from staging managed-table paths on a container's
    // private local filesystem.
    spark.sql(s"CREATE DATABASE IF NOT EXISTS fiq_sample LOCATION '$databaseLocation'")
    spark.sql(s"ALTER DATABASE fiq_sample SET LOCATION '$databaseLocation'")
    if (!DeltaTable.isDeltaTable(spark, path)) {
      spark.range(0, 6400).withColumnRenamed("id", "event_id").repartition(64)
        .write.format("delta").mode("overwrite").save(path)
    }
    spark.sql(s"CREATE TABLE IF NOT EXISTS fiq_sample.hms_small_files USING DELTA LOCATION '$path'")
    spark.sql("ALTER TABLE fiq_sample.hms_small_files SET TBLPROPERTIES ('fiq.sample'='true')")
  }

  private def seedDeletionVectors(spark: SparkSession, path: String): Unit = {
    if (DeltaTable.isDeltaTable(spark, path)) return
    spark.range(0, 1000).withColumnRenamed("id", "event_id").repartition(8)
      .write.format("delta")
      .option("delta.enableDeletionVectors", "true")
      .mode("overwrite").save(path)
    spark.sql(s"ALTER TABLE delta.`$path` SET TBLPROPERTIES ('fiq.sample'='true')")
    spark.sql(s"DELETE FROM delta.`$path` WHERE event_id % 10 = 0")
  }

  private def seedHistory(spark: SparkSession, path: String): Unit = {
    if (DeltaTable.isDeltaTable(spark, path)) return
    spark.range(0, 10).write.format("delta").mode("overwrite").save(path)
    (1 to 12).foreach { batch =>
      spark.range(batch * 10L, batch * 10L + 10).write.format("delta").mode("append").save(path)
    }
    spark.sql(s"ALTER TABLE delta.`$path` SET TBLPROPERTIES ('fiq.sample'='true')")
  }

  private def seedVacuum(spark: SparkSession, path: String): Unit = {
    if (DeltaTable.isDeltaTable(spark, path)) return
    spark.range(0, 800).withColumnRenamed("id", "event_id").repartition(8)
      .write.format("delta").mode("overwrite").save(path)
    spark.sql(s"ALTER TABLE delta.`$path` SET TBLPROPERTIES ('fiq.sample'='true')")
    spark.sql(s"DELETE FROM delta.`$path` WHERE event_id < 400")
  }

  private def parseArgs(args: Array[String]): Map[String, String] = {
    val result = mutable.LinkedHashMap.empty[String, String]
    var index = 0
    while (index < args.length) {
      if (!args(index).startsWith("--") || index + 1 >= args.length)
        throw new IllegalArgumentException(s"Invalid argument at position $index")
      result.put(args(index).drop(2), args(index + 1))
      index += 2
    }
    result.toMap
  }
}
