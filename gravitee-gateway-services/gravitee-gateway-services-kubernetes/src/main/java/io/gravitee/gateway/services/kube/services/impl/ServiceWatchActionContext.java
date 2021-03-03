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

import io.gravitee.definition.model.HttpClientSslOptions;
import io.gravitee.definition.model.HttpProxy;
import io.gravitee.gateway.handlers.api.definition.Api;
import io.gravitee.gateway.services.kube.crds.cache.GatewayCacheEntry;
import io.gravitee.gateway.services.kube.crds.cache.ServicesCacheEntry;
import io.gravitee.gateway.services.kube.crds.cache.PluginRevision;
import io.gravitee.gateway.services.kube.crds.resources.GraviteeGateway;
import io.gravitee.gateway.services.kube.crds.resources.GraviteeServices;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class ServiceWatchActionContext extends WatchActionContext<GraviteeServices> {
    /**
     * Gateway referenced by the service CustomResource
     */
    private GraviteeGateway gateway;
    /**
     * HttpSSLOptions of Gateway referenced by the service CustomResource
     */
    private HttpClientSslOptions gatewaySslOptions;
    /**
     * HttpProxy of Gateway referenced by the service CustomResource
     */
    private HttpProxy gatewayProxyConf;

    private List<Api> apis = new ArrayList<>();

    private ServicesCacheEntry serviceCacheEntry = new ServicesCacheEntry();

    private GatewayCacheEntry gatewayCacheEntry = new GatewayCacheEntry();

    public ServiceWatchActionContext(GraviteeServices resource, Event event) {
        super(resource, event);
    }

    public GraviteeGateway getGateway() {
        return gateway;
    }

    public void setGateway(GraviteeGateway gateway) {
        this.gateway = gateway;
    }

    public HttpClientSslOptions getGatewaySslOptions() {
        return gatewaySslOptions;
    }

    public void setGatewaySslOptions(HttpClientSslOptions gatewaySslOptions) {
        this.gatewaySslOptions = gatewaySslOptions;
    }

    public HttpProxy getGatewayProxyConf() {
        return gatewayProxyConf;
    }

    public void setGatewayProxyConf(HttpProxy gatewayProxyConf) {
        this.gatewayProxyConf = gatewayProxyConf;
    }

    public void addApi(Api api) {
        this.apis.add(api);
    }

    public List<Api> getApis() {
        return apis;
    }

    public ServicesCacheEntry getServiceCacheEntry() {
        return serviceCacheEntry;
    }

    public void setServiceCacheEntry(ServicesCacheEntry serviceCacheEntry) {
        this.serviceCacheEntry = serviceCacheEntry;
    }

    public GatewayCacheEntry getGatewayCacheEntry() {
        return gatewayCacheEntry;
    }

    public void setGatewayCacheEntry(GatewayCacheEntry gatewayCacheEntry) {
        this.gatewayCacheEntry = gatewayCacheEntry;
    }
    public void registerApiPlugins(List<PluginRevision<?>> plugins) {
        this.getPluginRevisions().addAll(plugins);
    }

}
