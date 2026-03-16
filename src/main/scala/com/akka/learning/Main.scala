package com.akka.learning

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import com.akka.learning.utils.CorrelationId
import com.akka.learning.models.infrastructure.CorrelationContext

import scala.io.StdIn

/**
 * Main application entry point
 * This demonstrates basic Akka Typed setup
 */
object Main {
  
  private val logger = LoggerFactory.getLogger(Main.getClass)

  // Root guardian actor
  sealed trait RootCommand
  private case object Start extends RootCommand

  def main(args: Array[String]): Unit = {
    // Load configuration
    val config = ConfigFactory.load()
    
    // Generate correlation ID for startup
    val correlationContext = CorrelationContext(
      correlationId = CorrelationId.generate()
    )
    
    CorrelationId.setContext(correlationContext)
    
    logger.info("=" * 60)
    logger.info("Starting SkyFlow - Flight Operations System")
    logger.info("=" * 60)
    logger.info("Scala Version: {}", util.Properties.versionString)
    logger.info("Configuration loaded from: application.conf")
    logger.info("=" * 60)

    // Create the actor system with root guardian
    val system: ActorSystem[RootCommand] = ActorSystem(
      Behaviors.setup[RootCommand] { context =>
        logger.info("SkyFlow Actor System initialized")
        logger.info("Actor system started: {}", context.system.name)
        
        // Create a simple test actor to verify setup
        createTestActor(context)
        
        Behaviors.receiveMessage { _ =>
          Behaviors.same
        }
      },
      "SkyFlowSystem",
      config
    )

    try {
      logger.info("System is running. Press ENTER to terminate.")
      logger.info("=" * 60)
      
      // Wait for user input
      val _ = StdIn.readLine()
      
    } finally {
      logger.info("=" * 60)
      logger.info("Shutting down actor system...")
      system.terminate()
      logger.info("System shutdown complete")
      logger.info("=" * 60)
    }
  }

  /**
   * Create a simple test actor to verify Akka Typed setup
   */
  private def createTestActor(context: akka.actor.typed.scaladsl.ActorContext[RootCommand]): Unit = {
    logger.info("Creating test actor to verify setup...")
    
    // Define a simple protocol
    sealed trait TestCommand
    case class SayHello(name: String) extends TestCommand
    case object GetStatus extends TestCommand
    
    // Define test actor behavior
    val testBehavior = Behaviors.setup[TestCommand] { testContext =>
      logger.info("Test actor '{}' created successfully!", testContext.self.path.name)
      
      Behaviors.receiveMessage {
        case SayHello(name) =>
          logger.info("Hello from Akka Typed, {}!", name)
          Behaviors.same
          
        case GetStatus =>
          logger.info("Test actor is running and ready!")
          Behaviors.same
      }
    }
    
    // Spawn the test actor
    val testActor = context.spawn(testBehavior, "test-actor")
    
    // Send test messages
    testActor ! SayHello("Flight Operations Team")
    testActor ! GetStatus
    
    logger.info("Test actor verification complete!")
  }
}
