/*
 * Copyright 2015 - 2016 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rbmhtechnology.eventuate

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.{ Function => JFunction }
import java.util.{ Set => JSet }

import akka.actor._
import akka.event.Logging
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import com.rbmhtechnology.eventuate.EndpointFilters.NoFilters
import com.rbmhtechnology.eventuate.EventsourcingProtocol.{ Delete, DeleteFailure, DeleteSuccess }
import com.rbmhtechnology.eventuate.ReplicationFilter.NoFilter
import com.rbmhtechnology.eventuate.ReplicationProtocol.{ ReplicationEndpointInfo, _ }
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration._

class ReplicationSettings(config: Config) {
  val writeBatchSize: Int =
    config.getInt("eventuate.log.write-batch-size")

  val writeTimeout: FiniteDuration =
    config.getDuration("eventuate.log.write-timeout", TimeUnit.MILLISECONDS).millis

  val readTimeout: FiniteDuration =
    config.getDuration("eventuate.log.read-timeout", TimeUnit.MILLISECONDS).millis

  val remoteReadTimeout: FiniteDuration =
    config.getDuration("eventuate.log.replication.remote-read-timeout", TimeUnit.MILLISECONDS).millis

  val remoteScanLimit: Int =
    config.getInt("eventuate.log.replication.remote-scan-limit")

  val retryDelay: FiniteDuration =
    config.getDuration("eventuate.log.replication.retry-delay", TimeUnit.MILLISECONDS).millis

  val failureDetectionLimit: FiniteDuration =
    config.getDuration("eventuate.log.replication.failure-detection-limit", TimeUnit.MILLISECONDS).millis

  require(failureDetectionLimit >= remoteReadTimeout + retryDelay, s"""
     |eventuate.log.replication.failure-detection-limit ($failureDetectionLimit) must be at least the sum of
     |eventuate.log.replication.retry-delay ($retryDelay) and
     |eventuate.log.replication.remote-read-timeout ($remoteReadTimeout)
   """.stripMargin)
}

object ReplicationEndpoint {
  /**
   * Default log name.
   */
  val DefaultLogName: String = "default"

  /**
   * Default application name.
   */
  val DefaultApplicationName: String = "default"

  /**
   * Default application version.
   */
  val DefaultApplicationVersion: ApplicationVersion = ApplicationVersion()

  /**
   * Published to the actor system's event stream if a remote log is available.
   */
  case class Available(endpointId: String, logName: String)

  /**
   * Published to the actor system's event stream if a remote log is unavailable.
   */
  case class Unavailable(endpointId: String, logName: String, causes: Seq[Throwable])

  /**
   * Matches a string of format "<hostname>:<port>".
   */
  private object Address {
    def unapply(s: String): Option[(String, Int)] = {
      val hp = s.split(":")
      Some((hp(0), hp(1).toInt))
    }
  }

  /**
   * Creates a [[ReplicationEndpoint]] with a single event log with name [[DefaultLogName]]. The
   * replication endpoint id and replication connections must be configured as follows in `application.conf`:
   *
   * {{{
   *   eventuate.endpoint.id = "endpoint-id"
   *   eventuate.endpoint.connections = ["host-1:port-1", "host-2:port-2", ..., "host-n:port-n"]
   * }}}
   *
   * Optionally, the `applicationName` and `applicationVersion` of a replication endpoint can be
   * configured with e.g.
   *
   * {{{
   *   eventuate.endpoint.application.name = "my-app"
   *   eventuate.endpoint.application.version = "1.2"
   * }}}
   *
   * @param logFactory Factory of log actor `Props`. The `String` parameter of the factory is a unique
   *                   log id generated by this endpoint. The log actor must be assigned this log id.
   */
  def apply(logFactory: String => Props)(implicit system: ActorSystem): ReplicationEndpoint = {
    val config = system.settings.config
    val connections = config.getStringList("eventuate.endpoint.connections").asScala.toSet[String].map {
      case Address(host, port) => ReplicationConnection(host, port)
    }
    apply(logFactory, connections)
  }

  /**
   * Creates a [[ReplicationEndpoint]] with a single event log with name [[DefaultLogName]]. The
   * replication endpoint id must be configured as follows in `application.conf`:
   *
   * {{{
   *   eventuate.endpoint.id = "endpoint-id"
   * }}}
   *
   * Optionally, the `applicationName` and `applicationVersion` of a replication endpoint can be
   * configured with e.g.
   *
   * {{{
   *   eventuate.endpoint.application.name = "my-app"
   *   eventuate.endpoint.application.version = "1.2"
   * }}}
   *
   * @param logFactory Factory of log actor `Props`. The `String` parameter of the factory is a unique
   *                   log id generated by this endpoint. The log actor must be assigned this log id.
   * @param connections Replication connections to other replication endpoints.
   */
  def apply(logFactory: String => Props, connections: Set[ReplicationConnection])(implicit system: ActorSystem): ReplicationEndpoint = {
    val config = system.settings.config
    val endpointId = config.getString("eventuate.endpoint.id")

    val applicationName =
      if (config.hasPath("eventuate.endpoint.application.name")) config.getString("eventuate.endpoint.application.name")
      else DefaultApplicationName

    val applicationVersion =
      if (config.hasPath("eventuate.endpoint.application.version")) ApplicationVersion(config.getString("eventuate.endpoint.application.version"))
      else DefaultApplicationVersion

    new ReplicationEndpoint(endpointId, Set(ReplicationEndpoint.DefaultLogName), logFactory, connections, applicationName = applicationName, applicationVersion = applicationVersion)(system)
  }

  /**
   * Java API that creates a [[ReplicationEndpoint]].
   *
   * Creates a [[ReplicationEndpoint]] with a single event log with name [[DefaultLogName]]. The
   * replication endpoint id and replication connections must be configured as follows in `application.conf`:
   *
   * {{{
   *   eventuate.endpoint.id = "endpoint-id"
   *   eventuate.endpoint.connections = ["host-1:port-1", "host-2:port-2", ..., "host-n:port-n"]
   * }}}
   *
   * Optionally, the `applicationName` and `applicationVersion` of a replication endpoint can be
   * configured with e.g.
   *
   * {{{
   *   eventuate.endpoint.application.name = "my-app"
   *   eventuate.endpoint.application.version = "1.2"
   * }}}
   *
   * @param logFactory Factory of log actor `Props`. The `String` parameter of the factory is a unique
   *                   log id generated by this endpoint. The log actor must be assigned this log id.
   */
  def create(logFactory: JFunction[String, Props], system: ActorSystem) =
    apply(id => logFactory.apply(id))(system)

  /**
   * Java API that creates a [[ReplicationEndpoint]].
   *
   * Creates a [[ReplicationEndpoint]] with a single event log with name [[DefaultLogName]]. The
   * replication endpoint id must be configured as follows in `application.conf`:
   *
   * {{{
   *   eventuate.endpoint.id = "endpoint-id"
   * }}}
   *
   * Optionally, the `applicationName` and `applicationVersion` of a replication endpoint can be
   * configured with e.g.
   *
   * {{{
   *   eventuate.endpoint.application.name = "my-app"
   *   eventuate.endpoint.application.version = "1.2"
   * }}}
   *
   * @param logFactory Factory of log actor `Props`. The `String` parameter of the factory is a unique
   *                   log id generated by this endpoint. The log actor must be assigned this log id.
   * @param connections Replication connections to other replication endpoints.
   */
  def create(logFactory: JFunction[String, Props], connections: JSet[ReplicationConnection], system: ActorSystem) =
    apply(id => logFactory.apply(id), connections.asScala.toSet)(system)

  /**
   * Java API that creates a [[ReplicationEndpoint]].
   *
   * @param id Unique replication endpoint id.
   * @param logNames Names of the event logs managed by this replication endpoint.
   * @param logFactory Factory of log actor `Props`. The `String` parameter of the factory is a unique
   *                   log id generated by this endpoint. The log actor must be assigned this log id.
   * @param connections Replication connections to other replication endpoints.
   * @param endpointFilters Replication filters applied to incoming replication read requests
   * @param applicationName Name of the application that creates this replication endpoint.
   * @param applicationVersion Version of the application that creates this replication endpoint.
   */
  def create(id: String,
    logNames: JSet[String],
    logFactory: JFunction[String, Props],
    connections: JSet[ReplicationConnection],
    endpointFilters: EndpointFilters,
    applicationName: String,
    applicationVersion: ApplicationVersion,
    system: ActorSystem): ReplicationEndpoint =
    new ReplicationEndpoint(id, logNames.asScala.toSet, id => logFactory.apply(id), connections.asScala.toSet, endpointFilters, applicationName, applicationVersion)(system)
}

