package ru.maxkar.niocomm


/**
 * Event (item) source pin.
 * @param T event type.
 */
trait Source[+T] {

  /**
   * Checks if this pin have more elements to extract.
   * @return <code>true</code> iff there are more elements to extract.
   */
  def hasNext() : Boolean



  /**
   * Removes and ceturns next element.
   * @return next element from this pin.
   * @throws IllegalStateException if there are no more elements.
   */
  def next() : T



  /**
   * Returns all remaining elements as a sequence.
   * @return all remaining elements as a sequence.
   */
  def allNext() : Seq[T] = {
    if (!hasNext)
      return Seq.empty

    var res = new scala.collection.mutable.ArrayBuffer[T]

    do {
      res += next
    } while (hasNext)

    res
  }



   /**
    * Moves all elements into the target sink.
    * @param target target to copy this item to.
    */
   final def sinkTo(target : Sink[T]) : Unit =
     target ++= this
}



/**
 * Trait companion object.
 */
final object Source {
  /**
   * Creates a new source by "hasNext" and "next" functions.
   */
  def apply[T](hasNextFn : () ⇒ Boolean, nextFn : () ⇒ T) : Source[T] =
    new Source[T] {
      override def hasNext() = hasNextFn()
      override def next() = nextFn()
    }



  /**
   * Contacts several sources into one stream.
   */
  def concat[T](items : Source[T]*) : Source[T] =
    new Source[T] {
      override def hasNext() : Boolean = {
        val itr = items.iterator
        while (itr.hasNext)
          if (itr.next.hasNext)
            return true
        false
      }


      override def next() : T = {
        val itr = items.iterator
        while (itr.hasNext) {
          val elt = itr.next
          if (elt.hasNext)
            return elt.next
        }

        throw new IllegalStateException(
          "Cannot extract element from empty source")
      }
    }
}
