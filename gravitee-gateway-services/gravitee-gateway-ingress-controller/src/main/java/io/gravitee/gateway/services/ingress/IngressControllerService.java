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
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.api.model.extensions.IngressList;
import io.fabric8.kubernetes.api.model.extensions.IngressRule;
import io.fabric8.kubernetes.client.*;
import io.gravitee.common.event.EventManager;
import io.gravitee.common.service.AbstractService;
import io.gravitee.definition.model.*;
import io.gravitee.definition.model.endpoint.HttpEndpoint;
import io.gravitee.gateway.handlers.api.definition.Api;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class IngressControllerService extends AbstractService {

    @Value("${services.ingress.enabled:true}")
    private boolean enabled;

    @Autowired
    private EventManager eventManager;

    @Override
    protected void doStart() throws Exception {
        if (enabled) {
            super.doStart();
            // TODO do something
            KubernetesClient client = new DefaultKubernetesClient();

        }
    }

    @Override
    protected void doStop() throws Exception {
        if (enabled) {
            // TODO do something
            super.doStop();
        };
    }

    public static void main(String[] args) {
        Config config = new ConfigBuilder().withMasterUrl("https://0.0.0.0:44071").build();
        KubernetesClient client = new DefaultKubernetesClient();
        IngressList ingressList = client.extensions().ingresses().inAnyNamespace().list();
        ingressList.getItems().forEach(ingress -> System.out.println(ingress.getMetadata().getName()));

        client.extensions().ingresses().watch(new Watcher<Ingress>() {
            @Override
            public void eventReceived(Action action, Ingress ingress) {
                System.out.println("ingress eventReceived : " + action + " / " + ingress.getMetadata().getName());

                // TODO iterate over rules
                final IngressRule ingressRule = ingress.getSpec().getRules().get(0);

                ingressRule.getHttp().getPaths().stream().map(path -> {
                    // TODO : keep API in memory to detect deletion and undeploy them
                    Api api = new Api();
                    api.setEnabled(true);
                    api.setId(ingress.getMetadata().getName()+"."+ingress.getMetadata().getName() + "." + ingressRule.getHost()+"."+path.hashCode());
                    api.setName(api.getId());

                    VirtualHost vHost = new VirtualHost(ingressRule.getHost(), path.getPath());

                    EndpointGroup group = new EndpointGroup();
                    group.setName("default");
                    group.setEndpoints(Sets.newHashSet(
                            new HttpEndpoint(path.getBackend().getServiceName(),
                            path.getBackend().getServiceName()+":"+path.getBackend().getServicePort().getIntVal())));
                    Proxy proxy = new Proxy();
                    proxy.setVirtualHosts(Arrays.asList(vHost));
                    proxy.setGroups(Sets.newHashSet(group));

                    api.setProxy(proxy);

                    return api;
                }).forEach(api -> {
                    try {
                        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(api));
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                });

            }

            @Override
            public void onClose(KubernetesClientException e) {
                System.out.println("onClose:" + e.getMessage());
            }
        });
    }
}