/**
 * A replication endpoint connects to other replication endpoints for replicating events. Events are
 * replicated from the connected endpoints to this endpoint. The connected endpoints are ''replication
 * sources'', this endpoint is a ''replication target''. To setup bi-directional replication, the other
 * replication endpoints must additionally setup replication connections to this endpoint.
 *
 * A replication endpoint manages one or more event logs. Event logs are indexed by name. Events are
 * replicated only between event logs with matching names.
 *
 * If `applicationName` equals that of a replication source, events are only replicated if `applicationVersion`
 * is greater than or equal to that of the replication source. This is a simple mechanism to support
 * incremental version upgrades of replicated applications where each replica can be upgraded individually
 * without shutting down other replicas. This avoids permanent state divergence during upgrade which may
 * occur if events are replicated from replicas with higher version to those with lower version. If
 * `applicationName` does not equal that of a replication source, events are always replicated, regardless
 * of the `applicationVersion` value.
 *
 * @param id Unique replication endpoint id.
 * @param logNames Names of the event logs managed by this replication endpoint.
 * @param logFactory Factory of log actor `Props`. The `String` parameter of the factory is a unique
 *                   log id generated by this endpoint. The log actor must be assigned this log id.
 * @param connections Replication connections to other replication endpoints.
 * @param endpointFilters Replication filters applied to incoming replication read requests
 * @param applicationName Name of the application that creates this replication endpoint.
 * @param applicationVersion Version of the application that creates this replication endpoint.
 */
