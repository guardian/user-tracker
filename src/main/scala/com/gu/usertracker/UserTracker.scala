package com.gu.usertracker

import com.gu.usertracker.persistence.Persistence
import org.joda.time.{DateTime, Duration => JodaTimeDuration}
import akka.agent.Agent
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object UserTracker {
  /** Starts the update cycle that persists the local state of the user tracker and gets the remote state of other user
    * trackers
    *
    * @param userTracker The user tracker
    * @param readFrequency How frequently to read from other user trackers
    * @param writeFrequency How frequently to write local state for other user trackers to read
    * @param garbageCollectFrequency How frequently to prune the locally seen users
    * @param system Akka actor system
    */
  def startPersistence(
    userTracker: UserTracker,
    readFrequency: FiniteDuration = 10.seconds,
    writeFrequency: FiniteDuration = 10.seconds,
    garbageCollectFrequency: FiniteDuration = 1.minute
  )(implicit system: ActorSystem, executionContext: ExecutionContext) {
    system.scheduler.schedule(0 seconds, readFrequency) {
      userTracker.readFromRemote()
    }

    system.scheduler.schedule(writeFrequency, writeFrequency) {
      userTracker.writeToRemote()
    }

    system.scheduler.schedule(garbageCollectFrequency, garbageCollectFrequency) {
      userTracker.collectGarbage()
    }
  }
}

class UserTracker(
    val persistence: Persistence,
    val consideredActiveFor: JodaTimeDuration = JodaTimeDuration.standardMinutes(5),
    val clientId: ClientId = ClientId.next
)(implicit system: ActorSystem, executionContext: ExecutionContext) {
  private val myId = ClientId.next
  private val remotelyActive = Agent[Map[UserId, DateTime]](Map.empty)
  private val locallyActive = Agent[Map[UserId, DateTime]](Map.empty)

  def recordSeenNow(user: UserId) = {
    locallyActive send { _ + (user -> DateTime.now) }
  }

  def currentlyActive: Set[UserId] = {
    val cutOff = DateTime.now.minus(consideredActiveFor)

    def relevantUsers(seen: Map[UserId, DateTime]) =
      seen.filter(_._2.isAfter(cutOff)).map(_._1)

    Set((relevantUsers(locallyActive.get()) ++ relevantUsers(remotelyActive.get())).toSeq: _*)
  }

  def readFromRemote() {
    persistence.getRecentlySeen foreach { seen =>
      remotelyActive.send((seen - myId).values.flatten.toMap)
    }
  }

  def writeToRemote() {
    persistence.setLastSeen(myId, locallyActive.get())
  }

  def collectGarbage() {
    locallyActive send { _.filter(_._2.isAfter(DateTime.now.minus(consideredActiveFor))) }
  }
}
