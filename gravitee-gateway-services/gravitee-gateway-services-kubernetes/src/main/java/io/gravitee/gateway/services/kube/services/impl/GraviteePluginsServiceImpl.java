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
package io.gravitee.gateway.services.kube.services.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Maps;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.gravitee.definition.model.Policy;
import io.gravitee.definition.model.plugins.resources.Resource;
import io.gravitee.gateway.services.kube.crds.cache.PluginRevision;
import io.gravitee.gateway.services.kube.crds.resources.*;
import io.gravitee.gateway.services.kube.crds.resources.plugin.Plugin;
import io.gravitee.gateway.services.kube.crds.status.GraviteePluginStatus;
import io.gravitee.gateway.services.kube.exceptions.PipelineException;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.listeners.GraviteePluginsListener;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.*;

import static io.gravitee.gateway.services.kube.utils.ControllerDigestHelper.computePolicyHashCode;
import static io.gravitee.gateway.services.kube.utils.ControllerDigestHelper.computeResourceHashCode;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class GraviteePluginsServiceImpl
    extends AbstractServiceImpl<GraviteePlugin, GraviteePluginList, DoneableGraviteePlugin>
    implements GraviteePluginsService, InitializingBean {

    private static Logger LOGGER = LoggerFactory.getLogger(GraviteePluginsServiceImpl.class);

    private List<GraviteePluginsListener> listeners = new ArrayList<>();

    @Override
    public void afterPropertiesSet() throws Exception {
        initializeGraviteePluginClient(client);
    }

    private void initializeGraviteePluginClient(KubernetesClient client) {
        CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
            .withGroup("gravitee.io")
            .withVersion("v1alpha1")
            .withScope("Namespaced")
            .withName("gravitee-plugins.gravitee.io")
            .withPlural("gravitee-plugins")
            .withKind("GraviteePlugins")
            .build();

        this.crdClient = client.customResources(context, GraviteePlugin.class, GraviteePluginList.class, DoneableGraviteePlugin.class);

        KubernetesDeserializer.registerCustomKind("gravitee.io/v1alpha1", "GraviteePlugin", GraviteePlugin.class);
    }

    @Override
    public void registerListener(GraviteePluginsListener listener) {
        if (listener != null) {
            LOGGER.debug("Addition of {} as PluginsListener", listener.getClass().getName());
            this.listeners.add(listener);
        }
    }

    @Override
    public Flowable<WatchActionContext<GraviteePlugin>> processAction(WatchActionContext<GraviteePlugin> context) {
        Flowable<WatchActionContext<GraviteePlugin>> pipeline = Flowable.just(context);
        switch (context.getEvent()) {
            case ADDED:
                // only validate plugins to compute hashcode
                pipeline = Flowable.just(context).map(this::validate).map(this::persistAsSuccess); // don't know why I can't use it at the end of GraviteePluginManagement flow
                break;
            case MODIFIED:
                pipeline = Flowable.just(context).map(this::validate).map(this::notifyListeners).map(this::persistAsSuccess); // don't know why I can't use it at the end of GraviteePluginManagement flow
                break;
            default:
            // TODO On delete event : read only status to undeploy services
        }
        return pipeline;
    }

    protected WatchActionContext<GraviteePlugin> validate(WatchActionContext<GraviteePlugin> context) {
        LOGGER.debug("Validating GraviteePlugin resource '{}'", context.getResourceName());

        GraviteePluginSpec spec = context.getResource().getSpec();
        List<PluginRevision<?>> pluginRevisions = new ArrayList<>();
        for (Map.Entry<String, Plugin> entry : spec.getPlugins().entrySet()) {
            Plugin plugin = entry.getValue();
            try {
                if (plugin.defineResource()) {
                    Resource resource = new Resource();
                    resource.setName(buildResourceName(context, entry.getKey()));
                    resource.setType(plugin.getResource());
                    resource.setConfiguration(
                        OBJECT_MAPPER.writeValueAsString(kubernetesService.resolveSecret(context, context.getNamespace(), plugin.getConfiguration()))
                    );

                    PluginReference ref = convertToRef(context, entry.getKey());
                    pluginRevisions.add(new PluginRevision<>(resource, ref, context.getGeneration(), computeResourceHashCode(resource)));
                } else {
                    // policy or security policy, both have the same controls
                    final Policy policy = new Policy();
                    policy.setName(plugin.getPolicy());
                    policy.setConfiguration(
                        OBJECT_MAPPER.writeValueAsString(kubernetesService.resolveSecret(context, context.getNamespace(), plugin.getConfiguration()))
                    );

                    PluginReference ref = convertToRef(context, entry.getKey());
                    pluginRevisions.add(new PluginRevision<>(policy, ref, context.getGeneration(), computePolicyHashCode(policy)));
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Unable to process configuration for plugin {}", entry.getKey(), e);
                throw new PipelineException(context, "Unable to convert plugin configuration", e);
            }
        }
        context.addAllRevisions(pluginRevisions);
        return context;
    }

    @Override
    public WatchActionContext<GraviteePlugin> persistAsSuccess(WatchActionContext<GraviteePlugin> context) {
        reloadCustomResource(context);

        GraviteePluginStatus status = context.getResource().getStatus();
        if (status == null) {
            status = new GraviteePluginStatus();
            context.getResource().setStatus(status);
        }

        final GraviteePluginStatus.IntegrationState integration = new GraviteePluginStatus.IntegrationState();
        integration.setState(GraviteePluginStatus.PluginState.SUCCESS);
        status.setIntegration(integration);
        integration.setMessage("");

        Map<String, String> newHashCodes = new HashMap<>();
        context
            .getPluginRevisions()
            .forEach(
                rev -> {
                    newHashCodes.put(rev.getPluginReference().getName(), rev.getHashCode());
                }
            );

        if (hasChanged(status, newHashCodes)) {
            // updating a CR status will trigger a new MODIFIED event, we have to test
            // if some plugins changed in order stop an infinite loop
            status.setHashCodes(newHashCodes);
            return context.refreshResource(crdClient.inNamespace(context.getNamespace()).updateStatus(context.getResource()));
        } else {
            LOGGER.debug("No changes in GravteePlugins '{}', bypass status update", context.getResourceName());
            return context;
        }
    }

    private boolean hasChanged(GraviteePluginStatus status, Map<String, String> newHashCodes) {
        return !Maps.difference(newHashCodes, status.getHashCodes()).areEqual();
    }

    @Override
    public WatchActionContext<GraviteePlugin> persistAsError(WatchActionContext<GraviteePlugin> context, String message) {
        reloadCustomResource(context);
        GraviteePluginStatus status = context.getResource().getStatus();
        if (status == null) {
            status = new GraviteePluginStatus();
            context.getResource().setStatus(status);
        }

        if (
            !GraviteePluginStatus.PluginState.ERROR.equals(status.getIntegration().getState()) ||
            !status.getIntegration().getMessage().equals(message)
        ) {
            // updating a CR status will trigger a new MODIFIED event, we have to test
            // if some plugins changed in order stop an infinite loop
            final GraviteePluginStatus.IntegrationState integration = new GraviteePluginStatus.IntegrationState();
            integration.setState(GraviteePluginStatus.PluginState.ERROR);
            integration.setMessage(message);
            status.setIntegration(integration);

            return context.refreshResource(crdClient.inNamespace(context.getNamespace()).updateStatus(context.getResource()));
        } else {
            LOGGER.debug("No changes in GravteePlugins '{}', bypass status update", context.getResourceName());
            return context;
        }
    }

    private String buildResourceName(WatchActionContext<GraviteePlugin> context, String name) {
        return name + "." + context.getResourceName() + "." + context.getNamespace();
    }

    protected WatchActionContext<GraviteePlugin> notifyListeners(WatchActionContext<GraviteePlugin> context) {
        GraviteePluginStatus status = context.getResource().getStatus();
        Map<String, String> newHashCodes = buildHashCodes(context);
        if (hasChanged(status, newHashCodes)) {
            for (GraviteePluginsListener listener : this.listeners) {
                listener.onPluginsUpdate(context);
            }
        }
        return context;
    }

    @Override
    public PluginRevision<Policy> buildAuthenticationPolicy(WatchActionContext context, PluginReference pluginRef) {
        PluginRevision<Policy> result = new PluginRevision<>(null, pluginRef, 0, null);
        try {
            // if namespace isn't specified in the plugin reference, we use the same namespace as the context resource
            final String namespace = getReferenceNamespace(context, pluginRef);
            GraviteePlugin gioPlugin = this.crdClient.inNamespace(namespace).withName(pluginRef.getResource()).get();
            Optional<Plugin> optPlugin = gioPlugin.getSpec().getPlugin(pluginRef.getName());
            if (optPlugin.isPresent()) {
                Plugin plugin = optPlugin.get();
                if (plugin.definePolicy()) {
                    final Policy policy = new Policy();
                    policy.setName(plugin.getPolicy());
                    policy.setConfiguration(
                        OBJECT_MAPPER.writeValueAsString(kubernetesService.resolveSecret(context, namespace, plugin.getConfiguration()))
                    );
                    result =
                        new PluginRevision<>(policy, pluginRef, gioPlugin.getMetadata().getGeneration(), computePolicyHashCode(policy));
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Unable to process security configuration for pluginRef {}", pluginRef, e);
        }

        return result;
    }

    @Override
    public PluginRevision<Policy> buildPolicy(WatchActionContext context, Plugin plugin, PluginReference pluginRef) {
        PluginRevision<Policy> result = new PluginRevision<>(null);
        try {
            if (plugin != null) {
                if (plugin.definePolicy()) {
                    final Policy policy = new Policy();
                    policy.setName(plugin.getPolicy());
                    policy.setConfiguration(
                        OBJECT_MAPPER.writeValueAsString(kubernetesService.resolveSecret(context, context.getNamespace(), plugin.getConfiguration()))
                    );
                    result = new PluginRevision<>(policy, pluginRef, context.getGeneration(), computePolicyHashCode(policy));
                }
            }

            if (pluginRef != null && !result.isValid()) {
                // if namespace isn't specified in the plugin reference, we use the same namespace as the context resource
                final String namespace = getReferenceNamespace(context, pluginRef);
                GraviteePlugin gioPlugin = this.crdClient.inNamespace(namespace).withName(pluginRef.getResource()).get();
                Optional<Plugin> optPlugin = gioPlugin.getSpec().getPlugin(pluginRef.getName());

                result = new PluginRevision<>(null, pluginRef, gioPlugin.getMetadata().getGeneration(), null);
                if (optPlugin.isPresent()) {
                    plugin = optPlugin.get();
                    if (plugin.definePolicy()) {
                        final Policy policy = new Policy();
                        policy.setName(plugin.getPolicy());
                        policy.setConfiguration(
                            OBJECT_MAPPER.writeValueAsString(kubernetesService.resolveSecret(context, namespace, plugin.getConfiguration()))
                        );
                        result =
                            new PluginRevision<>(policy, pluginRef, gioPlugin.getMetadata().getGeneration(), computePolicyHashCode(policy));
                    }
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Unable to process policy configuration for plugin {}", plugin, e);
        }

        return result;
    }

    @Override
    public PluginRevision<Resource> buildResource(WatchActionContext context, Plugin plugin, PluginReference pluginRef) {
        PluginRevision<Resource> result = new PluginRevision<>(null);
        try {
            if (plugin != null) {
                if (plugin.defineResource()) {
                    final Resource resource = new Resource();
                    resource.setName(buildResourceName(context, plugin.getIdentifier()));
                    resource.setType(plugin.getResource());
                    resource.setConfiguration(
                            OBJECT_MAPPER.writeValueAsString(kubernetesService.resolveSecret(context, context.getNamespace(), plugin.getConfiguration()))
                    );
                    result = new PluginRevision<>(resource, pluginRef, context.getGeneration(), computeResourceHashCode(resource));
                }
            }

            if (pluginRef != null && !result.isValid()) {
                // if namespace isn't specified in the plugin reference, we use the same namespace as the context resource
                final String namespace = getReferenceNamespace(context, pluginRef);
                GraviteePlugin gioPlugin = this.crdClient.inNamespace(namespace).withName(pluginRef.getResource()).get();
                Optional<Plugin> optPlugin = gioPlugin.getSpec().getPlugin(pluginRef.getName());

                result = new PluginRevision<>(null, pluginRef, gioPlugin.getMetadata().getGeneration(), null);
                if (optPlugin.isPresent()) {
                    plugin = optPlugin.get();
                    if (plugin.defineResource()) {
                        Resource resource = new Resource();
                        resource.setName(buildResourceName(context, pluginRef.getName()));
                        resource.setType(plugin.getResource());
                        resource.setConfiguration(
                            OBJECT_MAPPER.writeValueAsString(kubernetesService.resolveSecret(context, context.getNamespace(), plugin.getConfiguration()))
                        );

                        result = new PluginRevision<>(resource, pluginRef, context.getGeneration(), computeResourceHashCode(resource));
                    }
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Unable to process resource configuration for plugin {}", plugin, e);
        }

        return result;
    }

}