class ReplicationEndpoint(
  val id: String,
  val logNames: Set[String],
  val logFactory: String => Props,
  val connections: Set[ReplicationConnection],
  val endpointFilters: EndpointFilters = NoFilters,
  val applicationName: String = ReplicationEndpoint.DefaultApplicationName,
  val applicationVersion: ApplicationVersion = ReplicationEndpoint.DefaultApplicationVersion)(implicit val system: ActorSystem) {

  import Acceptor._

  private val active: AtomicBoolean =
    new AtomicBoolean(false)

  /**
   * The actor system's replication settings.
   */
  val settings =
    new ReplicationSettings(system.settings.config)

  /**
   * The log actors managed by this endpoint, indexed by their name.
   */
  val logs: Map[String, ActorRef] =
    logNames.map(logName => logName -> system.actorOf(logFactory(logId(logName)), logId(logName))).toMap

  private[eventuate] val connectors: Set[SourceConnector] =
    connections.map(new SourceConnector(this, _))

  // lazy to make sure concurrently running (created actors) do not access null-reference
  // https://github.com/RBMHTechnology/eventuate/issues/183
  private[eventuate] lazy val acceptor: ActorRef =
    system.actorOf(Props(new Acceptor(this)), name = Acceptor.Name)
  acceptor // make sure acceptor is started

  /**
   * Returns the unique log id for given `logName`.
   */
  def logId(logName: String): String =
    ReplicationEndpointInfo.logId(id, logName)

  /**
   * Runs an asynchronous disaster recovery procedure. This procedure recovers this endpoint in case of total or
   * partial event loss. Partial event loss means event loss from a given sequence number upwards (for example,
   * after having installed a storage backup). Recovery copies events from directly connected remote endpoints back
   * to this endpoint and automatically removes invalid snapshots. A snapshot is invalid if it covers events that
   * have been lost.
   *
   * This procedure requires that event replication between this and directly connected endpoints is bi-directional
   * and that these endpoints are available during recovery. After successful recovery the endpoint is automatically
   * activated. A failed recovery completes with a [[RecoveryException]] and must be retried. Activating this endpoint
   * without having successfully recovered from partial or total event loss may result in inconsistent replica states.
   *
   * Running a recovery on an endpoint that didn't loose events has no effect but may still fail due to unavailable
   * replication partners, for example. In this case, a recovery retry can be omitted if the `partialUpdate` field
   * of [[RecoveryException]] is set to `false`.
   */
  def recover(): Future[Unit] = {
    if (connections.isEmpty)
      Future.failed(new IllegalStateException("Recover an endpoint without connections"))
    else if (active.compareAndSet(false, true)) {
      import system.dispatcher

      val recovery = new Recovery(this)

      def recoveryFailure[U](partialUpdate: Boolean): PartialFunction[Throwable, Future[U]] = {
        case t => Future.failed(new RecoveryException(t, partialUpdate))
      }

      // Disaster recovery is executed in 3 steps:
      // 1. synchronize metadata to
      //    - reset replication progress of remote sites and
      //    - determine after disaster progress of remote sites
      // 2. Recover events from unfiltered links
      // 3. Recover events from filtered links
      // 4. Adjust the sequence numbers of local logs to their version vectors
      // unfiltered links are recovered first to ensure that no events are recovered from a filtered connection
      // where the causal predecessor is not yet recovered (from an unfiltered connection)
      // as causal predecessors cannot be written after their successors to the event log.
      // The sequence number of an event log needs to be adjusted if not all events could be
      // recovered as otherwise it could be less then the corresponding entriy in the
      // log's version vector
      for {
        localEndpointInfo <- recovery.readEndpointInfo.recoverWith(recoveryFailure(partialUpdate = false))
        _ = logLocalState(localEndpointInfo)
        recoveryLinks <- recovery.synchronizeReplicationProgressesWithRemote(localEndpointInfo).recoverWith(recoveryFailure(partialUpdate = false))
        unfilteredLinks = recoveryLinks.filterNot(recovery.isFilteredLink)
        _ = logLinksToBeRecovered(unfilteredLinks, "unfiltered")
        _ <- recovery.recoverLinks(unfilteredLinks).recoverWith(recoveryFailure(partialUpdate = true))
        filteredLinks = recoveryLinks.filter(recovery.isFilteredLink)
        _ = logLinksToBeRecovered(filteredLinks, "filtered")
        _ <- recovery.recoverLinks(filteredLinks).recoverWith(recoveryFailure(partialUpdate = true))
        _ <- recovery.adjustEventLogClocks.recoverWith(recoveryFailure(partialUpdate = true))
      } yield acceptor ! RecoveryCompleted
    } else Future.failed(new IllegalStateException("Recovery running or endpoint already activated"))
  }

  private def logLocalState(info: ReplicationEndpointInfo): Unit = {
    system.log.info("Disaster recovery initiated for endpoint {}. Sequence numbers of local logs are: {}",
      info.endpointId, sequenceNrsLogString(info))
    system.log.info("Need to reset replication progress stored at remote replicas {}",
      connectors.map(_.remoteAcceptor).mkString(","))
  }

  private def logLinksToBeRecovered(links: Set[RecoveryLink], linkType: String): Unit = {
    system.log.info("Start recovery for {} links: (from remote source log (target seq no) -> local target log (initial seq no))\n{}",
      linkType, links.map(l => s"(${l.replicationLink.source.logId} (${l.remoteSequenceNr}) -> ${l.replicationLink.target.logName} (${l.localSequenceNr}))").mkString(", "))
  }

  private def sequenceNrsLogString(info: ReplicationEndpointInfo): String =
    info.logSequenceNrs.map { case (logName, sequenceNr) => s"$logName:$sequenceNr" } mkString ","

  /**
   * Delete events from a local log identified by `logName` with a sequence number less than or equal to
   * `toSequenceNr`. Deletion is split into logical deletion and physical deletion. Logical deletion is
   * supported by any storage backend and ensures that deleted events are not replayed any more. It has
   * immediate effect. Logically deleted events can still be replicated to remote [[ReplicationEndpoint]]s.
   * They are only physically deleted if the storage backend supports that (currently LevelDB only). Furthermore,
   * physical deletion only starts after all remote replication endpoints identified by `remoteEndpointIds`
   * have successfully replicated these events. Physical deletion is implemented as reliable background
   * process that survives event log restarts.
   *
   * Use with care! When events are physically deleted they cannot be replicated any more to new replication
   * endpoints (i.e. those that were unknown at the time of deletion). Also, a location with deleted events
   * may not be suitable any more for disaster recovery of other locations.
   *
   * @param logName Events are deleted from the local log with this name.
   * @param toSequenceNr Sequence number up to which events shall be deleted (inclusive).
   * @param remoteEndpointIds A set of remote [[ReplicationEndpoint]] ids that must have replicated events
   *                          to their logs before they are allowed to be physically deleted at this endpoint.
   * @return The sequence number up to which events have been logically deleted. When the returned `Future`
   *         completes logical deletion is effective. The returned sequence number can differ from the requested
   *         one, if:
   *
   *         - the log's current sequence number is smaller than the requested number. In this case the current
   *          sequence number is returned.
   *         - there was a previous successful deletion request with a higher sequence number. In this case that
   *          number is returned.
   */
  def delete(logName: String, toSequenceNr: Long, remoteEndpointIds: Set[String]): Future[Long] = {
    import system.dispatcher
    implicit val timeout = Timeout(settings.writeTimeout)
    (logs(logName) ? Delete(toSequenceNr, remoteEndpointIds.map(ReplicationEndpointInfo.logId(_, logName)))).flatMap {
      case DeleteSuccess(deletedTo) => Future.successful(deletedTo)
      case DeleteFailure(ex)        => Future.failed(ex)
    }
  }

  /**
   * Activates this endpoint by starting event replication from remote endpoints to this endpoint.
   */
  def activate(): Unit = if (active.compareAndSet(false, true)) {
    acceptor ! Process
    connectors.foreach(_.activate(replicationLinks = None))
  } else throw new IllegalStateException("Recovery running or endpoint already activated")

  /**
   * Creates [[ReplicationTarget]] for given `logName`.
   */
  private[eventuate] def target(logName: String): ReplicationTarget =
    ReplicationTarget(this, logName, logId(logName), logs(logName))

  /**
   * Returns all log names this endpoint and `endpointInfo` have in common.
   */
  private[eventuate] def commonLogNames(endpointInfo: ReplicationEndpointInfo) =
    this.logNames.intersect(endpointInfo.logNames)
}

