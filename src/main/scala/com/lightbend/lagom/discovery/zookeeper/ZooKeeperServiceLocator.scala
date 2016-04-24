package com.lightbend.lagom.discovery.zookeeper

import java.io.Closeable
import java.net.URI
import java.util.Optional
import java.util.concurrent.{CompletableFuture, CompletionStage}
import java.util.function.{Function => JFunction}
import javax.inject.Inject

import com.lightbend.lagom.javadsl.api.ServiceLocator
import org.apache.curator.framework.{CuratorFrameworkFactory, CuratorFramework}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.utils.CloseableUtils
import org.apache.curator.x.discovery.{ServiceDiscoveryBuilder, ServiceDiscovery, ServiceProvider}

import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.{ExecutionContext, Future}

class ZooKeeperServiceLocator @Inject()(implicit ec: ExecutionContext) extends ServiceLocator with Closeable {

  // FIXME: externalize these to config file, what is the best way?
  val uriScheme = "http"
  val zkUri = "localhost:2181"
  val zkServicesPath = "/lagom/services"

  val zkClient: CuratorFramework =
    CuratorFrameworkFactory.newClient(zkUri, new ExponentialBackoffRetry(1000, 3))
    zkClient.start()

  val serviceDiscovery: ServiceDiscovery[String] =
    ServiceDiscoveryBuilder
        .builder(classOf[String])
        .client(zkClient)
        .basePath(zkServicesPath)
        .build()
    serviceDiscovery.start()

  override def locate(name: String): CompletionStage[Optional[URI]] =
    locateAsScala(name).map(_.asJava).toJava

  override def doWithService[T](name: String, block: JFunction[URI, CompletionStage[T]]): CompletionStage[Optional[T]] =
    locateAsScala(name).flatMap { uriOpt =>
      uriOpt.fold(Future.successful(Optional.empty[T])) { uri =>
        block.apply(uri).toScala.map(Optional.of(_))
      }
    }.toJava

  private def locateAsScala(name: String): Future[Option[URI]] = try {
    val instances = serviceDiscovery.queryForInstances(name)
    if (instances.isEmpty) Future.successful(None)
    else {
      val instance = instances.iterator().next() // grab the first one if we have more than one
      Future.successful(Some(new URI(s"$uriScheme://${instance.getAddress}:${instance.getPort}")))
    }
  } finally CloseableUtils.closeQuietly(serviceDiscovery)

  override def close(): Unit = {
    CloseableUtils.closeQuietly(serviceDiscovery)
    CloseableUtils.closeQuietly(zkClient)
  }
}
