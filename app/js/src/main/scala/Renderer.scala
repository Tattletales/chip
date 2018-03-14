import scalatags.Text.all._

object Renderer {

  def renderTweet(t: Tweet): String = {
    div(
      `class` := "tweet",
      p(s"${t.userId}: ${t.content}")
    ).render
  }

  def renderTweetingForm: String = {
    val inputFieldId = "tweet-input"
    div(
      id := "tweets-main-block",
      div(
        id := "tweets-container",
        `class` := "container"
      ),
      form(
        action := "#",
        method := "POST",
        id := "tweeting-form",
        `class` := "pure-form pure-form-aligned",
        div(
          `class` := "pure-control-group",
          label(
            `for` := inputFieldId,
            `class` := "",
            "Chip something :"
          ),
          textarea(
            rows := 6,
            cols := 50,
            `class` := "",
            name := inputFieldId,
            id := inputFieldId
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
    ).render
  }

  def renderLoginForm: String = {
    val usrInputId = "username"

    form(
      action := "#",
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
    ).render
  }
}
