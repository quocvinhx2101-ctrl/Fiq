package io.fiq.spark

import io.delta.tables.DeltaTable
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.collection.mutable

object MaintenanceJob {
  private val safeIdentifier = "[A-Za-z_][A-Za-z0-9_-]*".r

  def main(args: Array[String]): Unit = {
    val options = parseArgs(args)
    val operation = required(options, "operation")
    val rawTable = required(options, "table")
    val table = quoteQualified(rawTable)
    val expectedVersion = required(options, "expected-version").toLong
    val retentionHours = options.getOrElse("retention-hours", "168").toLong
    val predicate = options.getOrElse("predicate", "")
    rejectSqlControlCharacters(predicate)

    val spark = SparkSession.builder().appName(s"FIQ ${required(options, "operation-id")}").getOrCreate()
    try {
      val deltaTable = DeltaTable.forName(spark, rawTable)
      val actualVersion = deltaTable.history(1).select("version").head().getLong(0)
      if (actualVersion != expectedVersion) {
        throw new IllegalStateException(
          s"FIQ_STALE_PLAN: expected Delta version $expectedVersion but found $actualVersion")
      }

      val result = execute(spark, operation, table, predicate, retentionHours, options)
      val evidence = result.collect().map(_.mkString("[", ",", "]")).mkString("[", ",", "]")
      println(s"FIQ_EVIDENCE operation=$operation table=$rawTable result=$evidence")
    } finally {
      spark.stop()
    }
  }

  private def execute(
      spark: SparkSession,
      operation: String,
      table: String,
      predicate: String,
      retentionHours: Long,
      options: Map[String, String]): DataFrame = {
    val where = if (predicate.isBlank) "" else s" WHERE $predicate"
    operation match {
      case "OPTIMIZE_BINPACK" | "OPTIMIZE_CLUSTERING" => spark.sql(s"OPTIMIZE $table$where")
      case "OPTIMIZE_ZORDER" =>
        val columns = required(options, "zorder-columns").split(",").map(quoteIdentifier).mkString(", ")
        spark.sql(s"OPTIMIZE $table$where ZORDER BY ($columns)")
      case "OPTIMIZE_FULL" => spark.sql(s"OPTIMIZE $table FULL")
      case "REORG_PURGE" => spark.sql(s"REORG TABLE $table$where APPLY (PURGE)")
      case "VACUUM_LITE" => vacuum(spark, table, "LITE", retentionHours, "")
      case "VACUUM_FULL" => vacuum(spark, table, "FULL", retentionHours, "")
      case "VACUUM_INVENTORY" =>
        val inventory = required(options, "inventory")
        rejectSqlControlCharacters(inventory)
        vacuum(spark, table, "", retentionHours, s" USING INVENTORY $inventory")
      case other => throw new IllegalArgumentException(s"Unsupported FIQ operation: $other")
    }
  }

  private def vacuum(
      spark: SparkSession,
      table: String,
      mode: String,
      retentionHours: Long,
      suffix: String): DataFrame = {
    val modeSql = if (mode.isBlank) "" else s" $mode"
    val base = s"VACUUM $table$modeSql RETAIN $retentionHours HOURS$suffix"
    val candidates = spark.sql(s"$base DRY RUN")
    val candidateCount = candidates.count()
    println(s"FIQ_PREFLIGHT vacuumCandidates=$candidateCount retentionHours=$retentionHours")
    spark.sql(base)
  }

  private def quoteQualified(value: String): String =
    value.split("\\.").map(quoteIdentifier).mkString(".")

  private def quoteIdentifier(value: String): String = value match {
    case safeIdentifier() => s"`$value`"
    case _ => throw new IllegalArgumentException(s"Unsafe SQL identifier: $value")
  }

  private def rejectSqlControlCharacters(value: String): Unit = {
    val normalized = value.toLowerCase
    if (value.contains(";") || normalized.contains("--") || normalized.contains("/*")) {
      throw new IllegalArgumentException("Policy expression contains SQL control characters")
    }
  }

  private def required(options: Map[String, String], key: String): String =
    options.getOrElse(key, throw new IllegalArgumentException(s"Missing --$key"))

  private def parseArgs(args: Array[String]): Map[String, String] = {
    val result = mutable.LinkedHashMap.empty[String, String]
    var index = 0
    while (index < args.length) {
      val key = args(index)
      if (!key.startsWith("--") || index + 1 >= args.length) {
        throw new IllegalArgumentException(s"Invalid argument at position $index")
      }
      result.put(key.drop(2), args(index + 1))
      index += 2
    }
    result.toMap
  }
}
