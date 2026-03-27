package com.skyflow.streaming

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.skyflow.streaming.application.service._
import com.skyflow.streaming.infrastructure.stream.BookingDataGenerator

import java.nio.file.{Path, Paths}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Entry point để chạy các Akka Streams demo.
 *
 * Cách chạy:
 *   sbt "runMain com.skyflow.streaming.StreamingApp feature1"
 *   sbt "runMain com.skyflow.streaming.StreamingApp feature2"
 *   sbt "runMain com.skyflow.streaming.StreamingApp feature3"
 *   sbt "runMain com.skyflow.streaming.StreamingApp feature4"
 *   sbt "runMain com.skyflow.streaming.StreamingApp feature5"
 *   sbt "runMain com.skyflow.streaming.StreamingApp feature6"
 *   sbt "runMain com.skyflow.streaming.StreamingApp generate"
 */
object StreamingApp {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "streaming-demo")
    implicit val ec: ExecutionContext = system.executionContext

    val baseDir = Paths.get("src/main/resources/streaming-samples")
    val outputDir = Paths.get("target/streaming-output")
    java.nio.file.Files.createDirectories(outputDir)

    val feature = args.headOption.getOrElse("help")

    val result = feature match {
      case "generate" => generateData(baseDir)
      case "feature1" => runFeature1(baseDir, outputDir)
      case "feature2" => runFeature2(baseDir, outputDir)
      case "feature2.1" => runFeature2_1(baseDir, outputDir)
      case "feature3" => runFeature3(baseDir)
      case "feature4" => runFeature4(baseDir, outputDir)
      case "feature5" => runFeature5(baseDir)
      case "feature6" => runFeature6(baseDir, outputDir)
      case _ =>
        println(
          """
            |Usage: sbt "runMain com.skyflow.streaming.StreamingApp <command>"
            |
            |Commands:
            |  generate  — Tạo sample CSV files (medium & large)
            |  feature1  — CSV File Processing Pipeline
            |  feature2  — File Splitting (Large → Small)
            |  feature2.1  — File Splitting by Size
            |  feature3  — Batch Processing Pipeline
            |  feature4  — Error Handling & Supervision
            |  feature5  — Backpressure & Throttling
            |  feature6  — Graph DSL: Fan-out / Fan-in
          """.stripMargin)
        system.terminate()
        return
    }

    result.onComplete {
      case Success(_) =>
        println("\nDemo completed successfully!")
        system.terminate()
      case Failure(ex) =>
        println(s"\nDemo failed: ${ex.getMessage}")
        ex.printStackTrace()
        system.terminate()
    }

    Await.result(system.whenTerminated, 5.minutes)
  }

  // ==========================================================================

  private def generateData(baseDir: Path)(implicit ec: ExecutionContext) = {
    println("=== Generating sample data ===")
    BookingDataGenerator.generateCsv(baseDir.resolve("bookings_medium.csv"), 1000)
    BookingDataGenerator.generateCsv(baseDir.resolve("bookings_large.csv"), 100000)
    println("Done!")
    scala.concurrent.Future.successful(())
  }

  private def runFeature1(baseDir: Path, outputDir: Path)(implicit system: ActorSystem[_]) = {
    println("=== Feature 1: CSV File Processing Pipeline ===")
    val input = baseDir.resolve("bookings_large.csv")
    val output = outputDir.resolve("feature1_output.csv")

    FileProcessingService.processAndCount(input, output).map { case (ioResult, count) =>
      println(s"Processed $count valid records")
      println(s"Output written: ${ioResult.count} bytes to: $output")
    }(system.executionContext)
  }

  private def runFeature2(baseDir: Path, outputDir: Path)(implicit system: ActorSystem[_]) = {
    println("=== Feature 2: File Splitting ===")
    val input = baseDir.resolve("bookings_large.csv")
    val chunksDir = outputDir.resolve("chunks")

    FileSplittingService.splitFile(input, chunksDir, linesPerChunk = 1000).map { report =>
      println(s"Split '${report.inputFile}' into ${report.totalChunks} chunks")
      println(s"Total lines: ${report.totalLines}")
      report.chunks.foreach(c => println(s"  ${c.fileName}: ${c.lineCount} lines, ${c.bytesWritten} bytes"))
    }(system.executionContext)
  }

  private def runFeature2_1(baseDir: Path, outputDir: Path, maxBytesPerChunk: Long = 1 * 1024 * 1024)(implicit system: ActorSystem[_]) = {
    println("=== Feature 2.1: File Splitting by Size ===")
    val input = baseDir.resolve("bookings_large.csv")
    val chunksDir = outputDir.resolve("chunks_by_size")

    FileSplittingService.splitFileBySize(input, chunksDir, maxBytesPerChunk).map { report =>
      println(s"Split '${report.inputFile}' into ${report.totalChunks} chunks")
      println(s"Total lines: ${report.totalLines}")
      report.chunks.foreach(c => println(s"  ${c.fileName}: ${c.lineCount} lines, ${c.bytesWritten} bytes"))
    }(system.executionContext)
  }

  private def runFeature3(baseDir: Path)(implicit system: ActorSystem[_]) = {
    println("=== Feature 3: Batch Processing Pipeline ===")
    val input = baseDir.resolve("bookings_small.csv")

    BatchProcessingService.processByRoute(input, batchSize = 5).map { report =>
      println(s"Total processed: ${report.totalProcessed}")
      println(s"Total seats requested: ${report.totalSeatsRequested}")
      println("By route:")
      report.routeReports.foreach { case (route, routeReport) =>
        println(s"  $route: ${routeReport.recordCount} records, ${routeReport.totalSeatsRequested} seats")
      }
    }(system.executionContext)
  }

  private def runFeature4(baseDir: Path, outputDir: Path)(implicit system: ActorSystem[_]) = {
    println("=== Feature 4: Error Handling ===")
    val input = baseDir.resolve("bookings_malformed.csv")
    val output = outputDir.resolve("feature4_success.csv")
    val errors = outputDir.resolve("feature4_errors.csv")

    ErrorHandlingService.processWithEitherPattern(input, output, errors).map { report =>
      println(s"Success: ${report.successCount}")
      println(s"Errors:  ${report.errorCount}")
      println(s"Output:  $output")
      println(s"DLQ:     $errors")
    }(system.executionContext)
  }

  private def runFeature5(baseDir: Path)(implicit system: ActorSystem[_]) = {
    println("=== Feature 5: Backpressure & Throttling ===")
    val input = baseDir.resolve("bookings_small.csv")

    BackpressureService.processWithThrottle(input, elementsPerSecond = 5).map { report =>
      println(s"Processed: ${report.totalProcessed} records")
      println(f"Duration:  ${report.durationMs}ms")
      println(f"Rate:      ${report.effectiveRate}%.1f records/sec")
    }(system.executionContext)
  }

  private def runFeature6(baseDir: Path, outputDir: Path)(implicit system: ActorSystem[_]) = {
    println("=== Feature 6: Graph DSL ===")
    val input = baseDir.resolve("bookings_small.csv")

    println("\n--- 6.2: Balanced Workers ---")
    GraphDslService.balancedWorkers(input, workerCount = 3).map { results =>
      results.foreach(println)
      println(s"\nTotal: ${results.size} records processed across 3 workers")
    }(system.executionContext)
  }
}
