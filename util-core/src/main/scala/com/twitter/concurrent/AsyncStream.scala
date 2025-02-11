package com.twitter.concurrent

import com.twitter.conversions.SeqUtil
import com.twitter.util.{Future, Return, Throw, Promise}
import scala.annotation.varargs

/**
 * A representation of a lazy (and possibly infinite) sequence of asynchronous
 * values. We provide combinators for non-blocking computation over the sequence
 * of values.
 *
 * It is composable with Future, Seq and Option.
 *
 * {{{
 * val ids = Seq(123, 124, ...)
 * val users = fromSeq(ids).flatMap(id => fromFuture(getUser(id)))
 *
 * // Or as a for-comprehension...
 *
 * val users = for {
 *   id <- fromSeq(ids)
 *   user <- fromFuture(getUser(id))
 * } yield user
 * }}}
 *
 * All of its operations are lazy and don't force evaluation, unless otherwise
 * noted.
 *
 * The stream is persistent and can be shared safely by multiple threads.
 */
sealed abstract class AsyncStream[+A] {
  import AsyncStream._

  /**
   * Returns true if there are no elements in the stream.
   */
  def isEmpty: Future[Boolean] = this match {
    case Empty => Future.True
    case Embed(fas) => fas.flatMap(_.isEmpty)
    case _ => Future.False
  }

  /**
   * Returns the head of this stream if not empty.
   */
  def head: Future[Option[A]] = this match {
    case Empty => Future.None
    case FromFuture(fa) => fa.map(Some(_))
    case Cons(fa, _) => fa.map(Some(_))
    case Embed(fas) => fas.flatMap(_.head)
  }

  /**
   * Note: forces the first element of the tail.
   */
  def tail: Future[Option[AsyncStream[A]]] = this match {
    case Empty | FromFuture(_) => Future.None
    case Cons(_, more) => extract(more())
    case Embed(fas) => fas.flatMap(_.tail)
  }

  /**
   * The head and tail of this stream, if not empty. Note the tail thunk which
   * preserves the tail's laziness.
   *
   * {{{
   * empty.uncons     == Future.None
   * (a +:: m).uncons == Future.value(Some(a, () => m))
   * }}}
   */
  def uncons: Future[Option[(A, () => AsyncStream[A])]] = this match {
    case Empty => Future.None
    case FromFuture(fa) => fa.map(a => Some((a, () => empty)))
    case Cons(fa, more) => fa.map(a => Some((a, more)))
    case Embed(fas) => fas.flatMap(_.uncons)
  }

  /**
   * Note: forces the stream. For infinite streams, the future never resolves.
   */
  def foreach(f: A => Unit): Future[Unit] =
    foldLeft(()) { (_, a) => f(a) }

  /**
   * Execute the specified effect as each element of the resulting
   * stream is demanded. This method does '''not''' force the
   * stream. Since the head of the stream is not lazy, the effect will
   * happen to the first item in the stream (if any) right away.
   *
   * The effects will occur as the '''resulting''' stream is demanded
   * and will not occur if the original stream is demanded.
   *
   * This is useful for e.g. counting the number of items that were
   * consumed from a stream by a consuming process, regardless of
   * whether the entire stream is consumed.
   */
  def withEffect(f: A => Unit): AsyncStream[A] =
    map { a => f(a); a }

  /**
   * Maps each element of the stream to a Future action, resolving them from
   * head to tail. The resulting Future completes when the action completes
   * for the last element.
   *
   * Note: forces the stream. For infinite streams, the future never resolves.
   */
  def foreachF(f: A => Future[Unit]): Future[Unit] =
    foldLeftF(()) { (_, a) => f(a) }

