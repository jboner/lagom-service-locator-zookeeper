package com.lightbend.lagom.discovery.zookeeper;

import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.UriSpec;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ZooKeeperServiceRegistry implements Closeable {
    private final ServiceDiscovery<String> serviceDiscovery;
    private final CuratorFramework zkClient;

    public ZooKeeperServiceRegistry(String zkURL, String zkServicesPath) throws Exception {

        zkClient = CuratorFrameworkFactory.newClient(zkURL, new ExponentialBackoffRetry(1000, 3));
        zkClient.start();

        serviceDiscovery = ServiceDiscoveryBuilder
                .builder(String.class)
                .client(zkClient)
                .basePath(zkServicesPath)
                .build();
    }

    public void register(ServiceInstance<String> serviceInstance) throws Exception {
        serviceDiscovery.registerService(serviceInstance);
    }

    public void unregister(ServiceInstance<String> serviceInstance) throws Exception {
        serviceDiscovery.unregisterService(serviceInstance);
    }

    public CompletableFuture<Collection<URI>> locate(String serviceName) {
        return CompletableFuture.supplyAsync(() -> {
            return locateBlocking(serviceName);
        });
    }

    protected Collection<URI> locateBlocking(String serviceName) {
        try {
            return serviceDiscovery.queryForInstances(serviceName).stream().map(instance -> {
                String scheme = instance.getUriSpec().getParts().get(0).getValue();
                String hostname = instance.getAddress();
                int port = instance.getPort();
                try {
                    return new URI(scheme + "://" + hostname + ":" + port);
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void start() throws Exception {
        serviceDiscovery.start();
    }

    @Override
    public void close() throws IOException {
        CloseableUtils.closeQuietly(serviceDiscovery);
        CloseableUtils.closeQuietly(zkClient);
    }
}
