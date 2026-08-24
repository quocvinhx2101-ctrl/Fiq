package io.fiq.spark

import com.fasterxml.jackson.databind.ObjectMapper
import io.delta.tables.DeltaTable
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.collection.mutable

private[spark] object FiqSparkSupport {
  private val safeIdentifier = "[A-Za-z_][A-Za-z0-9_-]*".r

  sealed trait ResolvedTarget {
    def sql: String
    def display: String
    def targetType: String
    def asEvidence: Map[String, Any]
    def deltaTable(spark: SparkSession): DeltaTable
  }

  case class PathResolved(path: String) extends ResolvedTarget {
    rejectSqlControlCharacters(path)
    override val sql: String = s"delta.`${path.replace("`", "``")}`"
    override val display: String = path
    override val targetType: String = "PATH"
    override val asEvidence: Map[String, Any] = Map("type" -> "PATH", "uri" -> path)
    override def deltaTable(spark: SparkSession): DeltaTable = DeltaTable.forPath(spark, path)
  }

  case class CatalogResolved(catalog: String, namespace: Seq[String], table: String)
      extends ResolvedTarget {
    private val parts = catalog +: namespace :+ table
    parts.foreach(quoteIdentifier)
    override val sql: String = parts.map(quoteIdentifier).mkString(".")
    override val display: String = parts.mkString(".")
    override val targetType: String = "CATALOG"
    override val asEvidence: Map[String, Any] =
      Map("type" -> "CATALOG", "catalog" -> catalog, "namespace" -> namespace, "table" -> table)
    override def deltaTable(spark: SparkSession): DeltaTable = DeltaTable.forName(spark, display)
  }

  def resolveTarget(options: Map[String, String]): ResolvedTarget =
    required(options, "target-type") match {
      case "PATH" => PathResolved(required(options, "path"))
      case "CATALOG" =>
        CatalogResolved(
          required(options, "catalog"),
          required(options, "namespace").split("\\.").toSeq,
          required(options, "table"))
      case other => throw new IllegalArgumentException(s"Unsupported execution target: $other")
    }

  def currentVersion(spark: SparkSession, target: ResolvedTarget): Long =
    target.deltaTable(spark).history(1).select("version").head().getLong(0)

  def vacuumCandidates(
      spark: SparkSession,
      target: ResolvedTarget,
      retentionHours: Long,
      allowUnsafeRetention: Boolean): CandidateSet = {
    if (allowUnsafeRetention)
      spark.conf.set("spark.databricks.delta.retentionDurationCheck.enabled", "false")
    val rows = spark.sql(s"VACUUM ${target.sql} FULL RETAIN $retentionHours HOURS DRY RUN")
    val candidates = rows.collect().iterator.map(_.getString(0)).map(canonicalUri).toSeq.distinct.sorted
    val checksum = sha256(candidates.mkString("\n").getBytes(StandardCharsets.UTF_8))
    val sizes = candidates.map(pathSize(spark, _))
    val bytes = if (sizes.forall(_.isDefined)) Some(sizes.flatten.sum) else None
    CandidateSet(candidates, checksum, bytes)
  }

  def writeResult(spark: SparkSession, prefix: String, result: Map[String, Any]): WrittenResult = {
    if (prefix.isBlank) throw new IllegalArgumentException("--result-prefix is required")
    val mapper = new ObjectMapper().findAndRegisterModules()
    val bytes = mapper.writeValueAsBytes(result)
    val checksum = sha256(bytes)
    val resultPath = new Path(prefix.stripSuffix("/") + "/result.json")
    val manifestPath = new Path(prefix.stripSuffix("/") + "/manifest.json")
    val filesystem = resultPath.getFileSystem(spark.sparkContext.hadoopConfiguration)
    Option(resultPath.getParent).foreach(filesystem.mkdirs)
    val output = filesystem.create(resultPath, true)
    try output.write(bytes) finally output.close()
    val manifest = mapper.writeValueAsBytes(Map(
      "schemaVersion" -> 1,
      "resultUri" -> resultPath.toString,
      "sha256" -> checksum))
    val manifestOutput = filesystem.create(manifestPath, true)
    try manifestOutput.write(manifest) finally manifestOutput.close()
    WrittenResult(resultPath.toString, manifestPath.toString, checksum)
  }

  def rowEvidence(frame: DataFrame): Seq[Map[String, Any]] = {
    val names = frame.schema.fieldNames
    frame.collect().toSeq.map(row => names.zipWithIndex.map { case (name, index) =>
      name -> safeValue(row, index)
    }.toMap)
  }

  def required(options: Map[String, String], key: String): String =
    options.getOrElse(key, throw new IllegalArgumentException(s"Missing --$key"))

  def parseArgs(args: Array[String]): Map[String, String] = {
    val result = mutable.LinkedHashMap.empty[String, String]
    var index = 0
    while (index < args.length) {
      val key = args(index)
      if (!key.startsWith("--") || index + 1 >= args.length)
        throw new IllegalArgumentException(s"Invalid argument at position $index")
      result.put(key.drop(2), args(index + 1))
      index += 2
    }
    result.toMap
  }

  def rejectSqlControlCharacters(value: String): Unit = {
    val normalized = value.toLowerCase
    if (value.contains(";") || normalized.contains("--") || normalized.contains("/*"))
      throw new IllegalArgumentException("Policy expression contains SQL control characters")
  }

  private def quoteIdentifier(value: String): String = value match {
    case safeIdentifier() => s"`$value`"
    case _ => throw new IllegalArgumentException(s"Unsafe SQL identifier: $value")
  }

  private def canonicalUri(value: String): String = new URI(value).normalize().toString

  private def pathSize(spark: SparkSession, value: String): Option[Long] = try {
    val path = new Path(value)
    Some(path.getFileSystem(spark.sparkContext.hadoopConfiguration).getFileStatus(path).getLen)
  } catch {
    case _: Exception => None
  }

  private def safeValue(row: Row, index: Int): Any = {
    if (row.isNullAt(index)) null
    else row.get(index) match {
      case value: java.lang.Number => value
      case value: java.lang.Boolean => value
      case value: String => value
      case value => value.toString
    }
  }

  private def sha256(bytes: Array[Byte]): String =
    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  case class CandidateSet(uris: Seq[String], hash: String, bytes: Option[Long])
  case class WrittenResult(resultUri: String, manifestUri: String, checksum: String)
}
