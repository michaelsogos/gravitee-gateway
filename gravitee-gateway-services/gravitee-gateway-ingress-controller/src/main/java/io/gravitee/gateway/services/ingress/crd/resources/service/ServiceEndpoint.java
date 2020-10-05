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

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.gravitee.definition.model.EndpointGroup;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class ServiceEndpoint {
    private String name;
    @JsonProperty("backend-service")
    private BackendServiceReference backendService;
    private EndpointGroup configuration;

    public ServiceEndpoint() {

    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BackendServiceReference getBackendService() {
        return backendService;
    }

    public void setBackendService(BackendServiceReference backendService) {
        this.backendService = backendService;
    }

    public EndpointGroup getConfiguration() {
        return configuration;
    }

    public void setConfiguration(EndpointGroup configuration) {
        this.configuration = configuration;
    }

    @Override
    public String toString() {
        return "ServiceEndpointGroup{" +
                "name=" + name +
                ", backendService=" + backendService +
                '}';
    }
}
