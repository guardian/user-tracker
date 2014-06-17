package com.gu.usertracker.persistence

import scala.concurrent.{ExecutionContext, Future}
import com.gu.usertracker.ClientId
import org.joda.time.{Duration, DateTime}
import com.gu.usertracker.lib.const
import com.gu.usertracker.lib.DynamoDB._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.amazonaws.services.dynamodbv2.model._
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import com.gu.usertracker.UserId
import play.api.libs.json.Json
import grizzled.slf4j.Logging
import akka.actor.ActorSystem

case class DynamoDBRow(
  clientId: ClientId,
  lastUpdated: DateTime,
  recentlySeen: Map[UserId, DateTime]
)

case class StaleClient(
  clientId: ClientId,
  lastUpdated: DateTime
)

/**
 * Simple DynamoDB persistence layer.
 *
 * To avoid write conflicts, each server in the auto-scaling group writes its recent user data to its own row in the
 * DynamoDB table. Then, when we're updating our local cache of what users are active, we just scan the whole table
 * (which should have very few items under normal scenarios, i.e. something close to the size of the auto-scaling
 * group size), and do a Set union on all the users that have been seen within whatever is considered the 'live' time.
 *
 * In order to prevent the table from growing as servers are trashed and rebuilt, the garbage collect operation should
 * be periodically called. When the servers update their list of recent users, they also append a time stamp. There is
 * a grace period, within which a server is considered to still be alive. The GC looks for server time stamps after
 * that grace period, and deletes the rows from the Dynamo DB table, which should ensure that the Scan operation remains
 * able to perform relatively well. (*Fingers Crossed*)
 */
object DynamoDBPersistence extends Logging {
  val PrimaryKeyField = "client_id"
  val RecentlySeenField = "recently_seen"
  val LastUpdatedField = "updated_at"
  val HostsPK = "hosts"

  /** Persistence that uses the default AWS credentials chain */
  def apply(tableName: String) = DynamoDBPersistence(new AmazonDynamoDBAsyncClient(), tableName)

  def startGarbageCollection(persistence: DynamoDBPersistence,
                             frequency: scala.concurrent.duration.FiniteDuration)
                            (implicit actorSystem: ActorSystem, executionContext: ExecutionContext) {
    actorSystem.scheduler.schedule(frequency, frequency) {
      persistence.collectGarbage
    }
  }

  implicit class RichRawRow(javaRow: java.util.Map[String, AttributeValue]) {
    private val row = javaRow.asScala

    /** TODO validation type and more detailed error messages? */
    def deserialize = for {
      clientIdAttr <- row.get(PrimaryKeyField)
      clientId <- Option(clientIdAttr.getS)
      lastUpdatedAttr <- row.get(LastUpdatedField)
      lastUpdatedInstance <- lastUpdatedAttr.toDateTime
      recentlySeenAttr <- row.get(RecentlySeenField)
      recentlySeenJson <- Option(recentlySeenAttr.getS)
      recentlySeen <- Json.fromJson[Map[UserId, DateTime]](Json.parse(recentlySeenJson)).asOpt
    } yield DynamoDBRow(ClientId(clientId), lastUpdatedInstance, recentlySeen)

    def deserializeOrDie = deserialize getOrElse {
      logger.error(s"Unable to deserialize row from DynamoDB: $row")
      throw new RuntimeException(s"Unable to deserialize row from DynamoDB: $row")
    }
  }

  implicit class RichClientId(clientId: ClientId) {
    def toAttributeValue = new AttributeValue().withS(clientId.id)
  }
}

final case class DynamoDBPersistence(
    client: AmazonDynamoDBAsyncClient,
    tableName: String,
    /** If a server hasn't pinged DynamoDB within this time it's considered ripe for garbage collection */
    serverGraceTime: Duration = Duration.standardMinutes(5)
) extends Persistence with Logging {
  import DynamoDBPersistence._

  override def setLastSeen(host: ClientId, users: Map[UserId, DateTime]): Future[Unit] = {
    val writeItemsRequest = new BatchWriteItemRequest()
    writeItemsRequest.addRequestItemsEntry(tableName, List(new WriteRequest(new PutRequest(Map(
      PrimaryKeyField -> new AttributeValue().withS(host.id),
      LastUpdatedField -> DateTime.now.toAttributeValue,
      RecentlySeenField -> new AttributeValue(Json.stringify(Json.toJson(users)))
    )))))

    client.batchWriteItemFuture(writeItemsRequest).map(const(()))
  }

  def removeStale(staleClient: StaleClient): Future[Unit] = {
    val deleteItemsRequest = new DeleteItemRequest().withExpected(Map(
      LastUpdatedField -> new ExpectedAttributeValue().withValue(staleClient.lastUpdated.toAttributeValue)
    )).withKey(Map(
      PrimaryKeyField -> staleClient.clientId.toAttributeValue
    ))

    client.deleteItemFuture(deleteItemsRequest).map(const(()))
  }

  def collectGarbage = getGarbage.flatMap(Future.traverse(_)(removeStale)).map(const(()))

  def getGarbage: Future[List[StaleClient]] = {
    val scanRequest = new ScanRequest()

    val cutOffTime = DateTime.now.minus(serverGraceTime)

    scanRequest.addScanFilterEntry(
      LastUpdatedField,
      new Condition().withComparisonOperator(ComparisonOperator.LT).withAttributeValueList(
        new AttributeValue().withN(cutOffTime.toInstant.toString)
      )
    )

    client.scanFuture(scanRequest) map { results =>
      results.getItems.toList flatMap { row =>
        val deserialized = row.deserializeOrDie

        if (deserialized.lastUpdated.isAfter(cutOffTime)) {
          logger.warn(s"DynamoDB returned a 'stale' item that is not stale " +
            s"(last updated at ${deserialized.lastUpdated}})")
          None
        } else {
          Some(StaleClient(deserialized.clientId, deserialized.lastUpdated))
        }
      }
    }
  }

  override def getRecentlySeen: Future[Map[ClientId, Map[UserId, DateTime]]] = {
    val scanRequest = new ScanRequest()

    scanRequest.addScanFilterEntry(
      LastUpdatedField,
      new Condition().withComparisonOperator(ComparisonOperator.GT).withAttributeValueList(
        new AttributeValue().withN(DateTime.now.minus(serverGraceTime).toInstant.toString)
      )
    )

    client.scanFuture(scanRequest) map { results =>
      (results.getItems.toList map { row =>
        val deserialized = row.deserializeOrDie

        deserialized.clientId -> deserialized.recentlySeen
      }).toMap
    }
  }
}
