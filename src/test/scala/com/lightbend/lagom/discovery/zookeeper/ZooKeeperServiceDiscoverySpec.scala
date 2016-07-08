package com.lightbend.lagom.discovery.zookeeper

import java.net.{InetAddress, URI}
import java.util.Optional
import java.util.concurrent.TimeUnit

import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.apache.curator.utils.CloseableUtils
import org.apache.curator.x.discovery.{ServiceDiscovery, ServiceDiscoveryBuilder, ServiceInstance, UriSpec}

import scala.collection.convert.decorateAsScala._
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

class ZooKeeperServiceDiscoverySpec extends WordSpecLike with Matchers {
  val testTimeoutInSeconds = 5
  val zkServicesPath = "lagom/services"
  val serviceAddress = "127.0.0.1"
  val zkServerPort = 2181
  val zkUrl = s"$serviceAddress:$zkServerPort"
  val serviceUriSpec = new UriSpec("{scheme}://{serviceAddress}:{servicePort}")
  val localAddress = "127.0.0.1"

  def withServiceDiscovery(testCode: ZooKeeperServiceLocator => ZooKeeperServiceRegistry => ServiceDiscovery[String] => Any): Unit = {
    import scala.concurrent.ExecutionContext.Implicits._

    val zkServer = new TestingServer(zkServerPort)
    val locator = new ZooKeeperServiceLocator()
    val registry = new ZooKeeperServiceRegistry(zkUrl, zkServicesPath);

    val zkClient = CuratorFrameworkFactory.newClient(zkUrl, new ExponentialBackoffRetry(1000, 3))
    zkClient.start

    val serviceDiscovery = ServiceDiscoveryBuilder.builder(classOf[String]).client(zkClient).basePath(zkServicesPath).build

    try {
      zkServer.start()
      registry.start()
      serviceDiscovery.start()

      testCode(locator)(registry)(serviceDiscovery)

    } finally {
      CloseableUtils.closeQuietly(serviceDiscovery)
      CloseableUtils.closeQuietly(registry)
      CloseableUtils.closeQuietly(locator)
      CloseableUtils.closeQuietly(zkServer)
    }
  }

  def newServiceInstance(serviceName: String, serviceId: String, servicePort: Int): ServiceInstance[String] = {
    ServiceInstance.builder[String]
      .name(serviceName)
      .id(serviceId)
      .address(serviceAddress)
      .port(servicePort)
      .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
      .build
  }
  "A service registry" should {
    "allow registration and lookup of a service" in withServiceDiscovery { locator => registry => _ =>
      registry.register(newServiceInstance("service-1", "1", 9001))
      Thread.sleep(500)
      val uris = registry.locateBlocking("service-1")
      uris.size() shouldNot be(0)
      uris.iterator().next().getHost shouldBe serviceAddress
      uris.iterator().next().getPort shouldBe 9001
    }

    "allow to register a service with same name and same endpoint twice" in withServiceDiscovery { locator => registry => _ =>
    registry.register(newServiceInstance("service-2", "1", 9002))
      registry.register(newServiceInstance("service-2", "1", 9002))
      Thread.sleep(500)
      val uris = registry.locateBlocking("service-2")
      uris.size() shouldNot be(0)
      uris.iterator().next().getHost shouldBe serviceAddress
      uris.iterator().next().getPort shouldBe 9002
    }

    "allow to register a service with same name and different endpoints" in withServiceDiscovery { locator => registry => _ =>
    registry.register(newServiceInstance("service-3", "1", 9003))
      registry.register(newServiceInstance("service-3", "2", 9004))
      Thread.sleep(500)
      val uris = registry.locateBlocking("service-3")
      uris.size() should be(2)
    }

    "allow unregistration of a service" in withServiceDiscovery { locator => registry => _ =>
    val serviceInstance = newServiceInstance("service-4", "1", 9005)
      registry.register(serviceInstance)
      Thread.sleep(500)
      val uris1 = registry.locateBlocking("service-4")
      uris1.size() shouldNot be(0)
      uris1.iterator().next().getHost shouldBe serviceAddress
      uris1.iterator().next().getPort shouldBe 9005

      registry.unregister(serviceInstance)
      Thread.sleep(500)
      val uris2 = registry.locateBlocking("service-4")
      uris2.size() should be(0)
    }
  }