  /**
   * Map over this stream with the given concurrency. The items will likely be
   * completed out of order, and if so, the stream of results will also be out
   * of order.
   *
   * `concurrencyLevel` specifies an "eagerness factor", and that many actions
   * will be started when this method is called. Forcing the stream will yield
   * the results of completed actions, returning the results that finished
   * first, and will block if none of the actions has yet completed.
   *
   * This method is useful for speeding up calculations over a stream where
   * executing the actions (and processing their results) in order is not
   * important. To implement a concurrent fold, first call `mapConcurrent` and
   * then fold that stream. Similarly, concurrent `foreachF` can be achieved by
   * applying `mapConcurrent` and then `foreach`.
   *
   * @param concurrencyLevel: How many actions to execute concurrently. This
   *   many actions will be started and "raced", with the winner being
   *   the next item available in the stream.
   */
  def mapConcurrent[B](concurrencyLevel: Int)(f: A => Future[B]): AsyncStream[B] = {
    require(concurrencyLevel > 0, s"concurrencyLevel must be at least one. got: $concurrencyLevel")
    merge(fanout(this, concurrencyLevel).map(_.mapF(f)): _*)
  }

  /**
   * Given a predicate `p`, returns the longest prefix (possibly empty) of this
   * stream that satisfes `p`:
   *
   * {{{
   * AsyncStream(1, 2, 3, 4, 1).takeWhile(_ < 3) = AsyncStream(1, 2)
   * AsyncStream(1, 2, 3).takeWhile(_ < 5) = AsyncStream(1, 2, 3)
   * AsyncStream(1, 2, 3).takeWhile(_ < 0) = AsyncStream.empty
   * }}}
   */
  def takeWhile(p: A => Boolean): AsyncStream[A] =
    this match {
      case Empty => empty
      case FromFuture(fa) =>
        Embed(fa.map { a => if (p(a)) this else empty })
      case Cons(fa, more) =>
        Embed(fa.map { a =>
          if (p(a)) Cons(fa, () => more().takeWhile(p))
          else empty
        })
      case Embed(fas) => Embed(fas.map(_.takeWhile(p)))
    }

  /**
   * Given a predicate `p` returns the suffix remaining after `takeWhile(p)`:
   *
   * {{{
   * AsyncStream(1, 2, 3, 4, 1).dropWhile(_ < 3) = AsyncStream(3, 4, 1)
   * AsyncStream(1, 2, 3).dropWhile(_ < 5) = AsyncStream.empty
   * AsyncStream(1, 2, 3).dropWhile(_ < 0) = AsyncStream(1, 2, 3)
   * }}}
   */
  def dropWhile(p: A => Boolean): AsyncStream[A] =
    this match {
      case Empty => empty
      case FromFuture(fa) =>
        Embed(fa.map { a => if (p(a)) empty else this })
      case Cons(fa, more) =>
        Embed(fa.map { a =>
          if (p(a)) more().dropWhile(p)
          else Cons(fa, () => more())
        })
      case Embed(fas) => Embed(fas.map(_.dropWhile(p)))
    }

  /**
   * Concatenates two streams.
   *
   * Note: If this stream is infinite, we never process the concatenated
   * stream; effectively: m ++ k == m.
   *
   * @see [[concat]] for Java users.
   */
  def ++[B >: A](that: => AsyncStream[B]): AsyncStream[B] =
    concatImpl(() => that)

  protected def concatImpl[B >: A](that: () => AsyncStream[B]): AsyncStream[B] =
    this match {
      case Empty => that()
      case FromFuture(fa) =>
        Cons(fa, that)
      case Cons(fa, more) =>
        Cons(fa, () => more().concatImpl(that))
      case Embed(fas) =>
        Embed(fas.map(_.concatImpl(that)))
    }

  /**
   * @see ++
   */
  def concat[B >: A](that: => AsyncStream[B]): AsyncStream[B] = ++(that)

  /**
   * Map a function `f` over the elements in this stream and concatenate the
   * results.
   *
   * @note We use `flatMap` on `Future` instead of `map` to maintain
   * stack-safety for monadic recursion.
   */
  def flatMap[B](f: A => AsyncStream[B]): AsyncStream[B] =
    this match {
      case Empty => empty
      case FromFuture(fa) => Embed(fa.flatMap(a => Future.value(f(a))))
      case Cons(fa, more) =>
        Embed(fa.flatMap(a => Future.value(f(a)))) ++ more().flatMap(f)
      case Embed(fas) =>
        Embed(fas.flatMap(as => Future.value(as.flatMap(f))))
    }

  /**
   * `stream.map(f)` is the stream obtained by applying `f` to each element of
   * `stream`.
   */
  def map[B](f: A => B): AsyncStream[B] =
    this match {
      case Empty => empty
      case FromFuture(fa) => FromFuture(fa.map(f))
      case Cons(fa, more) => Cons(fa.map(f), () => more().map(f))
      case Embed(fas) => Embed(fas.map(_.map(f)))
    }

