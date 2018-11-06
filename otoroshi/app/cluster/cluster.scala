package cluster

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.regex.Pattern

import actions.ApiAction
import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Framing, Sink, Source}
import akka.util.ByteString
import auth.AuthConfigsDataStore
import com.typesafe.config.ConfigFactory
import env.Env
import events.{AlertDataStore, AuditDataStore, HealthCheckDataStore}
import gateway.{InMemoryRequestsDataStore, RequestsDataStore}
import models._
import play.api.http.HttpEntity
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.libs.ws.{InMemoryBody, WSAuthScheme}
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents}
import play.api.{Configuration, Environment, Logger}
import ssl.CertificateDataStore
import storage.inmemory._
import storage.{DataStoreHealth, DataStores, Healthy, RedisLike}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * # TODO:
  *
  * [ ] push stats increments (calls, duration, data in/ou) to leader
  * [ ] support cassandra
  * [ ] support mongo
  *
  * # Test
  *
  * java -Dhttp.port=8080 -Dhttps.port=8443 -Dotoroshi.cluster.mode=leader -jar otoroshi.jar
  * java -Dhttp.port=9080 -Dhttps.port=9443 -Dotoroshi.cluster.mode=worker -jar otoroshi.jar
  *
  */
object Cluster {
  lazy val logger = Logger("otoroshi-cluster")
}

trait ClusterMode {
  def name: String
  def clusterActive: Boolean
  def isWorker: Boolean
  def isLeader: Boolean
}

object ClusterMode {
  case object Off extends ClusterMode {
    def name: String = "Off"
    def clusterActive: Boolean = false
    def isWorker: Boolean = false
    def isLeader: Boolean = false
  }
  case object Leader extends ClusterMode {
    def name: String = "Leader"
    def clusterActive: Boolean = true
    def isWorker: Boolean = false
    def isLeader: Boolean = true
  }
  case object Worker extends ClusterMode {
    def name: String = "Worker"
    def clusterActive: Boolean = true
    def isWorker: Boolean = true
    def isLeader: Boolean = false
  }
  val values: Seq[ClusterMode] =
    Seq(Off, Leader, Worker)
  def apply(name: String): Option[ClusterMode] = name match {
    case "Off"             => Some(Off)
    case "Leader"          => Some(Leader)
    case "Worker"          => Some(Worker)
    case "off"             => Some(Off)
    case "leader"          => Some(Leader)
    case "worker"          => Some(Worker)
    case _                 => None
  }
}

case class WorkerQuotasConfig(pushEvery: Long = 2000)
case class WorkerStateConfig(pollEvery: Long = 10000)
case class WorkerConfig(state: WorkerStateConfig = WorkerStateConfig(), quotas: WorkerQuotasConfig = WorkerQuotasConfig())
case class LeaderConfig(urls: Seq[String] = Seq.empty, host: String = "otoroshi-api.foo.bar", clientId: String = "admin-api-apikey-id", clientSecret: String = "admin-api-apikey-secret", groupingBy: Int = 50)
case class ClusterConfig(mode: ClusterMode = ClusterMode.Off, leader: LeaderConfig = LeaderConfig(), worker: WorkerConfig = WorkerConfig())
object ClusterConfig {
  def apply(configuration: Configuration): ClusterConfig = {
    // Cluster.logger.debug(configuration.underlying.root().render(ConfigRenderOptions.concise()))
    ClusterConfig(
      mode = configuration.getOptional[String]("mode").flatMap(ClusterMode.apply).getOrElse(ClusterMode.Off),
      leader = LeaderConfig(
        urls = configuration.getOptional[Seq[String]]("leader.urls").map(_.toSeq).getOrElse(Seq("http://otoroshi-api.foo.bar:8080")),
        host = configuration.getOptional[String]("leader.host").getOrElse("otoroshi-api.foo.bar"),
        clientId = configuration.getOptional[String]("leader.clientId").getOrElse("admin-api-apikey-id"),
        clientSecret = configuration.getOptional[String]("leader.clientSecret").getOrElse("admin-api-apikey-secret"),
        groupingBy = configuration.getOptional[Int]("leader.groupingBy").getOrElse(50)
      ),
      worker = WorkerConfig(
        state = WorkerStateConfig(
          pollEvery = configuration.getOptional[Long]("worker.state.pollEvery").getOrElse(10000L)
        ),
        quotas = WorkerQuotasConfig(
          pushEvery = configuration.getOptional[Long]("worker.quotas.pushEvery").getOrElse(2000L)
        )
      )
    )
  }
}

