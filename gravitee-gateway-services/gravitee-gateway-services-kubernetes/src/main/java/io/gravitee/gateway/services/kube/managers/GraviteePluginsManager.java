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
import io.gravitee.gateway.services.kube.crds.resources.GraviteePlugin;
import io.gravitee.gateway.services.kube.exceptions.PipelineException;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.impl.WatchActionContext;
import io.gravitee.gateway.services.kube.watcher.GraviteePluginWatcher;
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
public class GraviteePluginsManager extends AbstractLifecycleComponent<GraviteePluginsManager> implements Publisher<WatchActionContext> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraviteePluginsManager.class);

    private Watch pluginWatcher;

    @Autowired
    private GraviteePluginsService graviteePluginsService;

    @Override
    public void subscribe(Subscriber<? super WatchActionContext> subscriber) {
        this.pluginWatcher = this.graviteePluginsService.getCrdClient().watch(new GraviteePluginWatcher(subscriber));
    }

    @Override
    protected void doStart() throws Exception {
        Flowable
            .fromPublisher(this)
            .subscribeOn(Schedulers.single())
            .flatMap(graviteePluginsService::processAction)
            .doOnError(
                (Object error) -> {
                    if (error instanceof PipelineException) {
                        final WatchActionContext<GraviteePlugin> context = ((PipelineException) error).getContext();
                        LOGGER.error("Process Action on GraviteePlugins fails on resource '{}'", context.getResourceName(), error);
                        graviteePluginsService.persistAsError(context, ((PipelineException) error).getMessage());
                    } else {
                        LOGGER.error("Process Action on GraviteePlugins fails", error);
                    }
                }
            )
            .subscribe(); // TODO create a LoggerConsumer??
    }

    @Override
    protected void doStop() throws Exception {
        LOGGER.info("Close plugins watcher");
        if (this.pluginWatcher != null) {
            this.pluginWatcher.close();
        }
    }
}
