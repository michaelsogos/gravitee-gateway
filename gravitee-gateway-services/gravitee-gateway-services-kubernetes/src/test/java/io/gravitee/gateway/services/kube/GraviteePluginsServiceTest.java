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
import io.gravitee.common.util.Maps;
import io.gravitee.definition.model.Policy;
import io.gravitee.gateway.services.kube.crds.resources.GraviteePlugin;
import io.gravitee.gateway.services.kube.crds.status.GraviteePluginStatus;
import io.gravitee.gateway.services.kube.exceptions.PipelineException;
import io.gravitee.gateway.services.kube.services.GraviteePluginsService;
import io.gravitee.gateway.services.kube.services.impl.WatchActionContext;
import io.gravitee.gateway.services.kube.services.listeners.GraviteePluginsListener;
import io.gravitee.gateway.services.kube.utils.ObjectMapperHelper;
import io.reactivex.subscribers.TestSubscriber;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = KubeSyncTestConfig.class)
public class GraviteePluginsServiceTest {

    @Autowired
    public KubernetesServer kubernetesServer;

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired
    protected GraviteePluginsService cut;

    protected GraviteePluginsListener listener = mock(GraviteePluginsListener.class);

    @Before
    public void prepareTest() {
        reset(listener);
        cut.registerListener(listener);
    }

    @Test
    public void shouldReplaceSecretInPlugin() {
        populateSecret("default", "myapp", "/kubernetes/test-secret-opaque.yml");
        populatePluginResource("default", "myapp-plugins", "/kubernetes/plugins/test-gravitee-plugin-success.yml", true);

        GraviteePlugin plugins = ObjectMapperHelper.readYamlAs("/kubernetes/plugins/test-gravitee-plugin-success.yml", GraviteePlugin.class);
        TestSubscriber<WatchActionContext<GraviteePlugin>> observable = cut.processAction(new WatchActionContext<>(plugins, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertNoErrors();
        observable.assertValue(ctx -> {
            boolean valid = ctx.getEvent() == WatchActionContext.Event.ADDED;
            valid = valid && (ctx.getPluginRevisions().size() == 6);
            valid = valid && ctx.getPluginRevisions().stream()
                    .filter(p -> p.getPlugin() instanceof Policy)
                    .filter(p -> ((Policy)p.getPlugin()).getName().equals("jwt"))
                    // DA7OLkdACP is the decoded value of the secret
                    .findFirst().map(p -> ((Policy)p.getPlugin()).getConfiguration().contains("DA7OLkdACP")).orElseGet(() -> false);
            return valid;
        });

        verify(listener, never()).onPluginsUpdate(any()); // notify other components is useless for new resources
    }

    @Test
    public void shouldNotNotifyListenerTwice() {
        populateSecret("default", "myapp", "/kubernetes/test-secret-opaque.yml");
        populatePluginResource("default", "myapp-plugins", "/kubernetes/plugins/test-gravitee-plugin-success.yml", true);

        final GraviteePluginStatus.IntegrationState integration = new GraviteePluginStatus.IntegrationState();
        integration.setState(GraviteePluginStatus.PluginState.SUCCESS);

        GraviteePlugin plugins = ObjectMapperHelper.readYamlAs("/kubernetes/plugins/test-gravitee-plugin-success.yml", GraviteePlugin.class);
        GraviteePluginStatus status = new GraviteePluginStatus();
        status.setIntegration(integration);
        status.setHashCodes(new HashMap<>());
        plugins.setStatus(status);

        GraviteePlugin pluginsSecond = ObjectMapperHelper.readYamlAs("/kubernetes/plugins/test-gravitee-plugin-success.yml", GraviteePlugin.class);
        GraviteePluginStatus statusSecond = new GraviteePluginStatus();
        statusSecond.setIntegration(integration);
        Map<String, String> hashCodes = new HashMap<>();
        hashCodes.put("jwt-poc", "f4624fabb81070eea9ca");
        hashCodes.put("oauth2-resource", "25e3a96dea07b801f9a5");
        hashCodes.put("rate-limit", "08693024974124779d1a");
        hashCodes.put("quota-policy", "9006c8a7761ad4c46d61");
        hashCodes.put("key-less-poc", "0d13ed4fc2b0a5aeaa8d");
        hashCodes.put("oauth2", "6a4601ff81416188b6dc");
        statusSecond.setHashCodes(hashCodes);
        pluginsSecond.setStatus(statusSecond);

        TestSubscriber<WatchActionContext<GraviteePlugin>> observable = cut.processAction(new WatchActionContext<>(plugins, WatchActionContext.Event.MODIFIED)).test();
        observable.awaitTerminalEvent();
        observable.assertNoErrors();
        observable.assertValue(ctx -> {
            boolean valid = ctx.getEvent() == WatchActionContext.Event.MODIFIED;
            valid = valid && (ctx.getPluginRevisions().size() == 6);
            valid = valid && ctx.getPluginRevisions().stream()
                    .filter(p -> p.getPlugin() instanceof Policy)
                    .filter(p -> ((Policy)p.getPlugin()).getName().equals("jwt"))
                    // DA7OLkdACP is the decoded value of the secret
                    .findFirst().map(p -> ((Policy)p.getPlugin()).getConfiguration().contains("DA7OLkdACP")).orElseGet(() -> false);
            return valid;
        });

        observable = cut.processAction(new WatchActionContext<>(pluginsSecond, WatchActionContext.Event.MODIFIED)).test();
        observable.awaitTerminalEvent();
        observable.assertNoErrors();
        observable.assertValue(ctx -> {
            boolean valid = ctx.getEvent() == WatchActionContext.Event.MODIFIED;
            valid = valid && (ctx.getPluginRevisions().size() == 6);
            valid = valid && ctx.getPluginRevisions().stream()
                    .filter(p -> p.getPlugin() instanceof Policy)
                    .filter(p -> ((Policy)p.getPlugin()).getName().equals("jwt"))
                    // DA7OLkdACP is the decoded value of the secret
                    .findFirst().map(p -> ((Policy)p.getPlugin()).getConfiguration().contains("DA7OLkdACP")).orElseGet(() -> false);
            return valid;
        });

        // same resource GraviteePlugin is processed twice, listener called only once
        verify(listener, times(1)).onPluginsUpdate(any());
    }

    @Test
    public void shouldFail_UnknownSecret() {
        populatePluginResource("default", "myapp-plugins", "/kubernetes/plugins/test-gravitee-plugin-unknown-secret.yml", true);

        GraviteePlugin plugins = ObjectMapperHelper.readYamlAs("/kubernetes/plugins/test-gravitee-plugin-unknown-secret.yml", GraviteePlugin.class);
        TestSubscriber<WatchActionContext<GraviteePlugin>> observable = cut.processAction(new WatchActionContext<>(plugins, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertError(error -> error instanceof PipelineException && error.getMessage().startsWith("Unable to read key 'myapp-unknown-password'"));

        verify(listener, never()).onPluginsUpdate(any());
    }

    private void populateSecret(String ns, String name, String filename) {
        Secret toCreate = kubernetesServer.getClient().secrets().load(getClass().getResourceAsStream(filename)).get();
        kubernetesServer.expect().get().withPath("/api/v1/namespaces/" + ns + "/secrets/" + name).andReturn(200, toCreate).always();
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


}
