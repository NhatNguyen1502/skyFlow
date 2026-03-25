package com.skyflow.flight.application.dto

import java.time.LocalDateTime
import com.skyflow.flight.domain.model.Flight

/**
 * DTOs for the Flight bounded context HTTP API.
 * These are the anti-corruption layer between external requests and the domain model.
 */
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

case class FlightResponse(
  flightId: String,
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
  totalSeats: Int,
  availableSeats: Int,
  status: String
)

case class FlightListResponse(flights: List[FlightResponse])

case class UpdateSeatsRequest(seats: Int)
case class UpdateStatusRequest(status: String)

/**
 * Mapper between domain model and DTOs.
 */
object FlightDtoMapper {

  def toResponse(flight: Flight): FlightResponse = FlightResponse(
    flightId = flight.id.value,
    flightNumber = flight.flightNumber.value,
    originCode = flight.route.origin.code.value,
    originName = flight.route.origin.name,
    originCity = flight.route.origin.city,
    originCountry = flight.route.origin.country,
    destinationCode = flight.route.destination.code.value,
    destinationName = flight.route.destination.name,
    destinationCity = flight.route.destination.city,
    destinationCountry = flight.route.destination.country,
    distance = flight.route.distance,
    estimatedDuration = flight.route.estimatedDuration.toString,
    scheduledDeparture = flight.scheduledDeparture,
    scheduledArrival = flight.scheduledArrival,
    totalSeats = flight.totalSeats.value,
    availableSeats = flight.availableSeats,
    status = flight.status.toString
  )

  def toListResponse(flights: List[Flight]): FlightListResponse =
    FlightListResponse(flights.map(toResponse))
}
