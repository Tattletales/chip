package backend

import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.{Uri => RefUri}

package object network {
  type Uri = String Refined RefUri
  type Route = Uri
}