class ClusterController(ApiAction: ApiAction, cc: ControllerComponents)(
  implicit env: Env
) extends AbstractController(cc) {

  import cluster.ClusterMode.{Leader, Off, Worker}

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val sourceBodyParser = BodyParser("ClusterController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def isSessionValid(sessionId: String) = ApiAction.async { ctx =>
    env.clusterConfig.mode match {
      case Off => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Worker => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Leader => {
        Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] valid session $sessionId")
        env.datastores.privateAppsUserDataStore.findById(sessionId).map {
          case Some(user) => Ok(user.toJson)
          case None       => NotFound(Json.obj("error" -> "Session not found"))
        }
      }
    }
  }

  def createSession() = ApiAction.async(parse.json) { ctx =>
    env.clusterConfig.mode match {
      case Off => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Worker => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Leader => {
        Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] creating session")
        PrivateAppsUser.fmt.reads(ctx.request.body) match {
          case JsError(e) => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad session format")))
          case JsSuccess(user, _) => user.save(Duration(System.currentTimeMillis() - user.expiredAt.getMillis, TimeUnit.MILLISECONDS)).map { session =>
            Ok(session.toJson)
          }
        }
      }
    }
  }

  def updateQuotas() = ApiAction.async(sourceBodyParser) { ctx =>
    env.clusterConfig.mode match {
      case Off => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Worker => FastFuture.successful(NotFound(Json.obj("error" -> "Cluster API not available")))
      case Leader => {
        Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] updating quotas")
        ctx.request.body.via(Framing.delimiter(ByteString("\n"), 100000)).mapAsync(4) { item =>
          val jsItem = Json.parse(item.utf8String)
          val id = (jsItem \ "apiKeyId").asOpt[String].getOrElse("--")
          val increment = (jsItem \ "increment").asOpt[Long].getOrElse(0L)
          env.datastores.apiKeyDataStore.findById(id).flatMap {
            case Some(apikey) => env.datastores.apiKeyDataStore.updateQuotas(apikey, increment).andThen {
              case e => Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Increment of ${increment} for apikey ${apikey.clientName}")
            }
            case None => FastFuture.successful(())
          }
        }.runWith(Sink.ignore)
          .map(_ => Ok(Json.obj("done" -> true)))
          .recover {
            case e => InternalServerError(Json.obj("error" -> e.getMessage))
          }
      }
    }
  }

  def internalState() = ApiAction { ctx =>
    env.clusterConfig.mode match {
      case Off => NotFound(Json.obj("error" -> "Cluster API not available"))
      case Worker => NotFound(Json.obj("error" -> "Cluster API not available"))
      case Leader => {
        val start = System.currentTimeMillis()
        // Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Exporting raw state")
        Ok.sendEntity(HttpEntity.Streamed(env.datastores.rawExport(env.clusterConfig.leader.groupingBy).alsoTo(Sink.onComplete {
          case Success(_) => Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Exported raw state in ${System.currentTimeMillis - start} ms.")
          case Failure(e) => Cluster.logger.error(s"[${env.clusterConfig.mode.name}] Stream error while exporting raw state", e)
        }).map { item =>
          ByteString(Json.stringify(item) + "\n")
        }, None, Some("application/x-ndjson")))
      }
    }
  }
}

object ClusterAgent {
  def apply(config: ClusterConfig, env: Env) = new ClusterAgent(config, env)
}

class ClusterAgent(config: ClusterConfig, env: Env) {

