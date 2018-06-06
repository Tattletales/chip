package chip.api
import chip.model.Tweet
import scalatags.Text.TypedTag
import scalatags.Text.all._

object Frontend {
  private[api] val usrInputId = "username"
  private[api] val chipInputFieldId = "tweet-input"

  private def page(content: TypedTag[String],
                   header: Option[TypedTag[String]] = None): TypedTag[String] =
    html(
      head(
        meta(
          charset := "UTF-8",
          title := "Chip chip!",
          link(
            rel := "stylesheet",
            href := "https://unpkg.com/purecss@1.0.0/build/pure-min.css"
          )
        ),
        header.getOrElse("")
      ),
      body(
        div(
          id := "app-contents",
          content
        )
      )
    )

  private[api] def renderTweet(t: Tweet): String =
    div(
      `class` := "tweet",
      p(s"${t.userId}: ${t.content}")
    ).render

  private[api] def renderTweetingForm(msg: Option[String] = None): String = {
    val message = msg.map { m =>
      p(
        style := "border: 1px solid red; background: rgba(255, 0, 0, 0.2);",
        m
      )
    }.getOrElse(p())

    val tweetingForm = div(
      id := "tweets-main-block",
      div(
        id := "tweets-container",
        `class` := "container"
      ),
      message,
      form(
        action := "postTweet",
        method := "POST",
        id := "tweeting-form",
        `class` := "pure-form pure-form-aligned",
        div(
          `class` := "pure-control-group",
          label(
            `for` := chipInputFieldId,
            `class` := "",
            "Chip something :"
          ),
          textarea(
            rows := 6,
            cols := 50,
            `class` := "",
            name := chipInputFieldId,
            id := chipInputFieldId
          )
        ),
        div(
          `class` := "pure-controls",
          button(
            `type` := "submit",
            `class` := "pure-button pure-button-primary",
            "Send"
          )
        )
      )
    )

    page(tweetingForm).render
  }

  private[api] def renderLoginForm: String = {

    val loginForm = form(
      action := "/login",
      method := "POST",
      id := "login-form",
      `class` := "pure-form pure-form-aligned",
      div(
        `class` := "pure-control-group",
        label(
          `for` := usrInputId,
          `class` := "",
          "Your username"
        ),
        input(
          `type` := "text",
          `class` := "",
          name := usrInputId,
          id := usrInputId
        )
      ),
      div(
        `class` := "pure-controls",
        button(
          `type` := "submit",
          `class` := "pure-button pure-button-primary",
          "Log in"
        )
      )
    )

    page(loginForm).render
  }

  private[api] def renderLoggedInPage: String = {
    val loggedIn = h1("Logged in !")
    val metaRedirect = meta(
      httpEquiv := "refresh",
      content := "2; url=/chip"
    )

    page(loggedIn, Some(metaRedirect)).render
  }
}
