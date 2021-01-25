/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.gateway.services.kube.watcher;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.gravitee.gateway.services.kube.crds.resources.GraviteeGateway;
import io.gravitee.gateway.services.kube.crds.resources.GraviteePlugin;
import io.gravitee.gateway.services.kube.crds.resources.GraviteeServices;
import io.gravitee.gateway.services.kube.services.GraviteeGatewayService;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.GraviteeServicesService;
import io.gravitee.gateway.services.kube.services.impl.WatchActionContext;
import io.gravitee.gateway.services.kube.services.impl.WatchActionContext.Event;
import io.gravitee.gateway.services.kube.services.listeners.GraviteeGatewayListener;
import io.gravitee.gateway.services.kube.services.listeners.GraviteePluginsListener;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GraviteeServiceWatcher implements Watcher<GraviteeServices>, GraviteePluginsListener, GraviteeGatewayListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraviteeServiceWatcher.class);

    private Subscriber<? super WatchActionContext> subscriber;

    private GraviteePluginsService pluginsService;

    private GraviteeGatewayService gatewayService;

    private GraviteeServicesService servicesService;

    public GraviteeServiceWatcher(
        Subscriber<? super WatchActionContext> subscriber,
        GraviteePluginsService pluginsService,
        GraviteeGatewayService gatewayService,
        GraviteeServicesService servicesService
    ) {
        this.subscriber = subscriber;
        this.pluginsService = pluginsService;
        this.gatewayService = gatewayService;
        this.servicesService = servicesService;
        this.gatewayService.registerListener(this);
        this.pluginsService.registerListener(this);
    }

    @Override
    public void eventReceived(Action action, GraviteeServices gioServices) {
        LOGGER.debug("Receive '{}' for '{}'", action, gioServices);
        switch (action) {
            case ADDED:
                subscriber.onNext(new WatchActionContext(gioServices, Event.ADDED));
                break;
            case MODIFIED:
                subscriber.onNext(new WatchActionContext(gioServices, Event.MODIFIED));
                break;
            case DELETED:
                subscriber.onNext(new WatchActionContext(gioServices, Event.DELETED));
                break;
            case ERROR:
                LOGGER.warn("Action {} received for GraviteeServices", action);
                break;
            default:
                LOGGER.warn("Unmanaged action {}", action);
        }
    }

    @Override
    public void onClose(KubernetesClientException e) {
        if (e != null) {
            LOGGER.debug("Exception received on close service watcher", e);
        }

        // complete the rx subscriber
        if (this.subscriber != null) {
            this.subscriber.onComplete();
        }
    }

    @Override
    public void onGatewayUpdate(WatchActionContext<GraviteeGateway> context) {
        triggerServiceUpdates(context);
    }

    @Override
    public void onPluginsUpdate(WatchActionContext<GraviteePlugin> context) {
        triggerServiceUpdates(context);
    }

    private void triggerServiceUpdates(WatchActionContext<? extends CustomResource> context) {
        // TODO maybe we should reference services that use this GW in GWStatus to limit search...?
        List<GraviteeServices> services = servicesService.listAllServices();
        for (GraviteeServices resource : services) {
            WatchActionContext<GraviteeServices> derivedContext = new WatchActionContext<>(resource, Event.REFERENCE_UPDATED);
            derivedContext.setHttpConfigHashCode(context.getHttpConfigHashCode());
            derivedContext.getPluginRevisions().addAll(context.getPluginRevisions());
            subscriber.onNext(derivedContext);
        }
    }
}
