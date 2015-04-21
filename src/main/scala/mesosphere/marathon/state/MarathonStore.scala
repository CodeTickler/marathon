package mesosphere.marathon.state

import com.codahale.metrics.{ Histogram, MetricRegistry }
import com.codahale.metrics.MetricRegistry.name
import com.google.protobuf.InvalidProtocolBufferException
import org.apache.mesos.state.{ State, Variable }
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ ExecutionException, Future }
import mesosphere.marathon.StorageException
import mesosphere.util.LockManager
import mesosphere.util.{ ThreadPoolContext, BackToTheFuture }
import mesosphere.marathon.MarathonConf
import scala.concurrent.duration._
import org.slf4j.LoggerFactory

import scala.util.{ Failure, Success }

object MarathonStore {
  private[MarathonStore] final case class CachedState[S <: MarathonState[_, S]](value: S, variable: Variable)
}

class MarathonStore[S <: MarathonState[_, S]](
  conf: MarathonConf,
  state: State,
  registry: MetricRegistry,
  newState: () => S,
  prefix: String = "app:")(
    implicit val timeout: BackToTheFuture.Timeout = BackToTheFuture.Timeout(Duration(conf.marathonStoreTimeout(),
      MILLISECONDS)))
    extends PersistenceStore[S] {

  import ThreadPoolContext.context
  import BackToTheFuture.futureToFutureOption
  import MarathonStore.CachedState

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] lazy val locks = LockManager[String]()
  private[this] val cachedState = TrieMap.empty[String, CachedState[S]]

  def contentClassName: String = newState().getClass.getSimpleName

  protected[this] val bytesRead: Histogram = registry.histogram(name(getClass, contentClassName, "read-data-size"))
  protected[this] val bytesWritten: Histogram = registry.histogram(name(getClass, contentClassName, "write-data-size"))

  def clearCachedState(): Unit = cachedState.clear()

  def fetch(key: String): Future[Option[S]] = fetchValueWithCache(key)

  def modify(key: String)(f: (() => S) => S): Future[Option[S]] = {
    val lock = locks.get(key)
    lock.acquire()

    val res: Future[Option[S]] = fetchVariableWithCache(key) flatMap {
      case Some(variable) =>
        bytesRead.update(variable.value.length)
        val deserialize = { () =>
          try {
            stateFromBytes(variable.value)
          }
          catch {
            case e: InvalidProtocolBufferException =>
              if (variable.value.nonEmpty) {
                log.error(s"Failed to read $key, could not deserialize data (${variable.value.length} bytes).", e)
              }
              newState()
          }
        }
        val newValue: S = f(deserialize)
        state.store(variable.mutate(newValue.toProtoByteArray)) map {
          case Some(newVar) =>
            bytesWritten.update(newVar.value.size)
            cachedState.update(key, CachedState(newValue, newVar))
            Some(stateFromBytes(newVar.value))
          case None =>
            throw new StorageException(s"Failed to store $key")
        }
      case None =>
        throw new StorageException(s"Failed to read $key, does not exist, should have been created automatically.")
    }

    res andThen {
      case _ =>
        lock.release()
    }
  }

  def expunge(key: String): Future[Boolean] = {
    val lock = locks.get(key)
    lock.acquire()

    val res = fetchVariableWithCache(key) flatMap {
      case Some(variable) =>
        bytesRead.update(Option(variable.value).map(_.length).getOrElse(0))
        state.expunge(variable) map {
          case Some(b) =>
            cachedState -= key
            b.booleanValue()
          case None => throw new StorageException(s"Failed to expunge $key")
        }

      case None => throw new StorageException(s"Failed to read $key")
    }

    res onComplete { _ =>
      lock.release()
    }

    res
  }

  def names(): Future[Iterator[String]] = {
    Future {
      try {
        state.names().get.asScala.collect {
          case name if name startsWith prefix =>
            name.replaceFirst(prefix, "")
        }
      }
      catch {
        // Thrown when node doesn't exist
        case e: ExecutionException => Seq().iterator
      }
    }
  }

  protected def fetchVariableWithCache(key: String): Future[Option[Variable]] = {
    def fromCache: Future[Option[Variable]] = cachedState.get(key) match {
      case Some(CachedState(_, variable)) => Future.successful(Some(variable))
      case _ =>
        Future.failed(new NoSuchElementException(s"Couldn't find cached value for key '$key'"))
    }

    def fromState(): Future[Option[Variable]] = state.fetch(prefix + key)

    fromCache recoverWith {
      case _ => fromState()
    }
  }

  protected def fetchWithCache(key: String): Future[Option[CachedState[S]]] = {
    val res = fetchVariableWithCache(key).map {
      case Some(variable) =>
        bytesRead.update(variable.value.length)
        try {
          Some(CachedState(stateFromBytes(variable.value), variable))
        }
        catch {
          case e: InvalidProtocolBufferException =>
            if (variable.value.nonEmpty) {
              log.error(s"Failed to read $key, could not deserialize data (${variable.value.length} bytes).", e)
            }
            None
        }
      case None =>
        throw new StorageException(s"Failed to read $key, does not exist, should have been created automatically.")
    }

    res andThen {
      case Success(Some(value)) =>
        // cache value for future requests
        cachedState.putIfAbsent(key, value)
      case Failure(_) => // nothing to cache
    }
  }

  protected def fetchValueWithCache(key: String): Future[Option[S]] = fetchWithCache(key).map {
    case Some(CachedState(value, _)) => Some(value)
    case _                           => None
  }

  private def stateFromBytes(bytes: Array[Byte]): S = {
    newState().mergeFromProto(bytes)
  }
}
