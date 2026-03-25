package com.skyflow.allocation.domain.service

import com.skyflow.allocation.domain.model._

/**
 * AllocationDomainService — Pure domain service for seat allocation algorithm.
 *
 * Contains the core allocation business logic with NO infrastructure dependencies.
 * This is a pure function: given demands and available flights, produce an allocation result.
 */
trait AllocationDomainService {
  /**
   * Allocate seats across available flights for the given O&D demands.
   *
   * @param demands List of (ODPair, demand count) pairs
   * @param availableFlights Map of flightId -> (originCode, destCode, availableSeats)
   * @param priority Allocation priority level
   * @return Tuple of (successful allocations, unallocated demands)
   */
  def allocate(
    demands: List[(ODPair, Int)],
    availableFlights: List[FlightCapacity],
    priority: Priority
  ): (List[Allocation], List[(ODPair, Int)])
}

/**
 * Simplified view of a flight's capacity from the allocation perspective.
 * This is the anti-corruption layer — Allocation context doesn't depend on Flight aggregate.
 */
case class FlightCapacity(
  flightId: String,
  originCode: String,
  destinationCode: String,
  availableSeats: Int
)

/**
 * Default implementation of the allocation algorithm.
 * Phase 2: simplified greedy allocation. Will be replaced with stream processing in Phase 4.
 */
class DefaultAllocationDomainService extends AllocationDomainService {

  override def allocate(
    demands: List[(ODPair, Int)],
    availableFlights: List[FlightCapacity],
    priority: Priority
  ): (List[Allocation], List[(ODPair, Int)]) = {

    val allocations = scala.collection.mutable.ListBuffer[Allocation]()
    val unallocated = scala.collection.mutable.ListBuffer[(ODPair, Int)]()
    // Track remaining capacity as we allocate
    val remainingCapacity = scala.collection.mutable.Map[String, Int]()
    availableFlights.foreach(f => remainingCapacity(f.flightId) = f.availableSeats)

    demands.foreach { case (odPair, demand) =>
      // Find flights matching this O&D pair with remaining capacity
      val matchingFlights = availableFlights.filter { f =>
        f.originCode == odPair.origin.value &&
        f.destinationCode == odPair.destination.value &&
        remainingCapacity.getOrElse(f.flightId, 0) > 0
      }.sortBy(f => -remainingCapacity.getOrElse(f.flightId, 0)) // largest capacity first

      var remainingDemand = demand
      matchingFlights.foreach { flight =>
        if (remainingDemand > 0) {
          val canAllocate = math.min(remainingDemand, remainingCapacity.getOrElse(flight.flightId, 0))
          if (canAllocate > 0) {
            allocations += Allocation(odPair, flight.flightId, canAllocate)
            remainingCapacity(flight.flightId) = remainingCapacity(flight.flightId) - canAllocate
            remainingDemand -= canAllocate
          }
        }
      }

      if (remainingDemand > 0) {
        unallocated += ((odPair, remainingDemand))
      }
    }

    (allocations.toList, unallocated.toList)
  }
}
