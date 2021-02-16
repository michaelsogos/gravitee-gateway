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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.gravitee.common.service.AbstractService;
import io.gravitee.definition.model.LoadBalancerType;
import io.gravitee.gateway.services.kube.crds.status.GraviteeGatewayStatus;
import io.gravitee.gateway.services.kube.crds.status.GraviteePluginStatus;
import io.gravitee.gateway.services.kube.managers.GraviteeGatewayManager;
import io.gravitee.gateway.services.kube.managers.GraviteePluginsManager;
import io.gravitee.gateway.services.kube.managers.GraviteeServicesManager;
import io.gravitee.gateway.services.kube.utils.Fabric8sMapperUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class KubeSyncService extends AbstractService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KubeSyncService.class);

    @Autowired
    private KubernetesClient client;

    @Autowired
    public GraviteeGatewayManager gatewayManager;

    @Autowired
    public GraviteePluginsManager pluginsManager;

    @Autowired
    public GraviteeServicesManager servicesManager;

    @Override
    protected void doStart() throws Exception {
        Fabric8sMapperUtils.initJsonMapper();

        if (pluginsManager != null) {
            pluginsManager.start();
        }
        if (gatewayManager != null) {
            gatewayManager.start();
        }
        if (servicesManager != null) {
            servicesManager.start();
        }
    }


    @Override
    protected void doStop() throws Exception {
        LOGGER.info("APIM Controller stopping...");
        if (servicesManager != null) {
            servicesManager.stop();
        }

        if (gatewayManager != null) {
            gatewayManager.stop();
        }

        if (pluginsManager != null) {
            pluginsManager.stop();
        }

        if (client != null) {
            client.close();
        }

    }
}
