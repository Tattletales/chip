package vault.api

import backend.gossip.Node.NodeId
import scalatags.Text.all._
import vault.model.Money

object Frontend {

  private[api] val amountFieldId = "amount-input-id"
  private[api] val beneficiaryFieldId = "beneficiary-input-id"

  private[api] def homeForm(balance: Money) =
    div(
      id := "vault-main-block",
      div(
        id := "account-container",
        `class` := "container"
      ),
      h1(
        id := "account-balance",
        s"Current balance: $balance"
      ),
      form(
        action := "/balance",
        method := "GET",
        id := "account-balance-form",
        `class` := "pure-form pure-form-aligned",
        div(
          `class` := "pure-controls",
          button(
            `type` := "submit",
            `class` := "pure-button pure-button-primary",
            "Check my account balance"
          )
        )
      ),
      hr,
      h1("Issue a transfer"),
      form(
        action := "/transfer",
        method := "POST",
        id := "account-transfer-form",
        `class` := "pure-form pure-form-aligned",
        div(
          `class` := "pure-control-group",
          label(`for` := amountFieldId, `class` := "", "Transfer "),
          input(
            `type` := "text",
            `class` := "",
            name := amountFieldId,
            id := amountFieldId,
            placeholder := "Insert amount"
          )
        ),
        div(
          `class` := "pure-control-group",
          label(`for` := beneficiaryFieldId, `class` := "", " to "),
          input(
            `type` := "text",
            `class` := "",
            name := beneficiaryFieldId,
            id := beneficiaryFieldId,
            placeholder := "Insert beneficiary"
          )
        ),
        div(
          `class` := "pure-controls",
          button(
            `type` := "submit",
            `class` := "pure-button pure-button-primary",
            "Send money"
          )
        )
      )
    )

  private[api] def homePage(balance: Money) =
    html(
      head(
        meta(
          charset := "UTF-8",
          httpEquiv := "Content-Type",
          content := "text/html"
        ),
        title := "With Vault, your money is secure !",
        link(
          rel := "stylesheet",
          href := "https://unpkg.com/purecss@1.0.0/build/pure-min.css"
        )
      ),
      body(
        div(
          id := "app-contents",
          p(homeForm(balance))
        ) /*,
            script(
              `type` := "text/javascript",
              src := "js/app-jsdeps.js"
            ),
            script(
              `type` := "text/javascript",
              src := "js/app-fastopt.js"
            )*/
      )
    ).render

  private[api] def balancePage(balance: Money) =
    html(
      head(
        meta(
          charset := "UTF-8",
          httpEquiv := "Content-Type",
          content := "text/html"
        ),
        title := "With Vault, your money is secure !",
        link(
          rel := "stylesheet",
          href := "https://unpkg.com/purecss@1.0.0/build/pure-min.css"
        )
      ),
      body(
        div(
          id := "app-contents",
          p(s"Your current account balance is : $balance"),
          p(a(href := "/", "Go back to home page"))
        )
      )
    ).render

  private[api] def transferPage(to: NodeId, amount: Money) =
    html(
      head(
        meta(
          charset := "UTF-8",
          httpEquiv := "Content-Type",
          content := "text/html"
        ),
        title := "With Vault, your money is secure !",
        link(
          rel := "stylesheet",
          href := "https://unpkg.com/purecss@1.0.0/build/pure-min.css"
        )
      ),
      body(
        div(
          id := "app-contents",
          p(s"Maybe the transfer of $amount to $to was successful. ",
            a(href := "/balance", "Check your balance to make sure")),
          p(a(href := "/", "Go back to home page"))
        )
      )
    ).render
}
