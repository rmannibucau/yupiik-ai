/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.llm.client.test;

import static io.yupiik.fusion.framework.api.ConfiguringContainer.of;

import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.serialization.JsonCodec;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit extension providing a {@link JsonMapper} covering all the {@code $FusionJsonCodec}s of the classpath.
 * Boots once per test class a lightweight container keeping only the JSON beans ({@link JsonCodec}s and the
 * {@link JsonMapper} itself) and filtering all listeners out (no Start/Stop, no configuration wiring: nothing
 * initializes) and lets JUnit auto-close the container and the mapper instance when the test class context
 * is closed.
 */
public class TestJsonMapper implements ParameterResolver {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(TestJsonMapper.class);

    @Override
    public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) {
        return JsonMapper.class == parameterContext.getParameter().getType();
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) {
        // the mapper and its container must live for the whole test class, not per test method:
        // store them in the class-level context so JUnit closes them once all the tests are done.
        var context = extensionContext;
        while (context.getTestMethod().isPresent()) {
            context = context.getParent().orElseThrow(() -> new IllegalStateException("No parent extension context"));
        }
        final var holder = context.getStore(NAMESPACE).computeIfAbsent(JsonMapper.class, key -> boot(), Holder.class);
        return holder.mapper();
    }

    private static Holder boot() {
        final var container = of().register(new FusionModule() {
                    @Override
                    public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                        // json only: the mapper, the codecs and the default beans it relies on
                        // (Configuration, Emitter, RuntimeContainer), the CLI/wiring/tui beans are not needed
                        return (runtimeContainer, bean) -> isA(bean.type(), JsonCodec.class)
                                || isA(bean.type(), JsonMapper.class)
                                || isA(bean.type(), Configuration.class)
                                || isA(bean.type(), Emitter.class)
                                || isA(bean.type(), RuntimeContainer.class);
                    }

                    @Override
                    public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                        return (runtimeContainer, listener) -> false; // no listener -> nothing initializes
                    }
                })
                .start();
        try {
            return new Holder(container.lookup(JsonMapper.class), container);
        } catch (final RuntimeException e) {
            container.close();
            throw e;
        }
    }

    private static boolean isA(final Type type, final Class<?> expected) {
        final var clazz = type instanceof ParameterizedType parameterized
                ? (Class<?>) parameterized.getRawType()
                : (Class<?>) type;
        return expected.isAssignableFrom(clazz);
    }

    private record Holder(Instance<JsonMapper> mapperInstance, RuntimeContainer container) implements AutoCloseable {
        private JsonMapper mapper() {
            return mapperInstance.instance();
        }

        @Override
        public void close() {
            mapperInstance.close(); // the container does not track instances: closing the lookup runs the bean destroy
            container.close();
        }
    }
}
