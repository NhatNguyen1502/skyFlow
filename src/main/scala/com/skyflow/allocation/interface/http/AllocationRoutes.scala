package com.skyflow.allocation.`interface`.http

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.duration._

import com.skyflow.allocation.application.command._
import com.skyflow.allocation.application.dto._
import com.skyflow.allocation.application.service.AllocationApplicationService
import com.skyflow.allocation.infrastructure.serialization.AllocationJsonProtocol
import com.skyflow.flight.application.command.RegistryCommand
import com.skyflow.flight.infrastructure.serialization.FlightJsonProtocol.ErrorResponse

/**
 * AllocationRoutes — Thin HTTP layer for the Allocation bounded context.
 *
 * Delegates validation to AllocationApplicationService.
 * Contains NO business logic.
 */
class AllocationRoutes(
  allocator: ActorRef[AllocationCommand],
  registry: ActorRef[RegistryCommand]
)(implicit system: ActorSystem[_]) extends AllocationJsonProtocol {

  private implicit val timeout: Timeout = 10.seconds

  // Import error response format from FlightJsonProtocol
  implicit val errorResponseFormat2: spray.json.RootJsonFormat[com.skyflow.flight.infrastructure.serialization.FlightJsonProtocol.ErrorResponse] =
    com.skyflow.flight.infrastructure.serialization.FlightJsonProtocol.errorResponseFormat

  val routes: Route = pathPrefix("allocations") {
    concat(
      // POST /allocations — Start new allocation run
      pathEnd {
        post {
          entity(as[AllocationRequestHttp]) { httpRequest =>
            AllocationApplicationService.createAllocationRequest(httpRequest) match {
              case Right(allocationRequest) =>
                val futureResponse: Future[AllocationResponse] =
                  allocator.ask[AllocationResponse](replyTo =>
                    StartAllocation(allocationRequest, replyTo)
                  )

                onSuccess(futureResponse) {
                  case AllocationStarted(allocId, runId) =>
                    complete(StatusCodes.Accepted, AllocationHttpResponse(
                      allocationId = allocId,
                      status = "started",
                      message = s"Allocation run $runId started with ID $allocId"
                    ))
                  case AllocationFailed(allocId, reason) =>
                    complete(StatusCodes.InternalServerError, ErrorResponse("AllocationFailed", reason))
                  case _ =>
                    complete(StatusCodes.InternalServerError, ErrorResponse("UnexpectedResponse", "Unexpected response"))
                }

              case Left(error) =>
                complete(StatusCodes.BadRequest, ErrorResponse("ValidationFailed", error.message))
            }
          }
        }
      },
      // Routes with allocationId
      pathPrefix(Segment) { allocationId =>
        concat(
          // GET /allocations/{id}
          pathEnd {
            get {
              val futureResponse: Future[AllocationResponse] =
                allocator.ask(replyTo => GetAllocationResult(allocationId, replyTo))

              onSuccess(futureResponse) {
                case AllocationComplete(result) =>
                  complete(StatusCodes.OK, result)
                case AllocationInProgress(id, progress) =>
                  complete(StatusCodes.OK, AllocationHttpResponse(
                    allocationId = id,
                    status = "in-progress",
                    message = f"Allocation is ${progress * 100}%.1f%% complete"
                  ))
                case AllocationNotFound(id) =>
                  complete(StatusCodes.NotFound, ErrorResponse("AllocationNotFound", s"Allocation $id not found"))
                case AllocationFailed(id, reason) =>
                  complete(StatusCodes.OK, AllocationHttpResponse(
                    allocationId = id,
                    status = "failed",
                    message = reason
                  ))
                case _ =>
                  complete(StatusCodes.InternalServerError, ErrorResponse("UnexpectedResponse", "Unexpected response"))
              }
            }
          },
          // GET /allocations/{id}/status
          path("status") {
            get {
              val futureResponse: Future[AllocationResponse] =
                allocator.ask(replyTo => GetAllocationStatus(allocationId, replyTo))

              onSuccess(futureResponse) {
                case AllocationComplete(result) =>
                  complete(StatusCodes.OK, AllocationHttpResponse(
                    allocationId = allocationId,
                    status = "completed",
                    message = s"Allocation completed with ${result.allocations.size} allocations"
                  ))
                case AllocationInProgress(id, progress) =>
                  complete(StatusCodes.OK, AllocationHttpResponse(
                    allocationId = id,
                    status = "in-progress",
                    message = f"${progress * 100}%.1f%% complete"
                  ))
                case AllocationNotFound(id) =>
                  complete(StatusCodes.NotFound, ErrorResponse("AllocationNotFound", s"Allocation $id not found"))
                case AllocationFailed(id, reason) =>
                  complete(StatusCodes.OK, AllocationHttpResponse(
                    allocationId = id,
                    status = "failed",
                    message = reason
                  ))
                case _ =>
                  complete(StatusCodes.InternalServerError, ErrorResponse("UnexpectedResponse", "Unexpected response"))
              }
            }
          }
        )
      }
    )
  }
}
