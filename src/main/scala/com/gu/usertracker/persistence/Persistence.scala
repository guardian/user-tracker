package com.gu.usertracker.persistence

import org.joda.time.DateTime
import scala.concurrent.Future
import com.gu.usertracker.{UserId, ClientId}

trait Persistence {
  def setLastSeen(host: ClientId, users: Map[UserId, DateTime]): Future[Unit]

  /** List all users that have been seen since the given date time */
  def getRecentlySeen: Future[Map[ClientId, Map[UserId, DateTime]]]
}

/** TODO add DynamoDB persistence layer */