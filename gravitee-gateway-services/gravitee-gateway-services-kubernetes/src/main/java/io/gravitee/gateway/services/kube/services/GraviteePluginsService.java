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
package io.gravitee.gateway.services.kube.services;

import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.gravitee.definition.model.Policy;
import io.gravitee.definition.model.plugins.resources.Resource;
import io.gravitee.gateway.services.kube.crds.cache.PluginRevision;
import io.gravitee.gateway.services.kube.crds.resources.DoneableGraviteePlugin;
import io.gravitee.gateway.services.kube.crds.resources.GraviteePlugin;
import io.gravitee.gateway.services.kube.crds.resources.GraviteePluginList;
import io.gravitee.gateway.services.kube.crds.resources.PluginReference;
import io.gravitee.gateway.services.kube.crds.resources.plugin.Plugin;
import io.gravitee.gateway.services.kube.services.impl.WatchActionContext;
import io.gravitee.gateway.services.kube.services.listeners.GraviteePluginsListener;
import io.reactivex.Flowable;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface GraviteePluginsService {

    void registerListener(GraviteePluginsListener listener);

    /**
     * Method used by {@link GraviteeGatewayService} or {@link GraviteeServicesService} to create Policy Plugin definition object
     *
     * @param context
     * @param plugin
     * @param pluginRef
     * @return
     */
    PluginRevision<Policy> buildPolicy(WatchActionContext context, Plugin plugin, PluginReference pluginRef);

    /**
     * Method used by {@link GraviteeGatewayService} or {@link GraviteeServicesService} to create Resource Plugin definition object
     *
     * @param context
     * @param plugin
     * @param pluginRef
     * @return
     */
    PluginRevision<Resource> buildResource(WatchActionContext context, Plugin plugin, PluginReference pluginRef);

    /**
     * Check if the GraviteePlugin definition may be safely created (no missing secret for example)
     * @param plugin
     * @throws io.gravitee.gateway.services.kube.exceptions.ValidationException in case of validation error
     */
    void maybeSafelyCreated(GraviteePlugin plugin);

    /**
     * Check if the GraviteePlugin definition may be safely updated (no deletion of plugin currently in used by an API)
     * @param plugin
     * @param oldPlugin
     * @throws io.gravitee.gateway.services.kube.exceptions.ValidationException in case of validation error
     */
    void maybeSafelyUpdated(GraviteePlugin plugin, GraviteePlugin oldPlugin);

    /**
     * Check if the GraviteePlugin definition may be safely deleted (no deletion of plugin currently in used by an API)
     * @param plugin
     * @throws io.gravitee.gateway.services.kube.exceptions.ValidationException in case of validation error
     */
    void maybeSafelyDeleted(GraviteePlugin plugin);

    Flowable<WatchActionContext<GraviteePlugin>> processAction(WatchActionContext<GraviteePlugin> context);

    WatchActionContext<GraviteePlugin> persistAsSuccess(WatchActionContext<GraviteePlugin> context);

    WatchActionContext<GraviteePlugin> persistAsError(WatchActionContext<GraviteePlugin> context, String message);

    MixedOperation<GraviteePlugin, GraviteePluginList, DoneableGraviteePlugin, io.fabric8.kubernetes.client.dsl.Resource<GraviteePlugin, DoneableGraviteePlugin>> getCrdClient();
}
