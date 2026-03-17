package com.akka.learning.models.commands

import akka.actor.typed.ActorRef
import com.akka.learning.models.CborSerializable
import com.akka.learning.models.domain.{AllocationRequest, AllocationResult}

/**
 * Allocation Actor Commands & Responses
 * 
 * Command messages for the AllocationActor that coordinates
 * batch allocation runs across multiple flights.
 */

/**
 * Base trait for Allocation Actor commands
 */
sealed trait AllocationCommand extends CborSerializable

/**
 * Internal commands (used by actor implementation only, not exposed to external API)
 */
private[learning] case class WrappedAllocationComplete(
  allocationId: String,
  result: AllocationResult,
  replyTo: ActorRef[AllocationResponse]
) extends AllocationCommand

private[learning] case class WrappedAllocationFailed(
  allocationId: String,
  reason: String,
  replyTo: ActorRef[AllocationResponse]
) extends AllocationCommand
 
/**
 * Start an allocation run
 */
case class StartAllocation(
  request: AllocationRequest,
  replyTo: ActorRef[AllocationResponse]
) extends AllocationCommand

/**
 * Get allocation status
 */
case class GetAllocationStatus(
  allocationId: String,
  replyTo: ActorRef[AllocationResponse]
) extends AllocationCommand

/**
 * Check if allocation is complete
 */
case class GetAllocationResult(
  allocationId: String,
  replyTo: ActorRef[AllocationResponse]
) extends AllocationCommand

/**
 * Responses from Allocation Actor
 */
sealed trait AllocationResponse extends CborSerializable

case class AllocationStarted(allocationId: String, runId: String) extends AllocationResponse
case class AllocationComplete(result: AllocationResult) extends AllocationResponse
case class AllocationInProgress(allocationId: String, progress: Double) extends AllocationResponse
case class AllocationNotFound(allocationId: String) extends AllocationResponse
case class AllocationFailed(allocationId: String, reason: String) extends AllocationResponse
