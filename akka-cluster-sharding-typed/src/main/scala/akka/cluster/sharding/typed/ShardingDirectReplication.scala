/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.persistence.typed.PublishedEvent
import akka.persistence.typed.ReplicaId

import akka.util.ccompat.JavaConverters._

/**
 * Used when sharding Replicated Event Sourced entities in multiple instances of sharding, for example one per DC in a Multi DC
 * Akka Cluster.
 *
 * This actor should be started once on each node where Replicated Event Sourced entities will run (the same nodes that you start
 * sharding on). The entities should be set up with [[akka.persistence.typed.scaladsl.EventSourcedBehavior.withEventPublishing]]
 * or [[akka.persistence.typed.javadsl.ReplicatedEventSourcedBehavior#withEventPublishing()]]
 * If using [[ReplicatedSharding]] the replication can be enabled through [[ReplicatedShardingSettings.withDirectReplication]]
 * instead of starting this actor manually.
 *
 * Subscribes to locally written events through the event stream and sends the seen events to all the sharded replicas
 * which can then fast forward their cross-replica event streams to improve latency while allowing less frequent poll
 * for the cross replica queries. Note that since message delivery is at-most-once this can not be the only
 * channel for replica events - the entities must still tail events from the journals of other replicas.
 *
 * The events are forwarded as [[akka.cluster.sharding.typed.ShardingEnvelope]] this will work out of the box both
 * by default and with a custom extractor since the envelopes are handled internally.
 */
@ApiMayChange
object ShardingDirectReplication {

  /**
   * Not for user extension
   */
  @DoNotInherit
  sealed trait Command

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] case class VerifyStarted(replyTo: ActorRef[Done]) extends Command

  private final case class WrappedPublishedEvent(publishedEvent: PublishedEvent) extends Command

  /**
   * Java API:
   * Factory for when the self replica id is unknown (or multiple)
   * @param replicaShardingProxies A replica id to sharding proxy mapping for each replica in the system
   */
  def create[T](replicaShardingProxies: java.util.Map[ReplicaId, ActorRef[T]]): Behavior[Command] =
    apply(None, replicaShardingProxies.asScala.toMap)

  /**
   * Java API:
   * @param selfReplica The replica id of the replica that runs on this node
   * @param replicaShardingProxies A replica id to sharding proxy mapping for each replica in the system
   */
  def create[T](
      selfReplica: ReplicaId,
      replicaShardingProxies: java.util.Map[ReplicaId, ActorRef[T]]): Behavior[Command] =
    apply(Some(selfReplica), replicaShardingProxies.asScala.toMap)

  /**
   * Scala API:
   * @param replicaShardingProxies A replica id to sharding proxy mapping for each replica in the system
   */
  def apply[T](replicaShardingProxies: Map[ReplicaId, ActorRef[T]]): Behavior[Command] =
    apply(None, replicaShardingProxies)

  /**
   * Scala API:
   * @param selfReplica The replica id of the replica that runs on this node
   * @param replicaShardingProxies A replica id to sharding proxy mapping for each replica in the system
   */
  def apply[T](selfReplica: ReplicaId, replicaShardingProxies: Map[ReplicaId, ActorRef[T]]): Behavior[Command] =
    apply(Some(selfReplica), replicaShardingProxies)

  private def apply[T](
      selfReplica: Option[ReplicaId],
      replicaShardingProxies: Map[ReplicaId, ActorRef[T]]): Behavior[Command] =
    Behaviors.setup[Command] { context =>
      context.log.debug(
        "Subscribing to event stream to forward events to [{}] sharded replicas",
        replicaShardingProxies.size - 1)
      val publishedEventAdapter = context.messageAdapter[PublishedEvent](WrappedPublishedEvent.apply)
      context.system.eventStream ! EventStream.Subscribe[PublishedEvent](publishedEventAdapter)

      Behaviors.receiveMessage {
        case WrappedPublishedEvent(event) =>
          context.log.trace(
            "Forwarding event for persistence id [{}] sequence nr [{}] to replicas",
            event.persistenceId,
            event.sequenceNumber)
          replicaShardingProxies.foreach {
            case (replica, proxy) =>
              val envelopedEvent = ShardingEnvelope(event.persistenceId.id, event)
              if (!selfReplica.contains(replica))
                proxy.asInstanceOf[ActorRef[ShardingEnvelope[PublishedEvent]]] ! envelopedEvent
          }
          Behaviors.same
        case VerifyStarted(replyTo) =>
          replyTo ! Done
          Behaviors.same
      }
    }

}
