import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import org.scalajs.dom.ext.Ajax
import org.scalajs.jquery.jQuery

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.annotation.JSExportTopLevel

case class User(id: String, name: String)
case class Tweet(id: String, userId: String, content: String)

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

      Ajax
        .get(
          s"$root:$port/login",
          username
        )
        .foreach { xhr =>
          decode[User](xhr.responseText) match {
            case Left(err) =>
              println(err)
            case Right(user) =>
              startTweeting(user)
          }
        }
      false
    })
  }

  private def startTweeting(user: User): Unit = {
    jQuery("#app-contents").html(Renderer.renderTweetingForm)
    retrieveAllTweets

    val tweetingForm = jQuery("#tweeting-form")

    tweetingForm.submit(() => {
      val tweet = Tweet("", user.id, jQuery("#tweet-input").value().toString)

      println(tweet.asJson)

      Ajax
        .post(
          s"$root:$port/postTweet",
          tweet.asJson.toString()
        )
        .foreach { xhr =>
          val t = decode[Tweet](xhr.responseText)
          t match {
            case Left(err) =>
              println(err)

            case Right(tweet) =>
              jQuery("#tweets-container").append(Renderer.renderTweet(tweet))
          }
        }

      false
    })
  }

  private def retrieveAllTweets: Unit = {
    val tweetBlock = jQuery("#tweets-container")

    Ajax.get(
      s"$root:$port/getAllTweets"
    ).foreach(xhr => decode[Seq[Tweet]](xhr.responseText) match {
      case Left(err) =>
        println(err)
      case Right(tweets) =>
        tweets.foreach{ t =>
          jQuery("#tweets-container").append(Renderer.renderTweet(t))
        }
    })
  }
}
