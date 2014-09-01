package ru.maxkar.niocomm


/**
 * Data flow pin which can accept some data.
 * @param T type of data accepted.
 */
trait Sink[-T] {

  /**
   * Adds one item to this sink pin.
   * @param item item to add.
   * @return the pin itself.
   */
  def += (item : T) : this.type



  /**
   * Adds many items to this sink pin.
   * @param items items to add.
   * @return the pin itself.
   */
  def ++= (items : Iterable[T]) : this.type = {
    val itr = items.iterator
    while (itr.hasNext)
      this += itr.next
    this
  }



  /**
   * Copies all elements from the source pin
   * into this pin.
   */
  final def ++= (source : Source[T]) : this.type = {
    while (source.hasNext)
      this += source.next
    this
  }
}



/**
 * Sink object companion.
 */
final object Sink {
  /**
   * Creates a new sink using "single-item-sink" function.
   */
  def apply[T](sinkFn : T ⇒ Unit) : Sink[T] =
    new Sink[T] {
      override def += (item : T) : this.type = {
        sinkFn(item)
        this
      }
    }



  /**
   * Sink which ignores all messages.
   */
  val ignore : Sink[Any] =
    new Sink[Any] {
      override def +=(item : Any) : this.type = this
    }
}
