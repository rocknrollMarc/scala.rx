package rx

import concurrent.{ExecutionContext, Future}
import java.util.concurrent.atomic.{AtomicLong, AtomicInteger}
import util.Try
import concurrent.duration._
import akka.actor.{Actor, Cancellable, ActorSystem}
import rx.Flow.{Settable, Reactor, Signal}
import rx.SyncSignals.DynamicSignal

/**
 * A collection of Rxs which may spontaneously update itself asynchronously,
 * even when nothing is going on. Use the extension methods in Combinators to
 * create these from other Rxs.
 *
 * These Rxs all required implicit ExecutionContexts and ActorSystems, in order
 * to properly schedule and fire the asynchronous operations.
 */
object AsyncSignals{

  abstract class Target[T](default: T){
    def handleSend(id: Long): Unit
    def handleReceive(id: Long, value: Try[T], callback: Try[T] => Unit): Unit
  }

  /**
   * Target which applies the result of the Future[T]s regardless
   * of when they come in. This may result in the results being applied out of
   * order, and the last-applied value may not be the result of the last-dispatched
   * Future[T].
   */
  case class RunAlways[T](default: T) extends Target[T](default){
    def handleSend(id: Long) = ()
    def handleReceive(id: Long, value: Try[T], callback: Try[T] => Unit) = {
      callback(value)
    }
  }

  /**
   * Target which applies the result of the Future[T] only if it was dispatched
   * after the Future[T] which created the current value. Future[T]s which
   * were the result of earlier dispatches are ignored.
   */
  case class DiscardLate[T](default: T) extends Target[T](default){
    val sendIndex = new AtomicLong(0)
    val receiveIndex = new AtomicLong(0)

    def handleSend(id: Long) = {
      sendIndex.set(id)
    }
    def handleReceive(id: Long, value: Try[T], callback: Try[T] => Unit) = {
      if (id >= receiveIndex.get()){
        receiveIndex.set(id)
        callback(value)
      }
    }
  }

  /**
   * A Rx which flattens out an Rx[Future[T]] into a Rx[T]. If the first
   * Future has not yet arrived, the AsyncSig contains its default value.
   * Afterwards, it updates itself when and with whatever the Futures complete
   * with.
   *
   * The AsyncSig can be configured with a variety of Targets, to configure
   * its handling of Futures which complete out of order (RunAlways, DiscardLate)
   */
  class AsyncSig[+T](default: T, source: Signal[Future[T]], targetC: T => Target[T])
                    (implicit executor: ExecutionContext)
    extends Settable[T](default){
    def name = "async " + source.name
    private[this] lazy val count = new AtomicLong(0)
    private[this] lazy val target = targetC(default)

    private[this] val listener = Obs(source){
      val future = source()
      val id = count.getAndIncrement
      target.handleSend(id)
      future.onComplete{ x =>
        target.handleReceive(id, x, updateS(_))
      }
    }
    listener.trigger()
  }

  /**
   * A Rx which does not change more than once per `interval` units of time. This
   * can cause it to change asynchronously, as an update which is ignored (due to
   * coming in before the interval has passed) will get spontaneously.
   */
  class ImmediateDebouncedSignal[+T](source: Signal[T], interval: FiniteDuration)
                        (implicit system: ActorSystem, ex: ExecutionContext)
    extends DynamicSignal[T]("debounced " + source.name, () => source()){

    @volatile private[this] var nextTime = Deadline.now
    @volatile private[this] var lastOutput: Option[Cancellable] = None

    override def ping(incoming: Seq[Flow.Emitter[Any]]) = {
      if (active && getParents.intersect(incoming).isDefinedAt(0)){
        val timeLeft = nextTime - Deadline.now
        (timeLeft.toMillis, lastOutput) match{
          case (t, _) if t < 0 =>
            nextTime = Deadline.now + interval
            super.ping(incoming)
          case (t, None) =>
            if (lastOutput.isEmpty) {
              lastOutput = Some(system.scheduler.scheduleOnce(timeLeft){
                super.ping(incoming)
                this.propagate()
              })
            }
            Nil
          case (t, Some(_)) =>
            Nil

        }
      } else Nil
    }
  }

  class DelayedRebounceSignal[+T](source: Signal[T], interval: FiniteDuration, delay: FiniteDuration)
                                  (implicit system: ActorSystem, ex: ExecutionContext)
  extends Settable(source.now){
    def name = "delayedDebounce " + source.name

    @volatile private[this] var nextTime = Deadline.now
    @volatile private[this] var lastOutput: Option[Cancellable] = None

    private val listener = Obs(source){
      val timeLeft = nextTime - Deadline.now
      lastOutput match {
        case (Some(_)) => Nil
        case (None) =>
          lastOutput = Some(system.scheduler.scheduleOnce(if (timeLeft < 0.seconds) delay else timeLeft){
            lastOutput = None
            nextTime = Deadline.now + interval
            this.updateS(source.now)
          })
          Nil
      }
    }
  }

}