  "A service locator" should {
    "allow lookup of a registered service" in withServiceDiscovery { locator => registry => _ =>
    registry.register(newServiceInstance("s-1", "1", 9006))
      Thread.sleep(500)
      val registeredUrl = locator.locate("s-1").toCompletableFuture.get(
        testTimeoutInSeconds, TimeUnit.SECONDS
      )
      val expectedUrl = Optional.of(new URI(s"http://$serviceAddress:9006"))
      expectedUrl shouldBe registeredUrl
    }

    "allow lookup of a service even if it has been registered twice" in withServiceDiscovery { locator => registry => _ =>
    registry.register(newServiceInstance("s-2", "1", 9007))
      registry.register(newServiceInstance("s-2", "1", 9007))
      Thread.sleep(500)
      val registeredUrl = locator.locate("s-2").toCompletableFuture.get(
        testTimeoutInSeconds, TimeUnit.SECONDS
      )
      registeredUrl.get.toString should startWith (s"http://$serviceAddress:900")
    }

    "return CompletableFuture[Empty] for lookup of services that aren't registered" in withServiceDiscovery { locator => registry => _ =>
    val registeredUrl = locator.locate("s-3").toCompletableFuture.get(
        testTimeoutInSeconds, TimeUnit.SECONDS
      )
      registeredUrl shouldBe Optional.empty[URI]()
    }


    "allow round-robin routing of a service during a static set of services" in withServiceDiscovery { locator => registry => discovery =>
      val service1 = ServiceInstance.builder[String]
        .name("service-5")
        .id("service-instance-5-1")
        .port(9008)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service1)
      val service2 = ServiceInstance.builder[String]
        .name("service-5")
        .id("service-instance-5-2")
        .port(9009)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service2)
      val service3 = ServiceInstance.builder[String]
        .name("service-5")
        .id("service-instance-5-3")
        .port(9010)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service3)
      Thread.sleep(500)

      val services: List[ServiceInstance[String]] = discovery.queryForInstances("service-5").asScala.toList
      services.size shouldBe 3

      val serviceURI1 = locator.pickRoundRobinInstance("service-5", services)
      serviceURI1.getHost shouldBe localAddress
      serviceURI1.getPort shouldBe 9008

      val serviceURI2 = locator.pickRoundRobinInstance("service-5", services)
      serviceURI2.getHost shouldBe localAddress
      serviceURI2.getPort shouldBe 9009

      val serviceURI3 = locator.pickRoundRobinInstance("service-5", services)
      serviceURI3.getHost shouldBe localAddress
      serviceURI3.getPort shouldBe 9010

      val serviceURI4 = locator.pickRoundRobinInstance("service-5", services)
      serviceURI4.getHost shouldBe localAddress
      serviceURI4.getPort shouldBe 9008

      discovery.unregisterService(service1)
      discovery.unregisterService(service2)
      discovery.unregisterService(service3)
    }

    "allow round-robin routing of a service while adding a service" in withServiceDiscovery { locator => registry => discovery =>
      val service1 = ServiceInstance.builder[String]
        .name("service-6")
        .id("service-instance-6-1")
        .port(9011)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service1)
      val service2 = ServiceInstance.builder[String]
        .name("service-6")
        .id("service-instance-6-2")
        .port(9012)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service2)
      Thread.sleep(500)

      val services1: List[ServiceInstance[String]] = discovery.queryForInstances("service-6").asScala.toList
      services1.size shouldBe 2

      val serviceURI1 = locator.pickRoundRobinInstance("service-6", services1)
      serviceURI1.getHost shouldBe localAddress
      serviceURI1.getPort shouldBe 9011

      val serviceURI2 = locator.pickRoundRobinInstance("service-6", services1)
      serviceURI2.getHost shouldBe localAddress
      serviceURI2.getPort shouldBe 9012

      val serviceURI3 = locator.pickRoundRobinInstance("service-6", services1)
      serviceURI3.getHost shouldBe localAddress
      serviceURI3.getPort shouldBe 9011

      val service3 = ServiceInstance.builder[String]
        .name("service-6")
        .id("service-instance-6-3")
        .port(9013)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service3)
      Thread.sleep(500)

      val services2: List[ServiceInstance[String]] = discovery.queryForInstances("service-6").asScala.toList
      services2.size shouldBe 3

      val serviceURI4 = locator.pickRoundRobinInstance("service-6", services2)
      serviceURI4.getHost shouldBe localAddress
      serviceURI4.getPort shouldBe 9012

      val serviceURI5 = locator.pickRoundRobinInstance("service-6", services2)
      serviceURI5.getHost shouldBe localAddress
      serviceURI5.getPort shouldBe 9013

      discovery.unregisterService(service1)
      discovery.unregisterService(service2)
      discovery.unregisterService(service3)
    }

    "allow random routing of a service" in withServiceDiscovery { locator => registry => discovery =>
      val service1 = ServiceInstance.builder[String]
        .name("service-8")
        .id("service-instance-8-1")
        .port(9017)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service1)
      val service2 = ServiceInstance.builder[String]
        .name("service-8")
        .id("service-instance-8-2")
        .port(9018)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service2)
      val service3 = ServiceInstance.builder[String]
        .name("service-8")
        .id("service-instance-8-3")
        .port(9019)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service3)
      Thread.sleep(500)

      val services1: List[ServiceInstance[String]] = discovery.queryForInstances("service-8").asScala.toList
      services1.size shouldBe 3

      locator.pickRandomInstance(services1).getHost shouldBe localAddress
      locator.pickRandomInstance(services1).getHost shouldBe localAddress
      locator.pickRandomInstance(services1).getHost shouldBe localAddress

      discovery.unregisterService(service1)
      discovery.unregisterService(service2)
      discovery.unregisterService(service3)
    }

    "allow routing to first instance of a service" in withServiceDiscovery { locator => registry => discovery =>
      val service1 = ServiceInstance.builder[String]
        .name("service-9")
        .id("service-instance-9-1")
        .port(9020)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service1)
      val service2 = ServiceInstance.builder[String]
        .name("service-9")
        .id("service-instance-9-2")
        .port(9021)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service2)
      val service3 = ServiceInstance.builder[String]
        .name("service-9")
        .id("service-instance-9-3")
        .port(9022)
        .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
        .build
      registry.register(service3)
      Thread.sleep(500)

      val services1: List[ServiceInstance[String]] = discovery.queryForInstances("service-9").asScala.toList
      services1.size shouldBe 3

      val serviceURI1 = locator.pickFirstInstance(services1)
      serviceURI1.getHost shouldBe localAddress
      serviceURI1.getPort shouldBe 9020

      val serviceURI2 = locator.pickFirstInstance(services1)
      serviceURI2.getHost shouldBe localAddress
      serviceURI2.getPort shouldBe 9020

      discovery.unregisterService(service1)
      discovery.unregisterService(service2)
      discovery.unregisterService(service3)
    }
  }
}
