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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.gravitee.gateway.services.kube.crds.resources.GraviteeGateway;
import io.gravitee.gateway.services.kube.crds.resources.GraviteePlugin;
import io.gravitee.gateway.services.kube.crds.resources.GraviteeServices;
import io.gravitee.gateway.services.kube.crds.resources.service.GraviteeService;
import io.gravitee.gateway.services.kube.crds.status.GraviteeGatewayStatus;
import io.gravitee.gateway.services.kube.crds.status.GraviteePluginStatus;
import io.gravitee.gateway.services.kube.utils.Fabric8sMapperUtils;
import io.gravitee.gateway.services.kube.utils.ObjectMapperHelper;
import org.junit.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class AbstractServiceTest {

    @Autowired
    public KubernetesServer kubernetesServer;

    @Autowired
    protected ApplicationContext applicationContext;

    @Before
    public void prepare() {
        Fabric8sMapperUtils.initJsonMapper();
    }

    protected void populateSecret(String ns, String name, String filename) {
        Secret toCreate = kubernetesServer.getClient().secrets().load(getClass().getResourceAsStream(filename)).get();
        kubernetesServer.expect().get().withPath("/api/v1/namespaces/" + ns + "/secrets/" + name).andReturn(200, toCreate).once();
    }

    protected void populatePluginResource(String ns, String name, String filename, boolean mockStatusUpdate) {
        GraviteePlugin resource = ObjectMapperHelper.readYamlAs(filename, GraviteePlugin.class);
        kubernetesServer.expect().get().withPath("/apis/gravitee.io/v1alpha1/namespaces/" + ns + "/gravitee-plugins/" + name).andReturn(200, resource).always();
        if (mockStatusUpdate) {
            GraviteePlugin resourceWithStatus = ObjectMapperHelper.readYamlAs(filename, GraviteePlugin.class);
            GraviteePluginStatus status = new GraviteePluginStatus();
            resourceWithStatus.setStatus(status);
            kubernetesServer.expect().put().withPath("/apis/gravitee.io/v1alpha1/namespaces/" + ns + "/gravitee-plugins/" + name +"/status").andReturn(200, resourceWithStatus).always();
        }
    }

    protected void populateGatewayResource(String ns, String name, String filename, boolean mockStatusUpdate) {
        GraviteeGateway resource = ObjectMapperHelper.readYamlAs(filename, GraviteeGateway.class);
        kubernetesServer.expect().get().withPath("/apis/gravitee.io/v1alpha1/namespaces/" + ns + "/gravitee-gateways/" + name).andReturn(200, resource).always();
        if (mockStatusUpdate) {
            GraviteeGateway resourceWithStatus = ObjectMapperHelper.readYamlAs(filename, GraviteeGateway.class);
            GraviteeGatewayStatus status = new GraviteeGatewayStatus();
            resourceWithStatus.setStatus(status);
            kubernetesServer.expect().put().withPath("/apis/gravitee.io/v1alpha1/namespaces/" + ns + "/gravitee-gateways/" + name +"/status").andReturn(200, resourceWithStatus).always();
        }
    }

    protected void populateServicesResource(String ns, String name, String filename, boolean mockStatusUpdate) {
        GraviteeServices resource = ObjectMapperHelper.readYamlAs(filename, GraviteeServices.class);
        kubernetesServer.expect().get().withPath("/apis/gravitee.io/v1alpha1/namespaces/" + ns + "/gravitee-services/" + name).andReturn(200, resource).always();
        if (mockStatusUpdate) {
            GraviteeServices resourceWithStatus = ObjectMapperHelper.readYamlAs(filename, GraviteeServices.class);
            /*GraviteeGatewayStatus status = new GraviteeGatewayStatus();
            resourceWithStatus.setStatus(status);
            kubernetesServer.expect().put().withPath("/apis/gravitee.io/v1alpha1/namespaces/" + ns + "/gravitee-services/" + name +"/status").andReturn(200, resourceWithStatus).always();*/
        }
    }

}
