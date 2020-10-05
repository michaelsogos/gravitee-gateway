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
import io.gravitee.definition.model.Cors;
import io.gravitee.gateway.services.ingress.crd.resources.GraviteePluginReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GraviteeService {
    public static final String DEFAULT_SERVICE_TYPE = "API";

    private String type = DEFAULT_SERVICE_TYPE;

    private String name;

    private boolean enabled = true;

    private GraviteePluginReference security;

    private Cors cors;

    private List<VirtualHost> entrypoints = new ArrayList<>();

    private List<ServicePath> paths = new ArrayList<>();

    private List<ServiceEndpoint> endpoints = new ArrayList<>();

    public GraviteeService() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public static String getDefaultServiceType() {
        return DEFAULT_SERVICE_TYPE;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public GraviteePluginReference getSecurity() {
        return security;
    }

    public void setSecurity(GraviteePluginReference security) {
        this.security = security;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public List<VirtualHost> getEntrypoints() {
        return entrypoints;
    }

    public void setEntrypoints(List<VirtualHost> entrypoints) {
        this.entrypoints = entrypoints;
    }

    public List<ServicePath> getPaths() {
        return paths;
    }

    public void setPaths(List<ServicePath> paths) {
        this.paths = paths;
    }

    public List<ServiceEndpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<ServiceEndpoint> endpoints) {
        this.endpoints = endpoints;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        return "GraviteeService{" +
                "type='" + type + '\'' +
                ", name=" + name +
                ", enable=" + enabled +
                ", security=" + security +
                ", cors=" + cors +
                ", entrypoints=" + entrypoints +
                ", paths=" + paths +
                ", endpoints=" + endpoints +
                '}';
    }

}
