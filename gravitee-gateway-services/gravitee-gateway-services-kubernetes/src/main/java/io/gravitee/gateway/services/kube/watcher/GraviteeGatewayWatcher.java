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

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.gravitee.gateway.services.kube.crds.resources.GraviteeGateway;
import io.gravitee.gateway.services.kube.crds.resources.GraviteePlugin;
import io.gravitee.gateway.services.kube.services.GraviteeGatewayService;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.impl.WatchActionContext;
import io.gravitee.gateway.services.kube.services.listeners.GraviteePluginsListener;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GraviteeGatewayWatcher implements Watcher<GraviteeGateway>, GraviteePluginsListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraviteeGatewayWatcher.class);

    private Subscriber<? super WatchActionContext> subscriber;

    private GraviteePluginsService graviteePluginsService;

    private GraviteeGatewayService graviteeGatewayService;

    public GraviteeGatewayWatcher(
        Subscriber<? super WatchActionContext> subscriber,
        GraviteePluginsService graviteePluginsService,
        GraviteeGatewayService graviteeGatewayService
    ) {
        this.subscriber = subscriber;
        this.graviteeGatewayService = graviteeGatewayService;
        this.graviteePluginsService = graviteePluginsService;
        this.graviteePluginsService.registerListener(this);
    }

    @Override
    public void eventReceived(Action action, GraviteeGateway graviteeGateway) {
        LOGGER.info("Action {} received for GraviteeGateway", action);
        switch (action) {
            case ADDED:
                subscriber.onNext(new WatchActionContext(graviteeGateway, WatchActionContext.Event.ADDED));
                break;
            case MODIFIED:
                subscriber.onNext(new WatchActionContext(graviteeGateway, WatchActionContext.Event.MODIFIED));
                break;
            case DELETED:
                subscriber.onNext(new WatchActionContext(graviteeGateway, WatchActionContext.Event.DELETED));
                break;
            case ERROR:
                LOGGER.warn("Action {} received for GraviteeGateway", action);
                break;
            default:
                LOGGER.warn("Unmanaged action {}", action);
        }
    }

    @Override
    public void onClose(KubernetesClientException e) {
        if (e != null) {
            LOGGER.debug("Exception received on close Gateway watcher", e);
        }

        // complete the rx subscriber
        if (this.subscriber != null) {
            this.subscriber.onComplete();
        }
    }

    @Override
    public void onPluginsUpdate(WatchActionContext<GraviteePlugin> context) {
        List<GraviteeGateway> services = graviteeGatewayService.listAllGateways();
        for (GraviteeGateway resource : services) {
            WatchActionContext<GraviteeGateway> derivedContext = new WatchActionContext<>(
                resource,
                WatchActionContext.Event.REFERENCE_UPDATED
            );
            derivedContext.setHttpConfigHashCode(context.getHttpConfigHashCode());
            derivedContext.getPluginRevisions().addAll(context.getPluginRevisions());
            subscriber.onNext(derivedContext);
        }
    }
}
