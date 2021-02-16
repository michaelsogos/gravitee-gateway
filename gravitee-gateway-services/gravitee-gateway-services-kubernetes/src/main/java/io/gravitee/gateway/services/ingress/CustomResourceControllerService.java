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
package io.gravitee.gateway.services.ingress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.gravitee.common.service.AbstractService;
import io.gravitee.definition.model.*;
import io.gravitee.definition.model.VirtualHost;
import io.gravitee.definition.model.endpoint.HttpEndpoint;
import io.gravitee.gateway.handlers.api.definition.Api;
import io.gravitee.gateway.handlers.api.manager.ApiManager;
import io.gravitee.gateway.services.ingress.crd.resources.*;
import io.gravitee.gateway.services.ingress.crd.resources.plugin.Plugin;
import io.gravitee.gateway.services.ingress.crd.resources.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CustomResourceControllerService extends AbstractService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomResourceControllerService.class);
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${services.k8s.crd.enabled:true}")
    private boolean enabled;

    @Autowired
    private ApiManager apiManager;

    private Watch serviceWatcher;

    private KubernetesClient client;

    private MixedOperation<GraviteeGateway,
            GraviteeGatewayList,
            DoneableGraviteeGateway,
            Resource<GraviteeGateway, DoneableGraviteeGateway>> gioGatewayClient;

    private MixedOperation<GraviteePlugin,
            GraviteePluginList,
            DoneableGraviteePlugin,
            Resource<GraviteePlugin, DoneableGraviteePlugin>> gioPluginClient;

    private MixedOperation<GraviteeServices,
            GraviteeServicesList,
            DoneableGraviteeServices,
            Resource<GraviteeServices, DoneableGraviteeServices>> gioServicesClient;
    
    @Override
    protected void doStart() throws Exception {
        if (enabled) {
            super.doStart();

            LOGGER.info("CustomResource Controller Service Starting!");

            this.client = new DefaultKubernetesClient();


            // TODO sur l'init comment ne pas sur consommer ???
            // Le watcher va lister toutes les resources ==> qd les plugins seront lu il faudra pas lister les Services/Gateways potentiellement pas encore chargés...
            initializeGraviteeGatewayClient();
            initializeGraviteePluginClient();
            initializeGraviteeServicesClient();


            this.serviceWatcher = this.gioServicesClient.watch(new Watcher<GraviteeServices>() {
                @Override
                public void eventReceived(Action action, GraviteeServices gioServices) {
                    LOGGER.info("#### Receive '{}' for '{}'", action, gioServices);
                    try {
                        switch (action) {
                            case ADDED:
                            case MODIFIED:
                                LOGGER.info("ADDED Or MODIFIED");
                                processGraviteeServices(gioServices);
                                break;
                            case DELETED:
                                deleteGraviteeServices(gioServices);
                                break;
                            case ERROR:
                                LOGGER.warn("Action {} received for GraviteeService", action);
                                break;
                            default:
                                LOGGER.warn("Unmanaged action {}", action);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onClose(KubernetesClientException e) {
                    System.out.println("onClose:" + e.getMessage());
                }
            });
        }
    }

    private void processGraviteeServices(GraviteeServices gioServices) {
        LOGGER.info("PROCESS GraviteeServices : {}", gioServices.getSpec());
        LOGGER.info("PROCESS GraviteeServices : {}", gioServices.getSpec().getServices());

        GraviteeServicesSpec spec = gioServices.getSpec();
        GraviteeGatewayReference gatewayRef = spec.getGateway();
        if (gatewayRef != null) {
            // TODO read GW CustomResource (use a cache ?)
            LOGGER.info("GraviteeServices reference {}, use it to complete service description", gatewayRef);
        }

        // if false, all API will be undeployed
        final boolean serviceEnabled = spec.isEnabled();

        spec.getServices().entrySet()
                .stream()
                .map(serviceserviceEntry -> buildApiDescription(gioServices, serviceEnabled, serviceserviceEntry))
                .forEach(api -> {
            if (apiManager.get(api.getId()) != null) {
                if (api.isEnabled()) {
                    LOGGER.info("Update Api '{}'", api.getId());
                    apiManager.register(api);
                } else {
                    LOGGER.info("Undeploy Api '{}'", api.getId());
                    apiManager.unregister(api.getId());
                }
            } else {
                LOGGER.info("Deploy Api '{}'", api.getId());
                apiManager.register(api);
            }
        });
    }

    private Api buildApiDescription(GraviteeServices gioServices, boolean serviceEnabled, Map.Entry<String, GraviteeService> serviceEntry) {
        // TODO test type to construct the right Object (for now only API)
        final String svcName = serviceEntry.getKey();
        Api api = new Api();
        api.setName(svcName);

        GraviteeService serviceDescription = serviceEntry.getValue();
        api.setId(buildApiId(gioServices, svcName));

        api.setEnabled(serviceDescription.isEnabled() && serviceEnabled);

        List<ServicePath> svcPaths = serviceDescription.getPaths();
        Map<String, Path> apiPaths = svcPaths.stream().map(svcPath -> {
            Path path = new Path();
            path.setPath(svcPath.getPrefix());

            path.setRules(svcPath.getRules().stream().map(r -> {
                Rule rule = new Rule();
                rule.setMethods(r.getMethods());
                Optional<Policy> policy = buildPolicyFromPlugin(r.getPolicy());
                if (!policy.isPresent()) {
                    policy = buildPolicyFromPluginRef(r.getPolicyRef());
                }

                if (policy.isPresent()) {
                    rule.setPolicy(policy.get());
                    return rule;
                } else {
                    LOGGER.error("Policy Plugin not found for Path {} in API {}", svcPath, api.getId());
                    return null;
                }
            }).filter(Objects::nonNull).collect(Collectors.toList()));
            return path;
        }).collect(Collectors.toMap(Path::getPath, Function.identity()));
        api.setPaths(apiPaths);


        Proxy proxy = new Proxy();
        proxy.setVirtualHosts(serviceDescription.getVhosts()
                .stream()
                .filter(v -> {
                    LOGGER.info("VirtualHost({},{}) = {}", v.getHost(), v.getPath(), v.isEnabled());
                    return v.isEnabled();
                })
                .map(v -> {
                    LOGGER.info("VirtualHost({},{})", v.getHost(), v.getPath());
                    return new VirtualHost(v.getHost(), v.getPath());
                }).collect(Collectors.toList()));
        proxy.setGroups(buildEndpoints(serviceDescription.getEndpoints()));
        proxy.setCors(serviceDescription.getCors());
        // TODO preserve host
        // TODO stripContextPath
        proxy.setPreserveHost(true);
        api.setProxy(proxy);

        // specific to ingress poc to avoid plan creation
        api.setPlanRequired(false);
        if (serviceDescription.getSecurity() != null) {
            applySecurityConfiguration(api, serviceDescription.getSecurity());
        } else {
            // if gw not null && gw security not null apply otherwise error
        }

        return api;
    }

    private String buildApiId(GraviteeServices gioServices, String serviceName) {
        return serviceName + "." + gioServices.getMetadata().getName() + "." + gioServices.getMetadata().getName();
    }

    private void deleteGraviteeServices(GraviteeServices gioServices) {
        GraviteeServicesSpec spec = gioServices.getSpec();
        spec.getServices().keySet()
                .stream()
                .map(serviceName -> buildApiId(gioServices, serviceName))
                .forEach(apiId -> {
                    LOGGER.info("Undeploy api '{}'", apiId);
                    apiManager.unregister(apiId);
                });
    }

    private Set<EndpointGroup> buildEndpoints(Map<String, ServiceEndpoint> endpoints) {
        return endpoints.entrySet().stream().map(entry -> {
            // TODO manage all types
            BackendConfiguration backEndConfig = entry.getValue().getConfiguration();
            EndpointGroup endpointGroup = new EndpointGroup();
            endpointGroup.setName(entry.getKey());
            boolean useHttps = endpointGroup.getHttpClientSslOptions() != null;
            BackendService backendSvcRef = entry.getValue().getBackendService();
            String target = (useHttps ? "https://" : "http://") + backendSvcRef.getName() +":"+ backendSvcRef.getPort();
            HttpEndpoint httpEndpoint = new HttpEndpoint(backendSvcRef.getName(), target);
            httpEndpoint.setName(backendSvcRef.getName());
            // TODO call BackendPolicy to retrive http configuration or set default one.
            endpointGroup.setEndpoints(Sets.newHashSet(httpEndpoint));
            return endpointGroup;
        }).collect(Collectors.toSet());
    }

    private Optional<Policy> buildPolicyFromPluginRef(GraviteePluginReference pluginRef) {
        GraviteePlugin gioPlugin = this.gioPluginClient.inNamespace(pluginRef.getNamespace()).withName(pluginRef.getResource()).get();
        Optional<Plugin> plugin = gioPlugin.getSpec().getPlugin(pluginRef.getName());
        if (plugin.isPresent()) {
            return buildPolicyFromPlugin(plugin.get());
        }
        return Optional.empty();
    }

    private Optional<Policy> buildPolicyFromPlugin(Plugin plugin) {
        if (plugin != null) {
            try {
                if ("policy".equalsIgnoreCase(plugin.getType())) {
                    final Policy policy = new Policy();
                    policy.setName(plugin.getIdentifier());
                    policy.setConfiguration(OBJECT_MAPPER.writeValueAsString(plugin.getConfiguration()));
                    return Optional.of(policy);
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Unable to process policy configuration for plugin {}", plugin, e);
            }
        }
        return Optional.empty();
    }

    private Optional<Policy> buildSecurityFromPlugin(GraviteePluginReference pluginRef) {
        GraviteePlugin gioPlugin = this.gioPluginClient.inNamespace(pluginRef.getNamespace()).withName(pluginRef.getResource()).get();
        Optional<Plugin> plugin = gioPlugin.getSpec().getPlugin(pluginRef.getName());
        if (plugin.isPresent()) {
            try {
                if ("security".equalsIgnoreCase(plugin.get().getType())) {
                    final Policy policy = new Policy();
                    policy.setName(plugin.get().getIdentifier());
                    policy.setConfiguration(OBJECT_MAPPER.writeValueAsString(plugin.get().getConfiguration()));
                    return Optional.of(policy);
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Unable to process security policy configuration for plugin reference {}", pluginRef, e);
            }
        }
        return Optional.empty();
    }

    private void initializeGraviteePluginClient() {
        CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                .withGroup("gravitee.io")
                .withVersion("v1alpha1")
                .withScope("Namespaced")
                .withName("gravitee-plugins.gravitee.io")
                .withPlural("gravitee-plugins")
                .withKind("GraviteePlugins")
                .build();

        this.gioPluginClient = this.client.customResources(context, 
                GraviteePlugin.class, 
                GraviteePluginList.class, 
                DoneableGraviteePlugin.class);

        KubernetesDeserializer.registerCustomKind("gravitee.io/v1alpha1", "GraviteePlugin", GraviteePlugin.class);
    }

    private void initializeGraviteeGatewayClient() {
        CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                .withGroup("gravitee.io")
                .withVersion("v1alpha1")
                .withScope("Namespaced")
                .withName("gravitee-gateways.gravitee.io")
                .withPlural("gravitee-gateways")
                .withKind("GraviteeGateway")
                .build();

        this.gioGatewayClient= this.client.customResources(context,
                GraviteeGateway.class,
                GraviteeGatewayList.class,
                DoneableGraviteeGateway.class);

        KubernetesDeserializer.registerCustomKind("gravitee.io/v1alpha1", "GraviteeGateway", GraviteeGateway.class);
    }

    private void initializeGraviteeServicesClient() {
        CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                .withGroup("gravitee.io")
                .withVersion("v1alpha1")
                .withScope("Namespaced")
                .withName("gravitee-services.gravitee.io")
                .withPlural("gravitee-services")
                .withKind("GraviteeServices")
                .build();

        this.gioServicesClient = this.client.customResources(context,
                GraviteeServices.class,
                GraviteeServicesList.class,
                DoneableGraviteeServices.class);

        KubernetesDeserializer.registerCustomKind("gravitee.io/v1alpha1", "GraviteeServices", GraviteeServices.class);
    }

    private boolean applySecurityConfiguration(Api api, GraviteePluginReference pluginRef) {
        boolean applied = false;
        Optional<Policy> securityPolicy = buildSecurityFromPlugin(pluginRef);
        if (securityPolicy.isPresent()) {
            LOGGER.info("applySecurityConfiguration==> '{}'", securityPolicy.get().getName());
            if ("key_less".equalsIgnoreCase(securityPolicy.get().getName())) {
                LOGGER.info("applySecurityConfiguration==> key_less");
                api.setAuthentication("key_less");
                applied = true;
            } else if ("jwt".equalsIgnoreCase(securityPolicy.get().getName())) {
                LOGGER.info("applySecurityConfiguration==> jwt");
                api.setAuthentication("JWT");
                api.setAuthenticationDefinition( securityPolicy.get().getConfiguration());
                applied = true;
            } // TODO other
        }
        return applied;
    }

    private  Optional<Plugin> lookupSecurityPlugin(String resName, String name, String identifier) {
        CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                .withGroup("gravitee.io")
                .withVersion("v1alpha1")
                .withScope("Namespaced")
                .withName("gravitee-plugins.gravitee.io")
                .withPlural("gravitee-plugins")
                .withKind("GraviteePlugins")
                .build();

        MixedOperation<GraviteePlugin,
                GraviteePluginList,
                DoneableGraviteePlugin,
                Resource<GraviteePlugin, DoneableGraviteePlugin>> gioPluginClient = this.client.customResources(
                context, GraviteePlugin.class, GraviteePluginList.class, DoneableGraviteePlugin.class);

        KubernetesDeserializer.registerCustomKind("gravitee.io/v1alpha1", "GraviteePlugin", GraviteePlugin.class);

        // TODO avoid conflict by specifying the CRD
        return gioPluginClient.list().getItems()
                .stream()
                .filter(res -> res.getMetadata().getName().equalsIgnoreCase(resName))
                .map(spec -> spec.getSpec().getPlugins().get(name))
                .filter(plugin -> plugin.getType().equalsIgnoreCase("security"))
                .filter(plugin -> plugin.getIdentifier().equalsIgnoreCase(identifier)).findFirst();
    }

    @Override
    protected void doStop() throws Exception {
        if (enabled) {
            if (this.serviceWatcher != null) {
                this.serviceWatcher.close();
            }

            if (client != null) {
                client.close();
            }
            super.doStop();
        };
    }

}
