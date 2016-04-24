package com.lightbend.lagom.discovery.zookeeper

import java.net.URI
import java.util.Optional
import java.util.concurrent.TimeUnit

import org.apache.curator.test.TestingServer
import org.apache.curator.utils.CloseableUtils
import org.apache.curator.x.discovery.{ServiceInstance, UriSpec}

import org.scalatest.Matchers
import org.scalatest.WordSpecLike

class ZooKeeperServiceDiscoverySpec extends WordSpecLike with Matchers {
  val testTimeoutInSeconds = 5
  val zkServicesPath = "lagom/services"
  val serviceAddress = "localhost"
  val zkServerPort = 2181
  val zkUrl = s"$serviceAddress:$zkServerPort"
  val serviceUriSpec = new UriSpec("{scheme}://{serviceAddress}:{servicePort}")

  def withServiceDiscovery(testCode: ZooKeeperServiceLocator => ZooKeeperServiceRegistry => Any): Unit = {
    import scala.concurrent.ExecutionContext.Implicits._

    val zkServer = new TestingServer(zkServerPort)
    val locator = new ZooKeeperServiceLocator()
    val registry = new ZooKeeperServiceRegistry(zkUrl, zkServicesPath);

    try {
      zkServer.start()
      registry.start()

      testCode(locator)(registry)

    } finally {
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
    "allow registration and lookup of a service" in withServiceDiscovery { locator => registry =>
      registry.register(newServiceInstance("service-1", "1", 9000))
      val uris = registry.locate("service-1")
      Thread.sleep(500)
      uris.size() shouldNot be(0)
      uris.iterator().next().getHost shouldBe serviceAddress
      uris.iterator().next().getPort shouldBe 9000
    }

    "allow to register a service with same name and same endpoint twice" in withServiceDiscovery { locator => registry =>
      registry.register(newServiceInstance("service-2", "1", 9000))
      registry.register(newServiceInstance("service-2", "1", 9000))
      Thread.sleep(500)
      val uris = registry.locate("service-2")
      uris.size() shouldNot be(0)
      uris.iterator().next().getHost shouldBe serviceAddress
      uris.iterator().next().getPort shouldBe 9000
    }

    "allow to register a service with same name and different endpoints" in withServiceDiscovery { locator => registry =>
      registry.register(newServiceInstance("service-3", "1", 9000))
      registry.register(newServiceInstance("service-3", "2", 9001))
      Thread.sleep(500)
      val uris = registry.locate("service-3")
      uris.size() should be(2)
    }

    "allow unregistration of a service" in withServiceDiscovery { locator => registry =>
      val serviceInstance = newServiceInstance("service-4", "1", 9000)
      registry.register(serviceInstance)
      Thread.sleep(500)
      val uris1 = registry.locate("service-4")
      uris1.size() shouldNot be(0)
      uris1.iterator().next().getHost shouldBe serviceAddress
      uris1.iterator().next().getPort shouldBe 9000

      registry.unregister(serviceInstance)
      Thread.sleep(500)
      val uris2 = registry.locate("service-4")
      uris2.size() should be(0)
    }
  }

  "A service locator" should {
    "allow lookup of a registered service" in withServiceDiscovery { locator => registry =>
      registry.register(newServiceInstance("s-1", "1", 9000))
      Thread.sleep(500)
      val registeredUrl = locator.locate("s-1").toCompletableFuture.get(
        testTimeoutInSeconds, TimeUnit.SECONDS
      )
      val expectedUrl = Optional.of(new URI(s"http://$serviceAddress:9000"))
      expectedUrl shouldBe registeredUrl
    }

    "allow lookup of a service even if it has been registered twice" in withServiceDiscovery { locator => registry =>
      registry.register(newServiceInstance("s-2", "1", 9000))
      registry.register(newServiceInstance("s-2", "1", 9000))
      Thread.sleep(500)
      val registeredUrl = locator.locate("s-2").toCompletableFuture.get(
        testTimeoutInSeconds, TimeUnit.SECONDS
      )
      registeredUrl.get.toString should startWith ("http://localhost:900")
    }

    "return CompletableFuture[Empty] for lookup of services that aren't registered" in withServiceDiscovery { locator => registry =>
      val registeredUrl = locator.locate("s-3").toCompletableFuture.get(
        testTimeoutInSeconds, TimeUnit.SECONDS
      )
      registeredUrl shouldBe Optional.empty[URI]()
    }
  }
}
