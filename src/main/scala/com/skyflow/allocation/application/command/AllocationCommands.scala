package com.skyflow.allocation.application.command

import akka.actor.typed.ActorRef
import com.skyflow.allocation.domain.model.{AllocationRequest, AllocationResult}
import com.skyflow.shared.infrastructure.serialization.CborSerializable

/**
 * AllocationCommand — Application-layer commands for AllocationActor.
 */
sealed trait AllocationCommand extends CborSerializable

case class StartAllocation(
  request: AllocationRequest,
  replyTo: ActorRef[AllocationResponse]
) extends AllocationCommand

case class GetAllocationStatus(
  allocationId: String,
  replyTo: ActorRef[AllocationResponse]
) extends AllocationCommand

case class GetAllocationResult(
  allocationId: String,
  replyTo: ActorRef[AllocationResponse]
) extends AllocationCommand

// ── Internal wrapper commands ──

private[skyflow] case class WrappedAllocationComplete(
  allocationId: String,
  result: AllocationResult,
  replyTo: ActorRef[AllocationResponse]
) extends AllocationCommand

private[skyflow] case class WrappedAllocationFailed(
  allocationId: String,
  reason: String,
  replyTo: ActorRef[AllocationResponse]
) extends AllocationCommand

/**
 * Responses from AllocationActor.
 */
sealed trait AllocationResponse extends CborSerializable

case class AllocationStarted(allocationId: String, runId: String) extends AllocationResponse
case class AllocationComplete(result: AllocationResult) extends AllocationResponse
case class AllocationInProgress(allocationId: String, progress: Double) extends AllocationResponse
case class AllocationNotFound(allocationId: String) extends AllocationResponse
case class AllocationFailed(allocationId: String, reason: String) extends AllocationResponse
