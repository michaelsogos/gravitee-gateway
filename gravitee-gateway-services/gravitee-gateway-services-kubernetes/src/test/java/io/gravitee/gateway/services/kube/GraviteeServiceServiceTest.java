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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.definition.model.LoadBalancerType;
import io.gravitee.gateway.handlers.api.manager.ApiManager;
import io.gravitee.gateway.services.kube.crds.resources.GraviteeServices;
import io.gravitee.gateway.services.kube.exceptions.PipelineException;
import io.gravitee.gateway.services.kube.services.GraviteeServicesService;
import io.gravitee.gateway.services.kube.services.impl.ServiceWatchActionContext;
import io.gravitee.gateway.services.kube.services.impl.WatchActionContext;
import io.gravitee.gateway.services.kube.utils.ObjectMapperHelper;
import io.reactivex.subscribers.TestSubscriber;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = KubeSyncTestConfig.class)
public class GraviteeServiceServiceTest extends AbstractServiceTest {

    @Autowired
    protected GraviteeServicesService cut;

    @Autowired
    protected ApiManager apiManager;

    @Before
    public void prepareTest() {
        reset(apiManager);
    }

    @Test
    public void shouldDeploy_SingleService_JWT() {
        populateSecret("default", "myapp", "/kubernetes/test-secret-opaque.yml");
        populateServicesResource("default", "test-single-standalone", "/kubernetes/services/test-gravitee-service-single-standalone-jwt.yml", true);

        GraviteeServices services = ObjectMapperHelper.readYamlAs("/kubernetes/services/test-gravitee-service-single-standalone-jwt.yml", GraviteeServices.class);
        TestSubscriber<WatchActionContext<GraviteeServices>> observable = cut.processAction(new ServiceWatchActionContext(services, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertNoErrors();

        verify(apiManager).register(argThat(api -> {
                    try {
                        System.out.println(new ObjectMapper().writeValueAsString(api));
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                    return  api.getAuthentication().equals("JWT")
                    && api.getAuthenticationDefinition().contains("DA7OLkdACP")
                    && api.isEnabled()
                    && api.getName().equals("my-api")
                    && api.getId().equals("my-api.test-single-standalone.default")
                    && !api.getProxy().getCors().isEnabled()
                    && api.getProxy().getVirtualHosts().get(0).getHost().equals("toto.domain.name:82")
                    && api.getPaths().size() == 2
                    && api.getPaths().containsKey("/*")
                    && api.getPaths().containsKey("/other-path/")
                    && api.getPaths().get("/other-path/").getRules().size() == 2
                    && api.getResources().size() == 1
                    && api.getResources().get(0).getName().equals("my-oauth2-res.test-single-standalone.default")
                    && api.getProxy().getGroups().size() == 1
                    && api.getProxy().getGroups().stream()
                    .filter(endpointGroup -> endpointGroup.getLoadBalancer().getType().equals(LoadBalancerType.ROUND_ROBIN)).count() == 1
                    && api.getProxy().getGroups().stream().findFirst().get().getEndpoints().size() == 2;
        }
        ));
    }

    @Test
    public void shouldNotDeploy_DisabledAt_CrdLevel() {
        populateServicesResource("default", "test-single-standalone", "/kubernetes/services/test-gravitee-service-single-disable-crd-level.yml", true);

        GraviteeServices services = ObjectMapperHelper.readYamlAs("/kubernetes/services/test-gravitee-service-single-disable-crd-level.yml", GraviteeServices.class);
        TestSubscriber<WatchActionContext<GraviteeServices>> observable = cut.processAction(new ServiceWatchActionContext(services, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertNoErrors();

        verify(apiManager, never()).register(any());
    }

    @Test
    public void shouldNotDeploy_DisabledAt_ApiLevel() {
        populateServicesResource("default", "test-single-standalone", "/kubernetes/services/test-gravitee-service-single-disable-api-level.yml", true);

        GraviteeServices services = ObjectMapperHelper.readYamlAs("/kubernetes/services/test-gravitee-service-single-disable-api-level.yml", GraviteeServices.class);
        TestSubscriber<WatchActionContext<GraviteeServices>> observable = cut.processAction(new ServiceWatchActionContext(services, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertNoErrors();

        verify(apiManager, never()).register(any());
    }

    @Test
    public void shouldDeploy_SingleService_Using_ExternalPlugins() {
        populateSecret("default", "myapp", "/kubernetes/test-secret-opaque.yml");
        populateServicesResource("default", "test-single-ref-jwt", "/kubernetes/services/test-gravitee-service-single-references-import-jwt.yml", true);

        GraviteeServices services = ObjectMapperHelper.readYamlAs("/kubernetes/services/test-gravitee-service-single-references-import-jwt.yml", GraviteeServices.class);
        TestSubscriber<WatchActionContext<GraviteeServices>> observable = cut.processAction(new ServiceWatchActionContext(services, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertError(error -> error instanceof PipelineException && error.getMessage().contains("Reference 'dep-plugins' undefined in namespace"));

        verify(apiManager, never()).register(any());

        // Deploy the CustomResource Plugin
        populatePluginResource("default", "dep-plugins", "/kubernetes/services/dependencies/dep-gravitee-plugins.yml", false);

        observable = cut.processAction(new ServiceWatchActionContext(services, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertNoErrors();

        verify(apiManager).register(argThat(api ->
                api.getAuthentication().equals("JWT")
                        && api.getAuthenticationDefinition().contains("DA7OLkdACP")
                        && api.isEnabled()
                        && api.getName().equals("my-api")
                        && api.getId().equals("my-api.test-single-ref-jwt.default")
                        && !api.getProxy().getCors().isEnabled()
                        && api.getProxy().getVirtualHosts().get(0).getHost().equals("toto.domain.name:82")
                        && api.getPaths().size() == 2
                        && api.getPaths().containsKey("/*")
                        && api.getPaths().containsKey("/other-path/")
                        && api.getPaths().get("/other-path/").getRules().size() == 2
                        && api.getResources().size() == 1
                        && api.getResources().get(0).getName().equals("oauth2-resource.dep-plugins.default")
                        && api.getProxy().getGroups().size() == 1
                        && api.getProxy().getGroups().stream()
                        .filter(endpointGroup -> endpointGroup.getLoadBalancer().getType().equals(LoadBalancerType.ROUND_ROBIN)).count() == 1
                        && api.getProxy().getGroups().stream().findFirst().get().getEndpoints().size() == 2
        ));
    }

    @Test
    public void shouldDeploy_SingleService_Using_StandAlone_Gateway() {
        populateSecret("default", "myapp", "/kubernetes/test-secret-opaque.yml");
        populateServicesResource("default", "test-single-ref-gw", "/kubernetes/services/test-gravitee-service-single-references-import-gateway.yml", true);

        GraviteeServices services = ObjectMapperHelper.readYamlAs("/kubernetes/services/test-gravitee-service-single-references-import-gateway.yml", GraviteeServices.class);
        TestSubscriber<WatchActionContext<GraviteeServices>> observable = cut.processAction(new ServiceWatchActionContext(services, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertError(error -> error instanceof PipelineException && error.getMessage().contains("Gateway Reference 'dep-gateway' undefined in namespace"));

        // Deployr CustomResource Gateway
        populateGatewayResource("default", "dep-gateway", "/kubernetes/services/dependencies/dep-gravitee-gateway-standalone.yml", false);

        services = ObjectMapperHelper.readYamlAs("/kubernetes/services/test-gravitee-service-single-references-import-gateway.yml", GraviteeServices.class);
        observable = cut.processAction(new ServiceWatchActionContext(services, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertNoErrors();

        verify(apiManager).register(argThat(api ->
                api.getAuthentication().equals("JWT")
                        && api.getAuthenticationDefinition().contains("DA7OLkdACP")
                        && api.isEnabled()
                        && api.getName().equals("my-api")
                        && api.getId().equals("my-api.test-single-ref-gw.default")
                        && !api.getProxy().getCors().isEnabled()
                        && api.getProxy().getVirtualHosts().get(0).getHost().equals("toto.domain.name:82")
                        && api.getPaths().size() == 2
                        && api.getPaths().containsKey("/*")
                        && api.getPaths().containsKey("/other-path/")
                        && api.getPaths().get("/other-path/").getRules().size() == 2
                        && api.getResources().size() == 1
                        && api.getResources().get(0).getName().equals("my-oauth2-res.dep-gateway.default")
                        && api.getProxy().getGroups().size() == 1
                        && api.getProxy().getGroups().stream()
                        .filter(endpointGroup -> endpointGroup.getLoadBalancer().getType().equals(LoadBalancerType.ROUND_ROBIN)).count() == 1
                        && api.getProxy().getGroups().stream().findFirst().get().getEndpoints().size() == 2
        ));
    }


    @Test
    public void shouldDeploy_SingleService_Using_Reference_Gateway() {
        populateSecret("default", "myapp", "/kubernetes/test-secret-opaque.yml");
        populateServicesResource("default", "test-single-ref-gw", "/kubernetes/services/test-gravitee-service-single-references-import-gateway-ref.yml", true);
        populateGatewayResource("default", "dep-gateway-ref", "/kubernetes/services/dependencies/dep-gravitee-gateway-reference.yml", false);

        GraviteeServices services = ObjectMapperHelper.readYamlAs("/kubernetes/services/test-gravitee-service-single-references-import-gateway-ref.yml", GraviteeServices.class);
        TestSubscriber<WatchActionContext<GraviteeServices>> observable = cut.processAction(new ServiceWatchActionContext(services, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertError(error -> error instanceof PipelineException && error.getMessage().contains("Reference 'dep-plugins-ref' undefined in namespace"));

        // Deploy CustomResource Plugins used by Gateway
        populatePluginResource("default", "dep-plugins-ref", "/kubernetes/services/dependencies/dep-gravitee-plugins-ref.yml", false);

        services = ObjectMapperHelper.readYamlAs("/kubernetes/services/test-gravitee-service-single-references-import-gateway-ref.yml", GraviteeServices.class);
        observable = cut.processAction(new ServiceWatchActionContext(services, WatchActionContext.Event.ADDED)).test();
        observable.awaitTerminalEvent();
        observable.assertNoErrors();

        verify(apiManager).register(argThat(api ->
                api.getAuthentication().equals("JWT")
                        && api.getAuthenticationDefinition().contains("DA7OLkdACP")
                        && api.isEnabled()
                        && api.getName().equals("my-api")
                        && api.getId().equals("my-api.test-single-ref-gw.default")
                        && !api.getProxy().getCors().isEnabled()
                        && api.getProxy().getVirtualHosts().get(0).getHost().equals("toto.domain.name:82")
                        && api.getPaths().size() == 2
                        && api.getPaths().containsKey("/*")
                        && api.getPaths().containsKey("/other-path/")
                        && api.getPaths().get("/other-path/").getRules().size() == 2
                        && api.getResources().size() == 1
                        && api.getResources().get(0).getName().equals("oauth2-resource.dep-plugins-ref.default")
                        && api.getProxy().getGroups().size() == 1
                        && api.getProxy().getGroups().stream()
                        .filter(endpointGroup -> endpointGroup.getLoadBalancer().getType().equals(LoadBalancerType.ROUND_ROBIN)).count() == 1
                        && api.getProxy().getGroups().stream().findFirst().get().getEndpoints().size() == 2
        ));
    }


}
