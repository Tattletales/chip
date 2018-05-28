package backend.errors

import akka.http.scaladsl.model.sse.ServerSentEvent
import utils.error.Error
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Uri

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
case class MalformedSSE(sse: ServerSentEvent) extends SubscriberError

/**
  *  HttpClient related errors
  */
sealed trait HttpClientError extends Error
case class MalformedUriError(uri: String, m: String) extends HttpClientError
case class FailedRequestResponse(uri: String Refined Uri) extends HttpClientError