  /**
   * Returns a stream of elements that satisfy the predicate `p`.
   *
   * Note: forces the stream up to the first element which satisfies the
   * predicate. This operation may block forever on infinite streams in which
   * no elements match.
   */
  def filter(p: A => Boolean): AsyncStream[A] =
    this match {
      case Empty => empty
      case FromFuture(fa) =>
        Embed(fa.map { a => if (p(a)) this else empty })
      case Cons(fa, more) =>
        Embed(fa.map { a =>
          if (p(a)) Cons(fa, () => more().filter(p))
          else more().filter(p)
        })
      case Embed(fas) => Embed(fas.map(_.filter(p)))
    }

  /**
   * @see filter
   */
  def withFilter(f: A => Boolean): AsyncStream[A] = filter(f)

  /**
   * Returns the prefix of this stream of length `n`, or the stream itself if
   * `n` is larger than the number of elements in the stream.
   */
  def take(n: Int): AsyncStream[A] =
    if (n < 1) empty
    else
      this match {
        case Empty => empty
        case FromFuture(_) => this
        // If we don't handle this case specially, then the next case
        // would return a stream whose full evaluation will evaulate
        // cons.tail.take(0), forcing one more effect than necessary.
        case Cons(fa, _) if n == 1 => FromFuture(fa)
        case Cons(fa, more) => Cons(fa, () => more().take(n - 1))
        case Embed(fas) => Embed(fas.map(_.take(n)))
      }

  /**
   * Returns the suffix of this stream after the first `n` elements, or
   * `AsyncStream.empty` if `n` is larger than the number of elements in the
   * stream.
   *
   * Note: this forces all of the intermediate dropped elements.
   */
  def drop(n: Int): AsyncStream[A] =
    if (n < 1) this
    else
      this match {
        case Empty | FromFuture(_) => empty
        case Cons(_, more) => more().drop(n - 1)
        case Embed(fas) => Embed(fas.map(_.drop(n)))
      }

  /**
   * Constructs a new stream by mapping each element of this stream to a
   * Future action, evaluated from head to tail.
   */
  def mapF[B](f: A => Future[B]): AsyncStream[B] =
    this match {
      case Empty => empty
      case FromFuture(fa) => FromFuture(fa.flatMap(f))
      case Cons(fa, more) => Cons(fa.flatMap(f), () => more().mapF(f))
      case Embed(fas) => Embed(fas.map(_.mapF(f)))
    }

  /**
   * Similar to foldLeft, but produces a stream from the result of each
   * successive fold:
   *
   * {{{
   * AsyncStream(1, 2, ...).scanLeft(z)(f) == z +:: f(z, 1) +:: f(f(z, 1), 2) +:: ...
   * }}}
   *
   * Note that for an `AsyncStream as`:
   *
   * {{{
   * as.scanLeft(z)(f).last == as.foldLeft(z)(f)
   * }}}
   *
   * The resulting stream always begins with the initial value `z`,
   * not subject to the fate of the underlying future, i.e.:
   *
   * {{{
   * val never = AsyncStream.fromFuture(Future.never)
   * never.scanLeft(z)(f) == z +:: never // logical equality
   * }}}
   */
  def scanLeft[B](z: B)(f: (B, A) => B): AsyncStream[B] =
    this match {
      case Embed(fas) => Cons(Future.value(z), () => Embed(fas.map(_.scanLeftEmbed(z)(f))))
      case Empty => FromFuture(Future.value(z))
      case FromFuture(fa) =>
        Cons(Future.value(z), () => FromFuture(fa.map(f(z, _))))
      case Cons(fa, more) =>
        Cons(Future.value(z), () => Embed(fa.map(a => more().scanLeft(f(z, a))(f))))
    }