  import scala.concurrent.duration._

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  private val pollRef = new AtomicReference[Cancellable]()
  private val pushRef = new AtomicReference[Cancellable]()
  private val counter = new AtomicInteger(0)
  private val incrementsRef = new AtomicReference[TrieMap[String, AtomicLong]](new TrieMap[String, AtomicLong]())

  private def otoroshiUrl: String = {
    val count = counter.incrementAndGet() % (if (config.leader.urls.nonEmpty) config.leader.urls.size else 1)
    config.leader.urls.zipWithIndex.find(t => t._2 == count).map(_._1).getOrElse(config.leader.urls.head)
  }

  def isSessionValid(id: String): Future[Option[PrivateAppsUser]] = {
    env.Ws.url(otoroshiUrl + s"/api/cluster/sessions/$id")
      .withHttpHeaders("Host" -> config.leader.host)
      .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
      .get()
      .map { resp =>
        if (resp.status == 200) {
          PrivateAppsUser.fmt.reads(Json.parse(resp.body)).asOpt
        } else {
          None
        }
      }
  }

  def createSession(user: PrivateAppsUser): Future[Unit] = {
    env.Ws.url(otoroshiUrl + s"/api/cluster/sessions")
      .withHttpHeaders("Host" -> config.leader.host, "Content-Type" -> "application/json")
      .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
      .post(user.toJson)
      .map(_ => ())
  }

  def incrementApi(id: String, increment: Long): Unit = {
    if (env.clusterConfig.mode == ClusterMode.Worker) {
      Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Increment API $id")
      if (!incrementsRef.get().contains(id)) {
        incrementsRef.get().putIfAbsent(id, new AtomicLong(0L))
      }
      incrementsRef.get().get(id).foreach(_.incrementAndGet())
    }
  }