/**
 * [[EndpointFilters]] computes a [[ReplicationFilter]] that shall be applied to a
 * replication read request that replicates from a source log (defined by ``sourceLogName``)
 * to a target log (defined by ``targetLogId``).
 */
trait EndpointFilters {
  def filterFor(targetLogId: String, sourceLogName: String): ReplicationFilter
}

object EndpointFilters {
  private class CombiningEndpointFilters(
    targetFilters: Map[String, ReplicationFilter],
    sourceFilters: Map[String, ReplicationFilter],
    targetSourceCombinator: (ReplicationFilter, ReplicationFilter) => ReplicationFilter) extends EndpointFilters {

    override def filterFor(targetLogId: String, sourceLogName: String): ReplicationFilter = {
      (targetFilters.get(targetLogId), sourceFilters.get(sourceLogName)) match {
        case (None, Some(sourceFilter))               => sourceFilter
        case (Some(targetFilter), None)               => targetFilter
        case (None, None)                             => NoFilter
        case (Some(targetFilter), Some(sourceFilter)) => targetSourceCombinator(targetFilter, sourceFilter)
      }
    }
  }

  /**
   * Creates an [[EndpointFilters]] instance that computes a [[ReplicationFilter]] for a replication read request
   * from a source log to a target log by and-combining target and source filters when given in the provided [[Map]]s.
   * If only source or target filter is given that is returned. If no filter is given [[com.rbmhtechnology.eventuate.ReplicationFilter.NoFilter]] is returned.
   * A typical use case is that target specific filters are and-combined with a (default) source filter.
   *
   * @param targetFilters maps target log ids to the [[ReplicationFilter]] that shall be applied when replicating from a source log to this target log
   * @param sourceFilters maps source log names to the [[ReplicationFilter]] that shall be applied when replicating from this source log to any target log
   */
  def targetAndSourceFilters(targetFilters: Map[String, ReplicationFilter], sourceFilters: Map[String, ReplicationFilter]): EndpointFilters =
    new CombiningEndpointFilters(targetFilters, sourceFilters, _ and _)

