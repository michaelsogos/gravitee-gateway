#
# Copyright (C) 2015 The Gravitee team (http://gravitee.io)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#         http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

FROM graviteeio/apim-gateway:3.5

RUN rm ${GRAVITEEIO_HOME}/lib/gravitee-gateway-handlers-api-3.*.jar && rm ${GRAVITEEIO_HOME}/lib/gravitee-gateway-security-core-3.*.jar

COPY gravitee-gateway-handlers/gravitee-gateway-handlers-api/target/gravitee-gateway-handlers-api-3.*-SNAPSHOT.jar ${GRAVITEEIO_HOME}/lib/
COPY gravitee-gateway-security/gravitee-gateway-security-core/target/gravitee-gateway-security-core-3.*-SNAPSHOT.jar ${GRAVITEEIO_HOME}/lib/
COPY gravitee-gateway-services/gravitee-gateway-services-kubernetes/target/gravitee-gateway-services-kubernetes-3.*-SNAPSHOT.zip ${GRAVITEEIO_HOME}/plugins/
