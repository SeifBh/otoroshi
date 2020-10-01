package events

import com.sksamuel.pulsar4s.{Consumer, ConsumerConfig, Producer, ProducerConfig, PulsarClient, PulsarClientConfig, Subscription, Topic}
import models.Exporter
import com.sksamuel.pulsar4s.playjson._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

case class PulsarConfig(
                         uri: String,
                         tlsTrustCertsFilePath: Option[String],
                         tenant: String,
                         namespace: String,
                         topic: String) extends Exporter {
  override def toJson: JsValue = PulsarConfig.format.writes(this)
}

object PulsarConfig {
  implicit val format = new Format[PulsarConfig] {
    override def writes(o: PulsarConfig): JsValue = Json.obj(
      "uri" -> o.uri,
      "tlsTrustCertsFilePath" -> o.tlsTrustCertsFilePath.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "tenant" -> o.tenant,
      "namespace" -> o.namespace,
      "topic" -> o.topic
    )

    override def reads(json: JsValue): JsResult[PulsarConfig] =
      Try {
        PulsarConfig(
          uri = ((json \ "uri").as[String]),
          tlsTrustCertsFilePath = (json \ "tlsTrustCertsFilePath").asOpt[String],
          tenant = (json \ "tenant").as[String],
          namespace = (json \ "namespace").as[String],
          topic = (json \ "topic").as[String]
        )
      } match {
        case Failure(e)  => JsError(e.getMessage)
        case Success(kc) => JsSuccess(kc)
      }
  }
}

object PulsarSetting {
  def client(_env: env.Env, config: PulsarConfig): PulsarClient = {
    val c = PulsarClientConfig(
      serviceUrl = config.uri,
      tlsTrustCertsFilePath = config.tlsTrustCertsFilePath)
    PulsarClient(c)
  }

  def producer(_env: env.Env, config: PulsarConfig): Producer[JsValue] = {

    val topic = Topic(s"persistent://${config.tenant}/${config.namespace}/${config.topic}")
    val producerConfig = ProducerConfig(topic)
    val cli = client(_env, config)
    cli.producer[JsValue](producerConfig)
  }

  def consumer(_env: env.Env, config: PulsarConfig): Consumer[JsValue] = {
    val topic = Topic(s"persistent://${config.tenant}/${config.namespace}/${config.topic}")
    val consumerConfig = ConsumerConfig(topics = Seq(topic), subscriptionName = Subscription("otoroshi"))
    val cli = client(_env, config)
    cli.consumer[JsValue](consumerConfig)
  }
}