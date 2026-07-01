/*
 * Copyright (c) 2026 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.ballerina.observe.trace.otel;

import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.propagation.ContextPropagators;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Test class for OtelTracerProvider.
 */
public class OtelTracerProviderTest {

    private OtelTracerProvider tracerProvider;

    @BeforeMethod
    public void setUp() {
        tracerProvider = new OtelTracerProvider();
    }

    @Test
    public void testGetName() {
        assertEquals(tracerProvider.getName(), "otel");
    }

    @Test
    public void testInit() {
        // init() does nothing, just ensure it doesn't throw an exception
        tracerProvider.init();
    }


    @Test
    public void testGetPropagators() {
        ContextPropagators propagators = tracerProvider.getPropagators();
        assertNotNull(propagators);
    }

    @Test
    public void testBuildResourceAttributesUsesConfiguredServiceName() throws Exception {
        BMap<BString, BString> resourceAttributes = map();
        resourceAttributes.put(bString("\"service.name\""), bString("orders"));
        resourceAttributes.put(bString("\"deployment.environment\""), bString("dev"));

        setStaticField("resourceAttributes", resourceAttributes);

        Attributes attributes = invokeBuildResourceAttributes("default-service");

        assertEquals(attributes.get(AttributeKey.stringKey("service.name")), "orders");
        assertEquals(attributes.get(AttributeKey.stringKey("deployment.environment")), "dev");
    }

    @SuppressWarnings("unchecked")
    private static BMap<BString, BString> map() {
        return (BMap<BString, BString>) (BMap<?, ?>) ValueCreator.createMapValue(
                TypeCreator.createMapType(PredefinedTypes.TYPE_STRING));
    }

    private static BString bString(String value) {
        return StringUtils.fromString(value);
    }

    private static void setStaticField(String fieldName, Object value) throws Exception {
        Field field = OtelTracerProvider.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Attributes invokeBuildResourceAttributes(String serviceName) throws Exception {
        Method method = OtelTracerProvider.class.getDeclaredMethod("buildResourceAttributes", String.class);
        method.setAccessible(true);
        return (Attributes) method.invoke(null, serviceName);
    }
}