  def fromJson(what: String, value: JsValue): Any = {

    import collection.JavaConverters._

    what match {
      case "string" => ByteString(value.as[String])
      case "set" => {
        val list = new java.util.concurrent.CopyOnWriteArraySet[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        list
      }
      case "list" => {
        val list = new java.util.concurrent.CopyOnWriteArrayList[ByteString]
        list.addAll(value.as[JsArray].value.map(a => ByteString(a.as[String])).asJava)
        list
      }
      case "hash" => {
        val map = new java.util.concurrent.ConcurrentHashMap[String, ByteString]
        map.putAll(value.as[JsObject].value.map(t => (t._1, ByteString(t._2.as[String]))).asJava)
        map
      }
    }
  }

  // TODO: retry on errors
  private def pollState(): Unit = {
    Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Fetching state from Otoroshi leader cluster")
    val start = System.currentTimeMillis()
    env.Ws.url(otoroshiUrl + "/api/cluster/state")
      .withHttpHeaders("Host" -> config.leader.host)
      .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
      .withMethod("GET")
      .stream()
      .filter(_.status == 200)
      .map { resp =>
        val store = new ConcurrentHashMap[String, Any]()
        val expirations = new ConcurrentHashMap[String, Long]()
        resp.bodyAsSource
          .via(Framing.delimiter(ByteString("\n"), 100000))
          .map(bs => Json.parse(bs.utf8String))
          .runWith(Sink.foreach { item =>
            val key = (item \ "k").as[String]
            val value = (item \ "v").as[JsValue]
            val what = (item \ "w").as[String]
            val ttl = (item \ "t").asOpt[Long].getOrElse(-1L)
            store.put(key, fromJson(what, value))
            if (ttl > -1L) {
              expirations.put(key, ttl)
            }
          }).map { _ =>
            Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Consumed state in ${System.currentTimeMillis() - start} ms.")
            env.datastores.asInstanceOf[SwappableInMemoryDataStores].swap(Memory(store, expirations))
          }
      }.recover {
      case e => Cluster.logger.error(s"[${env.clusterConfig.mode.name}] Error while trying to fetch state from Otoroshi leader cluster", e)
    }
  }

  // TODO: retry on errors or update in map
  private def pushQuotas(): Unit = {
    val old = incrementsRef.getAndSet(new TrieMap[String, AtomicLong]())
    if (old.nonEmpty) {
      Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Pushing api quotas updates to Otoroshi leader cluster")
      val body = old.toSeq.map {
        case (key, inc) => ByteString(Json.stringify(Json.obj("key" -> key, "increment" -> inc.get())) + "\n")
      }.fold(ByteString.empty)(_ ++ _)
      val wsBody = InMemoryBody(body)
      env.Ws.url(otoroshiUrl + "/api/cluster/quotas")
        .withHttpHeaders("Host" -> config.leader.host, "Content-Type" -> "application/x-ndjson")
        .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
        .withMethod("PUT")
        .withBody(wsBody)
        .stream()
        .filter(_.status == 200)
        .recover {
          case e => Cluster.logger.error(s"[${env.clusterConfig.mode.name}] Error while trying to push api quotas updates to Otoroshi leader cluster", e)
        }
    }
  }

  def startF(): Future[Unit] = FastFuture.successful(start())

  def start(): Unit = {
    if (config.mode == ClusterMode.Worker) {
      Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Starting cluster agent")
      pollRef.set(env.otoroshiScheduler.schedule(1.second, config.worker.state.pollEvery.millis)(pollState()))
      pushRef.set(env.otoroshiScheduler.schedule(1.second, config.worker.quotas.pushEvery.millis)(pushQuotas()))
    }
  }
  def stop(): Unit = {
    if (config.mode == ClusterMode.Worker) {
      Option(pollRef.get()).foreach(_.cancel())
      Option(pushRef.get()).foreach(_.cancel())
    }
  }
}

class SwappableInMemoryDataStores(configuration: Configuration,
                         environment: Environment,
                         lifecycle: ApplicationLifecycle,
                         env: Env)
  extends DataStores {

  lazy val redisStatsItems: Int  = configuration.get[Option[Int]]("app.inmemory.windowSize").getOrElse(99)
  lazy val experimental: Boolean = configuration.get[Option[Boolean]]("app.inmemory.experimental").getOrElse(false)
  lazy val actorSystem =
    ActorSystem(
      "otoroshi-inmemory-system",
      configuration
        .getOptional[Configuration]("app.actorsystems.datastore")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )
  lazy val redis: SwappableInMemoryRedis = new SwappableInMemoryRedis(env, actorSystem)

  override def before(configuration: Configuration,
                      environment: Environment,
                      lifecycle: ApplicationLifecycle): Future[Unit] = {
    Cluster.logger.warn("Now using Swappable InMemory DataStores")
    redis.start()
    FastFuture.successful(())
  }

  override def after(configuration: Configuration,
                     environment: Environment,
                     lifecycle: ApplicationLifecycle): Future[Unit] = {
    redis.stop()
    actorSystem.terminate()
    FastFuture.successful(())
  }

  def swap(memory: Memory): Unit = {
    redis.swap(memory)
  }

  private lazy val _privateAppsUserDataStore   = new InMemoryPrivateAppsUserDataStore(redis, env)
  private lazy val _backOfficeUserDataStore    = new InMemoryBackOfficeUserDataStore(redis, env)
  private lazy val _serviceGroupDataStore      = new InMemoryServiceGroupDataStore(redis, env)
  private lazy val _globalConfigDataStore      = new InMemoryGlobalConfigDataStore(redis, env)
  private lazy val _apiKeyDataStore            = new InMemoryApiKeyDataStore(redis, env)
  private lazy val _serviceDescriptorDataStore = new InMemoryServiceDescriptorDataStore(redis, redisStatsItems, env)
  private lazy val _u2FAdminDataStore          = new InMemoryU2FAdminDataStore(redis)
  private lazy val _simpleAdminDataStore       = new InMemorySimpleAdminDataStore(redis, env)
  private lazy val _alertDataStore             = new InMemoryAlertDataStore(redis)
  private lazy val _auditDataStore             = new InMemoryAuditDataStore(redis)
  private lazy val _healthCheckDataStore       = new InMemoryHealthCheckDataStore(redis, env)
  private lazy val _errorTemplateDataStore     = new InMemoryErrorTemplateDataStore(redis, env)
  private lazy val _requestsDataStore          = new InMemoryRequestsDataStore()
  private lazy val _canaryDataStore            = new InMemoryCanaryDataStore(redis, env)
  private lazy val _chaosDataStore             = new InMemoryChaosDataStore(redis, env)
  private lazy val _jwtVerifDataStore          = new InMemoryGlobalJwtVerifierDataStore(redis, env)
  private lazy val _authConfigsDataStore       = new InMemoryAuthConfigsDataStore(redis, env)
  private lazy val _certificateDataStore       = new InMemoryCertificateDataStore(redis, env)

  override def privateAppsUserDataStore: PrivateAppsUserDataStore               = _privateAppsUserDataStore
  override def backOfficeUserDataStore: BackOfficeUserDataStore                 = _backOfficeUserDataStore
  override def serviceGroupDataStore: ServiceGroupDataStore                     = _serviceGroupDataStore
  override def globalConfigDataStore: GlobalConfigDataStore                     = _globalConfigDataStore
  override def apiKeyDataStore: ApiKeyDataStore                                 = _apiKeyDataStore
  override def serviceDescriptorDataStore: ServiceDescriptorDataStore           = _serviceDescriptorDataStore
  override def u2FAdminDataStore: U2FAdminDataStore                             = _u2FAdminDataStore
  override def simpleAdminDataStore: SimpleAdminDataStore                       = _simpleAdminDataStore
  override def alertDataStore: AlertDataStore                                   = _alertDataStore
  override def auditDataStore: AuditDataStore                                   = _auditDataStore
  override def healthCheckDataStore: HealthCheckDataStore                       = _healthCheckDataStore
  override def errorTemplateDataStore: ErrorTemplateDataStore                   = _errorTemplateDataStore
  override def requestsDataStore: RequestsDataStore                             = _requestsDataStore
  override def canaryDataStore: CanaryDataStore                                 = _canaryDataStore
  override def chaosDataStore: ChaosDataStore                                   = _chaosDataStore
  override def globalJwtVerifierDataStore: GlobalJwtVerifierDataStore           = _jwtVerifDataStore
  override def authConfigsDataStore: AuthConfigsDataStore                       = _authConfigsDataStore
  override def certificatesDataStore: CertificateDataStore                      = _certificateDataStore
  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = redis.health()(ec)
  override def rawExport(group: Int)(implicit ec: ExecutionContext, mat: Materializer, env: Env): Source[JsValue, NotUsed] = throw new RuntimeException("Worker do not have to raw export !")
  override def rawSet(key: String, value: ByteString, px: Option[Long])(implicit ec: ExecutionContext, env: Env): Future[Boolean] = throw new RuntimeException("Worker do not have to raw set !")
}

class Memory(
  val store: ConcurrentHashMap[String, Any],
  val expirations: ConcurrentHashMap[String, Long]
)

object Memory {
  def apply(store: ConcurrentHashMap[String, Any], expirations: ConcurrentHashMap[String, Long]) = new Memory(store, expirations)
}

class SwappableInMemoryRedis(env: Env, actorSystem: ActorSystem) extends RedisLike {

