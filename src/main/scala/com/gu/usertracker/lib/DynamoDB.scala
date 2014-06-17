package com.gu.usertracker.lib

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.amazonaws.services.dynamodbv2.model._
import com.amazonaws.handlers.AsyncHandler
import scala.concurrent.Promise
import scala.util.{Try, Failure, Success}
import org.joda.time.DateTime

object DynamoDB {
  /** 4 U, Ben James! */
  implicit class ReactiveDynamoDBAsyncClient(client: AmazonDynamoDBAsyncClient) {
    private def createHandler[A <: com.amazonaws.AmazonWebServiceRequest, B]() = {
      val promise = Promise[B]()

      val handler = new AsyncHandler[A, B] {
        override def onSuccess(request: A, result: B): Unit = promise.complete(Success(result))

        override def onError(exception: Exception): Unit = promise.complete(Failure(exception))
      }

      (promise.future, handler)
    }

    private def asFuture[A <: com.amazonaws.AmazonWebServiceRequest, B](f: AsyncHandler[A, B] => Any) = {
      val (future, handler) = createHandler[A, B]()
      f(handler)
      future
    }

    def batchGetItemFuture(request: BatchGetItemRequest) =
      asFuture[BatchGetItemRequest, BatchGetItemResult](client.batchGetItemAsync(request, _))

    def batchWriteItemFuture(request: BatchWriteItemRequest) = {
      asFuture[BatchWriteItemRequest, BatchWriteItemResult](client.batchWriteItemAsync(request, _))
    }

    def scanFuture(request: ScanRequest) =
      asFuture[ScanRequest, ScanResult](client.scanAsync(request, _))

    def deleteItemFuture(request: DeleteItemRequest) =
      asFuture[DeleteItemRequest, DeleteItemResult](client.deleteItemAsync(request, _))
  }

  implicit class RichDateTime(dateTime: DateTime) {
    def toAttributeValue = new AttributeValue().withN(dateTime.toInstant.getMillis.toString)
  }

  implicit class RichAttributeValue(attributeValue: AttributeValue) {
    def toDateTime = for {
      timeStamp <- Option(attributeValue.getN).flatMap(n => Try(n.toLong).toOption)
    } yield new DateTime(timeStamp)
  }
}
