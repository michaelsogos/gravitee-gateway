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
package io.gravitee.gateway.services.ingress.crd.resources.service;

import io.gravitee.definition.model.HttpClientOptions;
import io.gravitee.definition.model.HttpClientSslOptions;
import io.gravitee.definition.model.HttpProxy;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class BackendConfiguration {
    private HttpProxy httpProxy;
    private HttpClientOptions httpClientOptions;
    private HttpClientSslOptions httpClientSslOptions;

    public BackendConfiguration() {
    }

    public HttpProxy getHttpProxy() {
        return httpProxy;
    }

    public void setHttpProxy(HttpProxy httpProxy) {
        this.httpProxy = httpProxy;
    }

    public HttpClientOptions getHttpClientOptions() {
        return httpClientOptions;
    }

    public void setHttpClientOptions(HttpClientOptions httpClientOptions) {
        this.httpClientOptions = httpClientOptions;
    }

    public HttpClientSslOptions getHttpClientSslOptions() {
        return httpClientSslOptions;
    }

    public void setHttpClientSslOptions(HttpClientSslOptions httpClientSslOptions) {
        this.httpClientSslOptions = httpClientSslOptions;
    }
}
