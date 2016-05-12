package com.lightbend.lagom.discovery.zookeeper

import java.io.{File, Closeable}
import java.net.{InetAddress, URI}
import java.util.Optional
import java.util.concurrent.{ConcurrentHashMap, CompletionStage}
import java.util.function.{Function => JFunction}
import javax.inject.Inject

import com.lightbend.lagom.javadsl.api.ServiceLocator
import com.typesafe.config.ConfigException.BadValue
import org.apache.curator.framework.{CuratorFrameworkFactory, CuratorFramework}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.utils.CloseableUtils
import org.apache.curator.x.discovery.{ServiceInstance, ServiceDiscoveryBuilder, ServiceDiscovery}
import play.api.{Mode, Environment, Configuration}

import scala.collection.concurrent.Map
import scala.collection.convert.decorateAsScala._
import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

object ZooKeeperServiceLocator {
  val config = Configuration.load(Environment(new File("."), getClass.getClassLoader, Mode.Prod)).underlying
  val serverHostname = config.getString("lagom.discovery.zookeeper.server-hostname")
  val serverPort     = config.getInt("lagom.discovery.zookeeper.server-port")
  val scheme         = config.getString("lagom.discovery.zookeeper.uri-scheme")
  val routingPolicy  = config.getString("lagom.discovery.zookeeper.routing-policy")
  val zkUri = s"$serverHostname:$serverPort"
  val zkServicesPath = "/lagom/services"
}

class ZooKeeperServiceLocator @Inject()(implicit ec: ExecutionContext) extends ServiceLocator with Closeable {
  import ZooKeeperServiceLocator._

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

  override def doWithService[T](name: String, block: JFunction[URI, CompletionStage[T]]): CompletionStage[Optional[T]] =
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
}
