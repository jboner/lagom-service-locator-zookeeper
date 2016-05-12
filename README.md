#  Lagom Service Locator for ZooKeeper

**DISCLAIMER: This is work in progress. This code has never been used in anger. Use it as a starting point and adapt it as-needed. I'd be happy to take pull requests.**

This project implements the [Lagom](http://lightbend.com/lagom) `ServiceLocator` interface for [Apache ZooKeeper](http://zookeeper.apache.org) and provides a ZooKeeper-based service registry for registering and unregistering service from within the services.

## Register service locator in Lagom

To use it the first step is to register the service locator in Lagom by using Guice, see `ZooKeeperServiceLocatorModule`. It is enabled in the `reference.conf ` file: 
```
# Enables the `ZooKeeperServiceLocatorModule` to register the `ZooKeeperServiceLocator`.
# The `ZooKeeperServiceLocator` implements Lagom's ServiceLocator
play.modules.enabled += "com.lightbend.lagom.discovery.zookeeper.ZooKeeperServiceLocatorModule"
```

This service locator is only enabled during `Prod` mode, during `Dev` mode the regular development service locator is used.
When you are using this library then you should *not* use the `sbt-conductr` sbt plugin. 

## Routing to service instances

The `ZooKeeperServiceLocator` has support for three simple routing policies: 
* `first`: picks the first service instance in a sorted list—sorted by IP-address and port
* `random`: picks a random service instance
* `round-robin`: performs a round-robin routing between the currently available service instances

## Configuration

An `application.conf` file needs to be created in `src/main/resources` with the following contents:

```
lagom {
  discovery {
    zookeeper {
      server-hostname = "127.0.0.1"   # hostname or IP-address for the ZooKeeper server
      server-port     = 2181          # port for the ZooKeeper server
      uri-scheme      = "http"        # for example: http or https
      routing-policy  = "round-robin" # valid routing policies: first, random, round-robin
    }
  }
}
```

## Register services in ZooKeeper

The second step is to register each of your services in ZooKeeper. This can be done either directly using the Apache ZooKeeper API, or using the [Apache Curator](https://curator.apache.org) library, or by using the `ZooKeeperServiceRegistry` API provided by this library. Here is some example code of how to use it in a service: 

```java
import com.lightbend.lagom.discovery.zookeeper.*;
import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.UriSpec;

/**
 * This shows a very simplified method of registering an instance with the service discovery. Each individual
 * instance in your distributed set of applications would create an instance of something similar to ExampleServer,
 * start it when the application comes up and close it when the application shuts down.
 */
public class ExampleService {
    private final ServiceInstance<String> serviceInstance;
    private final ZooKeeperServiceRegistry registry;

    public ExampleService(
            String serviceName,
            String serviceId,
            String serviceAddress,
            int servicePort) throws Exception {

        // start up the ZooKeeper-based service registry
        registry = new ZooKeeperServiceRegistry(
                ZooKeeperServiceLocator.zkUri(),
                ZooKeeperServiceLocator.zkServicesPath());
        registry.start();

        // create the service instance for the service discovery
        // needs to be held on to to be able to unregister the service on shutdown
        serviceInstance = ServiceInstance.<String>builder()
                .name(serviceName)
                .id(serviceId)
                .address(serviceAddress)
                .port(servicePort)
                .uriSpec(new UriSpec("{scheme}://{serviceAddress}:{servicePort}"))
                .build();

        // register the service
        registry.register(serviceInstance);
    }

    public void stop() throws Exception {
        registry.unregister(serviceInstance);
        CloseableUtils.closeQuietly(registry);
    }

    public static void main(String[] args) throws Exception {
        String serviceName = "testService";
        String serviceId = "uniqueId";
        String serviceAddress = "localhost";
        int servicePort = 9000;

        ExampleService service = new ExampleService(
                serviceName, serviceId, serviceAddress, servicePort);

        service.stop();
    }
}
```

## How to run the tests

The tests will start up and connect to an embedded ZooKeeper server. Run the tests by invoking `sbt test`.

