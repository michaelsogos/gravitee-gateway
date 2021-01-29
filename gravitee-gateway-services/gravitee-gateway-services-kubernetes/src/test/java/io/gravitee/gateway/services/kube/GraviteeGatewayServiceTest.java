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
import io.gravitee.definition.model.Policy;
import io.gravitee.gateway.services.kube.crds.resources.GraviteeGateway;
import io.gravitee.gateway.services.kube.crds.resources.GraviteePlugin;
import io.gravitee.gateway.services.kube.crds.status.GraviteeGatewayStatus;
import io.gravitee.gateway.services.kube.crds.status.GraviteePluginStatus;
import io.gravitee.gateway.services.kube.exceptions.PipelineException;
import io.gravitee.gateway.services.kube.services.GraviteeGatewayService;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.impl.WatchActionContext;
import io.gravitee.gateway.services.kube.services.listeners.GraviteeGatewayListener;
import io.gravitee.gateway.services.kube.services.listeners.GraviteePluginsListener;
import io.gravitee.gateway.services.kube.utils.ObjectMapperHelper;
import io.reactivex.subscribers.TestSubscriber;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = KubeSyncTestConfig.class)
public class GraviteeGatewayServiceTest {

    @Autowired
    public KubernetesServer kubernetesServer;

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired
    protected GraviteeGatewayService cut;

    protected GraviteeGatewayListener listener = mock(GraviteeGatewayListener.class);

    @Before
    public void prepareTest() {
        reset(listener);
        cut.registerListener(listener);
    }

