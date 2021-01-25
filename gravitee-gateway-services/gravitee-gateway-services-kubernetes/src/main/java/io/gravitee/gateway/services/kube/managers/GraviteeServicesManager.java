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
package io.gravitee.gateway.services.kube.managers;

import io.fabric8.kubernetes.client.Watch;
import io.gravitee.common.component.AbstractLifecycleComponent;
import io.gravitee.gateway.services.kube.exceptions.PipelineException;
import io.gravitee.gateway.services.kube.services.GraviteeGatewayService;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.GraviteeServicesService;
import io.gravitee.gateway.services.kube.services.impl.WatchActionContext;
import io.gravitee.gateway.services.kube.watcher.GraviteeServiceWatcher;
import io.reactivex.Flowable;
import io.reactivex.schedulers.Schedulers;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class GraviteeServicesManager extends AbstractLifecycleComponent<GraviteeServicesManager> implements Publisher<WatchActionContext> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraviteeServicesManager.class);

    private Watch serviceWatcher;

    @Autowired
    private GraviteeServicesService graviteeServices;

    @Autowired
    private GraviteePluginsService pluginsService;

    @Autowired
    private GraviteeGatewayService gatewayService;

    @Override
    public void subscribe(Subscriber<? super WatchActionContext> subscriber) {
        this.serviceWatcher =
            this.graviteeServices.getCrdClient()
                .watch(new GraviteeServiceWatcher(subscriber, pluginsService, gatewayService, graviteeServices));
    }

    @Override
    protected void doStart() throws Exception {
        Flowable
            .fromPublisher(this)
            .subscribeOn(Schedulers.single())
            .flatMap(graviteeServices::processAction)
            .doOnError(
                error -> {
                    if (error instanceof PipelineException) {
                        // TODO handle resource an subresource properly
                        LOGGER.error(
                            "Process Action on GraviteeServices fails on resource '{}'",
                            ((PipelineException) error).getContext().getResource(),
                            error
                        );
                    } else {
                        LOGGER.error("Process Action on GraviteeServices fails", error);
                    }
                }
            )
            .subscribe(); // TODO create a LoggerConsumer??
    }

    @Override
    protected void doStop() throws Exception {
        LOGGER.info("Close services watcher");
        if (this.serviceWatcher != null) {
            this.serviceWatcher.close();
        }
    }
}
