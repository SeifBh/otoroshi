package actions

import java.util.Base64

import akka.http.scaladsl.util.FastFuture
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import env.Env
import models.{ApiKey, BackOfficeUser, GlobalConfig}
import otoroshi.models.{RightsChecker, TeamAccess, TenantAccess}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import utils.RequestImplicits._

case class ApiActionContext[A](apiKey: ApiKey, request: Request[A]) {
  def user(implicit env: Env): Option[JsValue] =
    request.headers
      .get(env.Headers.OtoroshiAdminProfile)
      .flatMap(p => Try(Json.parse(new String(Base64.getDecoder.decode(p), Charsets.UTF_8))).toOption)
  def from(implicit env: Env): String = request.theIpAddress
  def ua: String                      = request.theUserAgent
  def checkRights(rc: RightsChecker)(f: Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    request.headers.get("Otoroshi-BackOffice-User") match {
      case None =>
        val tenantAccess = apiKey.metadata.get("otoroshi-tenants-access")
        val teamAccess = apiKey.metadata.get("otoroshi-teams-access")
        (tenantAccess, teamAccess) match {
          case (None, None) => f // standard api usage
          case (Some(tenants), Some(teams)) =>
            val user = BackOfficeUser(
              randomId = IdGenerator.token,
              name = apiKey.clientName,
              email = apiKey.clientId,
              profile = Json.obj(),
              authConfigId = "apikey",
              simpleLogin  = false,
              metadata = Map.empty,
              teams = teams.split(",").map(_.trim).map(TeamAccess.apply),
              tenants = tenants.split(",").map(_.trim).map(TenantAccess.apply),
            )
            rc.canPerform(user, None) match {
              case false => Results.Unauthorized(Json.obj("error" -> "You're not authorized here !")).future
              case true => f
            }
          case _ => Results.Unauthorized(Json.obj("error" -> "You're not authorized here (invalid setup) ! ")).future
        }
      case Some(userJwt) =>
        Try(JWT.require(Algorithm.HMAC512(apiKey.clientSecret)).build().verify(userJwt)) match {
          case Failure(e) =>
            Results.Unauthorized(Json.obj("error" -> "You're not authorized here !")).future
          case Success(decoded) => {
              val tenant = Option(decoded.getClaim("tenant"))
                .flatMap(c => Try(c.asString()).toOption)
              Option(decoded.getClaim("user"))
                .flatMap(c => Try(c.asString()).toOption)
                .flatMap(u => Try(Json.parse(u)).toOption)
                .flatMap(u => BackOfficeUser.fmt.reads(u).asOpt) match {
                case None => Results.Unauthorized(Json.obj("error" -> "You're not authorized here !")).future
                case Some(user) => rc.canPerform(user, tenant) match {
                  case false => Results.Unauthorized(Json.obj("error" -> "You're not authorized here !")).future
                  case true => f
                }
            }
          }
        }
    }
  }
}

class ApiAction(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[ApiActionContext, AnyContent]
    with ActionFunction[Request, ApiActionContext] {

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-api-action")

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

  def error(message: String, ex: Option[Throwable] = None)(implicit request: Request[_]): Future[Result] = {
    ex match {
      case Some(e) => logger.error(s"error message: $message", e)
      case None    => logger.error(s"error message: $message")
    }
    FastFuture.successful(
      Results
        .Unauthorized(Json.obj("error" -> message))
        .withHeaders(
          env.Headers.OtoroshiStateResp -> request.headers.get(env.Headers.OtoroshiState).getOrElse("--")
        )
    )
  }

  override def invokeBlock[A](request: Request[A], block: ApiActionContext[A] => Future[Result]): Future[Result] = {

    implicit val req = request

    val host = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.adminApiHost => {
        request.headers.get(env.Headers.OtoroshiClaim).get.split("\\.").toSeq match {
          case Seq(head, body, signature) => {
            val claim = Json.parse(new String(OtoroshiClaim.decoder.decode(body), Charsets.UTF_8))
            (claim \ "sub").as[String].split(":").toSeq match {
              case Seq("apikey", clientId) => {
                env.datastores.globalConfigDataStore
                  .singleton()
                  .filter(c => request.method.toLowerCase() == "get" || !c.apiReadOnly)
                  .flatMap { _ =>
                    env.datastores.apiKeyDataStore.findById(clientId).flatMap {
                      case Some(apikey) if apikey.authorizedGroup == env.backOfficeGroup.id => {
                        block(ApiActionContext(apikey, request)).foldM {
                          case Success(res) =>
                            res
                              .withHeaders(
                                env.Headers.OtoroshiStateResp -> request.headers
                                  .get(env.Headers.OtoroshiState)
                                  .getOrElse("--")
                              )
                              .asFuture
                          case Failure(err) => error(s"Server error : $err", Some(err))
                        }
                      }
                      case _ => error(s"You're not authorized1 - ${request.method} ${request.uri}")
                    }
                  } recoverWith {
                  case e =>
                    e.printStackTrace()
                    error(s"You're not authorized2 - ${request.method} ${request.uri}")
                }
              }
              case _ => error(s"You're not authorized3 - ${request.method} ${request.uri}")
            }
          }
          case _ => error(s"You're not authorized4 - ${request.method} ${request.uri}")
        }
      }
      case _ => error(s"Not found")
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

case class UnAuthApiActionContent[A](req: Request[A])

class UnAuthApiAction(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[UnAuthApiActionContent, AnyContent]
    with ActionFunction[Request, UnAuthApiActionContent] {

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-api-action")

  def error(message: String, ex: Option[Throwable] = None)(implicit request: Request[_]): Future[Result] = {
    ex match {
      case Some(e) => logger.error(s"error message: $message", e)
      case None    => logger.error(s"error message: $message")
    }
    FastFuture.successful(
      Results
        .Unauthorized(Json.obj("error" -> message))
        .withHeaders(
          env.Headers.OtoroshiStateResp -> request.headers.get(env.Headers.OtoroshiState).getOrElse("--")
        )
    )
  }

  override def invokeBlock[A](request: Request[A],
                              block: UnAuthApiActionContent[A] => Future[Result]): Future[Result] = {

    implicit val req = request

    val host = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.adminApiHost => block(UnAuthApiActionContent(request))
      case _                => error(s"Not found")
    }
  }

  override protected def executionContext: ExecutionContext = ec
}