  /**
   * Helper method used to avoid scanLeft being one behind in case of Embed AsyncStream.
   *
   * scanLeftEmbed, unlike scanLeft, does not return the initial value `z` and is there to
   * prevent the Embed case from returning duplicate initial `z` values for scanLeft.
   *
   */
  private def scanLeftEmbed[B](z: B)(f: (B, A) => B): AsyncStream[B] =
    this match {
      case Embed(fas) => Embed(fas.map(_.scanLeftEmbed(z)(f)))
      case Empty => Empty
      case FromFuture(fa) =>
        FromFuture(fa.map(f(z, _)))
      case Cons(fa, more) =>
        Embed(fa.map(a => more().scanLeft(f(z, a))(f)))
    }

  /**
   * Applies a binary operator to a start value and all elements of the stream,
   * from head to tail.
   *
   * Note: forces the stream. If the stream is infinite, the resulting future
   * is equivalent to Future.never.
   *
   * @param z the starting value.
   * @param f a binary operator applied to elements of this stream.
   */
  def foldLeft[B](z: B)(f: (B, A) => B): Future[B] = this match {
    case Empty => Future.value(z)
    case FromFuture(fa) => fa.map(f(z, _))
    case Cons(fa, more) => fa.map(f(z, _)).flatMap(more().foldLeft(_)(f))
    case Embed(fas) => fas.flatMap(_.foldLeft(z)(f))
  }

  /**
   * Like `foldLeft`, except that its result is encapsulated in a Future.
   * `foldLeftF` works from head to tail over the stream.
   *
   * Note: forces the stream. If the stream is infinite, the resulting future
   * is equivalent to Future.never.
   *
   * @param z the starting value.
   * @param f a binary operator applied to elements of this stream.
   */
  def foldLeftF[B](z: B)(f: (B, A) => Future[B]): Future[B] =
    this match {
      case Empty => Future.value(z)
      case FromFuture(fa) => fa.flatMap(a => f(z, a))
      case Cons(fa, more) => fa.flatMap(a => f(z, a)).flatMap(b => more().foldLeftF(b)(f))
      case Embed(fas) => fas.flatMap(_.foldLeftF(z)(f))
    }

  /**
   * This is a powerful and expert level function. A fold operation
   * encapsulated in a Future. Like foldRight on normal lists, it replaces
   * every cons with the folded function `f`, and the empty element with `z`.
   *
   * Note: For clarity, we imagine that surrounding a function with backticks
   * (&#96;) allows infix usage.
   *
   * {{{
   *     (1 +:: 2 +:: 3 +:: empty).foldRight(z)(f)
   *   = 1 `f` flatMap (2 `f` flatMap (3 `f` z))
   * }}}
   *
   * Note: if `f` always forces the second parameter, for infinite streams the
   * future never resolves.
   *
   * @param z the parameter that replaces the end of the list.
   * @param f a binary operator applied to elements of this stream. Note that
   * the second paramter is call-by-name.
   */
  def foldRight[B](z: => Future[B])(f: (A, => Future[B]) => Future[B]): Future[B] =
    this match {
      case Empty => z
      case FromFuture(fa) => fa.flatMap(f(_, z))
      case Cons(fa, more) => fa.flatMap(f(_, more().foldRight(z)(f)))
      case Embed(fas) => fas.flatMap(_.foldRight(z)(f))
    }

  /**
   * Concatenate a stream of streams.
   *
   * {{{
   * val a = AsyncStream(1)
   * AsyncStream(a, a, a).flatten = AsyncStream(1, 1, 1)
   * }}}
   *
   * Java users see [[AsyncStream.flattens]].
   */
  def flatten[B](implicit ev: A <:< AsyncStream[B]): AsyncStream[B] =
    this match {
      case Empty => empty
      case FromFuture(fa) => Embed(fa.map(ev))
      case Cons(fa, more) => Embed(fa.map(ev)) ++ more().flatten
      case Embed(fas) => Embed(fas.map(_.flatten))
    }

  /**
   * A Future of the stream realized as a list. This future completes when all
   * elements of the stream are resolved.
   *
   * Note: forces the entire stream. If one asynchronous call fails, it fails
   * the aggregated result.
   */
  def toSeq(): Future[Seq[A]] =
    observe().flatMap {
      case (s, None) => Future.value(s)
      case (_, Some(exc)) => Future.exception(exc)
    }

