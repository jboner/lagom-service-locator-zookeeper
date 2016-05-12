package com.lightbend.lagom.discovery.zookeeper;

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