  import actorSystem.dispatcher

  import collection.JavaConverters._
  import scala.concurrent.duration._

  val patterns: ConcurrentHashMap[String, Pattern] = new ConcurrentHashMap[String, Pattern]()

  private lazy val _storeHolder = new AtomicReference[Memory](Memory(
    store = new ConcurrentHashMap[String, Any],
    expirations = new ConcurrentHashMap[String, Long]
  ))

  @inline private def store: ConcurrentHashMap[String, Any] = _storeHolder.get().store
  @inline private def expirations: ConcurrentHashMap[String, Long] = _storeHolder.get().expirations

  private val cancel = actorSystem.scheduler.schedule(0.millis, 100.millis) {
    val time = System.currentTimeMillis()
    expirations.entrySet().asScala.foreach { entry =>
      if (entry.getValue < time) {
        store.remove(entry.getKey)
        expirations.remove(entry.getKey)
      }
    }
    ()
  }

  def swap(memory: Memory): Unit = {
    val oldSize = store.keySet.size
    _storeHolder.updateAndGet(_ => memory)
    val newSize = store.keySet.size
    Cluster.logger.debug(s"[${env.clusterConfig.mode.name}] Swapping store instance now ! ($oldSize / $newSize)")
  }

  override def stop(): Unit =
    cancel.cancel()

