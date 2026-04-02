package com.skyflow

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import org.slf4j.LoggerFactory

import com.skyflow.flight.infrastructure.actor.FlightRegistryActor
import com.skyflow.flight.`interface`.http.FlightRoutes

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

object Main {

  private val logger = LoggerFactory.getLogger(getClass)

  sealed trait Command
  case object Shutdown extends Command

  def main(args: Array[String]): Unit = {
    logger.info(
      "Starting SkyFlow - Flight Allocation System (DDD Architecture)"
    )

    val rootBehavior = Behaviors.setup[Command] { context =>
      // ── Flight Bounded Context ──
      val flightRegistry =
        context.spawn(FlightRegistryActor(), "flightRegistry")
      logger.info("FlightRegistry spawned at {}", flightRegistry.path)

      // ── HTTP Server Setup ──
      implicit val system: ActorSystem[_] = context.system
      implicit val executionContext: ExecutionContextExecutor =
        system.executionContext

      val flightRoutes = new FlightRoutes(flightRegistry)

      val routes = pathPrefix("api") {
        flightRoutes.routes
      }

      val interface_ = system.settings.config.getString("app.http.interface")
      val port = system.settings.config.getInt("app.http.port")

      val serverBinding = Http().newServerAt(interface_, port).bind(routes)

      serverBinding.onComplete {
        case Success(binding) =>
          val address = binding.localAddress
          logger.info(
            "HTTP Server online at http://{}:{}/",
            address.getHostString,
            address.getPort
          )
          logger.info(
            "POST   http://{}:{}/api/flights                  - Create new flight",
            address.getHostString,
            address.getPort
          )
          logger.info(
            "GET    http://{}:{}/api/flights                  - List all flights",
            address.getHostString,
            address.getPort
          )
          logger.info(
            "GET    http://{}:{}/api/flights/{{flightId}}     - Get specific flight",
            address.getHostString,
            address.getPort
          )
          logger.info("Press CTRL+C to stop the server.")

        case Failure(exception) =>
          logger.error("Failed to bind HTTP server!", exception)
          system.terminate()
      }

      Behaviors.receiveMessage[Command] { case Shutdown =>
        logger.info("Shutting down SkyFlow...")
        Behaviors.stopped
      }
    }

    val system = ActorSystem[Command](rootBehavior, "SkyFlow")

    sys.addShutdownHook {
      logger.info("Shutdown signal received...")
      system ! Shutdown
    }

    import scala.concurrent.Await
    import scala.concurrent.duration._
    Await.result(system.whenTerminated, Duration.Inf)
  }
}
