package com.akka.learning.models

/**
 * Marker trait for CBOR serialization in Akka Persistence
 * 
 * All commands, events, and responses that need to be serialized
 * by Akka Persistence should extend this trait.
 */
trait CborSerializable
