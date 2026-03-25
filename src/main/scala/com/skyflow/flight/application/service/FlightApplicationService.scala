package com.skyflow.flight.application.service

import java.time.Duration
import com.skyflow.flight.application.dto.CreateFlightRequest
import com.skyflow.flight.domain.error.FlightError
import com.skyflow.flight.domain.event.FlightDomainEvent
import com.skyflow.flight.domain.model._
import com.skyflow.shared.domain.AirportCode

/**
 * FlightApplicationService — Orchestration layer.
 *
 * Converts DTOs to domain objects, invokes domain logic, and returns results.
 * This service has NO dependency on Akka or infrastructure.
 */
object FlightApplicationService {

  /**
   * Create a new Flight from a DTO request.
   * Validates all fields and delegates to the Flight aggregate root factory.
   */
  def createFlight(dto: CreateFlightRequest): Either[FlightError, (Flight, FlightDomainEvent)] = {
    for {
      flightNumber <- FlightNumber.from(dto.flightNumber).left.map(FlightError.ValidationFailed)
      originCode   <- AirportCode.from(dto.originCode).left.map(FlightError.ValidationFailed)
      destCode     <- AirportCode.from(dto.destinationCode).left.map(FlightError.ValidationFailed)
      totalSeats   <- SeatCapacity.from(dto.totalSeats).left.map(FlightError.ValidationFailed)
      duration     <- parseDuration(dto.estimatedDuration)
      origin        = Airport(originCode, dto.originName, dto.originCity, dto.originCountry)
      destination   = Airport(destCode, dto.destinationName, dto.destinationCity, dto.destinationCountry)
      route        <- Route.from(origin, destination, dto.distance, duration).left.map(FlightError.ValidationFailed)
      result       <- Flight.create(flightNumber, route, dto.scheduledDeparture, dto.scheduledArrival, totalSeats)
    } yield result
  }

  private def parseDuration(s: String): Either[FlightError, Duration] =
    try Right(Duration.parse(s))
    catch { case _: Exception => Left(FlightError.ValidationFailed(s"Invalid duration format: '$s'")) }
}
