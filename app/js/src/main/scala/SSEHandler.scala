import org.scalajs.dom

object SSEHandler {
  private val source = new dom.EventSource("/tweet-source")

  def subscribe =
    source.onmessage = { (m: dom.MessageEvent) =>
      val t = ""
      println(t)
    }
}
