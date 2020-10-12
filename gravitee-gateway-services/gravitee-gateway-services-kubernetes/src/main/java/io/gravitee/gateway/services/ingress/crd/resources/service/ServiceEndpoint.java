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
package io.gravitee.gateway.services.ingress.crd.resources.service;

import io.gravitee.definition.model.LoadBalancer;
import io.gravitee.definition.model.services.Services;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class ServiceEndpoint {
    private BackendService backendService;

    private LoadBalancer loadBalancer = new LoadBalancer();

    private Services services = new Services();

    private BackendConfiguration configuration;

    public ServiceEndpoint() {

    }

    public BackendService getBackendService() {
        return backendService;
    }

    public void setBackendService(BackendService backendService) {
        this.backendService = backendService;
    }

    public BackendConfiguration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(BackendConfiguration configuration) {
        this.configuration = configuration;
    }

}