  /**
   * Creates an [[EndpointFilters]] instance that computes a [[ReplicationFilter]] for a replication read request
   * from a source log to a target log by returning a target filter when given in `targetFilters`. If only
   * a source filter is given in `sourceFilters` that is returned otherwise [[com.rbmhtechnology.eventuate.ReplicationFilter.NoFilter]] is returned.
   * A typical use case is that (more privileged) remote targets may replace a (default) source filter with a target-specific filter.
   *
   * @param targetFilters maps target log ids to the [[ReplicationFilter]] that shall be applied when replicating from a source log to this target log
   * @param sourceFilters maps source log names to the [[ReplicationFilter]] that shall be applied when replicating from this source log to any target log
   */
  def targetOverwritesSourceFilters(targetFilters: Map[String, ReplicationFilter], sourceFilters: Map[String, ReplicationFilter]): EndpointFilters =
    new CombiningEndpointFilters(targetFilters, sourceFilters, _ leftIdentity _)

  /**
   * Creates an [[EndpointFilters]] instance that computes a [[ReplicationFilter]] for a replication read request
   * from a source log to any target log by returning the source filter when given in `sourceFilters` or
   * [[com.rbmhtechnology.eventuate.ReplicationFilter.NoFilter]] otherwise.
   *
   * @param sourceFilters maps source log names to the [[ReplicationFilter]] that shall be applied when replicating from this source log to any target log
   */
  def sourceFilters(sourceFilters: Map[String, ReplicationFilter]): EndpointFilters = new EndpointFilters {
    override def filterFor(targetLogId: String, sourceLogName: String): ReplicationFilter =
      sourceFilters.getOrElse(sourceLogName, NoFilter)
  }

