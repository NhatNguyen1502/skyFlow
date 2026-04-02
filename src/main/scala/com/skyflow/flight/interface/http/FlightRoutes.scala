package com.skyflow.flight.`interface`.http

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.duration._

import com.skyflow.flight.application.command._
import com.skyflow.flight.application.dto._
import com.skyflow.flight.application.service.FlightApplicationService
import com.skyflow.flight.infrastructure.serialization.FlightJsonProtocol

/** FlightRoutes — Thin HTTP layer for the Flight bounded context.
  *
  * Responsibilities: parse request → call application service → map response.
  * Contains NO business logic.
  */
class FlightRoutes(registry: ActorRef[RegistryCommand])(implicit
    system: ActorSystem[_]
) extends FlightJsonProtocol {

  private implicit val timeout: Timeout = 3.seconds

  val routes: Route = pathPrefix("flights") {
    concat(
      pathEnd {
        concat(
          // POST /flights — Create new flight
          post {
            entity(as[CreateFlightRequest]) { request =>
              // Delegate to application service for validation + domain creation
              FlightApplicationService.createFlight(request) match {
                case Right((flight, _)) =>
                  val futureResponse: Future[RegistryResponse] =
                    registry.ask[RegistryResponse](replyTo =>
                      RegisterFlight(flight, replyTo)
                    )

                  onSuccess(futureResponse) {
                    case RegistryFlightCreated(created) =>
                      complete(
                        StatusCodes.Created,
                        FlightDtoMapper.toResponse(created)
                      )
                    case RegistryOperationFailed(_, reason) =>
                      complete(
                        StatusCodes.InternalServerError,
                        ErrorResponse("CreateFlightFailed", reason)
                      )
                    case _ =>
                      complete(
                        StatusCodes.InternalServerError,
                        ErrorResponse(
                          "UnexpectedResponse",
                          "Unexpected response from registry"
                        )
                      )
                  }

                case Left(error) =>
                  complete(
                    StatusCodes.BadRequest,
                    ErrorResponse("ValidationFailed", error.message)
                  )
              }
            }
          },
          // GET /flights — List all flights
          get {
            val futureResponse: Future[RegistryResponse] =
              registry.ask(replyTo => GetAllFlights(replyTo))

            onSuccess(futureResponse) {
              case FlightsRetrieved(flights) =>
                complete(
                  StatusCodes.OK,
                  FlightDtoMapper.toListResponse(flights)
                )
              case _ =>
                complete(
                  StatusCodes.InternalServerError,
                  ErrorResponse(
                    "GetFlightsFailed",
                    "Failed to retrieve flights"
                  )
                )
            }
          }
        )
      },
      // GET /flights/{flightId}
      pathPrefix(Segment) { flightId =>
        pathEnd {
          get {
            val futureResponse: Future[RegistryResponse] =
              registry.ask(replyTo => GetFlight(flightId, replyTo))

            onSuccess(futureResponse) {
              case FlightDetails(flight) =>
                complete(StatusCodes.OK, FlightDtoMapper.toResponse(flight))
              case RegistryFlightNotFound(_) =>
                complete(
                  StatusCodes.NotFound,
                  ErrorResponse("FlightNotFound", s"Flight $flightId not found")
                )
              case _ =>
                complete(
                  StatusCodes.InternalServerError,
                  ErrorResponse("GetFlightFailed", "Failed to retrieve flight")
                )
            }
          }
        }
      }
    )
  }
}
