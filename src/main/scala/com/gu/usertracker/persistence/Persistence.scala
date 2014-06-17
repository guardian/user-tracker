package com.gu.usertracker.persistence

import org.joda.time.DateTime
import scala.concurrent.Future
import com.gu.usertracker.{UserId, ClientId}

trait Persistence {
  /** Set the recent user history for the given client ID. */
  def setLastSeen(host: ClientId, users: Map[UserId, DateTime]): Future[Unit]

  def getRecentlySeen: Future[Map[ClientId, Map[UserId, DateTime]]]
}
