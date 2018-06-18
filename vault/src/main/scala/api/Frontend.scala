package vault
package api

import backend.gossip.Node.NodeId
import scalatags.Text.all._
import model.Money

object Frontend {

  private[api] val amountFieldId = "amount-input-id"
  private[api] val beneficiaryFieldId = "beneficiary-input-id"
  private[api] val checkBalanceForFieldId = "balance-for-node-id"

  private[api] val balanceCheckoutFor = {
    form(
      action := "/balance",
      method := "POST",
      id := "account-balance-form-for-node",
      `class` := "pure-form pure-form-aligned",
      div(
        `class` := "pure-control-group",
        label(`for` := checkBalanceForFieldId, "Checkout account of "),
        input(
          `type` := "text",
          `class` := "",
          name := checkBalanceForFieldId,
          id := checkBalanceForFieldId,
          placeholder := "Insert node id"
        )
      ),
      div(
        `class` := "pure-controls",
        button(
          `type` := "submit",
          `class` := "pure-button pure-button-primary",
          "Check this node's balance"
        )
      )
    )

  }

  private[api] def homeForm(balance: Money) =
    div(
      id := "vault-main-block",
      div(
        id := "account-container",
        `class` := "container"
      ),
      h2(
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
      h2("Issue a transfer"),
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

  private[api] def homePage(nodeId: NodeId, balance: Money) =
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
          h1(
            style := "text-transform: capitalize;",
            s"Hello $nodeId"
          ),
          p(homeForm(balance)),
          hr,
          p(balanceCheckoutFor)
        )
      )
    ).render

  private[api] def balancePage(nodeId: NodeId, balance: Money) =
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
          p(s"Current account balance for $nodeId is : $balance"),
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
