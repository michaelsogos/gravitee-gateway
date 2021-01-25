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

/**
 * This package contains Manager classes. Each Manager class handle a type of CustomResource (Gateway, Plugin, Service).
 * These managers are registered into the {@link io.gravitee.kube.controller.node.ControllerNode} in order to be started
 * during the controller boostrap.
 *
 * On the doStart method, each manager create a Rx Flow and act as the Flow Publisher.
 * On subscription, a Kubernetes Watcher is initialized in order to push CustomResource updated into the flow.
 * Each CustomResource update is wrapped into a {@link io.gravitee.gateway.services.kube.services.impl.WatchActionContext}
 * and this context is processed through the flow steps thanks to the services provided by the package
 * io.gravitee.gateway.services.kube.services
 */
package io.gravitee.gateway.services.kube.managers;