  /**
   * Attempts to transform the stream into a Seq, and in the case of failure,
   * `observe` returns whatever was able to be transformed up to the point of
   * failure along with the exception. As a result, this Future never fails,
   * and if there are errors they can be accessed via the Option.
   *
   * Note: forces the stream. For infinite streams, the future never resolves.
   */
  def observe(): Future[(Seq[A], Option[Throwable])] = {
    val buf = Vector.newBuilder[A]

    def go(as: AsyncStream[A]): Future[Unit] =
      as match {
        case Empty => Future.Done
        case FromFuture(fa) =>
          fa.flatMap { a =>
            buf += a
            Future.Done
          }
        case Cons(fa, more) =>
          fa.flatMap { a =>
            buf += a
            go(more())
          }
        case Embed(fas) =>
          fas.flatMap(go)
      }

    go(this).transform {
      case Throw(exc) => Future.value(buf.result() -> Some(exc))
      case Return(_) => Future.value(buf.result() -> None)
    }
  }

  /**
   * Buffer the specified number of items from the stream, or all
   * remaining items if the end of the stream is reached before finding
   * that many items. In all cases, this method should act like
   * <https://www.scala-lang.org/api/current/index.html#scala.collection.GenTraversableLike@splitAt(n:Int):(Repr,Repr)>
   * and not cause evaluation of the remainder of the stream.
   */
  private[concurrent] def buffer(n: Int): Future[(Seq[A], () => AsyncStream[A])] = {
    // pre-allocate the buffer, unless it's very large
    val buffer = Vector.newBuilder[A]
    buffer.sizeHint(n.max(0).min(1024))

    def fillBuffer(
      sizeRemaining: Int
    )(
      s: => AsyncStream[A]
    ): Future[(Seq[A], () => AsyncStream[A])] =
      if (sizeRemaining < 1) Future.value((buffer.result(), () => s))
      else
        s match {
          case Empty => Future.value((buffer.result(), () => s))

          case FromFuture(fa) =>
            fa.flatMap { a =>
              buffer += a
              Future.value((buffer.result(), () => empty))
            }

          case Cons(fa, more) =>
            fa.flatMap { a =>
              buffer += a
              fillBuffer(sizeRemaining - 1)(more())
            }

          case Embed(fas) =>
            fas.flatMap(as => fillBuffer(sizeRemaining)(as))
        }

    fillBuffer(n)(this)
  }

  /**
   * Convert the stream into a stream of groups of items. This
   * facilitates batch processing of the items in the stream. In all
   * cases, this method should act like
   * <https://www.scala-lang.org/api/current/index.html#scala.collection.IterableLike@grouped(size:Int):Iterator[Repr]>
   * The resulting stream will cause this original stream to be
   * evaluated group-wise, so calling this method will cause the first
   * `groupSize` cells to be evaluated (even without examining the
   * result), and accessing each subsequent element will evaluate a
   * further `groupSize` elements from the stream.
   * @param groupSize must be a positive number, or an IllegalArgumentException will be thrown.
   */
  def grouped(groupSize: Int): AsyncStream[Seq[A]] =
    if (groupSize > 1) {
      Embed(buffer(groupSize).map {
        case (items, _) if items.isEmpty => empty
        case (items, remaining) => Cons(Future.value(items), () => remaining().grouped(groupSize))
      })
    } else if (groupSize == 1) {
      map(Seq(_))
    } else {
      throw new IllegalArgumentException(s"groupSize must be positive, but was $groupSize")
    }

  /**
   * Add up the values of all of the elements in this stream. If you
   * hold a reference to the head of the stream, this will cause the
   * entire stream to be held in memory.
   *
   * Note: forces the stream. If the stream is infinite, the resulting future
   * is equivalent to Future.never.
   */
  def sum[B >: A](implicit numeric: Numeric[B]): Future[B] =
    foldLeft(numeric.zero)(numeric.plus)

  /**
   * Eagerly consume the entire stream and return the number of elements
   * that are in it. If you hold a reference to the head of the stream,
   * this will cause the entire stream to be held in memory.
   *
   * Note: forces the stream. If the stream is infinite, the resulting future
   * is equivalent to Future.never.
   */
  def size: Future[Int] = foldLeft(0)((n, _) => n + 1)

