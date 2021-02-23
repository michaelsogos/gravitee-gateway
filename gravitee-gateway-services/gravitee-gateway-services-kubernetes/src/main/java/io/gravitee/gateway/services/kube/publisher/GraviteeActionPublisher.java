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
package io.gravitee.gateway.services.kube.publisher;

import io.gravitee.gateway.services.kube.services.impl.WatchActionContext;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GraviteeActionPublisher implements Publisher<WatchActionContext> {

    private Subscriber<? super WatchActionContext> subscriber;

    @Override
    public void subscribe(Subscriber<? super WatchActionContext> subscriber) {
        this.subscriber = subscriber;
    }

    public void emit(WatchActionContext action) {
        if (this.subscriber != null) {
            this.subscriber.onNext(action);
        }
    }

    public void shutdown() {
        if (this.subscriber != null) {
            this.subscriber.onComplete();
        }
    }
}
