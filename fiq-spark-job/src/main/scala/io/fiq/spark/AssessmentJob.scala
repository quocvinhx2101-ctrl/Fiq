package io.fiq.spark

import com.fasterxml.jackson.databind.ObjectMapper
import io.fiq.spark.compatibility.delta401.Delta401AssessmentAdapter
import org.apache.spark.sql.SparkSession

import java.util.Base64
import scala.collection.mutable

object AssessmentJob {
  def main(args: Array[String]): Unit = {
    val options = parseArgs(args)
    val assessmentId = required(options, "assessment-id")
    val spark = SparkSession.builder().appName(s"FIQ assessment $assessmentId").enableHiveSupport().getOrCreate()
    try {
      val facts = Delta401AssessmentAdapter.inspect(spark, options)
      val payload = facts ++ Map("assessmentId" -> assessmentId)
      val json = new ObjectMapper().findAndRegisterModules().writeValueAsBytes(payload)
      println("FIQ_ASSESSMENT_RESULT:" + Base64.getEncoder.encodeToString(json))
    } finally spark.stop()
  }

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