  /**
   * Creates an [[EndpointFilters]] instance that computes a [[ReplicationFilter]] for a replication read request
   * to a target log by returning the target filter when given in `targetFilters` or
   * [[com.rbmhtechnology.eventuate.ReplicationFilter.NoFilter]] otherwise.
   *
   * @param targetFilters maps target log ids to the [[ReplicationFilter]] that shall be applied when replicating from a source log to this target log
   */
  def targetFilters(targetFilters: Map[String, ReplicationFilter]): EndpointFilters = new EndpointFilters {
    override def filterFor(targetLogId: String, sourceLogName: String): ReplicationFilter =
      targetFilters.getOrElse(targetLogId, NoFilter)
  }

  /**
   * An [[EndpointFilters]] instance that always returns [[com.rbmhtechnology.eventuate.ReplicationFilter.NoFilter]]
   * independent from source/target logs of the replication read request.
   */
  val NoFilters: EndpointFilters = new EndpointFilters {
    override def filterFor(targetLogId: String, sourceLogName: String): ReplicationFilter = NoFilter
  }
}

/**
 * References a remote event log at a source [[ReplicationEndpoint]].
 */
private case class ReplicationSource(
  endpointId: String,
  logName: String,
  logId: String,
  acceptor: ActorSelection)

/**
 * References a local event log at a target [[ReplicationEndpoint]].
 */
private case class ReplicationTarget(
  endpoint: ReplicationEndpoint,
  logName: String,
  logId: String,
  log: ActorRef) {
}

/**
 * Represents an unidirectional replication link between a `source` and a `target`.
 */
private case class ReplicationLink(
  source: ReplicationSource,
  target: ReplicationTarget)

private class SourceConnector(val targetEndpoint: ReplicationEndpoint, val connection: ReplicationConnection) {
  def links(sourceInfo: ReplicationEndpointInfo): Set[ReplicationLink] =
    targetEndpoint.commonLogNames(sourceInfo).map { logName =>
      val sourceLogId = sourceInfo.logId(logName)
      val source = ReplicationSource(sourceInfo.endpointId, logName, sourceLogId, remoteAcceptor)
      ReplicationLink(source, targetEndpoint.target(logName))
    }

  def activate(replicationLinks: Option[Set[ReplicationLink]]): Unit =
    targetEndpoint.system.actorOf(Props(new Connector(this, replicationLinks.map(_.filter(fromThisSource)))))

  private def fromThisSource(replicationLink: ReplicationLink): Boolean =
    replicationLink.source.acceptor == remoteAcceptor

  def remoteAcceptor: ActorSelection =
    remoteActorSelection(Acceptor.Name)

  def remoteActorSelection(actor: String): ActorSelection = {
    import connection._

    val protocol = targetEndpoint.system match {
      case sys: ExtendedActorSystem => sys.provider.getDefaultAddress.protocol
      case sys                      => "akka.tcp"
    }

    targetEndpoint.system.actorSelection(s"${protocol}://${name}@${host}:${port}/user/${actor}")
  }
}

