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
package io.gravitee.gateway.services.kube.webhook;

import io.gravitee.gateway.services.kube.services.GraviteeGatewayService;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.GraviteeServicesService;
import io.gravitee.gateway.services.kube.webhook.validator.ResourceValidatorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@Configuration
public class WebhookTestConfig {

    @Bean
    public GraviteePluginsService graviteePluginsService() {
        return mock(GraviteePluginsService.class);
    }

    @Bean
    public GraviteeGatewayService graviteeGatewayService() {
        return mock(GraviteeGatewayService.class);
    }

    @Bean
    public GraviteeServicesService graviteeServicesService() {
        return mock(GraviteeServicesService.class);
    }

    @Bean
    public ResourceValidatorFactory resourceValidatorFactory() {
        return spy(new ResourceValidatorFactory());
    }

    @Bean
    public AdmissionWebHook webhook() {
        return new AdmissionWebHook();
    }
}
