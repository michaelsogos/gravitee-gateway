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
package io.gravitee.gateway.services.ingress;

import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionList;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.gravitee.gateway.services.ingress.crd.resources.*;

import java.util.Map;
import java.util.Optional;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class Main {

    public static void main(String[] args) {
        Config config = new ConfigBuilder().withMasterUrl("https://0.0.0.0:44071").build();
        KubernetesClient client = new DefaultKubernetesClient();

       /* CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                .withGroup("gravitee.io")
                        .withVersion("v1alpha1")
                        .withScope("Namespaced")
                        .withName("gravitee-plugins.gravitee.io")
                                        .withPlural("gravitee-plugins")
                                        .withKind("GraviteePlugins")
                                        .build();
        MixedOperation<GraviteePlugin,
                GraviteePluginList,
                DoneableGraviteePlugin,
                Resource<GraviteePlugin, DoneableGraviteePlugin>> gioPluginClient = client.customResources(
                        context, GraviteePlugin.class, GraviteePluginList.class, DoneableGraviteePlugin.class);
        KubernetesDeserializer.registerCustomKind("gravitee.io/v1alpha1", "GraviteePlugin", GraviteePlugin.class);

        GraviteePlugin pl = gioPluginClient.list().getItems().get(0);
        System.out.println(pl);*/

        CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                .withGroup("gravitee.io")
                .withVersion("v1alpha1")
                .withScope("Namespaced")
                .withName("gravitee-services.gravitee.io")
                .withPlural("gravitee-services")
                .withKind("GraviteeServices")
                .build();


        KubernetesDeserializer.registerCustomKind("gravitee.io/v1alpha1", "GraviteeServices", GraviteeServices.class);
        client.customResources(context,
                GraviteeServices.class,
                GraviteeServicesList.class,
                DoneableGraviteeServices.class).watch(new Watcher<GraviteeServices>() {
            @Override
            public void eventReceived(Action action, GraviteeServices graviteeServices) {
                System.out.println(action + " " + graviteeServices);
            }

            @Override
            public void onClose(KubernetesClientException e) {

            }
        });
    }
}
