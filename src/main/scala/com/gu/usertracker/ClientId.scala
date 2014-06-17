package com.gu.usertracker

import java.util.UUID

object ClientId {
  def next = ClientId(UUID.randomUUID().toString)
}

case class ClientId(id: String) extends AnyVal