    @Test
    public void shouldValidatePluginReference_withResolveSecret() {
        populateSecret("default", "myapp", "/kubernetes/test-secret-opaque.yml");
        populateGatewayResource("default", "internal-gw-ref", "/kubernetes/gateways/test-gravitee-gateway-reference.yml", true);
        populatePluginResource("default", "mygateway-plugins", "/kubernetes/plugins/test-gravitee-plugins-for-gateway.yml", true);

        GraviteeGateway gateway = ObjectMapperHelper.readYamlAs("/kubernetes/gateways/test-gravitee-gateway-reference.yml", GraviteeGateway.class);
        TestSubscriber<WatchActionContext<GraviteeGateway>> observable = cut.processAction(new WatchActionContext<>(gateway, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertNoErrors();
        observable.assertValue(ctx -> {
            boolean valid = ctx.getEvent() == WatchActionContext.Event.ADDED;
            valid = valid && (ctx.getPluginRevisions().size() == 2);
            valid = valid && ctx.getPluginRevisions().stream()
                    .filter(p -> p.getPlugin() instanceof Policy)
                    .filter(p -> ((Policy)p.getPlugin()).getName().equals("jwt"))
                    // DA7OLkdACP is the decoded value of the secret
                    .findFirst().map(p -> ((Policy)p.getPlugin()).getConfiguration().contains("DA7OLkdACP")).orElseGet(() -> false);
            return valid;
        });

        verify(listener, never()).onGatewayUpdate(any()); // notify other components is useless for new resources
    }


    @Test
    public void shouldValidatePluginDefinition_withResolveSecret() {
        populateSecret("default", "myapp", "/kubernetes/test-secret-opaque.yml");
        populateGatewayResource("default", "internal-gw-def", "/kubernetes/gateways/test-gravitee-gateway-definition.yml", true);
        populatePluginResource("default", "mygateway-plugins", "/kubernetes/plugins/test-gravitee-plugins-for-gateway.yml", true);

        GraviteeGateway gateway = ObjectMapperHelper.readYamlAs("/kubernetes/gateways/test-gravitee-gateway-definition.yml", GraviteeGateway.class);
        TestSubscriber<WatchActionContext<GraviteeGateway>> observable = cut.processAction(new WatchActionContext<>(gateway, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertNoErrors();
        observable.assertValue(ctx -> {
            boolean valid = ctx.getEvent() == WatchActionContext.Event.ADDED;
            valid = valid && (ctx.getPluginRevisions().size() == 2);
            valid = valid && ctx.getPluginRevisions().stream()
                    .filter(p -> p.getPlugin() instanceof Policy)
                    .filter(p -> ((Policy)p.getPlugin()).getName().equals("jwt"))
                    // DA7OLkdACP is the decoded value of the secret
                    .findFirst().map(p -> ((Policy)p.getPlugin()).getConfiguration().contains("DA7OLkdACP")).orElseGet(() -> false);
            return valid;
        });

        verify(listener, never()).onGatewayUpdate(any()); // notify other components is useless for new resources
    }

    @Test
    public void shouldFail_ValidatePluginDefinition_UnknownSecret() {
        // do not populate the secret
        GraviteeGateway gateway = ObjectMapperHelper.readYamlAs("/kubernetes/gateways/test-gravitee-gateway-definition.yml", GraviteeGateway.class);
        TestSubscriber<WatchActionContext<GraviteeGateway>> observable = cut.processAction(new WatchActionContext<>(gateway, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertError(error -> error instanceof PipelineException && error.getMessage().startsWith("Unable to read key 'myapp-password'"));

        verify(listener, never()).onGatewayUpdate(any());
    }

    @Test
    public void shouldFail_ValidatePluginRef_UnknownSecret() {
        // do not populate the secret
        populateGatewayResource("default", "internal-gw-ref", "/kubernetes/gateways/test-gravitee-gateway-reference.yml", true);
        populatePluginResource("default", "mygateway-plugins", "/kubernetes/plugins/test-gravitee-plugins-for-gateway.yml", true);

        GraviteeGateway gateway = ObjectMapperHelper.readYamlAs("/kubernetes/gateways/test-gravitee-gateway-reference.yml", GraviteeGateway.class);
        TestSubscriber<WatchActionContext<GraviteeGateway>> observable = cut.processAction(new WatchActionContext<>(gateway, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertError(error -> error instanceof PipelineException && error.getMessage().startsWith("Unable to read key 'myapp-password'"));

        verify(listener, never()).onGatewayUpdate(any());
    }


    private void populateSecret(String ns, String name, String filename) {
        Secret toCreate = kubernetesServer.getClient().secrets().load(getClass().getResourceAsStream(filename)).get();
        kubernetesServer.expect().get().withPath("/api/v1/namespaces/" + ns + "/secrets/" + name).andReturn(200, toCreate).once();
    }

    private void populatePluginResource(String ns, String name, String filename, boolean mockStatusUpdate) {
        GraviteePlugin resource = ObjectMapperHelper.readYamlAs(filename, GraviteePlugin.class);
        kubernetesServer.expect().get().withPath("/apis/gravitee.io/v1alpha1/namespaces/" + ns + "/gravitee-plugins/" + name).andReturn(200, resource).always();
        if (mockStatusUpdate) {
            GraviteePlugin resourceWithStatus = ObjectMapperHelper.readYamlAs(filename, GraviteePlugin.class);
            GraviteePluginStatus status = new GraviteePluginStatus();
            resourceWithStatus.setStatus(status);
            kubernetesServer.expect().put().withPath("/apis/gravitee.io/v1alpha1/namespaces/" + ns + "/gravitee-plugins/" + name +"/status").andReturn(200, resourceWithStatus).always();
        }
    }

    private void populateGatewayResource(String ns, String name, String filename, boolean mockStatusUpdate) {
        GraviteeGateway resource = ObjectMapperHelper.readYamlAs(filename, GraviteeGateway.class);
        kubernetesServer.expect().get().withPath("/apis/gravitee.io/v1alpha1/namespaces/" + ns + "/gravitee-gateways/" + name).andReturn(200, resource).always();
        if (mockStatusUpdate) {
            GraviteeGateway resourceWithStatus = ObjectMapperHelper.readYamlAs(filename, GraviteeGateway.class);
            GraviteeGatewayStatus status = new GraviteeGatewayStatus();
            resourceWithStatus.setStatus(status);
            kubernetesServer.expect().put().withPath("/apis/gravitee.io/v1alpha1/namespaces/" + ns + "/gravitee-gateways/" + name +"/status").andReturn(200, resourceWithStatus).always();
        }
    }


}
