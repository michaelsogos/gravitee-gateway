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
public class GraviteePluginStatus {

    private Map<String, String> hashCodes = new HashMap<>();

    private IntegrationState integration = new IntegrationState();

    public Map<String, String> getHashCodes() {
        return hashCodes;
    }

    public void setHashCodes(Map<String, String> hashCodes) {
        this.hashCodes = hashCodes;
    }

    public IntegrationState getIntegration() {
        return integration;
    }

    public void setIntegration(IntegrationState integration) {
        this.integration = integration;
    }

    public static class IntegrationState {

        private PluginState state;
        private String message;

        public PluginState getState() {
            return state;
        }

        public void setState(PluginState state) {
            this.state = state;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public enum PluginState {
        SUCCESS,
        ERROR,
    }
}
