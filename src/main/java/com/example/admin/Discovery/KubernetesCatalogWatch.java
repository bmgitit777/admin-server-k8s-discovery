package com.example.admin.Discovery;


import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@EnableScheduling
public class KubernetesCatalogWatch implements ApplicationEventPublisherAware {

    private static final Logger logger = LoggerFactory
            .getLogger(KubernetesCatalogWatch.class);

    private final KubernetesClient kubernetesClient;

    private final AtomicReference<List<String>> catalogEndpointsState = new AtomicReference<>();

    private ApplicationEventPublisher publisher;

    public KubernetesCatalogWatch(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(
            fixedDelayString = "${spring.cloud.kubernetes.discovery.catalogServicesWatchDelay:30000}")
    public void catalogServicesWatch() {
        try {
            List<String> previousState = this.catalogEndpointsState.get();

            // not all pods participate in the service discovery. only those that have endpoints.
            List<Endpoints> endpoints = this.kubernetesClient.endpoints().list()
                    .getItems();
            List<String> endpointsPodNames = endpoints.stream().map(Endpoints::getSubsets)
                    .filter(Objects::nonNull).flatMap(Collection::stream)
                    .map(EndpointSubset::getAddresses).filter(Objects::nonNull)
                    .flatMap(Collection::stream).map(EndpointAddress::getTargetRef)
                    .filter(Objects::nonNull).map(ObjectReference::getName) // pod name, unique in, namespace
                    .sorted(String::compareTo).collect(Collectors.toList());

            this.catalogEndpointsState.set(endpointsPodNames);

            if (!endpointsPodNames.equals(previousState)) {
                logger.trace("Received endpoints update from kubernetesClient: {}", endpointsPodNames);
                this.publisher.publishEvent(new HeartbeatEvent(this, endpointsPodNames));
            }
        }
        catch (Exception e) {
            logger.error("Error watching Kubernetes Services", e);
        }
    }
}
