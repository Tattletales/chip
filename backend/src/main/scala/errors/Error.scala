package backend.errors

import akka.http.scaladsl.model.sse.ServerSentEvent
import utils.error.Error
import backend.network._

/**
  * Daemon related errors
  */
sealed trait GossipDeamonError extends Error

/**
  * Impossible to retrieve the identifier of a node.
  */
case object NodeIdError extends GossipDeamonError {
  override def toString: String = "Could not retrieve the node id."
}

/**
  * Sending of a message failed.
  */
case object SendError extends GossipDeamonError

/**
  * Cannot retrieve the log.
  */
case object LogRetrievalError extends GossipDeamonError

/**
  * Subscriber related errors
  */
sealed trait SubscriberError extends Error

/**
  * The Server Sent Event does not contain all the necessary information.
  */
final case class MalformedSSE(sse: ServerSentEvent) extends SubscriberError

/**
  *  HttpClient related errors
  */
sealed trait HttpClientError extends Error

/**
  * Failure of a request.
  */
final case class FailedRequestResponse(uri: Uri) extends HttpClientError
