package com.akka.learning.http

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.akka.learning.models.domain._
import com.akka.learning.models.commands._
import com.akka.learning.models.events._
import spray.json._

import java.time.{Duration, LocalDateTime}
import java.time.format.DateTimeFormatter

/**
 * JSON Protocols for HTTP API
 * 
 * Spray JSON formats for serializing domain models, commands, and responses.
 */
trait JsonProtocols extends SprayJsonSupport with DefaultJsonProtocol {

  // Custom formatter for LocalDateTime
  implicit object LocalDateTimeFormat extends RootJsonFormat[LocalDateTime] {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    
    def write(obj: LocalDateTime): JsValue = JsString(obj.format(formatter))
    
    def read(json: JsValue): LocalDateTime = json match {
      case JsString(s) => LocalDateTime.parse(s, formatter)
      case _ => throw DeserializationException("LocalDateTime expected")
    }
  }

  // Custom formatter for Duration
  implicit object DurationFormat extends RootJsonFormat[Duration] {
    def write(obj: Duration): JsValue = JsString(obj.toString)
    
    def read(json: JsValue): Duration = json match {
      case JsString(s) => Duration.parse(s)
      case _ => throw DeserializationException("Duration expected")
    }
  }

  // FlightStatus enum format
  implicit object FlightStatusFormat extends RootJsonFormat[FlightStatus] {
    def write(obj: FlightStatus): JsValue = JsString(obj match {
      case FlightStatus.Scheduled => "Scheduled"
      case FlightStatus.Boarding => "Boarding"
      case FlightStatus.Departed => "Departed"
      case FlightStatus.Arrived => "Arrived"
      case FlightStatus.Cancelled => "Cancelled"
      case FlightStatus.Delayed => "Delayed"
    })
    
    def read(json: JsValue): FlightStatus = json match {
      case JsString("Scheduled") => FlightStatus.Scheduled
      case JsString("Boarding") => FlightStatus.Boarding
      case JsString("Departed") => FlightStatus.Departed
      case JsString("Arrived") => FlightStatus.Arrived
      case JsString("Cancelled") => FlightStatus.Cancelled
      case JsString("Delayed") => FlightStatus.Delayed
      case _ => throw DeserializationException("FlightStatus expected")
    }
  }

  // Priority enum format
  implicit object PriorityFormat extends RootJsonFormat[Priority] {
    def write(obj: Priority): JsValue = JsString(obj match {
      case Priority.Low => "Low"
      case Priority.Medium => "Medium"
      case Priority.High => "High"
      case Priority.Critical => "Critical"
    })
    
    def read(json: JsValue): Priority = json match {
      case JsString("Low") => Priority.Low
      case JsString("Medium") => Priority.Medium
      case JsString("High") => Priority.High
      case JsString("Critical") => Priority.Critical
      case _ => throw DeserializationException("Priority expected")
    }
  }

  // Domain model formats
  implicit val airportFormat: RootJsonFormat[Airport] = jsonFormat4(Airport)
  implicit val routeFormat: RootJsonFormat[Route] = jsonFormat4(Route)
  implicit val flightFormat: RootJsonFormat[Flight] = jsonFormat8(Flight)
  implicit val odPairFormat: RootJsonFormat[ODPair] = jsonFormat2(ODPair)
  implicit val allocationFormat: RootJsonFormat[Allocation] = jsonFormat3(Allocation)
  implicit val allocationRequestFormat: RootJsonFormat[AllocationRequest] = jsonFormat5(AllocationRequest)
  implicit val allocationResultFormat: RootJsonFormat[AllocationResult] = jsonFormat6(AllocationResult)

  // Command formats for HTTP requests
  case class CreateFlightRequest(
    flightNumber: String,
    originCode: String,
    originName: String,
    originCity: String,
    originCountry: String,
    destinationCode: String,
    destinationName: String,
    destinationCity: String,
    destinationCountry: String,
    distance: Double,
    estimatedDuration: String,
    scheduledDeparture: LocalDateTime,
    scheduledArrival: LocalDateTime,
    totalSeats: Int
  )

  case class UpdateSeatsRequest(seats: Int)
  case class UpdateStatusRequest(status: FlightStatus)
  case class GetFlightResponse(flight: Option[Flight])
  case class FlightListResponse(flights: List[Flight])
  
  case class AllocationRequestHttp(
    runId: String,
    odPairs: List[ODPairDemand],
    priority: Priority
  )
  
  case class ODPairDemand(
    originCode: String,
    destinationCode: String,
    demand: Int
  )
  
  case class AllocationHttpResponse(
    allocationId: String,
    status: String,
    message: String
  )

  implicit val createFlightRequestFormat: RootJsonFormat[CreateFlightRequest] = jsonFormat14(CreateFlightRequest)
  implicit val updateSeatsRequestFormat: RootJsonFormat[UpdateSeatsRequest] = jsonFormat1(UpdateSeatsRequest)
  implicit val updateStatusRequestFormat: RootJsonFormat[UpdateStatusRequest] = jsonFormat1(UpdateStatusRequest)
  implicit val getFlightResponseFormat: RootJsonFormat[GetFlightResponse] = jsonFormat1(GetFlightResponse)
  implicit val flightListResponseFormat: RootJsonFormat[FlightListResponse] = jsonFormat1(FlightListResponse)
  implicit val odPairDemandFormat: RootJsonFormat[ODPairDemand] = jsonFormat3(ODPairDemand)
  implicit val allocationRequestHttpFormat: RootJsonFormat[AllocationRequestHttp] = jsonFormat3(AllocationRequestHttp)
  implicit val allocationHttpResponseFormat: RootJsonFormat[AllocationHttpResponse] = jsonFormat3(AllocationHttpResponse)

  // Response formats
  case class ErrorResponse(error: String, message: String)
  case class SuccessResponse(message: String)
  
  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] = jsonFormat2(ErrorResponse)
  implicit val successResponseFormat: RootJsonFormat[SuccessResponse] = jsonFormat1(SuccessResponse)
}

object JsonProtocols extends JsonProtocols
