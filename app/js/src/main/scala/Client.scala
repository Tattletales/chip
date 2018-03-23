import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import chip.model.Tweet
import chip.model.decoderImplicits._
import org.scalajs.dom._
import org.scalajs.dom.ext.Ajax
import org.scalajs.jquery.{JQueryAjaxSettings, JQueryXHR, jQuery}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("Client")
object Client {
  private val root = "http://localhost"
  private val port = 8080

  def main(args: Array[String]): Unit = {
    jQuery("#app-contents").html(Renderer.renderLoginForm)

    val loginForm = jQuery("#login-form")

    loginForm.submit(() => {
      val username: String = jQuery("#username").value().toString
      println(s"Sending username $username")

      jQuery.ajax(
        js.Dynamic
          .literal(
            `type` = "GET",
            url = s"$root:$port/login/$username",
            data = "",
            success = successLoginCallback,
            error = failedLoginCallback
          )
          .asInstanceOf[JQueryAjaxSettings]
      )

      false
    })
  }

  private val successLoginCallback = (data: js.Any, textStatus: String, xhr: JQueryXHR) => {
    decode[String](xhr.responseText) match {
      case Left(err) =>
        println(s"$err for response ${xhr.responseText}")
      case Right(msg) =>
        println(msg)
        startTweeting
    }
  }

  private val failedLoginCallback = (xhr: JQueryXHR, textStatus: String, errorThrown: String) => {
    println(s"Login failed ($textStatus). Server sent : ${xhr.responseText}")
  }

  private def startTweeting: Unit = {
    jQuery("#app-contents").html(Renderer.renderTweetingForm)
    retrieveAllTweets

    val tweetingForm = jQuery("#tweeting-form")

    tweetingForm.submit(
      () => {
        val tweet = jQuery("#tweet-input").value().toString

        jQuery.ajax(
          js.Dynamic
            .literal(
              `type` = "POST",
              url = s"$root:$port/postTweet",
              data = tweet.asJson.toString(),
              xhrFields =
                js.Dynamic.literal(withCredentials = true).asInstanceOf[JQueryAjaxSettings],
              success = successTweetPostCallback,
              error = failedTweetPostCallback
            )
            .asInstanceOf[JQueryAjaxSettings]
        )

        false
      })
  }

  private def successTweetPostCallback = (data: js.Any, textStatus: String, xhr: JQueryXHR) => {
    decode[Tweet](xhr.responseText) match {
      case Left(err) =>
        println(s"$err for response ${xhr.responseText}")

      case Right(tweet) =>
        println(s"adding tweet $tweet")
        jQuery("#tweets-container").append(Renderer.renderTweet(tweet))
    }
  }

  private def failedTweetPostCallback =
    (xhr: JQueryXHR, textStatus: String, errorThrown: String) => {
      println(s"An error occurred : ${xhr.responseText}")
    }

  private def retrieveAllTweets: Unit = {
    val tweetBlock = jQuery("#tweets-container")

    Ajax
      .get(
        s"$root:$port/getAllTweets",
        withCredentials = true
      )
      .foreach(xhr =>
        decode[Seq[Tweet]](xhr.responseText) match {
          case Left(err) =>
            println(err)
          case Right(tweets) =>
            tweets.foreach { t =>
              jQuery("#tweets-container").append(Renderer.renderTweet(t))
            }
      })
  }
}
