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

import java.util.*;

import static com.google.common.collect.Sets.intersection;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class ServicesCacheEntry {
    // TODO define an object to wrap all attributes and keep only one map
    Map<String, String> serviceHashes = new HashMap<>();
    Map<String, Set<String>> serviceContextPaths = new HashMap<>();
    Map<String, Boolean> serviceEnabled = new HashMap<>();

    public Boolean isEnable(String service) {
        return Optional.ofNullable(this.serviceEnabled.get(service)).orElse(Boolean.FALSE);
    }

    public void setServiceEnabled(String service, Boolean enabled) {
        serviceEnabled.put(service, enabled);
    }

    public String getHash(String service) {
        return serviceHashes.get(service);
    }

    public void setHash(String service, String hash) {
        serviceHashes.put(service, hash);
    }

    public Set<String> getContextPath(String service) {
        return serviceContextPaths.get(service);
    }

    public void setServiceContextPaths(String service, Set<String> contextPaths) {
        serviceContextPaths.put(service, contextPaths);
    }

    public boolean hasContextPath(Set<String> contextPath, String serviceToExclude) {
        return this.serviceContextPaths.entrySet()
                .stream()
                .filter(e -> serviceToExclude == null || !e.getKey().equals(serviceToExclude))
                .filter(e -> !intersection(e.getValue(), contextPath).isEmpty())
                .findFirst().isPresent();
    }
}
