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

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Allocation REST API Routes
 * 
 * Provides endpoints for seat allocation operations:
 * - POST /allocations              - Start a new allocation run
 * - GET  /allocations/{id}         - Get allocation result
 * - GET  /allocations/{id}/status  - Get allocation status
 */
class AllocationRoutes(
  allocator: ActorRef[AllocationCommand],
  registry: ActorRef[RegistryCommand]
)(implicit system: ActorSystem[_]) extends JsonProtocols {

  private implicit val timeout: Timeout = 10.seconds
  import system.executionContext

  val routes: Route = pathPrefix("allocations") {
    concat(
      // POST /allocations - Start new allocation run
      pathEnd {
        post {
          entity(as[AllocationRequestHttp]) { httpRequest =>
            val allocationId = UUID.randomUUID().toString
            
            // First, we need to convert HTTP request to domain AllocationRequest
            // This requires fetching airport data for each O&D pair
            val odPairsFuture: Future[List[(ODPair, Int)]] = {
              Future.sequence(httpRequest.odPairs.map { demand =>
                // In a real system, we'd look up airports from a repository
                // For now, we'll create minimal airport objects
                val origin = Airport(demand.originCode, "", "", "")
                val destination = Airport(demand.destinationCode, "", "", "")
                val odPair = ODPair(origin, destination)
                Future.successful((odPair, demand.demand))
              })
            }

            val futureResponse = odPairsFuture.flatMap { odPairs =>
              val allocationRequest = AllocationRequest(
                allocationId = allocationId,
                runId = httpRequest.runId,
                odPairs = odPairs,
                priority = httpRequest.priority,
                requestTime = LocalDateTime.now()
              )

              allocator.ask[AllocationResponse](replyTo => 
                StartAllocation(allocationRequest, replyTo)
              )
            }

            onSuccess(futureResponse) {
              case AllocationStarted(allocId, runId) =>
                complete(StatusCodes.Accepted, AllocationHttpResponse(
                  allocationId = allocId,
                  status = "started",
                  message = s"Allocation run $runId started with ID $allocId"
                ))
              case AllocationFailed(allocId, reason) =>
                complete(StatusCodes.InternalServerError, ErrorResponse(
                  error = "AllocationFailed",
                  message = reason
                ))
              case _ =>
                complete(StatusCodes.InternalServerError, ErrorResponse(
                  error = "UnexpectedResponse",
                  message = "Unexpected response from allocator"
                ))
            }
          }
        }
      },
      // Routes with allocationId parameter
      pathPrefix(Segment) { allocationId =>
        concat(
          // GET /allocations/{id} - Get allocation result
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
                  complete(StatusCodes.NotFound, ErrorResponse(
                    error = "AllocationNotFound",
                    message = s"Allocation $id not found"
                  ))
                case AllocationFailed(id, reason) =>
                  complete(StatusCodes.OK, AllocationHttpResponse(
                    allocationId = id,
                    status = "failed",
                    message = reason
                  ))
                case _ =>
                  complete(StatusCodes.InternalServerError, ErrorResponse(
                    error = "UnexpectedResponse",
                    message = "Unexpected response from allocator"
                  ))
              }
            }
          },
          // GET /allocations/{id}/status - Get allocation status
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
                  complete(StatusCodes.NotFound, ErrorResponse(
                    error = "AllocationNotFound",
                    message = s"Allocation $id not found"
                  ))
                case AllocationFailed(id, reason) =>
                  complete(StatusCodes.OK, AllocationHttpResponse(
                    allocationId = id,
                    status = "failed",
                    message = reason
                  ))
                case _ =>
                  complete(StatusCodes.InternalServerError, ErrorResponse(
                    error = "UnexpectedResponse",
                    message = "Unexpected response from allocator"
                  ))
              }
            }
          }
        )
      }
    )
  }
}
