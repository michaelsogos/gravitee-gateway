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

import com.google.common.collect.Maps;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.gravitee.definition.model.Policy;
import io.gravitee.definition.model.plugins.resources.Resource;
import io.gravitee.gateway.services.kube.crds.cache.GatewayCacheManager;
import io.gravitee.gateway.services.kube.crds.cache.PluginCacheManager;
import io.gravitee.gateway.services.kube.crds.cache.PluginRevision;
import io.gravitee.gateway.services.kube.crds.resources.*;
import io.gravitee.gateway.services.kube.crds.resources.plugin.Plugin;
import io.gravitee.gateway.services.kube.crds.resources.service.BackendConfiguration;
import io.gravitee.gateway.services.kube.crds.status.GraviteeGatewayStatus;
import io.gravitee.gateway.services.kube.crds.status.IntegrationState;
import io.gravitee.gateway.services.kube.exceptions.PipelineException;
import io.gravitee.gateway.services.kube.exceptions.ValidationException;
import io.gravitee.gateway.services.kube.services.GraviteeGatewayService;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.listeners.GraviteeGatewayListener;
import io.reactivex.Flowable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.gravitee.gateway.services.kube.crds.ResourceConstants.*;
import static io.gravitee.gateway.services.kube.utils.ControllerDigestHelper.computeGenericConfigHashCode;
import static io.gravitee.gateway.services.kube.utils.K8SResourceUtils.getFullName;
import static io.reactivex.Flowable.just;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class GraviteeGatewayServiceImpl
    extends AbstractServiceImpl<GraviteeGateway, GraviteeGatewayList, DoneableGraviteeGateway>
    implements GraviteeGatewayService, InitializingBean {

    private List<GraviteeGatewayListener> listeners = new ArrayList<>();

    @Autowired
    private GraviteePluginsService graviteePluginsService;

    @Autowired
    private PluginCacheManager pluginCacheManager;

    @Autowired
    private GatewayCacheManager gatewayCacheManager;

    private void initializeGraviteeGatewayClient(KubernetesClient client) {
        CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP)
            .withVersion(DEFAULT_VERSION)
            .withScope(SCOPE)
            .withName(GATEWAY_FULLNAME)
            .withPlural(GATEWAY_PLURAL)
            .withKind(GATEWAY_KIND)
            .build();

        this.crdClient = client.customResources(context, GraviteeGateway.class, GraviteeGatewayList.class, DoneableGraviteeGateway.class);

        KubernetesDeserializer.registerCustomKind(GROUP+"/"+DEFAULT_VERSION, GATEWAY_KIND, GraviteeGateway.class);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        initializeGraviteeGatewayClient(client);
    }

    @Override
    public void registerListener(GraviteeGatewayListener listener) {
        if (listener != null) {
            LOGGER.debug("Addition of {} as GatewayListener", listener.getClass().getName());
            this.listeners.add(listener);
        }
    }

    public List<GraviteeGateway> listAllGateways() {
        GraviteeGatewayList list = crdClient.list();
        return list.getItems();
    }

    @Override
    public GraviteeGateway lookup(WatchActionContext context, GraviteeGatewayReference ref) {
        final String namespace = getReferenceNamespace(context.getNamespace(), ref);
        GraviteeGateway gw = this.crdClient.inNamespace(namespace).withName(ref.getName()).get();
        if (gw == null) {
            throw new PipelineException(context, "Gateway Reference '" + ref.getName() + "' undefined in namespace '" + namespace + "'");
        }
        return gw;
    }

    @Override
    public Flowable processAction(WatchActionContext<GraviteeGateway> context) {
        Flowable<WatchActionContext<GraviteeGateway>> pipeline = just(context);
        switch (context.getEvent()) {
            case ADDED:
                // only validate plugins and HttpConfigs to compute hashcode
                pipeline = validateGatewayResource(pipeline)
                        .map(this::persistAsSuccess); // don't know why I can't use it at the end of GraviteeGatewayManagement flow
                break;
            case MODIFIED:
            case REFERENCE_UPDATED:
                pipeline = validateGatewayResource(pipeline)
                        .map(this::notifyListeners)
                        .map(this::persistAsSuccess); // don't know why I can't use it at the end of GraviteeGatewayManagement flow
                break;
            case DELETED:
                pipeline = pipeline.map(this::clearPluginCache);
        }
        return pipeline;
    }

    protected WatchActionContext<GraviteeGateway> clearPluginCache(WatchActionContext<GraviteeGateway> context) {
        LOGGER.debug("remove entries in plugin cache for '{}' gateway", context.getResourceName());
        // Remove all plugins reference used by this Gateway from the plugin cache
        pluginCacheManager.removePluginsUsedBy(context.getResourceFullName());
        return context;
    }

    protected Flowable<WatchActionContext<GraviteeGateway>> validateGatewayResource(Flowable<WatchActionContext<GraviteeGateway>> pipeline) {
        return pipeline.map(this::computeBackendConfigHashCode)
                .map(this::validateAuthentication)
                .map(this::validateResources);
    }


    protected WatchActionContext<GraviteeGateway> validateAuthentication(WatchActionContext<GraviteeGateway> context) {
        LOGGER.debug("Validate and Compute HashCode for authentication plugin of GraviteeGateway '{}'", context.getResourceName());
        GraviteeGatewaySpec spec = context.getResource().getSpec();
        if (spec.getAuthentication() != null) {
            PluginRevision<Policy> policy = graviteePluginsService.buildPolicy(context, spec.getAuthentication(), convertToRef(context, "authentication"));
            context.getPluginRevisions().add(policy);
        } else if (spec.getAuthenticationReference() != null) {
            PluginRevision<Policy> policy = graviteePluginsService.buildPolicy(context, null, spec.getAuthenticationReference());
            context.getPluginRevisions().add(policy);
        }
        return context;
    }

    protected WatchActionContext<GraviteeGateway> validateResources(WatchActionContext<GraviteeGateway> context) {
        LOGGER.debug("Validate and Compute HashCode for resources of GraviteeGateway '{}'", context.getResourceName());
        extractResources(context).forEach(context.getPluginRevisions()::add);
        return context;
    }

    @Override
    public List<PluginRevision<Resource>> extractResources(WatchActionContext<GraviteeGateway> context) {
        List<PluginRevision<Resource>> accumulator = new ArrayList<>();
        GraviteeGatewaySpec spec = context.getResource().getSpec();
        if (spec.getResourceReferences() != null) {
            for (PluginReference pluginRef : spec.getResourceReferences()) {
                PluginRevision<Resource> resource = graviteePluginsService.buildResource(context, null, pluginRef);
                accumulator.add(resource);
            }
        }
        if (spec.getResources() != null) {
            for (Map.Entry<String, Plugin> pluginEntry : spec.getResources().entrySet()) {
                pluginEntry.getValue().setIdentifier(pluginEntry.getKey()); // use the identifier field to initialize resource name
                PluginRevision<Resource> resource = graviteePluginsService.buildResource(context, pluginEntry.getValue(),convertToRef(context, pluginEntry.getKey()));
                accumulator.add(resource);
            }
        }
        return accumulator;
    }

    protected WatchActionContext<GraviteeGateway> computeBackendConfigHashCode(WatchActionContext<GraviteeGateway> context) {
        LOGGER.debug("Compute HashCode for DefaultBackendConfiguration of GraviteeGateway '{}'", context.getResourceName());
        GraviteeGatewaySpec spec = context.getResource().getSpec();
        // try to resolve secret before hash processing
        BackendConfiguration backendConfig = spec.getDefaultBackendConfigurations();
        Map<String, Object> sslOptions = kubernetesService.resolveSecret(context, context.getNamespace(), backendConfig.getHttpClientSslOptions());
        Map<String, Object> proxyOptions = kubernetesService.resolveSecret(context, context.getNamespace(), backendConfig.getHttpProxy());
        String hashCode = computeGenericConfigHashCode(backendConfig.getHttpClientOptions(), sslOptions, proxyOptions);
        context.setHttpConfigHashCode(hashCode);
        return context;
    }

    protected WatchActionContext<GraviteeGateway> notifyListeners(WatchActionContext<GraviteeGateway> context) {
        GraviteeGatewayStatus status = context.getResource().getStatus();
        Map<String, String> newHashCodes = buildHashCodes(context);
        if (hasChanged(context, status, newHashCodes)) {
            for (GraviteeGatewayListener listener : this.listeners) {
                listener.onGatewayUpdate(context);
            }
        }
        return context;
    }

    @Override
    public WatchActionContext<GraviteeGateway> persistAsSuccess(WatchActionContext<GraviteeGateway> context) {
        // keep in cache all plugin reference used by this Gateway
        // in order to test broken dependencies on GraviteePlugins Update
        pluginCacheManager.registerPluginsFor(
                context.getResourceFullName(),
                context.getPluginRevisions()
                        .stream()
                        .filter(PluginRevision::isRef)
                        .collect(Collectors.toList()));

        GraviteeGatewayStatus status = context.getResource().getStatus();
        if (status == null) {
            status = new GraviteeGatewayStatus();
            status.setHashCodes(status.new GatewayHashCodes());
            context.getResource().setStatus(status);
        }

        final IntegrationState integration = new IntegrationState();
        integration.setState(IntegrationState.State.SUCCESS);
        status.setIntegration(integration);
        integration.setMessage("");

        Map<String, String> newHashCodes = buildHashCodes(context);
        if (hasChanged(context, status, newHashCodes)) {
            // updating a CR status will trigger a new MODIFIED event, we have to test
            // if some plugins changed in order stop an infinite loop
            status.getHashCodes().setPlugins(newHashCodes);
            status.getHashCodes().setBackendConfig(context.getHttpConfigHashCode());

            return updateResourceStatusOnSuccess(context);
        } else {
            LOGGER.debug("No changes in GravteeGateway '{}', bypass status update", context.getResourceName());
            return context;
        }
    }

    private boolean hasChanged(
        WatchActionContext<GraviteeGateway> context,
        GraviteeGatewayStatus status,
        Map<String, String> newHashCodes
    ) {
        return (
            !Maps.difference(newHashCodes, status.getHashCodes().getPlugins()).areEqual() ||
            !context.getHttpConfigHashCode().equals(status.getHashCodes().getBackendConfig())
        );
    }

    @Override
    public WatchActionContext<GraviteeGateway> persistAsError(WatchActionContext<GraviteeGateway> context, String message) {
        GraviteeGatewayStatus status = context.getResource().getStatus();
        if (status == null) {
            status = new GraviteeGatewayStatus();
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
            LOGGER.debug("No changes in GraviteeGateway '{}', bypass status update", context.getResourceName());
            return context;
        }
    }

    @Override
    public void maybeSafelyCreated(GraviteeGateway gateway) {
        try {
            validateGatewayResource(just(new WatchActionContext<GraviteeGateway>(gateway, WatchActionContext.Event.ADDED))).blockingSubscribe();
        } catch (PipelineException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    @Override
    public void maybeSafelyUpdated(GraviteeGateway gateway) {
        try {
            validateGatewayResource(just(new WatchActionContext<GraviteeGateway>(gateway, WatchActionContext.Event.ADDED))).blockingSubscribe();
            List<String> services = this.gatewayCacheManager.getServiceByGateway(getFullName(gateway.getMetadata()));
            for (String service : new ArrayList<>(services)) { // iterate on new Array to remove entries into services
                final boolean serviceUseGatewayAuth = this.gatewayCacheManager.getCacheEntryByService(service).useGatewayAuthDefinition();
                final boolean gwWithAuthDefinition = gateway.getSpec().getAuthentication() != null || gateway.getSpec().getAuthenticationReference() != null;
                if (!serviceUseGatewayAuth || gwWithAuthDefinition) {
                    services.remove(service);
                }
            }

            if (!services.isEmpty()) {
                throw new ValidationException("Authentication definition is missing but expected by some services : [" + String.join(", ", services) + "]");
            }
        } catch (PipelineException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    @Override
    public void maybeSafelyDeleted(GraviteeGateway gateway) {
        List<String> services = this.gatewayCacheManager.getServiceByGateway(getFullName(gateway.getMetadata()));
        if (!services.isEmpty()) {
            throw new ValidationException("Gateway resource is used by some services : [" + String.join(", ", services) + "]");
        }
    }

    @Override
    protected void resetIntegrationState(IntegrationState integration, GraviteeGateway refreshedResource) {
        refreshedResource.getStatus().setIntegration(integration);
    }

    @Override
    protected IntegrationState extractIntegrationState(GraviteeGateway refreshedResource) {
        return refreshedResource.getStatus().getIntegration();
    }
}
