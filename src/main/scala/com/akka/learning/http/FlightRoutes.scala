package com.akka.learning.http

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.akka.learning.http.JsonProtocols._
import com.akka.learning.models.commands._
import com.akka.learning.models.domain._

import java.time.Duration
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Flight REST API Routes
 */
class FlightRoutes(registry: ActorRef[RegistryCommand])(implicit system: ActorSystem[_]) extends JsonProtocols {

  private implicit val timeout: Timeout = 3.seconds

  val routes: Route = pathPrefix("flights") {
    concat(
      // POST /flights - Create new flight
      pathEnd {
        post {
          entity(as[CreateFlightRequest]) { request =>
            val flightId = UUID.randomUUID().toString
            val origin = Airport(
              request.originCode,
              request.originName,
              request.originCity,
              request.originCountry
            )
            val destination = Airport(
              request.destinationCode,
              request.destinationName,
              request.destinationCity,
              request.destinationCountry
            )
            val flightRoute = com.akka.learning.models.domain.Route(
              origin,
              destination,
              request.distance,
              Duration.parse(request.estimatedDuration)
            )
            val flight = Flight(
              flightId = flightId,
              flightNumber = request.flightNumber,
              route = flightRoute,
              scheduledDeparture = request.scheduledDeparture,
              scheduledArrival = request.scheduledArrival,
              totalSeats = request.totalSeats,
              availableSeats = request.totalSeats,
              status = FlightStatus.Scheduled
            )

            val futureResponse: Future[RegistryResponse] =
              registry.ask[RegistryResponse](replyTo => RegisterFlight(flight, replyTo))

            onSuccess(futureResponse) {
              case response: RegistryFlightCreated =>
                complete(StatusCodes.Created, response.flight)
              case RegistryOperationFailed(_, reason) =>
                complete(StatusCodes.InternalServerError, ErrorResponse("CreateFlightFailed", reason))
              case _ =>
                complete(StatusCodes.InternalServerError, ErrorResponse("UnexpectedResponse", "Unexpected response from registry"))
            }
          }
        }
      },
      // GET /flights - List all flights
      pathEnd {
        get {
          val futureResponse: Future[RegistryResponse] =
            registry.ask(replyTo => GetAllFlights(replyTo))

          onSuccess(futureResponse) {
            case FlightsRetrieved(flights) =>
              complete(StatusCodes.OK, FlightListResponse(flights))
            case _ =>
              complete(StatusCodes.InternalServerError, ErrorResponse("GetFlightsFailed", "Failed to retrieve flights"))
          }
        }
      },
      // Routes with flightId parameter
      pathPrefix(Segment) { flightId =>
        concat(
          // GET /flights/{flightId} - Get specific flight
          pathEnd {
            get {
              val futureResponse: Future[RegistryResponse] =
                registry.ask(replyTo => GetFlight(flightId, replyTo))

              onSuccess(futureResponse) {
                case FlightDetails(flight) =>
                  complete(StatusCodes.OK, flight)
                case RegistryFlightNotFound(_) =>
                  complete(StatusCodes.NotFound, ErrorResponse("FlightNotFound", s"Flight $flightId not found"))
                case _ =>
                  complete(StatusCodes.InternalServerError, ErrorResponse("GetFlightFailed", "Failed to retrieve flight"))
              }
            }
          }
        )
      }
    )
  }
}
