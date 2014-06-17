package com.gu.usertracker

import com.gu.usertracker.persistence.Persistence
import org.joda.time.DateTime
import scala.concurrent.Future
import akka.agent.Agent
import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, WordSpec, ShouldMatchers}
import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.concurrent.{ScalaFutures, Eventually}
import scala.concurrent.duration._

class UserTrackerSpec(_system: ActorSystem) extends WordSpec with ShouldMatchers with BeforeAndAfterAll with Eventually with ScalaFutures {
  implicit val system = _system

  def this() = this(ActorSystem("ContentApiUpdateActorSpec"))

  def newStatefulPersistenceFixture = new Persistence {
    val store = Agent[Map[ClientId, Map[UserId, DateTime]]](Map.empty)

    override def getRecentlySeen: Future[Map[ClientId, Map[UserId, DateTime]]] =
      Future.successful(store.get())

    override def setLastSeen(host: ClientId, users: Map[UserId, DateTime]): Future[Unit] = {
      store send { _ + (host -> users) }
      Future.successful(())
    }
  }

  /** Persistence fixture that always returns the given set of recently seen user IDs */
  def newConstantPersistence(store: Map[ClientId, Map[UserId, DateTime]]) = new Persistence {
    override def getRecentlySeen: Future[Map[ClientId, Map[UserId, DateTime]]] = Future.successful(store)

    override def setLastSeen(host: ClientId, users: Map[UserId, DateTime]): Future[Unit] = Future.successful(())
  }

  def newUserTrackerFixture = new UserTracker(newStatefulPersistenceFixture)

  val client2Id = ClientId.next
  val client3Id = ClientId.next

  "readFromRemote" must {
    "cause the User Tracker to report users reported by the Persistence layer" in {
      val persistenceFixture = newConstantPersistence(Map(
        ClientId("1") -> Map(UserId("rjberry") -> DateTime.now),
        ClientId("2") -> Map(UserId("rjberry") -> DateTime.now, UserId("francis") -> DateTime.now)
      ))

      val userTrackerFixture = new UserTracker(persistenceFixture)

      userTrackerFixture.readFromRemote()

      eventually {
        userTrackerFixture.currentlyActive should equal(Set(
          UserId("rjberry"),
          UserId("francis")
        ))
      }
    }
  }

  "currentlyActive" must {
    "return no users if none have been recorded" in {
      newUserTrackerFixture.currentlyActive shouldEqual Set.empty
    }

    "return no users if all users were seen longer than the 'considered active' time ago" in {
      val userTrackerFixture = newUserTrackerFixture

      userTrackerFixture.persistence.setLastSeen(client2Id, Map(
        UserId("1") -> DateTime.now.minusMinutes(5).minusMillis(1)
      ))

      userTrackerFixture.readFromRemote()

      userTrackerFixture.currentlyActive shouldEqual Set.empty
    }

    "return users locally seen even if they have not yet been persisted" in {
      val userTrackerFixture = newUserTrackerFixture

      val userFixture = UserId("rob")

      userTrackerFixture.recordSeenNow(userFixture)

      eventually {
        userTrackerFixture.currentlyActive should contain(userFixture)
      }
    }

    "return users seen remotely after reading from remote" in {
      val userFixture = UserId("francis")

      val persistenceFixture = newConstantPersistence(Map(
        client3Id -> Map(
          userFixture -> DateTime.now
        ))
      )

      val userTrackerFixture = new UserTracker(persistenceFixture)

      userTrackerFixture.readFromRemote()

      eventually {
        userTrackerFixture.currentlyActive should contain(userFixture)
      }
    }

    "not return users seen remotely who were seen longer than the persistence time ago" in {
      val persistenceFixture = newConstantPersistence(Map(
        client2Id -> Map(
          UserId("robert") -> DateTime.now.minusMinutes(6),
          UserId("francis") -> DateTime.now.minusMinutes(6)
        ),
        client3Id -> Map(
          UserId("robert") -> DateTime.now.minusMinutes(1)
        )
      ))

      val userTrackerFixture = new UserTracker(persistenceFixture)
      userTrackerFixture.readFromRemote()

      eventually {
        userTrackerFixture.currentlyActive should equal(Set(UserId("robert")))
      }
    }
  }

  override def afterAll {
    TestKit.shutdownActorSystem(_system)
  }
}
