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
package io.gravitee.gateway.services.kube.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.gravitee.definition.model.LoadBalancerType;
import io.gravitee.gateway.services.kube.crds.status.GraviteeGatewayStatus;
import io.gravitee.gateway.services.kube.crds.status.GraviteePluginStatus;

import java.io.IOException;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class Fabric8sMapperUtils {

    public static SimpleModule initializeJsonModule() {
        SimpleModule module = new SimpleModule();

        // useful to send data to gateway
        module.addSerializer(
                Enum.class,
                new StdSerializer<Enum>(Enum.class) {
                    @Override
                    public void serialize(Enum value, JsonGenerator jgen, SerializerProvider provider) throws IOException {
                        jgen.writeString(value.name().toLowerCase());
                    }
                }
        );

        module.addSerializer(
                GraviteeGatewayStatus.GatewayState.class,
                new StdSerializer<GraviteeGatewayStatus.GatewayState>(GraviteeGatewayStatus.GatewayState.class) {
                    @Override
                    public void serialize(GraviteeGatewayStatus.GatewayState value, JsonGenerator jgen, SerializerProvider provider)
                            throws IOException {
                        jgen.writeString(value.name());
                    }
                }
        );

        module.addSerializer(
                GraviteePluginStatus.PluginState.class,
                new StdSerializer<GraviteePluginStatus.PluginState>(GraviteePluginStatus.PluginState.class) {
                    @Override
                    public void serialize(GraviteePluginStatus.PluginState value, JsonGenerator jgen, SerializerProvider provider)
                            throws IOException {
                        jgen.writeString(value.name());
                    }
                }
        );

        module.addDeserializer(
                LoadBalancerType.class,
                new StdDeserializer<LoadBalancerType>(LoadBalancerType.class) {
                    @Override
                    public LoadBalancerType deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
                            throws IOException, JsonProcessingException {
                        return LoadBalancerType.valueOf(jsonParser.getValueAsString().replaceAll("-", "_").toUpperCase());
                    }
                }
        );
        return module;
    }

    public static void initJsonMapper() {
        SimpleModule module = initializeJsonModule();
        Serialization.jsonMapper().registerModule(module);
    }
}