/**
 * If `replicationLinks` is [[None]] reliably sends [[GetReplicationEndpointInfo]] requests to the [[Acceptor]] at a source [[ReplicationEndpoint]].
 * On receiving a [[GetReplicationEndpointInfoSuccess]] reply, this connector sets up log [[Replicator]]s, one per
 * common log name between source and target endpoints.
 *
 * If `replicationLinks` is not [[None]] [[Replicator]]s will be setup for the given [[ReplicationLink]]s.
 */
private class Connector(sourceConnector: SourceConnector, replicationLinks: Option[Set[ReplicationLink]]) extends Actor {
  import context.dispatcher

  private val acceptor = sourceConnector.remoteAcceptor
  private var acceptorRequestSchedule: Option[Cancellable] = None

  private var connected = false

  def receive = {
    case GetReplicationEndpointInfoSuccess(info) if !connected =>
      sourceConnector.links(info).foreach(createReplicator)
      connected = true
      acceptorRequestSchedule.foreach(_.cancel())
  }

  private def scheduleAcceptorRequest(acceptor: ActorSelection): Cancellable =
    context.system.scheduler.schedule(0.seconds, sourceConnector.targetEndpoint.settings.retryDelay, new Runnable {
      override def run() = acceptor ! GetReplicationEndpointInfo
    })

  private def createReplicator(link: ReplicationLink): Unit = {
    val filter = sourceConnector.connection.filters.get(link.target.logName) match {
      case Some(f) => f
      case None    => NoFilter
    }
    context.actorOf(Props(new Replicator(link.target, link.source, filter)))
  }

  override def preStart(): Unit =
    replicationLinks match {
      case Some(links) => links.foreach(createReplicator)
      case None        => acceptorRequestSchedule = Some(scheduleAcceptorRequest(acceptor))
    }

  override def postStop(): Unit =
    acceptorRequestSchedule.foreach(_.cancel())
}

/**
 * Replicates events from a remote source log to a local target log. This replicator guarantees that
 * the ordering of replicated events is preserved. Potential duplicates are either detected at source
 * (which is an optimization) or at target (for correctness). Duplicate detection is based on tracked
 * event vector times.
 */
private class Replicator(target: ReplicationTarget, source: ReplicationSource, filter: ReplicationFilter) extends Actor with ActorLogging {
  import FailureDetector._
  import context.dispatcher
  import target.endpoint.settings

  val scheduler = context.system.scheduler
  val detector = context.actorOf(Props(new FailureDetector(source.endpointId, source.logName, settings.failureDetectionLimit)))

  var readSchedule: Option[Cancellable] = None

  val fetching: Receive = {
    case GetReplicationProgressSuccess(_, storedReplicationProgress, currentTargetVersionVector) =>
      context.become(reading)
      read(storedReplicationProgress, currentTargetVersionVector)
    case GetReplicationProgressFailure(cause) =>
      log.warning("replication progress read failed: {}", cause)
      scheduleFetch()
  }

  val idle: Receive = {
    case ReplicationDue =>
      readSchedule.foreach(_.cancel()) // if it's notification from source concurrent to a scheduled read
      context.become(fetching)
      fetch()
  }

  val reading: Receive = {
    case ReplicationReadSuccess(events, fromSequenceNr, replicationProgress, _, currentSourceVersionVector) =>
      detector ! AvailabilityDetected
      context.become(writing)
      write(events, replicationProgress, currentSourceVersionVector, replicationProgress >= fromSequenceNr)
    case ReplicationReadFailure(cause, _) =>
      detector ! FailureDetected(cause)
      log.warning(s"replication read failed: {}", cause)
      context.become(idle)
      scheduleRead()
  }

  val writing: Receive = {
    case writeSuccess @ ReplicationWriteSuccess(_, storedReplicationProgress, _, _, false) =>
      notifyLocalAcceptor(writeSuccess)
      context.become(idle)
      scheduleRead()
    case writeSuccess @ ReplicationWriteSuccess(_, storedReplicationProgress, _, currentTargetVersionVector, true) =>
      notifyLocalAcceptor(writeSuccess)
      context.become(reading)
      read(storedReplicationProgress, currentTargetVersionVector)
    case ReplicationWriteFailure(cause) =>
      log.warning("replication write failed: {}", cause)
      context.become(idle)
      scheduleRead()
  }

