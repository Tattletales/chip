package backend.errors

import akka.http.scaladsl.model.sse.ServerSentEvent
import utils.error.Error
import backend.network._

/**
  * Daemon related errors
  */
sealed trait GossipDeamonError extends Error
case object NodeIdError extends GossipDeamonError {
  override def toString: String = "Could not retrieve the node id."
}
case object SendError extends GossipDeamonError
case object LogRetrievalError extends GossipDeamonError

/**
  * Subscriber related errors
  */
sealed trait SubscriberError extends Error
final case class MalformedSSE(sse: ServerSentEvent) extends SubscriberError

/**
  *  HttpClient related errors
  */
sealed trait HttpClientError extends Error
final case class MalformedUriError(uri: String, m: String) extends HttpClientError
final case class FailedRequestResponse(uri: Uri) extends HttpClientError
