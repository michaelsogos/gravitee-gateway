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
package io.gravitee.gateway.services.kube;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.gravitee.gateway.services.kube.managers.GraviteePluginsManager;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.KubernetesService;
import io.gravitee.gateway.services.kube.services.impl.GraviteePluginsServiceImpl;
import io.gravitee.gateway.services.kube.services.impl.KubernetesServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@Configuration
public class KubeSyncTestConfig {

    @Bean(destroyMethod = "after")
    public KubernetesServer kubernetesServerMock() {
        KubernetesServer server = new KubernetesServer();
        server.before();
        return server;
    }

    @Bean
    public KubernetesClient kubernetesClientMock(KubernetesServer server) {
        return server.getClient();
    }

    @Bean
    public GraviteePluginsManager graviteePluginsManager() {
        return new GraviteePluginsManager();
    }

    @Bean
    public GraviteePluginsService graviteePluginsService() {
        return new GraviteePluginsServiceImpl();
    }

    @Bean
    public KubernetesService kubernetesService(KubernetesClient client) {
        return new KubernetesServiceImpl(client);
    }
}
