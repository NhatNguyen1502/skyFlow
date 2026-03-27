package com.skyflow.streaming.infrastructure.stream

import com.skyflow.streaming.domain.model.BookingRecord

import java.nio.file.{Files, Path}
import scala.util.Random

/**
 * Sinh file CSV booking data cho testing.
 * Tạo file medium (1,000 records) và large (100,000 records).
 */
object BookingDataGenerator {

  private val routes = List(
    ("HAN", "SGN"), ("HAN", "DAD"), ("SGN", "DAD"),
    ("SGN", "PQC"), ("HAN", "CXR"), ("DAD", "PQC")
  )

  private val firstNames = List(
    "Nguyen", "Tran", "Le", "Pham", "Hoang",
    "Vu", "Dao", "Bui", "Ngo", "Ly"
  )

  private val lastNames = List(
    "Van A", "Thi B", "Van C", "Thi D", "Van E",
    "Thi F", "Van G", "Thi H", "Van I", "Thi K"
  )

  def generateCsv(path: Path, recordCount: Int, seed: Long = 42L): Unit = {
    val random = new Random(seed)
    Files.createDirectories(path.getParent)

    val writer = Files.newBufferedWriter(path)
    try {
      writer.write(BookingRecord.CsvHeader)
      writer.newLine()

      (1 to recordCount).foreach { i =>
        val (origin, dest) = routes(random.nextInt(routes.length))
        val firstName = firstNames(random.nextInt(firstNames.length))
        val lastName = lastNames(random.nextInt(lastNames.length))
        val seats = random.nextInt(5) + 1

        writer.write(f"P$i%06d,$firstName $lastName,p$i@example.com,$origin,$dest,$seats")
        writer.newLine()
      }
    } finally {
      writer.close()
    }

    println(s"Generated $recordCount records → $path")
  }
}
