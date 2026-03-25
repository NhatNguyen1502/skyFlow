package com.skyflow.flight.infrastructure.serialization

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.skyflow.flight.application.dto._
import spray.json._
import java.time.{Duration, LocalDateTime}
import java.time.format.DateTimeFormatter

/**
 * FlightJsonProtocol — JSON serialization for the Flight bounded context.
 */
trait FlightJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {

  implicit object LocalDateTimeFormat extends RootJsonFormat[LocalDateTime] {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    def write(obj: LocalDateTime): JsValue = JsString(obj.format(formatter))
    def read(json: JsValue): LocalDateTime = json match {
      case JsString(s) => LocalDateTime.parse(s, formatter)
      case _ => throw DeserializationException("LocalDateTime expected")
    }
  }

  implicit object DurationFormat extends RootJsonFormat[Duration] {
    def write(obj: Duration): JsValue = JsString(obj.toString)
    def read(json: JsValue): Duration = json match {
      case JsString(s) => Duration.parse(s)
      case _ => throw DeserializationException("Duration expected")
    }
  }

  implicit val createFlightRequestFormat: RootJsonFormat[CreateFlightRequest] = jsonFormat14(CreateFlightRequest)
  implicit val flightResponseFormat: RootJsonFormat[FlightResponse] = jsonFormat17(FlightResponse)
  implicit val flightListResponseFormat: RootJsonFormat[FlightListResponse] = jsonFormat1(FlightListResponse)
  implicit val updateSeatsRequestFormat: RootJsonFormat[UpdateSeatsRequest] = jsonFormat1(UpdateSeatsRequest)
  implicit val updateStatusRequestFormat: RootJsonFormat[UpdateStatusRequest] = jsonFormat1(UpdateStatusRequest)

  case class ErrorResponse(error: String, message: String)
  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] = jsonFormat2(ErrorResponse)

  case class SuccessResponse(message: String)
  implicit val successResponseFormat: RootJsonFormat[SuccessResponse] = jsonFormat1(SuccessResponse)
}

object FlightJsonProtocol extends FlightJsonProtocol
