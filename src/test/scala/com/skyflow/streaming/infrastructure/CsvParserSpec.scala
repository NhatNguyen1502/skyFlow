package com.skyflow.streaming.infrastructure

import com.skyflow.streaming.domain.model.{BookingRecord, CsvParseError}
import com.skyflow.streaming.infrastructure.stream.CsvParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Unit tests cho CsvParser — không cần ActorSystem.
  *
  * Bài học:
  *   - Logic parse thuần túy nên test ở tầng unit (nhanh, không phụ thuộc
  *     infrastructure).
  *   - Dùng Either → dễ assert Right/Left mà không cần try/catch.
  */
class CsvParserSpec extends AnyWordSpec with Matchers {

  // ── helpers ──────────────────────────────────────────────────────────────

  private def validLine(id: String = "P001"): String =
    s"$id,Nguyen Van A,vana@example.com,HAN,SGN,2"

  // ── test cases ───────────────────────────────────────────────────────────

  "CsvParser.parseLine" when {

    "nhận vào dòng CSV hợp lệ" should {

      "trả về Right(BookingRecord) với đầy đủ field" in {
        val result = CsvParser.parseLine(validLine())
        result shouldBe Right(
          BookingRecord(
            passengerId = "P001",
            passengerName = "Nguyen Van A",
            email = "vana@example.com",
            origin = "HAN",
            destination = "SGN",
            seatsRequested = 2
          )
        )
      }

      "tự động trim khoảng trắng ở đầu/cuối mỗi field" in {
        val result =
          CsvParser.parseLine(" P099 , Le Thi B , b@b.com , SGN , HAN , 3 ")
        result shouldBe Right(
          BookingRecord("P099", "Le Thi B", "b@b.com", "SGN", "HAN", 3)
        )
      }

      "parse seatsRequested = 1 (min hợp lệ)" in {
        CsvParser.parseLine("P001,A,a@a.com,HAN,SGN,1") shouldBe a[Right[_, _]]
      }

      "parse seatsRequested = 9 (max hợp lệ)" in {
        CsvParser.parseLine("P001,A,a@a.com,HAN,SGN,9") shouldBe a[Right[_, _]]
      }
    }

    "nhận vào dòng sai số field" should {

      "trả về Left(CsvParseError) khi thiếu field" in {
        val result = CsvParser.parseLine("P001,Nguyen Van A,HAN,SGN,2")
        result shouldBe a[Left[_, _]]
        result.left.get shouldBe a[CsvParseError]
        result.left.get.message should include("Expected 6 fields")
      }

      "trả về Left khi dư field (7 cột)" in {
        val result = CsvParser.parseLine("P001,A,a@a.com,HAN,SGN,2,EXTRA")
        result shouldBe a[Left[_, _]]
      }

      "trả về Left cho chuỗi rỗng" in {
        CsvParser.parseLine("") shouldBe a[Left[_, _]]
      }
    }

    "nhận vào seatsRequested không phải số" should {

      "trả về Left(CsvParseError) với thông báo rõ ràng" in {
        val result = CsvParser.parseLine("P001,A,a@a.com,HAN,SGN,abc")
        result shouldBe a[Left[_, _]]
        result.left.get.message should include("Invalid seatsRequested")
      }

      "trả về Left khi seats là số thực (1.5)" in {
        val result = CsvParser.parseLine("P001,A,a@a.com,HAN,SGN,1.5")
        result shouldBe a[Left[_, _]]
      }
    }
  }
}