  /**
   * Force the entire stream. If you hold a reference to the head of the
   * stream, this will cause the entire stream to be held in memory. The
   * resulting Future will be satisfied once the entire stream has been
   * consumed.
   *
   * This is useful when you want the side-effects of consuming the
   * stream to occur, but do not need to do anything with the resulting
   * values.
   */
  def force: Future[Unit] = foreach { _ => }
}

object AsyncStream {
  private case object Empty extends AsyncStream[Nothing]
  private case class Embed[A](fas: Future[AsyncStream[A]]) extends AsyncStream[A]
  private case class FromFuture[A](fa: Future[A]) extends AsyncStream[A]

  /**
   * This is defined as a case class so that we get a generated unapply that works with
   *  [[AsyncStream]]s covariant type in Scala 3. See CSL-11207 or
   *  https://github.com/lampepfl/dotty/issues/13125
   */
  private case class Cons[A] private (fa: Future[A], next: () => AsyncStream[A])
      extends AsyncStream[A]
  object Cons {
    def apply[A](fut: Future[A], nextAsync: () => AsyncStream[A]): AsyncStream[A] = {
      lazy val _more: AsyncStream[A] = nextAsync()
      new Cons(fut, () => _more) {
        override def toString(): String = s"Cons($fut, $nextAsync)"
      }
    }
  }

  implicit class Ops[A](tail: => AsyncStream[A]) {

    /**
     * Right-associative infix Cons constructor.
     *
     * Note: Because of https://issues.scala-lang.org/browse/SI-1980 we can't
     * define +:: as a method on AsyncStream without losing tail laziness.
     */
    def +::[B >: A](b: B): AsyncStream[B] = mk(b, tail)
  }

  def empty[A]: AsyncStream[A] = Empty.asInstanceOf[AsyncStream[A]]

  /**
   * Var-arg constructor for AsyncStreams.
   *
   * {{{
   * AsyncStream(1,2,3)
   * }}}
   */
  @varargs
  def apply[A](as: A*): AsyncStream[A] = fromSeq(as)

  /**
   * An AsyncStream with a single element.
   */
  def of[A](a: A): AsyncStream[A] = FromFuture(Future.value(a))

  /**
   * Like `Ops.+::`.
   */
  def mk[A](a: A, tail: => AsyncStream[A]): AsyncStream[A] =
    Cons(Future.value(a), () => tail)

  /**
   * A failed AsyncStream
   */
  def exception[A](e: Throwable): AsyncStream[A] =
    fromFuture[A](Future.exception(e))

  /**
   * Transformation (or lift) from `Seq` into `AsyncStream`.
   */
  def fromSeq[A](seq: Seq[A]): AsyncStream[A] = seq match {
    case Nil => empty
    case _ if SeqUtil.hasKnownSize(seq) && seq.tail.isEmpty => of(seq.head)
    case _ => of(seq.head).flatMap(_ +:: fromSeq(seq.tail))
  }

  /**
   * Transformation (or lift) from [[com.twitter.util.Future]] into `AsyncStream`.
   */
  def fromFuture[A](f: Future[A]): AsyncStream[A] =
    FromFuture(f)

  /**
   * Transformation (or lift) from `Option` into `AsyncStream`.
   */
  def fromOption[A](o: Option[A]): AsyncStream[A] =
    o match {
      case None => empty
      case Some(a) => of(a)
    }

  /**
   * Lift from [[Future]] into `AsyncStream` and then flatten.
   */
  private[concurrent] def embed[A](fas: Future[AsyncStream[A]]): AsyncStream[A] =
    Embed(fas)

  private def extract[A](as: AsyncStream[A]): Future[Option[AsyncStream[A]]] =
    as match {
      case Empty => Future.None
      case Embed(fas) => fas.flatMap(extract)
      case _ => Future.value(Some(as))
    }

  /**
   * Java friendly [[AsyncStream.flatten]].
   */
  def flattens[A](as: AsyncStream[AsyncStream[A]]): AsyncStream[A] =
    as.flatten

