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
package io.gravitee.gateway.services.kube.crds.status;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GraviteeGatewayStatus {

    private GatewayHashCodes hashCodes = new GatewayHashCodes();

    private IntegrationState integration = new IntegrationState();

    public GatewayHashCodes getHashCodes() {
        return hashCodes;
    }

    public void setHashCodes(GatewayHashCodes hashCodes) {
        this.hashCodes = hashCodes;
    }

    public IntegrationState getIntegration() {
        return integration;
    }

    public void setIntegration(IntegrationState integration) {
        this.integration = integration;
    }

    public class GatewayHashCodes {

        private String defaultHttpConfig = "";
        private Map<String, String> plugins = new HashMap<>();

        public String getDefaultHttpConfig() {
            return defaultHttpConfig;
        }

        public void setDefaultHttpConfig(String defaultHttpConfig) {
            this.defaultHttpConfig = defaultHttpConfig;
        }

        public Map<String, String> getPlugins() {
            return plugins;
        }

        public void setPlugins(Map<String, String> plugins) {
            this.plugins = plugins;
        }
    }

    public static class IntegrationState {

        private GatewayState state;
        private String message;

        public GatewayState getState() {
            return state;
        }

        public void setState(GatewayState state) {
            this.state = state;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public enum GatewayState {
        SUCCESS,
        ERROR,
    }
}
