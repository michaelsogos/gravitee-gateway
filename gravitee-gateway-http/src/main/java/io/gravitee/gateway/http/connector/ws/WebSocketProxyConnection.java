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
package io.gravitee.gateway.http.connector.ws;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.definition.model.endpoint.HttpEndpoint;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.proxy.ProxyConnection;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.api.proxy.ws.WebSocketProxyRequest;
import io.gravitee.gateway.api.stream.WriteStream;
import io.gravitee.gateway.core.proxy.EmptyProxyResponse;
import io.gravitee.gateway.core.proxy.ws.SwitchProtocolProxyResponse;
import io.gravitee.gateway.http.connector.AbstractProxyConnection;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebsocketRejectedException;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class WebSocketProxyConnection extends AbstractProxyConnection {

    private static final Set<CharSequence> WS_HOP_HEADERS;

    static {
        Set<CharSequence> wsHopHeaders = new HashSet<>();

        // Hop-by-hop headers Websocket
        wsHopHeaders.add(HttpHeaderNames.KEEP_ALIVE);
        wsHopHeaders.add(HttpHeaderNames.PROXY_AUTHORIZATION);
        wsHopHeaders.add(HttpHeaderNames.PROXY_AUTHENTICATE);
        wsHopHeaders.add(HttpHeaderNames.PROXY_CONNECTION);
        wsHopHeaders.add(HttpHeaderNames.TE);
        wsHopHeaders.add(HttpHeaderNames.TRAILER);

        WS_HOP_HEADERS = Collections.unmodifiableSet(wsHopHeaders);
    }

    private final WebSocketProxyRequest wsProxyRequest;

    public WebSocketProxyConnection(HttpEndpoint endpoint, ProxyRequest proxyRequest) {
        super(endpoint);
        this.wsProxyRequest = (WebSocketProxyRequest) proxyRequest;
    }

    @Override
    public ProxyConnection connect(HttpClient httpClient, int port, String host, String uri) {
        // Remove hop-by-hop headers.
        for (CharSequence header : WS_HOP_HEADERS) {
            wsProxyRequest.headers().remove(header);
        }

        httpClient.connectionHandler(new io.vertx.core.Handler<HttpConnection>() {
            @Override
            public void handle(HttpConnection event) {
            //    event.
            }
        });

        httpClient.websocket(port, host, uri, new io.vertx.core.Handler<WebSocket>() {
            @Override
            public void handle(WebSocket event) {
                // The client -> gateway connection must be upgraded now that the one between gateway -> upstream
                // has been accepted
                wsProxyRequest.upgrade();

                // From server to client
                wsProxyRequest.frameHandler(frame -> {
                    if (frame.type() == io.gravitee.gateway.api.ws.WebSocketFrame.Type.BINARY) {
                        event.writeBinaryMessage(io.vertx.core.buffer.Buffer.buffer(frame.data().getBytes()));
                    } else if (frame.type() == io.gravitee.gateway.api.ws.WebSocketFrame.Type.TEXT) {
                        event.writeTextMessage(frame.data().toString());
                    }
                });

                wsProxyRequest.closeHandler(result -> event.close());

                // From client to server
                event.frameHandler(frame -> wsProxyRequest.write(new WebSocketFrame(frame)));

                event.closeHandler(event1 -> wsProxyRequest.close());

                event.exceptionHandler(new io.vertx.core.Handler<Throwable>() {
                    @Override
                    public void handle(Throwable throwable) {
                        wsProxyRequest.reject(HttpStatusCode.BAD_REQUEST_400);
                        sendToClient(new EmptyProxyResponse(HttpStatusCode.BAD_REQUEST_400));
                    }
                });

                // Tell the reactor that the request has been handled by the HTTP client
                sendToClient(new SwitchProtocolProxyResponse());
            }
        }, throwable -> {
            if (throwable instanceof WebsocketRejectedException) {
                wsProxyRequest.reject(((WebsocketRejectedException) throwable).getStatus());
                sendToClient(new EmptyProxyResponse(((WebsocketRejectedException) throwable).getStatus()));
            } else {
                wsProxyRequest.reject(HttpStatusCode.BAD_GATEWAY_502);
                sendToClient(new EmptyProxyResponse(HttpStatusCode.BAD_GATEWAY_502));
            }
        });

        return this;
    }

    @Override
    public WriteStream<Buffer> write(Buffer content) {
        return this;
    }

    @Override
    public void end() {

    }
}