  override def flushall(): Future[Boolean] = {
    store.clear()
    expirations.clear()
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def get(key: String): Future[Option[ByteString]] = {
    val value = Option(store.get(key)).map(_.asInstanceOf[ByteString])
    FastFuture.successful(value)
  }

  override def set(key: String,
                   value: String,
                   exSeconds: Option[Long] = None,
                   pxMilliseconds: Option[Long] = None): Future[Boolean] =
    setBS(key, ByteString(value), exSeconds, pxMilliseconds)

  override def setBS(key: String,
                     value: ByteString,
                     exSeconds: Option[Long] = None,
                     pxMilliseconds: Option[Long] = None): Future[Boolean] = {
    store.put(key, value)
    if (exSeconds.isDefined) {
      expire(key, exSeconds.get.toInt)
    }
    if (pxMilliseconds.isDefined) {
      pexpire(key, pxMilliseconds.get)
    }
    FastFuture.successful(true)
  }

  override def del(keys: String*): Future[Long] = {
    val value = keys
      .map { k =>
        store.remove(k)
        1L
      }
      .foldLeft(0L)((a, b) => a + b)
    FastFuture.successful(value)
  }

  override def incr(key: String): Future[Long] = incrby(key, 1L)

  override def incrby(key: String, increment: Long): Future[Long] = {
    val value: Long    = Option(store.get(key)).map(_.asInstanceOf[ByteString]).map(_.utf8String.toLong).getOrElse(0L)
    val newValue: Long = value + increment
    store.put(key, ByteString(newValue.toString))
    FastFuture.successful(newValue)
  }

  override def exists(key: String): Future[Boolean] = FastFuture.successful(store.containsKey(key))

  override def mget(keys: String*): Future[Seq[Option[ByteString]]] =
    FastFuture.sequence(keys.map(k => get(k)))

  override def keys(pattern: String): Future[Seq[String]] = {
    val pat = patterns.computeIfAbsent(pattern, _ => Pattern.compile(pattern.replaceAll("\\*", ".*")))
    FastFuture.successful(
      store
        .keySet()
        .asScala
        .filter { k =>
          pat.matcher(k).find
        }
        .toSeq
    )
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def hdel(key: String, fields: String*): Future[Long] = {
    val hash = if (!store.containsKey(key)) {
      new ConcurrentHashMap[String, ByteString]()
    } else {
      store.get(key).asInstanceOf[ConcurrentHashMap[String, ByteString]]
    }
    val value = hash
      .keySet()
      .asScala
      .filter(k => fields.contains(k))
      .map(k => {
        hash.remove(k)
        1L
      })
      .foldLeft(0L)(_ + _)
    FastFuture.successful(value)
  }

  override def hgetall(key: String): Future[Map[String, ByteString]] = {
    val hash = if (!store.containsKey(key)) {
      new ConcurrentHashMap[String, ByteString]()
    } else {
      store.get(key).asInstanceOf[ConcurrentHashMap[String, ByteString]]
    }
    FastFuture.successful(hash.asScala.toMap)
  }

  override def hset(key: String, field: String, value: String): Future[Boolean] = hsetBS(key, field, ByteString(value))

  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = {
    val hash = if (!store.containsKey(key)) {
      new ConcurrentHashMap[String, ByteString]()
    } else {
      store.get(key).asInstanceOf[ConcurrentHashMap[String, ByteString]]
    }
    hash.put(field, value)
    store.put(key, hash)
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def emptySeq(): java.util.List[ByteString] =
    new java.util.concurrent.CopyOnWriteArrayList[ByteString]

  override def llen(key: String): Future[Long] = {
    val value = Option(store.get(key)).map(_.asInstanceOf[Seq[ByteString]]).getOrElse(Seq.empty[ByteString]).size.toLong
    FastFuture.successful(value)
  }

  override def lpush(key: String, values: String*): Future[Long] = lpushBS(key, values.map(ByteString.apply): _*)

  override def lpushLong(key: String, values: Long*): Future[Long] =
    lpushBS(key, values.map(_.toString).map(ByteString.apply): _*)

  override def lpushBS(key: String, values: ByteString*): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySeq())
    }
    val seq = store.get(key).asInstanceOf[java.util.List[ByteString]]
    seq.addAll(0, values.asJava)
    FastFuture.successful(values.size.toLong)
  }

  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = {
    val seq    = Option(store.get(key)).map(_.asInstanceOf[java.util.List[ByteString]]).getOrElse(emptySeq())
    val result = seq.asScala.slice(start.toInt, stop.toInt - start.toInt)
    FastFuture.successful(result)
  }

  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySeq())
    }
    val seq    = store.get(key).asInstanceOf[java.util.List[ByteString]]
    val result = seq.asScala.slice(start.toInt, stop.toInt - start.toInt).asJava
    seq.retainAll(result)
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def pttl(key: String): Future[Long] =
    FastFuture.successful(
      Option(expirations.get(key))
        .map(e => {
          val ttlValue = e - System.currentTimeMillis()
          if (ttlValue < 0) -1L else ttlValue
        })
        .getOrElse(-1L)
    )

  override def ttl(key: String): Future[Long] =
    pttl(key).map(t => Duration(t, TimeUnit.MILLISECONDS).toSeconds)

  override def expire(key: String, seconds: Int): Future[Boolean] = {
    expirations.put(key, System.currentTimeMillis() + (seconds * 1000L))
    FastFuture.successful(true)
  }

  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = {
    expirations.put(key, System.currentTimeMillis() + milliseconds)
    FastFuture.successful(true)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def emptySet(): java.util.Set[ByteString] =
    new java.util.concurrent.CopyOnWriteArraySet[ByteString]

  override def sadd(key: String, members: String*): Future[Long] = saddBS(key, members.map(ByteString.apply): _*)

  override def saddBS(key: String, members: ByteString*): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySet())
    }
    val seq = store.get(key).asInstanceOf[java.util.Set[ByteString]]
    seq.addAll(members.asJava)
    FastFuture.successful(members.size.toLong)
  }

  override def sismember(key: String, member: String): Future[Boolean] = sismemberBS(key, ByteString(member))

  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = {
    val seq = Option(store.get(key)).map(_.asInstanceOf[java.util.Set[ByteString]]).getOrElse(emptySet())
    FastFuture.successful(seq.contains(member))
  }

  override def smembers(key: String): Future[Seq[ByteString]] = {
    val seq = Option(store.get(key)).map(_.asInstanceOf[java.util.Set[ByteString]]).getOrElse(emptySet())
    FastFuture.successful(seq.asScala.toSeq)
  }

  override def srem(key: String, members: String*): Future[Long] = sremBS(key, members.map(ByteString.apply): _*)

  override def sremBS(key: String, members: ByteString*): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySet())
    }
    val seq    = store.get(key).asInstanceOf[java.util.Set[ByteString]]
    val newSeq = seq.asScala.filterNot(b => members.contains(b))
    seq.retainAll(newSeq.asJava)
    FastFuture.successful(members.size.toLong)
  }

  override def scard(key: String): Future[Long] = {
    if (!store.containsKey(key)) {
      store.putIfAbsent(key, emptySet())
    }
    val seq = store.get(key).asInstanceOf[java.util.Set[ByteString]]
    FastFuture.successful(seq.size.toLong)
  }

  def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = FastFuture.successful(Healthy)
}

