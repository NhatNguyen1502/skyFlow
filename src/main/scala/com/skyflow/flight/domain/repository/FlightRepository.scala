package com.skyflow.flight.domain.repository

import com.skyflow.flight.domain.error.FlightError
import com.skyflow.flight.domain.model.{Flight, FlightId}
import com.skyflow.shared.domain.AirportCode
import scala.concurrent.Future

/**
 * FlightRepository — Port (trait) defining the persistence contract.
 *
 * The domain layer defines WHAT it needs; the infrastructure layer decides HOW.
 * This trait has NO dependency on Akka, JDBC, or any framework.
 */
trait FlightRepository {
  def save(flight: Flight): Future[Either[FlightError, Flight]]
  def findById(id: FlightId): Future[Option[Flight]]
  def findAll(): Future[List[Flight]]
  def findByRoute(origin: AirportCode, destination: AirportCode): Future[List[Flight]]
}
