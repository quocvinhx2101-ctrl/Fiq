package io.fiq.spark

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.spark.sql.SparkSession

import java.util.Base64
import scala.collection.mutable

object CatalogDiscoveryJob {
  def main(args: Array[String]): Unit = {
    val options = parseArgs(args)
    val runId = required(options, "run-id")
    val catalog = required(options, "catalog")
    val spark = SparkSession.builder().appName(s"FIQ catalog discovery $runId").enableHiveSupport().getOrCreate()
    try {
      spark.catalog.setCurrentCatalog(catalog)
      val tables = spark.catalog.listDatabases().collect().toSeq.flatMap { database =>
        spark.catalog.listTables(database.name).collect().toSeq.filterNot(_.isTemporary).flatMap { table =>
          describeDelta(spark, catalog, database.name, table.name)
        }
      }
      val payload = Map("schemaVersion" -> 1, "runId" -> runId, "catalog" -> catalog, "tables" -> tables)
      val json = new ObjectMapper().findAndRegisterModules().writeValueAsBytes(payload)
      println("FIQ_CATALOG_DISCOVERY:" + Base64.getEncoder.encodeToString(json))
    } finally spark.stop()
  }

  private def describeDelta(
      spark: SparkSession,
      catalog: String,
      namespace: String,
      table: String): Option[Map[String, Any]] = {
    val qualified = Seq(catalog, namespace, table).map(quote).mkString(".")
    try {
      val detail = spark.sql(s"DESCRIBE DETAIL $qualified").head()
      if (!"delta".equalsIgnoreCase(detail.getAs[String]("format"))) return None
      val properties = value[scala.collection.Map[String, String]](detail, "properties")
        .map(_.toMap).getOrElse(Map.empty)
      // Delta 4.0.1 / Spark 4 DESCRIBE DETAIL no longer exposes `version`; history is the
      // catalog-qualified, supported source of the authoritative current version.
      val version = spark.sql(s"DESCRIBE HISTORY $qualified LIMIT 1")
        .select("version").head().getLong(0)
      Some(Map(
        "namespace" -> Seq(namespace),
        "table" -> table,
        "location" -> detail.getAs[String]("location"),
        "version" -> version,
        "minReaderVersion" -> detail.getAs[Int]("minReaderVersion"),
        "minWriterVersion" -> detail.getAs[Int]("minWriterVersion"),
        "partitionColumns" -> value[Seq[String]](detail, "partitionColumns").getOrElse(Seq.empty),
        "tableFeatures" -> value[Seq[String]](detail, "tableFeatures").getOrElse(Seq.empty),
        "properties" -> properties,
        "sample" -> properties.get("fiq.sample").contains("true")))
    } catch {
      case exception: Exception =>
        // A non-Delta or unreadable table must not fail the whole catalog scan, but emit enough
        // bounded evidence for operators to understand why it was skipped.
        println(s"FIQ_CATALOG_DISCOVERY_SKIPPED:$qualified:${exception.getClass.getSimpleName}:${Option(exception.getMessage).getOrElse("")}")
        None
    }
  }

  private def value[T](row: org.apache.spark.sql.Row, name: String): Option[T] =
    if (row.schema.fieldNames.contains(name) && !row.isNullAt(row.fieldIndex(name)))
      Some(row.getAs[T](name))
    else None

  private def quote(value: String): String = "`" + value.replace("`", "``") + "`"

  private def required(options: Map[String, String], key: String): String =
    options.getOrElse(key, throw new IllegalArgumentException(s"Missing --$key"))

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
