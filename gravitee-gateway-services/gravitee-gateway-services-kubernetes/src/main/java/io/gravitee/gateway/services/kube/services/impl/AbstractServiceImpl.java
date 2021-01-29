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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.gravitee.gateway.services.kube.crds.resources.GraviteeGatewayReference;
import io.gravitee.gateway.services.kube.crds.resources.GraviteePlugin;
import io.gravitee.gateway.services.kube.crds.resources.PluginReference;
import io.gravitee.gateway.services.kube.services.KubernetesService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class AbstractServiceImpl<A extends CustomResource, B, C> {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    protected KubernetesClient client;

    @Autowired
    protected KubernetesService kubernetesService;

    protected MixedOperation<A, B, C, Resource<A, C>> crdClient;

    public MixedOperation<A, B, C, Resource<A, C>> getCrdClient() {
        return this.crdClient;
    }

    protected String formatErrorMessage(String msg, String... params) {
        return String.format(msg, params);
    }

    protected void reloadCustomResource(WatchActionContext<A> context) {
        A resource = crdClient.inNamespace(context.getNamespace()).withName(context.getResourceName()).get();
        context.refreshResource(resource);
    }

    protected Map<String, String> buildHashCodes(WatchActionContext<A> context) {
        Map<String, String> newHashCodes = new HashMap<>();
        context
            .getPluginRevisions()
            .forEach(
                rev -> {
                    newHashCodes.put(rev.getPluginReference().getName(), rev.getHashCode());
                }
            );
        return newHashCodes;
    }

    protected PluginReference convertToRef(WatchActionContext<A> context, String name) {
        PluginReference ref = new PluginReference();
        ref.setName(name);
        ref.setNamespace(context.getNamespace());
        ref.setResource(context.getResourceName());
        return ref;
    }

    public static String getReferenceNamespace(WatchActionContext context, PluginReference pluginRef) {
        return Optional.ofNullable(pluginRef.getNamespace()).orElse(context.getNamespace());
    }

    public static String getReferenceNamespace(String namespace, GraviteeGatewayReference gwReference) {
        return Optional.ofNullable(gwReference.getNamespace()).orElse(namespace);
    }

    public static String buildQualifiedPluginName(WatchActionContext context, PluginReference pluginRef) {
        return pluginRef.getName() + "." + pluginRef.getResource() + "." + getReferenceNamespace(context, pluginRef);
    }
}
