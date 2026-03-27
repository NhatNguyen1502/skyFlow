package com.skyflow.streaming.application.service

import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.{FileIO, Framing, Sink, Source}
import akka.util.ByteString
import akka.Done
import com.skyflow.streaming.domain.model.BookingRecord
import com.skyflow.streaming.infrastructure.stream.CsvParser

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.Future

/** Feature 2: File Splitting — chia file lớn thành nhiều file nhỏ.
  *
  * Concepts học được:
  *   - grouped(n) — gom N elements thành 1 Seq
  *   - zipWithIndex — đánh số thứ tự
  *   - mapAsync(parallelism) — ghi file bất đồng bộ
  *   - mapAsyncUnordered — khi thứ tự không quan trọng (nhanh hơn)
  */
object FileSplittingService {

  /** Chia file CSV thành nhiều file nhỏ, mỗi file chứa tối đa linesPerChunk
    * dòng.
    *
    * Output: outputDir/chunk_0001.csv, chunk_0002.csv, ... Mỗi chunk file đều
    * có header row.
    */
  def splitFile(inputPath: Path, outputDir: Path, linesPerChunk: Int = 1000)(
      implicit system: ActorSystem[_]
  ): Future[SplitReport] = {

    implicit val ec = system.executionContext
    Files.createDirectories(outputDir)

    FileIO
      .fromPath(inputPath)
      .via(
        Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true)
      ) // Source[ByteString, Future[IOResult]]
      .map(_.utf8String.trim) // Source[String, Future[IOResult]]
      .filterNot(_.isEmpty)
      .drop(1) // bỏ header
      .grouped(
        linesPerChunk
      ) // gom thành từng batch, type Source[Seq[String], Future[IOResult]]
      .zipWithIndex // Source[(Seq[String], Long), Future[IOResult]]
      .mapAsync(4) {
        case (lines, idx) => // ghi 4 files song song
          val chunkPath = outputDir.resolve(f"chunk_${idx + 1}%04d.csv")
          val content = (BookingRecord.CsvHeader +: lines).mkString("\n") + "\n"

          Source
            .single(ByteString(content))
            .runWith(FileIO.toPath(chunkPath))
            .map(ioResult =>
              ChunkInfo(
                chunkPath.getFileName.toString,
                lines.size,
                ioResult.count
              )
            )
      }
      .runWith(Sink.seq) // Future[Seq[ChunkInfo]]
      .map { chunks =>
        SplitReport(
          inputFile = inputPath.getFileName.toString,
          totalChunks = chunks.size,
          totalLines = chunks.map(_.lineCount).sum,
          chunks = chunks.toList
        )
      } // Future[SplitReport]
  }

  /** Variant: Split theo route. Mỗi route tạo 1 file riêng: HAN_SGN.csv,
    * HAN_DAD.csv, ...
    *
    * Sử dụng groupBy thay vì grouped — chia theo nội dung, không theo size.
    */
  def splitByRoute(inputPath: Path, outputDir: Path)(implicit
      system: ActorSystem[_]
  ): Future[SplitReport] = {

    implicit val ec = system.executionContext
    Files.createDirectories(outputDir)

    FileIO
      .fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .filterNot(_.isEmpty)
      .drop(1)
      .map(line => CsvParser.parseLine(line))
      .collect { case Right(record) => record }
      .groupBy(maxSubstreams = 50, _.routeKey) // chia theo route
      .fold(List.empty[BookingRecord])((acc, r) =>
        r :: acc
      ) // gom tất cả records của route
      .mergeSubstreams
      .mapAsync(4) { records =>
        val routeKey = records.headOption.map(_.routeKey).getOrElse("unknown")
        val chunkPath = outputDir.resolve(s"$routeKey.csv")
        val content =
          (BookingRecord.CsvHeader +: records.reverse.map(_.toCsvLine))
            .mkString("\n") + "\n"

        Source
          .single(ByteString(content))
          .runWith(FileIO.toPath(chunkPath))
          .map(ioResult =>
            ChunkInfo(
              chunkPath.getFileName.toString,
              records.size,
              ioResult.count
            )
          )
      }
      .runWith(Sink.seq)
      .map { chunks =>
        SplitReport(
          inputFile = inputPath.getFileName.toString,
          totalChunks = chunks.size,
          totalLines = chunks.map(_.lineCount).sum,
          chunks = chunks.toList
        )
      }
  }

  /** Feature 2.1: Chia file theo kích thước byte thay vì số dòng.
    *
    * Concepts học được:
    *   - statefulMapConcat — operator có state, mỗi element có thể emit 0 hoặc
    *     nhiều elements
    *   - Sentinel pattern — append 1 element đặc biệt vào cuối stream để flush
    *     batch cuối cùng
    *   - Tránh nested stream materialization bằng Files.write
    *
    * Tại sao cần sentinel? statefulMapConcat không có "on stream complete"
    * callback, nên batch cuối có thể bị bỏ qua nếu nó chưa đủ maxBytesPerChunk.
    * Trick: concat Source.single(Flush) vào cuối stream.
    */
  def splitFileBySize(inputPath: Path, outputDir: Path, maxBytesPerChunk: Long)(
      implicit system: ActorSystem[_]
  ): Future[SplitReport] = {

    implicit val ec = system.executionContext
    Files.createDirectories(outputDir)

    // Sentinel pattern: phân biệt data line vs tín hiệu flush cuối stream
    sealed trait SizeEvent
    case class DataLine(content: String) extends SizeEvent
    case object Flush extends SizeEvent

    FileIO
      .fromPath(inputPath)
      .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
      .map(_.utf8String.trim)
      .filterNot(_.isEmpty)
      .drop(1)
      .map[SizeEvent](DataLine(_)) // Source[SizeEvent, Future[IOResult]]
      .concat(Source.single(Flush)) // append sentinel để flush batch cuối
      .statefulMapConcat { () =>
        // State nằm trong closure, tồn tại suốt vòng đời stream
        var buffer = Vector.empty[String]
        var bufBytes = 0L

        {
          case DataLine(line) =>
            val lineBytes = line
              .getBytes(StandardCharsets.UTF_8)
              .length
              .toLong + 1L // +1 cho "\n"
            if (bufBytes + lineBytes > maxBytesPerChunk && buffer.nonEmpty) {
              // Buffer đã đầy → emit batch hiện tại, bắt đầu buffer mới
              val batch = buffer
              buffer = Vector(line)
              bufBytes = lineBytes
              Iterator.single(batch) // emit batch hiện tại (mapConcat yêu cầu emiter Iterator)
            } else {
              buffer = buffer :+ line
              bufBytes += lineBytes
              Iterator.empty // chưa đủ size → chưa emit
            }

          case Flush =>
            // Cuối stream → emit phần còn lại dù chưa đủ size
            if (buffer.nonEmpty) {
              val batch = buffer; buffer = Vector.empty; Iterator.single(batch) // emit batch cuối
            } else Iterator.empty
        }
      } // Source[Vector[String], Future[IOResult]]
      .zipWithIndex // đánh số chunk: (Vector[String], Long)
      .mapAsync(4) { case (lines, idx) =>
        val chunkPath = outputDir.resolve(f"chunk_${idx + 1}%04d.csv")
        val content = (BookingRecord.CsvHeader +: lines).mkString("\n") + "\n"
        // Dùng Files.write thay vì Source.single+runWith để tránh nested materialization
        Future {
          val bytes = content.getBytes(StandardCharsets.UTF_8)
          Files.write(chunkPath, bytes)
          ChunkInfo(
            chunkPath.getFileName.toString,
            lines.size,
            bytes.length.toLong
          )
        }
      }
      .runWith(Sink.seq)
      .map { chunks =>
        SplitReport(
          inputFile = inputPath.getFileName.toString,
          totalChunks = chunks.size,
          totalLines = chunks.map(_.lineCount).sum,
          chunks = chunks.toList
        )
      }
  }

  /** Merge nhiều file nhỏ thành 1 file lớn */
  def mergeFiles(inputFiles: Seq[Path], outputPath: Path)(implicit
      system: ActorSystem[_]
  ): Future[Long] = {

    implicit val ec = system.executionContext

    // Tạo source từ mỗi file, skip header của file 2+
    val sources = inputFiles.zipWithIndex.map { case (path, idx) =>
      val fileSource = FileIO
        .fromPath(path)
        .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
        .map(_.utf8String.trim)
        .filterNot(_.isEmpty)

      if (idx == 0) fileSource // file đầu tiên: giữ header
      else fileSource.drop(1) // file 2+: bỏ header
    }

    // Concat tất cả sources
    sources
      .reduceLeft(_ concat _)
      .map(line => ByteString(line + "\n"))
      .runWith(FileIO.toPath(outputPath))
      .map(_.count)
  }
}

final case class ChunkInfo(
    fileName: String,
    lineCount: Int,
    bytesWritten: Long
)

final case class SplitReport(
    inputFile: String,
    totalChunks: Int,
    totalLines: Int,
    chunks: List[ChunkInfo]
)
