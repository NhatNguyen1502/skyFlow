package com.akka.learning

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import com.akka.learning.actors.{AllocationActor, FlightRegistry}
import com.akka.learning.http.{AllocationRoutes, FlightRoutes}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

object Main {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  sealed trait Command
  case object Shutdown extends Command
  
  def main(args: Array[String]): Unit = {
    logger.info("Starting SkyFlow - Flight Allocation System")
    
    // Create the root behavior that spawns all actors
    val rootBehavior = Behaviors.setup[Command] { context =>
      
      // Spawn FlightRegistry
      val flightRegistry = context.spawn(FlightRegistry(), "flightRegistry")
      logger.info("FlightRegistry spawned at {}", flightRegistry.path)
      
      // Spawn AllocationActor
      val allocator = context.spawn(AllocationActor(flightRegistry), "allocator")
      logger.info("AllocationActor spawned at {}", allocator.path)
      
      // Setup HTTP server
      implicit val system: ActorSystem[_] = context.system
      implicit val executionContext: ExecutionContextExecutor = system.executionContext
      
      // Create routes
      val flightRoutes = new FlightRoutes(flightRegistry)
      val allocationRoutes = new AllocationRoutes(allocator, flightRegistry)
      
      // Combine all routes
      val routes = pathPrefix("api") {
        concat(
          flightRoutes.routes,
          allocationRoutes.routes
        )
      }
      
      // Read configuration
      val interface = system.settings.config.getString("app.http.interface")
      val port = system.settings.config.getInt("app.http.port")
      
      // Start HTTP server
      val serverBinding = Http().newServerAt(interface, port).bind(routes)
      
      serverBinding.onComplete {
        case Success(binding) =>
          val address = binding.localAddress
          logger.info("HTTP Server online at http://{}:{}/", address.getHostString, address.getPort)
          logger.info("    POST   http://{}:{}/api/flights                  - Create new flight", address.getHostString, address.getPort)
          logger.info("    GET    http://{}:{}/api/flights                  - List all flights", address.getHostString, address.getPort)
          logger.info("    GET    http://{}:{}/api/flights/{flightId}       - Get specific flight", address.getHostString, address.getPort)
          logger.info("    PUT    http://{}:{}/api/flights/{flightId}/seats - Update available seats", address.getHostString, address.getPort)
          logger.info("    PUT    http://{}:{}/api/flights/{flightId}/status - Update flight status", address.getHostString, address.getPort)
          logger.info("    DELETE http://{}:{}/api/flights/{flightId}       - Cancel flight", address.getHostString, address.getPort)
          logger.info("    POST   http://{}:{}/api/allocations              - Start allocation run", address.getHostString, address.getPort)
          logger.info("    GET    http://{}:{}/api/allocations/{id}         - Get allocation result", address.getHostString, address.getPort)
          logger.info("    GET    http://{}:{}/api/allocations/{id}/status  - Get allocation status", address.getHostString, address.getPort)
          logger.info("   Press CTRL+C to stop the server.\n")
          
        case Failure(exception) =>
          logger.error("Failed to bind HTTP server!", exception)
          system.terminate()
      }
      
      // Keep the root actor alive and handle shutdown
      Behaviors.receiveMessage[Command] {
        case Shutdown =>
          logger.info("Shutting down SkyFlow...")
          Behaviors.stopped
      }
    }
    
    // Start the actor system
    val system = ActorSystem[Command](rootBehavior, "SkyFlow")
    
    // Add shutdown hook
    sys.addShutdownHook {
      logger.info("\nShutdown signal received...")
      system ! Shutdown
    }
    
    // Block main thread until system terminates
    import scala.concurrent.Await
    import scala.concurrent.duration._
    Await.result(system.whenTerminated, Duration.Inf)
  }
}
