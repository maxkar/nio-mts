package ru.maxkar.cs.chan

import java.nio.channels._

/**
 * Utilities to simplify life with selectors.
 */
final object SelectorUtil {

  /** Aborts a selector by calling handler for each registered
   * selection key and then closing the selector. If abortion
   * of any selection key fail with an exception, then
   * other items will not be processed.
   */
  def unsafeAbortBy(sel : Selector, itemAborter : SelectionKey ⇒ Unit) : Unit = {
    try {
      val iter = sel.keys().iterator()
      while (iter.hasNext())
        itemAborter(iter.next())
    } finally {
      sel.close()
    }
  }

  /** Aborts a selector by calling handler for each
   * registered selection key and then closing the selector.
   * It tries to log all the errors occured in item aborter and
   * proceeds to other elements.
   */
  def safeAbortBy(sel : Selector, itemAborter : SelectionKey ⇒ Unit) : Unit =
    unsafeAbortBy(sel, item ⇒
      try {
        itemAborter(item)
      } catch {
        case t : Throwable ⇒
          try {
            t.printStackTrace()
          } catch {
            case t : Throwable ⇒ ()
            // Yes, I am paranoid
          }
      })
}
