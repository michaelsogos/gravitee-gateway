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
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.definition.model.Policy;
import io.gravitee.definition.model.plugins.resources.Resource;
import io.gravitee.gateway.services.kube.crds.cache.PluginCacheManager;
import io.gravitee.gateway.services.kube.crds.cache.PluginRevision;
import io.gravitee.gateway.services.kube.crds.resources.*;
import io.gravitee.gateway.services.kube.crds.resources.plugin.Plugin;
import io.gravitee.gateway.services.kube.crds.status.GraviteePluginStatus;
import io.gravitee.gateway.services.kube.crds.status.IntegrationState;
import io.gravitee.gateway.services.kube.exceptions.PipelineException;
import io.gravitee.gateway.services.kube.exceptions.ValidationException;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.listeners.GraviteePluginsListener;
import io.reactivex.Flowable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.gravitee.gateway.services.kube.crds.ResourceConstants.*;
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

    private List<GraviteePluginsListener> listeners = new ArrayList<>();

    @Autowired
    private PluginCacheManager pluginCacheManager;

    @Override
    public void afterPropertiesSet() throws Exception {
        initializeGraviteePluginClient(client);
    }

    private void initializeGraviteePluginClient(KubernetesClient client) {
        CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP)
            .withVersion(DEFAULT_VERSION)
            .withScope(SCOPE)
            .withName(PLUGINS_FULLNAME)
            .withPlural(PLUGINS_PLURAL)
            .withKind(PLUGINS_KIND)
            .build();

        this.crdClient = client.customResources(context, GraviteePlugin.class, GraviteePluginList.class, DoneableGraviteePlugin.class);

        KubernetesDeserializer.registerCustomKind(GROUP + "/" + DEFAULT_VERSION, PLUGINS_KIND, GraviteePlugin.class);
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
            case DELETED:
                LOGGER.debug("Nothing to do on DELETE plugins"); // AdmissionHook avoid deleting Plugins currently in used
        }
        return pipeline;
    }

    protected WatchActionContext<GraviteePlugin> validate(WatchActionContext<GraviteePlugin> context) {
        LOGGER.debug("Validating GraviteePlugin resource '{}'", context.getResourceName());
        List<PluginRevision<?>> pluginRevisions = generatePluginRevision(context, true);
        context.addAllRevisions(pluginRevisions);
        return context;
    }

    /**
     * Parse all Plocy and Resource definition to generate list of PluginRevision.
     * If the readConf is set to false, only configuration deserialization and secret resolution are bypass. This is useful to process the CustomResource for validation by AdmissionHook
     * @param context
     * @param readConf
     * @return
     */
    private List<PluginRevision<?>> generatePluginRevision(WatchActionContext<GraviteePlugin> context, boolean readConf) {
        GraviteePluginSpec spec = context.getResource().getSpec();
        List<PluginRevision<?>> pluginRevisions = new ArrayList<>();
        for (Map.Entry<String, Plugin> entry : spec.getPlugins().entrySet()) {
            Plugin plugin = entry.getValue();
            try {
                if (plugin.defineResource()) {
                    Resource resource = new Resource();
                    resource.setName(buildResourceName(context, entry.getKey()));
                    resource.setType(plugin.getResource());
                    if (readConf) {
                        resource.setConfiguration(
                                OBJECT_MAPPER.writeValueAsString(kubernetesService.resolveSecret(context, context.getNamespace(), plugin.getConfiguration()))
                        );
                    }
                    PluginReference ref = convertToRef(context, entry.getKey());
                    pluginRevisions.add(new PluginRevision<>(resource, ref, context.getGeneration(), computeResourceHashCode(resource)));
                } else {
                    // policy or security policy, both have the same controls
                    final Policy policy = new Policy();
                    policy.setName(plugin.getPolicy());
                    if (readConf) {
                        policy.setConfiguration(
                                OBJECT_MAPPER.writeValueAsString(kubernetesService.resolveSecret(context, context.getNamespace(), plugin.getConfiguration()))
                        );
                    }
                    PluginReference ref = convertToRef(context, entry.getKey());
                    pluginRevisions.add(new PluginRevision<>(policy, ref, context.getGeneration(), computePolicyHashCode(policy)));
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Unable to process configuration for plugin {}", entry.getKey(), e);
                throw new PipelineException(context, "Unable to convert plugin configuration", e);
            }
        }
        return pluginRevisions;
    }

    @Override
    public WatchActionContext<GraviteePlugin> persistAsSuccess(WatchActionContext<GraviteePlugin> context) {
        GraviteePluginStatus status = context.getResource().getStatus();
        if (status == null) {
            status = new GraviteePluginStatus();
            context.getResource().setStatus(status);
        }

        final IntegrationState integration = new IntegrationState();
        integration.setState(IntegrationState.State.SUCCESS);
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
            return updateResourceStatusOnSuccess(context);
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
        GraviteePluginStatus status = context.getResource().getStatus();
        if (status == null) {
            status = new GraviteePluginStatus();
            context.getResource().setStatus(status);
        }

        if (
            !IntegrationState.State.ERROR.equals(status.getIntegration().getState()) ||
            !status.getIntegration().getMessage().equals(message)
        ) {
            // updating a CR status will trigger a new MODIFIED event, we have to test
            // if some plugins changed in order stop an infinite loop
            final IntegrationState integration = new IntegrationState();
            integration.setState(IntegrationState.State.ERROR);
            integration.setMessage(message);
            status.setIntegration(integration);

            return updateResourceStatusOnError(context, integration);
        } else {
            LOGGER.debug("No changes in GravteePlugins '{}', bypass status update", context.getResourceName());
            return context;
        }
    }

    private String buildResourceName(WatchActionContext<GraviteePlugin> context, String name) {
        return name + "." + context.getResourceName() + "." + context.getNamespace();
    }

    private String buildResourceName(GraviteePlugin pluginCustomResource, String name) {
        return name + "." + pluginCustomResource.getMetadata().getName() + "." + pluginCustomResource.getMetadata().getNamespace();
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
                GraviteePlugin gioPlugin = loadPluginDefinition(context, pluginRef, namespace);

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
                GraviteePlugin gioPlugin = loadPluginDefinition(context, pluginRef, namespace);

                Optional<Plugin> optPlugin = gioPlugin.getSpec().getPlugin(pluginRef.getName());

                result = new PluginRevision<>(null, pluginRef, gioPlugin.getMetadata().getGeneration(), null);
                if (optPlugin.isPresent()) {
                    plugin = optPlugin.get();
                    if (plugin.defineResource()) {
                        Resource resource = new Resource();
                        resource.setName(buildResourceName(gioPlugin, pluginRef.getName()));
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

    protected GraviteePlugin loadPluginDefinition(WatchActionContext context, PluginReference pluginRef, String namespace) {
        GraviteePlugin gioPlugin = this.crdClient.inNamespace(namespace).withName(pluginRef.getResource()).get();
        if (gioPlugin == null) {
            throw new PipelineException(context, "Reference '" + pluginRef.getResource() + "' undefined in namespace '" + namespace + "'");
        }
        return gioPlugin;
    }

    @Override
    public void maybeSafelyCreated(GraviteePlugin plugin) {
        try {
            this.validate(new WatchActionContext<>(plugin, WatchActionContext.Event.ADDED));
        } catch (PipelineException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    @Override
    public void maybeSafelyUpdated(GraviteePlugin plugin, GraviteePlugin oldPlugin) {
        WatchActionContext<GraviteePlugin> pluginCtx = new WatchActionContext<>(plugin, WatchActionContext.Event.MODIFIED);
        WatchActionContext<GraviteePlugin> oldPluginCtx = new WatchActionContext<>(oldPlugin, WatchActionContext.Event.MODIFIED);

        try {
            this.validate(pluginCtx);
            List<PluginRevision<?>> oldPluginRevisions = this.generatePluginRevision(oldPluginCtx, false);
            checkBrokenPluginUsage(getDeletedPlugins(pluginCtx.getPluginRevisions(), oldPluginRevisions));
        } catch (PipelineException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    /**
     * Perform a diff between both context to generate a list of PluginReference removed from the GraviteePlugin by the resource update.
     * @param pluginRevision
     * @param oldPluginRevision
     * @return
     */
    private Stream<PluginReference> getDeletedPlugins(List<PluginRevision<?>> pluginRevision, List<PluginRevision<?>> oldPluginRevision) {
        return oldPluginRevision.stream().map(PluginRevision::getPluginReference)
                .filter(maybeDeleted -> !pluginRevision.stream()
                        .map(PluginRevision::getPluginReference)
                        .filter(present -> present.equals(maybeDeleted)).findFirst().isPresent());
    }

    @Override
    public void maybeSafelyDeleted(GraviteePlugin deletedPlugin) {
        WatchActionContext<GraviteePlugin> context = new WatchActionContext<>(deletedPlugin, WatchActionContext.Event.DELETED);
        checkBrokenPluginUsage(this.generatePluginRevision(context, false).stream().map(PluginRevision::getPluginReference));
    }

    private void checkBrokenPluginUsage(Stream<PluginReference> pluginRef) {
        List<String> resources = pluginRef.flatMap(ref -> pluginCacheManager.resourcesUsingPlugin(ref).stream()).distinct().collect(Collectors.toList());
        if (!resources.isEmpty()) {
            throw new ValidationException("Plugins are used by GraviteeGateway or GraviteeServices : [" + String.join(", " , resources) + "]");
        }
    }

    @Override
    protected void resetIntegrationState(IntegrationState integration, GraviteePlugin refreshedResource) {
        refreshedResource.getStatus().setIntegration(integration);
    }

    @Override
    protected IntegrationState extractIntegrationState(GraviteePlugin refreshedResource) {
        return refreshedResource.getStatus().getIntegration();
    }
}
