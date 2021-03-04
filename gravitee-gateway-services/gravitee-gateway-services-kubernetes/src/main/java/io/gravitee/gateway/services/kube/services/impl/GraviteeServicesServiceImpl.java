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

import com.google.common.collect.Streams;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.definition.model.VirtualHost;
import io.gravitee.definition.model.*;
import io.gravitee.definition.model.endpoint.HttpEndpoint;
import io.gravitee.definition.model.plugins.resources.Resource;
import io.gravitee.gateway.handlers.api.definition.Api;
import io.gravitee.gateway.handlers.api.manager.ApiManager;
import io.gravitee.gateway.services.kube.crds.cache.*;
import io.gravitee.gateway.services.kube.crds.resources.*;
import io.gravitee.gateway.services.kube.crds.resources.plugin.Plugin;
import io.gravitee.gateway.services.kube.crds.resources.service.*;
import io.gravitee.gateway.services.kube.crds.status.GraviteePluginStatus;
import io.gravitee.gateway.services.kube.crds.status.GraviteeServicesStatus;
import io.gravitee.gateway.services.kube.crds.status.IntegrationState;
import io.gravitee.gateway.services.kube.exceptions.PipelineException;
import io.gravitee.gateway.services.kube.exceptions.ValidationException;
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

import static com.google.common.collect.Sets.newHashSet;
import static io.gravitee.gateway.services.kube.crds.ResourceConstants.*;
import static io.gravitee.gateway.services.kube.crds.resources.service.BackendConfiguration.buildHttpClientSslOptions;
import static io.gravitee.gateway.services.kube.crds.resources.service.BackendConfiguration.buildHttpProxy;
import static io.gravitee.gateway.services.kube.utils.ControllerDigestHelper.computeApiHashCode;
import static io.gravitee.gateway.services.kube.utils.K8SResourceUtils.getFullName;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class GraviteeServicesServiceImpl
    extends AbstractServiceImpl<GraviteeServices, GraviteeServicesList, DoneableGraviteeServices>
    implements GraviteeServicesService, InitializingBean {

    public final Set<HttpMethod> All_METHODS = new LinkedHashSet<>();

    @Autowired
    private GraviteePluginsService pluginsService;

    @Autowired
    private GraviteeGatewayService gatewayService;

    @Autowired
    private ApiManager apiManager;

    @Autowired
    private PluginCacheManager pluginCacheManager;

    @Autowired
    private GatewayCacheManager gatewayCacheManager;

    @Autowired
    private ServicesCacheManager servicesCacheManager;

    public GraviteeServicesServiceImpl() {
        Collections.addAll(this.All_METHODS, HttpMethod.values());
    }

    private void initializeGraviteeServicesClient(KubernetesClient client) {
        LOGGER.debug("Creating CRD Client for 'gravitee-services'");

        CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP)
            .withVersion(DEFAULT_VERSION)
            .withScope(SCOPE)
            .withName(SERVICES_FULLNAME)
            .withPlural(SERVICES_PLURAL)
            .withKind(SERVICES_KIND)
            .build();

        this.crdClient =
            client.customResources(context, GraviteeServices.class, GraviteeServicesList.class, DoneableGraviteeServices.class);

        KubernetesDeserializer.registerCustomKind(GROUP + "/" + DEFAULT_VERSION, SERVICES_KIND, GraviteeServices.class);
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
            case MODIFIED:
            case REFERENCE_UPDATED:
                pipeline = validateResource((ServiceWatchActionContext) context)
                        .map(this::deployService)
                        .map(this::preserveApiData);
                break;
            case DELETED:
                pipeline = prepareApiDefinition((ServiceWatchActionContext)context)
                        .map(this::deleteService)
                        .map(this::cleanApiData);
                break;
            default:
                pipeline = Flowable.just(context);
        }
        return pipeline;
    }

    private Flowable<ServiceWatchActionContext> validateResource(ServiceWatchActionContext context) {
        return Flowable.just(context)
                .map(this::lookupGatewayRef)
                .flatMap(this::computeApiDefinition);
    }

    private ServiceWatchActionContext lookupGatewayRef(ServiceWatchActionContext context) {
        GraviteeGatewayReference gatewayReference = context.getResource().getSpec().getGateway();
        if (gatewayReference != null) {
            GraviteeGateway gw = gatewayService.lookup(context, gatewayReference);
            context.setGateway(gw);

            context.getGatewayCacheEntry().setGateway(getFullName(gw.getMetadata()));

            BackendConfiguration backendConfiguration = gw.getSpec().getDefaultBackendConfigurations();
            if (backendConfiguration != null) {
                String gwNamespace = gw.getMetadata().getNamespace();
                buildHttpClientSslOptions(kubernetesService.resolveSecret(context, gwNamespace, backendConfiguration.getHttpClientSslOptions())).ifPresent(context::setGatewaySslOptions);
                buildHttpProxy(kubernetesService.resolveSecret(context, gwNamespace, backendConfiguration.getHttpProxy())).ifPresent(context::setGatewayProxyConf);
            }
        }
        return context;
    }

    private Flowable<ServiceWatchActionContext> computeApiDefinition(ServiceWatchActionContext context) {
        return prepareApiDefinition(context)
                .map(this::buildApiPaths)
                .map(this::buildApiProxy)
                .map(this::buildApiResources).reduce(context, (acc, ctx) -> {
                    Api api = ctx.getApi();

                    // update Api/Service CacheEntry
                    ServicesCacheEntry cacheEntry = acc.getServiceCacheEntry();
                    acc.registerApiPlugins(ctx.getPluginRevisions());
                    cacheEntry.setServiceEnabled(api.getId(), api.isEnabled());
                    cacheEntry.setHash(api.getId(), computeApiHashCode(api));
                    if (cacheEntry.hasContextPath(ctx.getContextPaths(), api.getId())) {
                        throw new PipelineException(context, "Context path already used");
                    }
                    cacheEntry.setServiceContextPaths(api.getId(), ctx.getContextPaths());
                    // update Gateway Cache entry
                    acc.getGatewayCacheEntry().addService(api.getId(), ctx.isUseGatewayAuthentication());

                    acc.addApi(api);
                    return acc;
                }).toFlowable();
    }

    private Flowable<SingleServiceActionContext> prepareApiDefinition(ServiceWatchActionContext srvCtx) {
        return toFlowable(split(srvCtx))
                .map(this::prepareApi);
    }

    private Stream<SingleServiceActionContext> split(ServiceWatchActionContext action) {
        if (action.getResource() != null && action.getResource().getSpec().getServices() != null) {
            return action
                .getResource()
                .getSpec()
                .getServices()
                .entrySet()
                .stream()
                .map(entry -> new SingleServiceActionContext(action, entry.getValue(), entry.getKey()));
        } else {
            return Stream.empty();
        }
    }

    private Flowable<SingleServiceActionContext> toFlowable(Stream<SingleServiceActionContext> stream) {
        return Flowable.generate(() -> stream.iterator(), (iterator, emitter) -> {
            if (iterator.hasNext()) {
                emitter.onNext(iterator.next());
            } else {
                emitter.onComplete();
            }
        });
    }

    private WatchActionContext<GraviteeServices> preserveApiData(ServiceWatchActionContext context) {
        this.servicesCacheManager.register(context.getResourceFullName(), context.getServiceCacheEntry());
        this.pluginCacheManager.registerPluginsFor(context.getResourceFullName(), context.getPluginRevisions());
        this.gatewayCacheManager.registerEntryForService(context.getResourceFullName(), context.getGatewayCacheEntry());

        GraviteeServicesStatus status = context.getResource().getStatus();
        if (status == null) {
            status = new GraviteeServicesStatus();
            context.getResource().setStatus(status);
        }

        // TODO do we have to persist hash ?
        final IntegrationState integration = new IntegrationState();
        integration.setState(IntegrationState.State.SUCCESS);
        status.setIntegration(integration);
        integration.setMessage("");

        return updateResourceStatusOnSuccess(context);

    }

    private SingleServiceActionContext cleanApiData(SingleServiceActionContext context) {
        this.servicesCacheManager.remove(context.getResourceFullName());
        this.gatewayCacheManager.removeEntryForService(context.getResourceFullName());
        this.pluginCacheManager.removePluginsUsedBy(context.getResourceFullName());
        return context;
    }

    private ServiceWatchActionContext deployService(ServiceWatchActionContext context) {
        for (Api api : context.getApis()) {
            ServicesCacheEntry entry = this.servicesCacheManager.get(context.getResourceFullName());

            // check that ContextPath is already used
            if (this.servicesCacheManager.hasContextPathCollision(api.getId(), context.getServiceCacheEntry().getContextPath(api.getId()))) {
                throw new PipelineException(context, "Context path already used");
            }

            if (!api.isEnabled()) {
                boolean wasPresentAndEnabled = (entry != null && entry.isEnable(api.getId()));
                if (wasPresentAndEnabled) {
                    LOGGER.info("Undeploy Api '{}'", api.getId());
                    apiManager.unregister(api.getId());
                } else {
                    LOGGER.debug("Ignore disabled Api '{}'", api.getId());
                }
            } else {
                boolean needRedeploy = (entry == null || !Objects.equals(entry.getHash(api.getId()), context.getServiceCacheEntry().getHash(api.getId())));
                if (needRedeploy) {
                    LOGGER.info("Deploy Api '{}'", api.getId());
                    api.setDeployedAt(new Date());
                    apiManager.register(api);
                } else {
                    LOGGER.debug("Api '{}' is disabled or doesn't change", api.getId());
                }
            }
        }

        return context;
    }

    private SingleServiceActionContext deleteService(SingleServiceActionContext context) {
        LOGGER.info("Undeploy api '{}'", context.getApi().getId());
        apiManager.unregister(context.getApi().getId());
        return context;
    }

    private SingleServiceActionContext prepareApi(SingleServiceActionContext context) {
        Api api = new Api();
        api.setName(context.getServiceName());
        api.setId(context.buildApiId());
        api.setEnabled(context.getServiceResource().isEnabled() && context.getResource().getSpec().isEnabled());
        api.setPlanRequired(false); // TODO change it to use a context
        api.setDefinitionVersion(DefinitionVersion.V1);
        // do not call api.setDeployedAt(new Date()) here to avoid Hashcode update

        context.setApi(api);

        return context;
    }

    private SingleServiceActionContext buildApiResources(SingleServiceActionContext context) {
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
                context.addPluginRevision(resource);
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
            WatchActionContext<GraviteeGateway> gwContext = new WatchActionContext<>(context.getGateway(), WatchActionContext.Event.NONE);
            for (PluginRevision<Resource> rev : gatewayService.extractResources(gwContext)) {
                resources.add(rev.getPlugin());
                gwContext.getPluginRevisions().forEach(context::addPluginRevision);
            }
        }

        return context;
    }

    private SingleServiceActionContext buildApiPaths(SingleServiceActionContext context) {
        Api api = context.getApi();
        final Policy authPolicy = getAuthenticationPolicy(context);
        List<ServicePath> svcPaths = context.getServiceResource().getPaths();
        Map<String, Path> apiPaths = svcPaths
            .stream()
            .map(
                svcPath -> {
                    Path path = new Path();
                    path.setPath(svcPath.getPrefix());

                    Rule authRule = new Rule();
                    authRule.setMethods(All_METHODS);
                    authRule.setPolicy(authPolicy);

                    List<Rule> rules = new ArrayList<>();
                    rules.add(authRule);
                    rules.addAll(
                        svcPath
                            .getRules()
                            .stream()
                            .map(
                                r -> {
                                    Rule rule = new Rule();
                                    Set<HttpMethod> methods = r.getMethods();
                                    if (methods == null || methods.isEmpty()) {
                                        rule.setMethods(All_METHODS);
                                    } else {
                                        rule.setMethods(methods);
                                    }
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

                    path.setRules(rules);

                    return path;
                }
            )
            .collect(Collectors.toMap(Path::getPath, Function.identity()));

        api.setPaths(apiPaths);
        return context;
    }

    private SingleServiceActionContext buildApiProxy(SingleServiceActionContext context) {
        Api api = context.getApi();
        Proxy proxy = new Proxy();
        proxy.setVirtualHosts(
            context
                .getServiceResource()
                .getVhosts()
                .stream().map(v -> {
                    String contextPath = v.getHost() + v.getPath();
                    // preserve the context path in order to avoid collision with other Services
                    context.addContextPath(contextPath);
                    return v;
                })
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

    private Set<EndpointGroup> buildEndpoints(SingleServiceActionContext context) {
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

    protected Policy getAuthenticationPolicy(SingleServiceActionContext context) {
        Api api = context.getApi();
        PluginRevision<Policy> authenticationPolicy = null;
        if (context.getServiceResource().getAuthentication() != null) {
            authenticationPolicy = pluginsService.buildPolicy(context, context.getServiceResource().getAuthentication(), null);
        } else if (context.getServiceResource().getAuthenticationReference() != null) {
            authenticationPolicy = pluginsService.buildPolicy(context, null, context.getServiceResource().getAuthenticationReference());
            // keep reference to allow comparaison on Plugins/Gateway updates
            context.addPluginRevision(authenticationPolicy);
        } else if (context.getGateway() != null) {
            GraviteeGateway gateway = context.getGateway();
            if (gateway.getSpec().getAuthentication() != null) {
                authenticationPolicy = pluginsService.buildPolicy(context, gateway.getSpec().getAuthentication(), null);
            } else if (gateway.getSpec().getAuthenticationReference() != null) {
                authenticationPolicy = pluginsService.buildPolicy(context, null, gateway.getSpec().getAuthenticationReference());
            }
            // if Service uses Gateway authentication definition preserve this information
            context.setUseGatewayAuthentication(true);
        }

        if (authenticationPolicy == null || !authenticationPolicy.isValid()) {
            throw new PipelineException(context, "Authentication policy is missing for API '" + api.getId() +"'");
        }

        return authenticationPolicy.getPlugin();
    }

    @Override
    public void maybeSafelyUpdated(GraviteeServices services) {
        try {
            validateResource(new ServiceWatchActionContext(services, WatchActionContext.Event.ADDED)).map(context -> {
                for (Api api : context.getApis()) {
                    ServicesCacheEntry entry = this.servicesCacheManager.get(context.getResourceFullName());

                    // check that ContextPath is already used
                    if (this.servicesCacheManager.hasContextPathCollision(api.getId(), context.getServiceCacheEntry().getContextPath(api.getId()))) {
                        throw new PipelineException(context, "Context path already used");
                    }
                }
                return true;
            }).blockingSubscribe();
        } catch (PipelineException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    @Override
    protected void resetIntegrationState(IntegrationState integration, GraviteeServices refreshedResource) {
        refreshedResource.getStatus().setIntegration(integration);
    }

    @Override
    protected IntegrationState extractIntegrationState(GraviteeServices refreshedResource) {
        return refreshedResource.getStatus().getIntegration();
    }

    @Override
    public WatchActionContext<GraviteeServices> persistAsError(WatchActionContext<GraviteeServices> context, String message) {
        GraviteeServicesStatus status = context.getResource().getStatus();
        if (status == null) {
            status = new GraviteeServicesStatus();
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
            LOGGER.debug("No changes in GraviteeServices '{}', bypass status update", context.getResourceName());
            return context;
        }
    }
}