  def receive = fetching

  override def unhandled(message: Any): Unit = message match {
    case ReplicationDue => // currently replicating, ignore
    case other          => super.unhandled(message)
  }

  private def notifyLocalAcceptor(writeSuccess: ReplicationWriteSuccess): Unit =
    target.endpoint.acceptor ! writeSuccess

  private def scheduleFetch(): Unit =
    scheduler.scheduleOnce(settings.retryDelay)(fetch())

  private def scheduleRead(): Unit =
    readSchedule = Some(scheduler.scheduleOnce(settings.retryDelay, self, ReplicationDue))

  private def fetch(): Unit = {
    implicit val timeout = Timeout(settings.readTimeout)

    target.log ? GetReplicationProgress(source.logId) recover {
      case t => GetReplicationProgressFailure(t)
    } pipeTo self
  }

  private def read(storedReplicationProgress: Long, currentTargetVersionVector: VectorTime): Unit = {
    implicit val timeout = Timeout(settings.remoteReadTimeout)
    val replicationRead = ReplicationRead(storedReplicationProgress + 1, settings.writeBatchSize, settings.remoteScanLimit, filter, target.logId, self, currentTargetVersionVector)

    (source.acceptor ? ReplicationReadEnvelope(replicationRead, source.logName, target.endpoint.applicationName, target.endpoint.applicationVersion)) recover {
      case t => ReplicationReadFailure(ReplicationReadTimeoutException(settings.remoteReadTimeout), target.logId)
    } pipeTo self
  }

  private def write(events: Seq[DurableEvent], replicationProgress: Long, currentSourceVersionVector: VectorTime, continueReplication: Boolean): Unit = {
    implicit val timeout = Timeout(settings.writeTimeout)

    target.log ? ReplicationWrite(events, replicationProgress, source.logId, currentSourceVersionVector, continueReplication) recover {
      case t => ReplicationWriteFailure(t)
    } pipeTo self
  }

  override def preStart(): Unit =
    fetch()

  override def postStop(): Unit =
    readSchedule.foreach(_.cancel())
}

private object FailureDetector {
  case object AvailabilityDetected
  case class FailureDetected(cause: Throwable)
  case class FailureDetectionLimitReached(counter: Int)
}

private class FailureDetector(sourceEndpointId: String, logName: String, failureDetectionLimit: FiniteDuration) extends Actor with ActorLogging {
  import FailureDetector._
  import ReplicationEndpoint._
  import context.dispatcher

  private var counter: Int = 0
  private var causes: Vector[Throwable] = Vector.empty
  private var schedule: Cancellable = scheduleFailureDetectionLimitReached()

  private val failureDetectionLimitNanos = failureDetectionLimit.toNanos
  private var lastReportedAvailability: Long = 0L

  def receive = {
    case AvailabilityDetected =>
      val currentTime = System.nanoTime()
      val lastInterval = currentTime - lastReportedAvailability
      if (lastInterval >= failureDetectionLimitNanos) {
        context.system.eventStream.publish(Available(sourceEndpointId, logName))
        lastReportedAvailability = currentTime
      }
      schedule.cancel()
      schedule = scheduleFailureDetectionLimitReached()
      causes = Vector.empty
    case FailureDetected(cause) =>
      causes = causes :+ cause
    case FailureDetectionLimitReached(scheduledCount) if scheduledCount == counter =>
      log.error(causes.lastOption.getOrElse(Logging.Error.NoCause), "replication failure detection limit reached ({})," +
        " publishing Unavailable for {}/{} (last exception being reported)", failureDetectionLimit, sourceEndpointId, logName)
      context.system.eventStream.publish(Unavailable(sourceEndpointId, logName, causes))
      schedule = scheduleFailureDetectionLimitReached()
      causes = Vector.empty
  }

  private def scheduleFailureDetectionLimitReached(): Cancellable = {
    counter += 1
    context.system.scheduler.scheduleOnce(failureDetectionLimit, self, FailureDetectionLimitReached(counter))
  }
}