package com.lightbend.lagom.discovery.zookeeper

import java.io.{Closeable, File}
import java.net.{InetAddress, URI}
import java.util.Optional
import java.util.concurrent.{CompletionStage, ConcurrentHashMap}
import java.util.function.{Function => JFunction}
import javax.inject.Inject

import com.lightbend.lagom.javadsl.api.Descriptor.Call
import com.lightbend.lagom.javadsl.api.ServiceLocator
import com.typesafe.config.ConfigException.BadValue
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.utils.CloseableUtils
import org.apache.curator.x.discovery.{ServiceDiscovery, ServiceDiscoveryBuilder, ServiceInstance}
import com.typesafe.config.{ Config => TSConfig }
import play.api.{Configuration, Environment, Mode}

import scala.collection.concurrent.Map
import scala.collection.convert.decorateAsScala._
import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

trait ZooKeeperServiceLocatorConfig {
  def serverHostname:String
  def serverPort:Int
  def scheme:String
  def routingPolicy:String
  def zkServicesPath:String
  def zkUri:String
}

object ZooKeeperServiceLocator {
  case class Config(serverHostname:String,
                    serverPort:Int,
                    scheme:String, // Technically should be another type
                    routingPolicy:String, // Technically should be another type
                    zkServicesPath:String) extends ZooKeeperServiceLocatorConfig {
    def zkUri = ZooKeeperServiceLocator.zkUri(serverHostname,serverPort)
  }

  def javaConfig(serverHostname:String,
                 serverPort:Int,
                 scheme:String,
                 routingPolicy:String,
                 zkServicesPath:String): ZooKeeperServiceLocatorConfig =
    Config(serverHostname,
           serverPort,
           scheme,
           routingPolicy,
           zkServicesPath)

  def fromConfigurationWithPath(in:Configuration,
                                path:String = defaultConfigPath):Config =
    fromConfiguration(in.getConfig(path).get)

  def fromConfiguration(in:Configuration):Config =
    fromConfig(in.underlying)

  def fromConfig(in:TSConfig):Config =
    Config(serverHostname = in.getString("server-hostname"),
           serverPort     = in.getInt("server-port"),
           scheme         = in.getString("uri-scheme"),
           routingPolicy  = in.getString("routing-policy"),
           zkServicesPath = defaultZKServicesPath)

  val defaultConfigPath = "lagom.discovery.zookeeper"
  val defaultZKServicesPath = "/lagom/services"
  def zkUri(serverHostname:String, serverPort:Int):String = s"$serverHostname:$serverPort"
}

class ZooKeeperServiceLocator (serverHostname:String,
                               serverPort:Int,
                               scheme:String,
                               routingPolicy:String,
                               zkServicesPath:String,
                               zkUri:String)(implicit ec: ExecutionContext) extends ServiceLocator with Closeable {

  @Inject()
  def this(config:ZooKeeperServiceLocator.Config)(implicit ec: ExecutionContext) =
    this(config.serverHostname,
         config.serverPort,
         config.scheme,
         config.routingPolicy,
         config.zkServicesPath,
         config.zkUri)(ec)

  private val zkClient: CuratorFramework =
    CuratorFrameworkFactory.newClient(zkUri, new ExponentialBackoffRetry(1000, 3))
    zkClient.start()

  private val serviceDiscovery: ServiceDiscovery[String] =
    ServiceDiscoveryBuilder
        .builder(classOf[String])
        .client(zkClient)
        .basePath(zkServicesPath)
        .build()
    serviceDiscovery.start()

  private val roundRobinIndexFor: Map[String, Int] = new ConcurrentHashMap[String, Int]().asScala

  override def locate(name: String): CompletionStage[Optional[URI]] =
    locateAsScala(name).map(_.asJava).toJava



  override def doWithService[T](name: String, serviceCall: Call[_, _], block: JFunction[URI, CompletionStage[T]]): CompletionStage[Optional[T]] =
    locateAsScala(name).flatMap { uriOpt =>
    uriOpt.fold(Future.successful(Optional.empty[T])) { uri =>
      block.apply(uri).toScala.map(Optional.of(_))
    }
  }.toJava


  private def locateAsScala(name: String): Future[Option[URI]] = {
    val instances: List[ServiceInstance[String]] = serviceDiscovery.queryForInstances(name).asScala.toList
    Future {
      instances.size match {
        case 0 => None
        case 1 => toURIs(instances).headOption
        case _ =>
          routingPolicy match {
            case "first" => Some(pickFirstInstance(instances))
            case "random" => Some(pickRandomInstance(instances))
            case "round-robin" => Some(pickRoundRobinInstance(name, instances))
            case unknown => throw new BadValue("lagom.discovery.zookeeper.routing-policy", s"[$unknown] is not a valid routing algorithm")
          }
      }
    }
  }

  override def close(): Unit = {
    CloseableUtils.closeQuietly(serviceDiscovery)
    CloseableUtils.closeQuietly(zkClient)
  }

  private[zookeeper] def pickFirstInstance(services: List[ServiceInstance[String]]): URI = {
    assert(services.size > 1)
    toURIs(services).sortWith(_.toString < _.toString).apply(0)
  }

  private[zookeeper] def pickRandomInstance(services: List[ServiceInstance[String]]): URI = {
    assert(services.size > 1)
    toURIs(services).sortWith(_.toString < _.toString).apply(Random.nextInt(services.size - 1))
  }

  private[zookeeper] def pickRoundRobinInstance(name: String, services: List[ServiceInstance[String]]): URI = {
    assert(services.size > 1)
    roundRobinIndexFor.putIfAbsent(name, 0)
    val sortedServices = toURIs(services).sortWith(_.toString < _.toString)
    val currentIndex = roundRobinIndexFor(name)
    val nextIndex =
      if (sortedServices.size > currentIndex + 1) currentIndex + 1
      else 0
    roundRobinIndexFor += (name -> nextIndex)
    sortedServices(currentIndex)
  }

  private def toURIs(services: List[ServiceInstance[String]]): List[URI] =
    services.map { service =>
      val address = service.getAddress
      val serviceAddress =
        if (address == "" || address == "localhost") InetAddress.getLoopbackAddress.getHostAddress
        else address
      new URI(s"$scheme://$serviceAddress:${service.getPort}")
    }

  override def locate(name: String, serviceCall: Call[_, _]): CompletionStage[Optional[URI]] =
    locateAsScala(name).map(_.asJava).toJava

}
