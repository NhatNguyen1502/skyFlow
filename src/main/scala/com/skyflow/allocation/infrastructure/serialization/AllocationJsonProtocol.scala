package com.skyflow.allocation.infrastructure.serialization

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.skyflow.allocation.application.dto._
import com.skyflow.allocation.domain.model._
import com.skyflow.shared.domain.AirportCode
import spray.json._
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * AllocationJsonProtocol — JSON serialization for the Allocation bounded context.
 */
trait AllocationJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {

  implicit object LocalDateTimeFormat extends RootJsonFormat[LocalDateTime] {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    def write(obj: LocalDateTime): JsValue = JsString(obj.format(formatter))
    def read(json: JsValue): LocalDateTime = json match {
      case JsString(s) => LocalDateTime.parse(s, formatter)
      case _ => throw DeserializationException("LocalDateTime expected")
    }
  }

  implicit object PriorityFormat extends RootJsonFormat[Priority] {
    def write(obj: Priority): JsValue = JsString(obj match {
      case Priority.Low      => "Low"
      case Priority.Medium   => "Medium"
      case Priority.High     => "High"
      case Priority.Critical => "Critical"
    })
    def read(json: JsValue): Priority = json match {
      case JsString("Low")      => Priority.Low
      case JsString("Medium")   => Priority.Medium
      case JsString("High")     => Priority.High
      case JsString("Critical") => Priority.Critical
      case _ => throw DeserializationException("Priority expected")
    }
  }

  implicit object AirportCodeFormat extends RootJsonFormat[AirportCode] {
    def write(obj: AirportCode): JsValue = JsString(obj.value)
    def read(json: JsValue): AirportCode = json match {
      case JsString(s) => AirportCode.from(s).getOrElse(
        throw DeserializationException(s"Invalid airport code: $s")
      )
      case _ => throw DeserializationException("AirportCode expected")
    }
  }

  implicit val odPairFormat: RootJsonFormat[ODPair] = jsonFormat2(ODPair.apply)

  implicit val allocationFormat: RootJsonFormat[Allocation] = jsonFormat3(Allocation)

  // Tuple format for (ODPair, Int)
  implicit object ODPairDemandTupleFormat extends RootJsonFormat[(ODPair, Int)] {
    def write(t: (ODPair, Int)): JsValue = JsObject(
      "odPair" -> t._1.toJson,
      "demand" -> JsNumber(t._2)
    )
    def read(json: JsValue): (ODPair, Int) = json.asJsObject.getFields("odPair", "demand") match {
      case Seq(od, JsNumber(d)) => (od.convertTo[ODPair], d.toInt)
      case _ => throw DeserializationException("(ODPair, Int) expected")
    }
  }

  implicit val allocationRequestFormat: RootJsonFormat[AllocationRequest] = jsonFormat5(AllocationRequest)
  implicit val allocationResultFormat: RootJsonFormat[AllocationResult] = jsonFormat6(AllocationResult)

  implicit val odPairDemandFormat: RootJsonFormat[ODPairDemand] = jsonFormat3(ODPairDemand)
  implicit val allocationRequestHttpFormat: RootJsonFormat[AllocationRequestHttp] = jsonFormat3(AllocationRequestHttp)
  implicit val allocationHttpResponseFormat: RootJsonFormat[AllocationHttpResponse] = jsonFormat3(AllocationHttpResponse)
}

object AllocationJsonProtocol extends AllocationJsonProtocol
