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
package io.gravitee.gateway.services.kube.crds.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GraviteeServicesCacheEntry {

    Map<String, List<PluginRevision<?>>> serviceToPlugins = new HashMap<>();
    Map<String, String> serviceHashes = new HashMap<>();
    Map<String, Boolean> serviceWithGatewayAuth = new HashMap<>();
    Map<String, Boolean> serviceEnabled = new HashMap<>();
    String gateway;


    public List<PluginRevision<?>> getPlugins(String service) {
        return serviceToPlugins.get(service);
    }

    public void setPlugins(String service, List<PluginRevision<?>> plugins) {
        serviceToPlugins.put(service, plugins);
    }

    public Boolean useGatewayAuth(String service) {
        return Optional.ofNullable(serviceWithGatewayAuth.get(service)).orElse(Boolean.FALSE);
    }

    public void setServiceWithGatewayAuth(String service) {
        serviceWithGatewayAuth.put(service, Boolean.TRUE);
    }

    public Boolean isEnable(String service) {
        return Optional.ofNullable(this.serviceEnabled.get(service)).orElse(Boolean.FALSE);
    }

    public void setServiceEnabled(String service, Boolean enabled) {
        serviceWithGatewayAuth.put(service, enabled);
    }

    public String getHash(String service) {
        return serviceHashes.get(service);
    }

    public void setHash(String service, String hash) {
        serviceHashes.put(service, hash);
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }
}
