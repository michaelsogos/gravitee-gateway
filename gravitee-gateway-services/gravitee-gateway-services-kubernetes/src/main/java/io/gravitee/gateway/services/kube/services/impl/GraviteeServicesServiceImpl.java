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

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.gravitee.definition.model.*;
import io.gravitee.definition.model.VirtualHost;
import io.gravitee.definition.model.endpoint.HttpEndpoint;
import io.gravitee.definition.model.plugins.resources.Resource;
import io.gravitee.gateway.handlers.api.definition.Api;
import io.gravitee.gateway.handlers.api.manager.ApiManager;
import io.gravitee.gateway.services.kube.crds.cache.PluginRevision;
import io.gravitee.gateway.services.kube.crds.resources.*;
import io.gravitee.gateway.services.kube.crds.resources.plugin.Plugin;
import io.gravitee.gateway.services.kube.crds.resources.service.*;
import io.gravitee.gateway.services.kube.services.GraviteeGatewayService;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.GraviteeServicesService;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.gravitee.gateway.services.kube.crds.resources.service.BackendConfiguration.buildHttpClientSslOptions;
import static io.gravitee.gateway.services.kube.crds.resources.service.BackendConfiguration.buildHttpProxy;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class GraviteeServicesServiceImpl
    extends AbstractServiceImpl<GraviteeServices, GraviteeServicesList, DoneableGraviteeServices>
    implements GraviteeServicesService, InitializingBean {

    private static Logger LOGGER = LoggerFactory.getLogger(GraviteeServicesServiceImpl.class);

    @Autowired
    private GraviteePluginsService pluginsService;

    @Autowired
    private GraviteeGatewayService gatewayService;

    @Autowired
    private ApiManager apiManager;

    private void initializeGraviteeServicesClient(KubernetesClient client) {
        LOGGER.debug("Creating CRD Client for 'gravitee-services'");

        CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
            .withGroup("gravitee.io")
            .withVersion("v1alpha1")
            .withScope("Namespaced")
            .withName("gravitee-services.gravitee.io")
            .withPlural("gravitee-services")
            .withKind("GraviteeServices")
            .build();

        this.crdClient =
            client.customResources(context, GraviteeServices.class, GraviteeServicesList.class, DoneableGraviteeServices.class);

        KubernetesDeserializer.registerCustomKind("gravitee.io/v1alpha1", "GraviteeServices", GraviteeServices.class);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        initializeGraviteeServicesClient(client);
    }

    public List<GraviteeServices> listAllServices() {
        GraviteeServicesList list = crdClient.list();
        return list.getItems();
    }

    @Override
    public Flowable processAction(WatchActionContext<GraviteeServices> context) {
        Flowable pipeline = null;
        switch (context.getEvent()) {
            case ADDED:
                pipeline =
                    toFlowable(split(context))
                        .map(this::lookupGatewayRef)
                        .map(this::prepareApi)
                        .map(this::buildApiPaths)
                        .map(this::buildApiProxy)
                        .map(this::buildApiResources)
                        .map(this::applyAuthenticationPlugin)
                        .map(this::addService)
                        .map(this::preserveApiData);
                break;
            case MODIFIED:
                pipeline =
                    toFlowable(split(context))
                        .map(this::lookupGatewayRef)
                        .map(this::prepareApi)
                        .map(this::buildApiPaths)
                        .map(this::buildApiProxy)
                        .map(this::buildApiResources)
                        .map(this::applyAuthenticationPlugin)
                        .map(this::updateService)
                        .map(this::preserveApiData);
                break;
            case REFERENCE_UPDATED:
                pipeline =
                    toFlowable(split(context))
                        .map(this::lookupGatewayRef)
                        .map(this::prepareApi)
                        .map(this::buildApiPaths)
                        .map(this::buildApiProxy)
                        .map(this::buildApiResources)
                        .map(this::applyAuthenticationPlugin)
                        .map(this::updateService)
                        .map(this::preserveApiData);
                break;
            case DELETED:
                pipeline = toFlowable(split(context)).map(this::prepareApi).map(this::deleteService).map(this::cleanApiData);
                break;
            default:
                pipeline = Flowable.just(context);
        }
        return pipeline;
    }

    private Stream<ServiceWatchActionContext> split(WatchActionContext<GraviteeServices> action) {
        if (action.getResource() != null && action.getResource().getSpec().getServices() != null) {
            return action
                .getResource()
                .getSpec()
                .getServices()
                .entrySet()
                .stream()
                .map(entry -> new ServiceWatchActionContext(action, entry.getValue(), entry.getKey()));
        } else {
            return Stream.empty();
        }
    }

    private Flowable<ServiceWatchActionContext> toFlowable(Stream<ServiceWatchActionContext> stream) {
        return Flowable.generate(() -> stream.iterator(), (iterator, emitter) -> {
            if (iterator.hasNext()) {
                emitter.onNext(iterator.next());
            } else {
                emitter.onComplete();
            }
        });
    }

    public ServiceWatchActionContext preserveApiData(ServiceWatchActionContext context) {
        // TODO add to cache
        return context;
    }

    public ServiceWatchActionContext cleanApiData(ServiceWatchActionContext context) {
        // TODO remove from cache
        return context;
    }

    public ServiceWatchActionContext addService(ServiceWatchActionContext context) {
        if (context.getApi().isEnabled()) {
            LOGGER.info("Deploy Api '{}'", context.getApi().getId());
            apiManager.register(context.getApi());
        } else {
            LOGGER.debug("Ignore disabled Api '{}'", context.getApi().getId());
        }
        return context;
    }

    public ServiceWatchActionContext updateService(ServiceWatchActionContext context) {
        boolean wasPresentAndEnabled = false;
        if (!context.getApi().isEnabled()) {
            if (wasPresentAndEnabled) {
                LOGGER.info("Undeploy Api '{}'", context.getApi().getId());
                // TODO apiManager.undeploy(api);
            } else {
                LOGGER.debug("Ignore disabled Api '{}'", context.getApi().getId());
            }
        } else {
            LOGGER.info("Deploy Api '{}'", context.getApi().getId());
            // TODO apiManager.deploy(api);
        }
        return context;
    }

    public ServiceWatchActionContext deleteService(ServiceWatchActionContext context) {
        LOGGER.info("Undeploy api '{}'", context.getApi().getId());
        //TODO apiManager.undeploy(apiId);
        return context;
    }

    public ServiceWatchActionContext lookupGatewayRef(ServiceWatchActionContext context) {
        // TODO do this only once for all Services
        GraviteeGatewayReference gatewayReference = context.getResource().getSpec().getGateway();
        if (gatewayReference != null) {
            GraviteeGateway gw = gatewayService.lookup(context, gatewayReference);
            context.setGateway(gw);

            BackendConfiguration backendConfiguration = gw.getSpec().getDefaultBackendConfigurations();
            if (backendConfiguration != null) {
                String gwNamespace = gw.getMetadata().getNamespace();
                buildHttpClientSslOptions(kubernetesService.resolveSecret(context, gwNamespace, backendConfiguration.getHttpClientSslOptions())).ifPresent(context::setGatewaySslOptions);
                buildHttpProxy(kubernetesService.resolveSecret(context, gwNamespace, backendConfiguration.getHttpProxy())).ifPresent(context::setGatewayProxyConf);
            }
        }
        return context;
    }

    private ServiceWatchActionContext prepareApi(ServiceWatchActionContext context) {
        Api api = new Api();
        api.setName(context.getServiceName());
        api.setId(context.buildApiId());
        api.setEnabled(context.getServiceResource().isEnabled() && context.getResource().getSpec().isEnabled());
        api.setPlanRequired(false); // TODO maybe useless for the right reactable type
        context.setApi(api);
        return context;
    }

    private ServiceWatchActionContext buildApiResources(ServiceWatchActionContext context) {
        GraviteeService service = context.getServiceResource();
        List<Resource> resources = context.getApi().getResources();
        if (resources == null) {
            resources = new ArrayList<>();
            context.getApi().setResources(resources);
        }

        if (service.getResourceReferences() != null) {
            for (PluginReference pluginRef : service.getResourceReferences()) {
                PluginRevision<Resource> resource = pluginsService.buildResource(context, null, pluginRef);
                resources.add(resource.getPlugin());
            }
        }

        if (service.getResources() != null) {
            for (Map.Entry<String, Plugin> pluginEntry : service.getResources().entrySet()) {
                pluginEntry.getValue().setIdentifier(pluginEntry.getKey()); // use the identifier field to initialize resource name
                PluginRevision<Resource> resource = pluginsService.buildResource(context, pluginEntry.getValue(),convertToRef(context, pluginEntry.getKey()));
                resources.add(resource.getPlugin());
            }
        }

        if (context.getGateway() != null) {
            for (PluginRevision<Resource> rev : gatewayService.extractResources(new WatchActionContext<>(context.getGateway(), WatchActionContext.Event.NONE))) {
                resources.add(rev.getPlugin());
            }
        }

        return context;
    }

    private ServiceWatchActionContext buildApiPaths(ServiceWatchActionContext context) {
        Api api = context.getApi();

        List<ServicePath> svcPaths = context.getServiceResource().getPaths();
        Map<String, Path> apiPaths = svcPaths
            .stream()
            .map(
                svcPath -> {
                    Path path = new Path();
                    path.setPath(svcPath.getPrefix());

                    path.setRules(
                        svcPath
                            .getRules()
                            .stream()
                            .map(
                                r -> {
                                    Rule rule = new Rule();
                                    rule.setMethods(r.getMethods());
                                    final Plugin policy = r.getPolicy();

                                    if (policy != null) {
                                        // policy declared in path rule use the policy type per default
                                        policy.setPolicy(policy.getIdentifier());
                                    }

                                    PluginRevision<Policy> optPolicy = pluginsService.buildPolicy(context, policy, r.getPolicyRef());

                                    if (optPolicy.isRef()) {
                                        // policy comes from reference, keep its version in memory
                                        context.addPluginRevision(optPolicy);
                                    }

                                    if (optPolicy.isValid()) {
                                        rule.setPolicy(optPolicy.getPlugin());
                                        return rule;
                                    } else {
                                        LOGGER.error("Policy Plugin not found for Path {} in API {}", svcPath, api.getId());
                                        return null;
                                    }
                                }
                            )
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList())
                    );
                    return path;
                }
            )
            .collect(Collectors.toMap(Path::getPath, Function.identity()));

        api.setPaths(apiPaths);
        return context;
    }

    private ServiceWatchActionContext buildApiProxy(ServiceWatchActionContext context) {
        Api api = context.getApi();
        Proxy proxy = new Proxy();
        proxy.setVirtualHosts(
            context
                .getServiceResource()
                .getVhosts()
                .stream()
                .filter(
                    v -> {
                        LOGGER.info("VirtualHost({},{}) = {}", v.getHost(), v.getPath(), v.isEnabled());
                        return v.isEnabled();
                    }
                )
                .map(
                    v -> {
                        LOGGER.info("VirtualHost({},{})", v.getHost(), v.getPath());
                        return new VirtualHost(v.getHost(), v.getPath());
                    }
                )
                .collect(Collectors.toList())
        );
        proxy.setGroups(buildEndpoints(context));
        proxy.setCors(context.getServiceResource().getCors());
        // TODO preserve host
        // proxy.setPreserveHost();
        // TODO stripContextPath
        // proxy.setStripContextPath();
        proxy.setPreserveHost(true);
        api.setProxy(proxy);
        return context;
    }

    private Set<EndpointGroup> buildEndpoints(ServiceWatchActionContext context) {
        Map<String, ServiceEndpoint> endpoints = context.getServiceResource().getEndpoints();
        return endpoints
            .entrySet()
            .stream()
            .map(
                entry -> {
                    BackendConfiguration groupBackendConfig = entry.getValue().getConfiguration();
                    EndpointGroup endpointGroup = new EndpointGroup();
                    endpointGroup.setName(entry.getKey());

                    Map<String, Object> groupSslOptions = null;
                    Map<String, Object> groupProxyOptions = null;
                    if (groupBackendConfig != null) {
                        groupSslOptions = kubernetesService.resolveSecret(context, context.getNamespace(), groupBackendConfig.getHttpClientSslOptions());
                        groupProxyOptions = kubernetesService.resolveSecret(context, context.getNamespace(), groupBackendConfig.getHttpProxy());
                    }

                    Set<Endpoint> targetEndpoints = new HashSet<>();
                    for (BackendService backendSvcRef : entry.getValue().getBackendServices()) {
                        HttpClientOptions clientOptions = null;
                        HttpProxy proxyConfig = null;
                        HttpClientSslOptions sslClientOptions = null;

                        BackendConfiguration endpointBackendConfig = entry.getValue().getConfiguration();
                        if (endpointBackendConfig != null) {
                            if (endpointBackendConfig.getHttpClientOptions() != null) {
                                clientOptions = endpointBackendConfig.getHttpClientOptions();
                            }

                            Map<String, Object> endpointSslOptions = kubernetesService.resolveSecret(context, context.getNamespace(), endpointBackendConfig.getHttpClientSslOptions());
                            Map<String, Object> endpointProxyOptions = kubernetesService.resolveSecret(context, context.getNamespace(), endpointBackendConfig.getHttpProxy());

                            sslClientOptions = buildHttpClientSslOptions(endpointSslOptions).orElse(null);
                            proxyConfig = buildHttpProxy(endpointProxyOptions).orElse(null);
                        }

                        if (clientOptions == null && groupBackendConfig != null && groupBackendConfig.getHttpClientOptions() != null) {
                            clientOptions = groupBackendConfig.getHttpClientOptions();
                        }

                        if (sslClientOptions == null) {
                            sslClientOptions = buildHttpClientSslOptions(groupSslOptions).orElse(null);
                        }

                        if (proxyConfig == null) {
                            proxyConfig = buildHttpProxy(groupProxyOptions).orElse(null);
                        }

                        if (clientOptions == null && context.getGateway() != null) {
                            BackendConfiguration gwBackendConfig = context.getGateway().getSpec().getDefaultBackendConfigurations();
                            clientOptions = gwBackendConfig == null ? null : gwBackendConfig.getHttpClientOptions();
                        }

                        if (sslClientOptions == null) {
                            sslClientOptions = context.getGatewaySslOptions();
                        }

                        if (proxyConfig == null) {
                            proxyConfig = context.getGatewayProxyConf();
                        }

                        boolean useHttps = sslClientOptions != null;
                        String scheme = backendSvcRef.getProtocol().equals(BackendService.BackendServiceProtocol.GRPC) ? "grpc://" : (useHttps ? "https://" : "http://");
                        String target = scheme + backendSvcRef.getName() + ":" + backendSvcRef.getPort();
                        HttpEndpoint httpEndpoint = new HttpEndpoint(backendSvcRef.getName(), target);
                        httpEndpoint.setName(backendSvcRef.getName());
                        httpEndpoint.setHttpProxy(proxyConfig);
                        httpEndpoint.setHttpClientOptions(clientOptions == null ? new HttpClientOptions() : clientOptions);
                        httpEndpoint.setHttpClientSslOptions(sslClientOptions);
                        targetEndpoints.add(httpEndpoint);
                    }
                    endpointGroup.setEndpoints(targetEndpoints);
                    return endpointGroup;
                }
            )
            .collect(Collectors.toSet());
    }

    private ServiceWatchActionContext applyAuthenticationPlugin(ServiceWatchActionContext context) {
        Api api = context.getApi();
        if (context.getServiceResource().getAuthentication() != null) {
            PluginRevision<Policy> authenticationPolicy = pluginsService.buildPolicy(context, context.getServiceResource().getAuthentication(), null);
            setAuthenticationPlugin(api, authenticationPolicy);
        } else if (context.getServiceResource().getAuthenticationReference() != null) {
            PluginRevision<Policy> authenticationPolicy = pluginsService.buildPolicy(context, null, context.getServiceResource().getAuthenticationReference());
            setAuthenticationPlugin(api, authenticationPolicy);
        } else if (context.getGateway() != null) {
            GraviteeGateway gateway = context.getGateway();
            if (gateway.getSpec().getAuthentication() != null) {
                PluginRevision<Policy> authenticationPolicy = pluginsService.buildPolicy(context, gateway.getSpec().getAuthentication(), null);
                setAuthenticationPlugin(api, authenticationPolicy);
            } else if (gateway.getSpec().getAuthenticationReference() != null) {
                PluginRevision<Policy> authenticationPolicy = pluginsService.buildPolicy(context, null, gateway.getSpec().getAuthenticationReference());
                setAuthenticationPlugin(api, authenticationPolicy);
            }
        }
        return context;
    }

    private void setAuthenticationPlugin(Api api, PluginRevision<Policy> authenticationPolicy) {
        if (authenticationPolicy.isValid()) {
            final Policy plugin = authenticationPolicy.getPlugin();
            LOGGER.info("Api '{}' authenticated by '{}' policy", plugin.getName());
            if ("key_less".equalsIgnoreCase(plugin.getName())) {
                api.setAuthentication("key_less");
            } else if ("jwt".equalsIgnoreCase(plugin.getName())) {
                api.setAuthentication("JWT");
                api.setAuthenticationDefinition(plugin.getConfiguration());
            } else if ("oauth2".equalsIgnoreCase(plugin.getName())) {
                api.setAuthentication("OAUTH2");
                api.setAuthenticationDefinition(plugin.getConfiguration());
            }
        }
    }
}