  /**
   * Combinator, merges multiple [[AsyncStream]]s into a single stream. The
   * resulting stream contains elements in FIFO order per input stream but order
   * between streams is not guaranteed. The resulting stream is completed when
   * all input streams are completed. The stream is failed when any input stream
   * is failed
   */
  @varargs
  def merge[A](s: AsyncStream[A]*): AsyncStream[A] = {
    def step(next: Seq[Future[Option[(A, () => AsyncStream[A])]]]): AsyncStream[A] = {
      fromFuture(Future.select(next)).flatMap {
        case (Return(Some((head, tail))), tails) =>
          head +:: step(tail().uncons +: tails)
        case (Throw(cause), tails) =>
          fromFuture(Future.exception(cause))
        case (Return(None), Nil) =>
          empty
        case (Return(None), tails) =>
          step(tails)
      }
    }

    if (s.isEmpty) {
      empty
    } else {
      step(s.map(_.uncons))
    }
  }

  // Oneshot turns an AsyncStream into a resource, where each read depletes the
  // stream of that item. In other words, each item of the stream is observable
  // only once.
  //
  // more must be guarded by synchronization on this.
  private final class Oneshot[A](var more: () => AsyncStream[A]) {
    def read(): Future[Option[A]] = {
      // References to readEmbed args for the Embed case.
      var fas: Future[AsyncStream[A]] = null
      var headp: Promise[Option[A]] = null
      var tailp: Promise[() => AsyncStream[A]] = null

      // Reference to head for the default case.
      var headf: Future[Option[A]] = null

      synchronized {
        more() match {
          case Embed(embedded) =>
            // The Embed case is special because
            //
            //     val head = as.head
            //     as = as.drop(1)
            //
            // reduces to a chain of maps, which can be very long, as many as the
            // number of times read is called while waiting for the embedded
            // AsyncStream.
            //
            //     val head = fas.map(_.drop(1)).map(_.drop(1))....flatMap(_.head)
            //     as = Embed(fas.map(_.drop(1)).map(_.drop(1))...)
            //
            // In other words, for AsyncStreams with Embed tails, the naive
            // implementation multiplies the work of each item by the concurrency
            // level.
            //
            // The optimization here is straight forward: create a head and tail
            // promise and that wait for the embedded AsyncStream. Subsequent
            // calls to read will create new head and tail promises that wait on
            // the previous tail promise.
            fas = embedded
            headp = new Promise[Option[A]]
            tailp = new Promise[() => AsyncStream[A]]
            more = () => AsyncStream.embed(tailp.map(_()))
            headp

          case as =>
            headf = as.head
            more = () => as.drop(1)
        }
      }

      // Non-null fas implies Embed case.
      if (fas != null) {
        readEmbed(fas, headp, tailp)
        headp
      } else {
        headf
      }
    }

    private[this] def readEmbed(
      fas: Future[AsyncStream[A]],
      headp: Promise[Option[A]],
      tailp: Promise[() => AsyncStream[A]]
    ): Unit =
      fas.respond {
        case Throw(e) =>
          headp.setException(e)
          tailp.setException(e)
        case Return(v) =>
          v match {
            case Empty =>
              headp.become(Future.None)
              tailp.setValue(Oneshot.empty)
            case FromFuture(fa) =>
              headp.become(fa.map(Some(_)))
              tailp.setValue(Oneshot.empty)
            case Cons(fa, more2) =>
              headp.become(fa.map(Some(_)))
              tailp.setValue(more2)
            case Embed(newFas) =>
              readEmbed(newFas, headp, tailp)
          }
      }

    // Recover the AsyncStream interface. We don't return `as` because we want
    // to deplete the original stream with each read. With this we can create
    // multiple AsyncStream views of the same Oneshot resource, thus "fanning
    // out" the original stream into multiple distinct AsyncStreams.
    def toAsyncStream: AsyncStream[A] =
      AsyncStream.fromFuture(read()).flatMap {
        case None => AsyncStream.empty
        case Some(a) => a +:: toAsyncStream
      }
  }

  private object Oneshot {
    val emptyVal: () => AsyncStream[Nothing] = () => AsyncStream.empty[Nothing]
    def empty[A]: () => AsyncStream[A] = emptyVal.asInstanceOf[() => AsyncStream[A]]
  }

  private def fanout[A](as: AsyncStream[A], n: Int): Seq[AsyncStream[A]] = {
    val reader = new Oneshot(() => as)
    0.until(n).map(_ => reader.toAsyncStream)
  }
}
