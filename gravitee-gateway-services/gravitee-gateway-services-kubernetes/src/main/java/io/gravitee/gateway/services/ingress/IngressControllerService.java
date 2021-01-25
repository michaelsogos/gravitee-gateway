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
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.api.model.extensions.IngressRule;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.gravitee.common.service.AbstractService;
import io.gravitee.common.util.Maps;
import io.gravitee.definition.model.*;
import io.gravitee.definition.model.endpoint.HttpEndpoint;
import io.gravitee.gateway.handlers.api.definition.Api;
import io.gravitee.gateway.handlers.api.manager.ApiManager;
import io.gravitee.gateway.services.ingress.crd.resources.DoneableGraviteePlugin;
import io.gravitee.gateway.services.ingress.crd.resources.GraviteePlugin;
import io.gravitee.gateway.services.ingress.crd.resources.GraviteePluginList;
import io.gravitee.gateway.services.ingress.crd.resources.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.Optional;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class IngressControllerService extends AbstractService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngressControllerService.class);

    @Value("${services.ingress.enabled:true}")
    private boolean enabled;

    @Autowired
    private ApiManager apiManager;

    private Watch ingressWatcher;
    private KubernetesClient client;

    @Override
    protected void doStart() throws Exception {
        if (enabled) {
            super.doStart();

            LOGGER.info("Ingress Service Starting!");

            this.client = new DefaultKubernetesClient();

            this.ingressWatcher = client.extensions().ingresses().watch(new Watcher<Ingress>() {
                @Override
                public void eventReceived(Action action, Ingress ingress) {
                    if (ingress.getMetadata() != null && ingress.getMetadata().getAnnotations() != null && ingress.getMetadata().getAnnotations().get("kubernetes.io/ingress.class").equalsIgnoreCase("graviteeio")) {

                        LOGGER.debug("New action '{}' on ingress '{}'", action, ingress.getMetadata().getName());

                        // TODO iterate over rules
                        for(IngressRule ingressRule : ingress.getSpec().getRules()) {

                            ingressRule.getHttp().getPaths().stream().map(path -> {
                                // TODO : keep API in memory to detect deletion and undeploy them
                                Api api = new Api();
                                api.setEnabled(true);
                                api.setId(ingress.getMetadata().getName() + "." + ingress.getMetadata().getName() + "." + ingressRule.getHost() + "." + path.hashCode());
                                api.setName(api.getId());

                                // define default path
                                // TODO use CRD to extend this
                                Path defaultPath = new Path();
                                defaultPath.setPath("/*");
                                api.setPaths(Maps.<String, Path>builder().put(defaultPath.getPath(), defaultPath).build());

                                VirtualHost vHost = new VirtualHost(ingressRule.getHost(), path.getPath());

                                EndpointGroup group = new EndpointGroup();
                                group.setName("default");

                                final HttpEndpoint endpoint = new HttpEndpoint(path.getBackend().getServiceName(),
                                        "http://" + path.getBackend().getServiceName() + ":" + path.getBackend().getServicePort().getIntVal());
                                // TODO load from CRD
                                endpoint.setHttpClientOptions(new HttpClientOptions());
                                endpoint.setHttpClientSslOptions(new HttpClientSslOptions());
                                group.setEndpoints(Sets.newHashSet(endpoint));

                                Proxy proxy = new Proxy();
                                proxy.setVirtualHosts(Arrays.asList(vHost));
                                proxy.setGroups(Sets.newHashSet(group));

                                api.setProxy(proxy);

                                // specific to ingress poc to avoid plan creation
                                api.setPlanRequired(false);
                                applySecurityConfiguration(api, ingress.getMetadata());

                                return api;
                            }).forEach(api -> {
                                try {
                                    LOGGER.debug("Api definition created from Ingress event : " + new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(api));
                                    if (apiManager.get(api.getId()) != null) {
                                        LOGGER.info("Update Api '{}'", api.getId());
                                        apiManager.register(api);
                                    } else {
                                        LOGGER.info("Deploy Api '{}'", api.getId());
                                        apiManager.register(api);
                                    }
                                } catch (JsonProcessingException e) {
                                    e.printStackTrace();
                                }
                            });
                        }
                    }
                }

                @Override
                public void onClose(KubernetesClientException e) {
                    System.out.println("onClose:" + e.getMessage());
                }
            });
        }
    }

    private void applySecurityConfiguration(Api api, ObjectMeta ingressMeta) {
        if (ingressMeta.getAnnotations().containsKey("gravitee.io/security-type")) {
            String securityType = ingressMeta.getAnnotations().get("gravitee.io/security-type");
            if ("key_less".equalsIgnoreCase(securityType)) {
                api.setSecurity("key_less");
            } else {
                String conf = ingressMeta.getAnnotations().get("gravitee.io/security-config");
                LOGGER.info("###" + securityType + " " + conf);
                final String[] confIds = conf.split("#");
                Optional<Plugin> plugin = lookupSecurityPlugin(confIds[0], confIds[1], securityType);
                LOGGER.info("### founded ==> " + plugin.isPresent());

                if (plugin.isPresent()) {
                    if ("jwt".equalsIgnoreCase(securityType)) {
                        api.setSecurity("JWT");
                        try {
                            final String securityDefinition = new ObjectMapper().writeValueAsString(plugin.get().getConfiguration());
                            LOGGER.info("JWT Config: " + securityDefinition);
                            api.setSecurityDefinition(securityDefinition);
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } else {
            // no security handler, all requests will fail
        }
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
            if (this.ingressWatcher != null) {
                this.ingressWatcher.close();
            }

            super.doStop();
        };
    }

}
