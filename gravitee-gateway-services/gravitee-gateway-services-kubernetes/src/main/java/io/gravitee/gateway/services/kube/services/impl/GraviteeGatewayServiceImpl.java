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
import io.gravitee.gateway.services.kube.crds.cache.PluginRevision;
import io.gravitee.gateway.services.kube.crds.resources.*;
import io.gravitee.gateway.services.kube.crds.resources.service.BackendConfiguration;
import io.gravitee.gateway.services.kube.crds.status.GraviteeGatewayStatus;
import io.gravitee.gateway.services.kube.crds.status.GraviteePluginStatus;
import io.gravitee.gateway.services.kube.services.GraviteeGatewayService;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.listeners.GraviteeGatewayListener;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.gravitee.gateway.services.kube.utils.ControllerDigestHelper.computeGenericConfigHashCode;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class GraviteeGatewayServiceImpl
    extends AbstractServiceImpl<GraviteeGateway, GraviteeGatewayList, DoneableGraviteeGateway>
    implements GraviteeGatewayService, InitializingBean {

    private static Logger LOGGER = LoggerFactory.getLogger(GraviteeServicesServiceImpl.class);

    private List<GraviteeGatewayListener> listeners = new ArrayList<>();

    @Autowired
    private GraviteePluginsService graviteePluginsService;

    private void initializeGraviteeGatewayClient(KubernetesClient client) {
        CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
            .withGroup("gravitee.io")
            .withVersion("v1alpha1")
            .withScope("Namespaced")
            .withName("gravitee-gateways.gravitee.io")
            .withPlural("gravitee-gateways")
            .withKind("GraviteeGateway")
            .build();

        this.crdClient = client.customResources(context, GraviteeGateway.class, GraviteeGatewayList.class, DoneableGraviteeGateway.class);

        KubernetesDeserializer.registerCustomKind("gravitee.io/v1alpha1", "GraviteeGateway", GraviteeGateway.class);
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
        return this.crdClient.inNamespace(namespace).withName(ref.getName()).get();
    }

    @Override
    public Flowable processAction(WatchActionContext<GraviteeGateway> context) {
        Flowable<WatchActionContext<GraviteeGateway>> pipeline = Flowable.just(context);
        switch (context.getEvent()) {
            case ADDED:
                // only validate plugins and HttpConfigs to compute hashcode
                pipeline =
                    Flowable
                        .just(context)
                        .map(this::computeBackendConfigHashCode)
                        .map(this::validatePolicies)
                        .map(this::validateSecurity)
                        .map(this::validateResources)
                        .map(this::persistAsSuccess); // don't know why I can't use it at the end of GraviteeGatewayManagement flow
                break;
            case MODIFIED:
                pipeline =
                    Flowable
                        .just(context)
                        .map(this::computeBackendConfigHashCode)
                        .map(this::validatePolicies)
                        .map(this::validateSecurity)
                        .map(this::validateResources)
                        .map(this::notifyListeners)
                        .map(this::persistAsSuccess); // don't know why I can't use it at the end of GraviteeGatewayManagement flow
                break;
            case REFERENCE_UPDATED:
                pipeline =
                    Flowable
                        .just(context)
                        .map(this::computeBackendConfigHashCode)
                        .map(this::validatePolicies)
                        .map(this::validateSecurity)
                        .map(this::validateResources)
                        .map(this::notifyListeners)
                        .map(this::persistAsSuccess); // don't know why I can't use it at the end of GraviteeGatewayManagement flow
                break;
            default:
            // TODO On delete event : read only status to undeploy services
        }
        return pipeline;
    }

    protected WatchActionContext<GraviteeGateway> validatePolicies(WatchActionContext<GraviteeGateway> context) {
        LOGGER.debug("Validate and Compute HashCode for policies of GraviteeGateway '{}'", context.getResourceName());
        GraviteeGatewaySpec spec = context.getResource().getSpec();
        if (spec.getPolicies() != null) {
            for (GraviteePluginReference pluginRef : spec.getPolicies()) {
                PluginRevision<Policy> policy = graviteePluginsService.buildPolicy(context, null, pluginRef);
                context.getPluginRevisions().add(policy);
            }
        }
        return context;
    }

    protected WatchActionContext<GraviteeGateway> validateSecurity(WatchActionContext<GraviteeGateway> context) {
        LOGGER.debug("Validate and Compute HashCode for security plugin of GraviteeGateway '{}'", context.getResourceName());
        GraviteeGatewaySpec spec = context.getResource().getSpec();
        if (spec.getSecurity() != null) {
            PluginRevision<Policy> policy = graviteePluginsService.buildPolicy(context, null, spec.getSecurity());
            context.getPluginRevisions().add(policy);
        }
        return context;
    }

    protected WatchActionContext<GraviteeGateway> validateResources(WatchActionContext<GraviteeGateway> context) {
        LOGGER.debug("Validate and Compute HashCode for resources of GraviteeGateway '{}'", context.getResourceName());
        GraviteeGatewaySpec spec = context.getResource().getSpec();
        if (spec.getResources() != null) {
            for (GraviteePluginReference pluginRef : spec.getResources()) {
                PluginRevision<Resource> resource = graviteePluginsService.buildResource(context, null, pluginRef);
                context.getPluginRevisions().add(resource);
            }
        }
        return context;
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
        reloadCustomResource(context);

        GraviteeGatewayStatus status = context.getResource().getStatus();
        if (status == null) {
            status = new GraviteeGatewayStatus();
            status.setHashCodes(status.new GatewayHashCodes());
            context.getResource().setStatus(status);
        }

        final GraviteeGatewayStatus.IntegrationState integration = new GraviteeGatewayStatus.IntegrationState();
        integration.setState(GraviteeGatewayStatus.GatewayState.SUCCESS);
        status.setIntegration(integration);
        integration.setMessage("");

        Map<String, String> newHashCodes = buildHashCodes(context);
        if (hasChanged(context, status, newHashCodes)) {
            // updating a CR status will trigger a new MODIFIED event, we have to test
            // if some plugins changed in order stop an infinite loop
            status.getHashCodes().setPlugins(newHashCodes);
            status.getHashCodes().setDefaultHttpConfig(context.getHttpConfigHashCode());
            return context.refreshResource(crdClient.updateStatus(context.getResource()));
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
            !context.getHttpConfigHashCode().equals(status.getHashCodes().getDefaultHttpConfig())
        );
    }

    @Override
    public WatchActionContext<GraviteeGateway> persistAsError(WatchActionContext<GraviteeGateway> context, String message) {
        reloadCustomResource(context);
        GraviteeGatewayStatus status = context.getResource().getStatus();
        if (status == null) {
            status = new GraviteeGatewayStatus();
            context.getResource().setStatus(status);
        }

        if (
            !GraviteePluginStatus.PluginState.ERROR.equals(status.getIntegration().getState()) ||
            !status.getIntegration().getMessage().equals(message)
        ) {
            // updating a CR status will trigger a new MODIFIED event, we have to test
            // if some plugins changed in order stop an infinite loop
            final GraviteeGatewayStatus.IntegrationState integration = new GraviteeGatewayStatus.IntegrationState();
            integration.setState(GraviteeGatewayStatus.GatewayState.ERROR);
            integration.setMessage(message);
            status.setIntegration(integration);

            return context.refreshResource(crdClient.updateStatus(context.getResource()));
        } else {
            LOGGER.debug("No changes in GraviteeGateway '{}', bypass status update", context.getResourceName());
            return context;
        }
    }
}
