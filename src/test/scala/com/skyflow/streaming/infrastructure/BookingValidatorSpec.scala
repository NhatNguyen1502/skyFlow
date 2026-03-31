package com.skyflow.streaming.infrastructure

import com.skyflow.streaming.domain.model.{BookingRecord, ValidationError}
import com.skyflow.streaming.infrastructure.stream.BookingValidator
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Unit tests cho BookingValidator — không cần ActorSystem.
  *
  * Bài học:
  *   - validate() trả về Either → test bằng Right/Left thuần túy.
  *   - Gom tất cả vi phạm trong 1 lần chạy thay vì fail-fast.
  */
class BookingValidatorSpec extends AnyWordSpec with Matchers {

  // ── helper: record hợp lệ làm baseline ──────────────────────────────────

  private val valid = BookingRecord(
    passengerId = "P001",
    passengerName = "Nguyen Van A",
    email = "vana@example.com",
    origin = "HAN",
    destination = "SGN",
    seatsRequested = 2
  )

  // ── test cases ───────────────────────────────────────────────────────────

  "BookingValidator.validate" when {

    "nhận record hợp lệ" should {

      "trả về Right(record) không thay đổi dữ liệu" in {
        BookingValidator.validate(valid) shouldBe Right(valid)
      }
    }

    "passengerId không hợp lệ" should {

      "trả về Left khi passengerId là blank" in {
        val result = BookingValidator.validate(valid.copy(passengerId = "  "))
        result shouldBe a[Left[_, _]]
        result.left.get.asInstanceOf[ValidationError].violations should contain(
          "passengerId is empty"
        )
      }
    }

    "passengerName không hợp lệ" should {

      "trả về Left khi passengerName là blank" in {
        val result = BookingValidator.validate(valid.copy(passengerName = ""))
        result shouldBe a[Left[_, _]]
        result.left.get.asInstanceOf[ValidationError].violations should contain(
          "passengerName is empty"
        )
      }
    }

    "email không hợp lệ" should {

      "trả về Left khi email thiếu @" in {
        val result =
          BookingValidator.validate(valid.copy(email = "invalid-email"))
        result.left.get.message should include("Invalid email")
      }

      "trả về Left khi email thiếu phần domain" in {
        val result = BookingValidator.validate(valid.copy(email = "user@"))
        result shouldBe a[Left[_, _]]
      }

      "chấp nhận email có subdomain" in {
        val result =
          BookingValidator.validate(valid.copy(email = "user@mail.example.com"))
        result shouldBe a[Right[_, _]]
      }
    }

    "airport code không hợp lệ" should {

      "trả về Left khi origin không đúng 3 chữ hoa" in {
        val result = BookingValidator.validate(valid.copy(origin = "ha"))
        result.left.get.message should include("Invalid origin airport code")
      }

      "trả về Left khi destination chứa chữ thường" in {
        val result = BookingValidator.validate(valid.copy(destination = "sgn"))
        result.left.get.message should include(
          "Invalid destination airport code"
        )
      }

      "trả về Left khi origin == destination" in {
        val result = BookingValidator.validate(
          valid.copy(origin = "SGN", destination = "SGN")
        )
        result.left.get.asInstanceOf[ValidationError].violations should contain(
          "Origin and destination must be different"
        )
      }
    }

    "seatsRequested ngoài khoảng [1, 9]" should {

      "trả về Left khi seats = 0" in {
        val result = BookingValidator.validate(valid.copy(seatsRequested = 0))
        result.left.get.message should include("seatsRequested must be 1-9")
      }

      "trả về Left khi seats = 10" in {
        val result = BookingValidator.validate(valid.copy(seatsRequested = 10))
        result shouldBe a[Left[_, _]]
      }

      "chấp nhận seats = 9 (biên trên)" in {
        BookingValidator
          .validate(valid.copy(seatsRequested = 9)) shouldBe a[Right[_, _]]
      }

      "chấp nhận seats = 1 (biên dưới)" in {
        BookingValidator
          .validate(valid.copy(seatsRequested = 1)) shouldBe a[Right[_, _]]
      }
    }

    "nhiều vi phạm cùng lúc" should {

      "gom tất cả violations vào một Left duy nhất" in {
        val badRecord = valid.copy(
          passengerId = "",
          email = "not-an-email",
          seatsRequested = 99
        )
        val result = BookingValidator.validate(badRecord)
        result shouldBe a[Left[_, _]]
        val violations =
          result.left.get.asInstanceOf[ValidationError].violations
        violations.size shouldBe 3
        violations should contain("passengerId is empty")
        violations.exists(_.contains("Invalid email")) shouldBe true
        violations.exists(
          _.contains("seatsRequested must be 1-9")
        ) shouldBe true
      }
    }
  }
}
