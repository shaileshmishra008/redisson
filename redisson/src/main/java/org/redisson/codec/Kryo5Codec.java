/**
 * Copyright (c) 2013-2019 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.codec;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author Nikita Koksharov
 *
 */
public class Kryo5Codec extends BaseCodec {

    private final Pool<Kryo> kryoPool;
    private final Pool<Input> inputPool;
    private final Pool<Output> outputPool;
    private final List<Class<?>> classes;

    public Kryo5Codec() {
        this(Collections.emptyList());
    }

    public Kryo5Codec(ClassLoader classLoader) {
        this(Collections.emptyList(), classLoader);
    }

    public Kryo5Codec(ClassLoader classLoader, Kryo5Codec codec) {
        this(codec.getClasses(), classLoader);
    }

    public Kryo5Codec(List<Class<?>> classes) {
        this(classes, null);
    }

    public Kryo5Codec(List<Class<?>> classes, ClassLoader classLoader) {
        this.classes = classes;

        this.kryoPool = new Pool<Kryo>(true, false) {
            @Override
            protected Kryo create() {
                Kryo kryo = new Kryo();
                if (classLoader != null) {
                    kryo.setClassLoader(classLoader);
                }
                kryo.setRegistrationRequired(false);
                kryo.setReferences(false);
                for (Class<?> clazz : classes) {
                    kryo.register(clazz);
                }
                return kryo;
            }
        };

        this.inputPool = new Pool<Input>(true, false) {
            @Override
            protected Input create() {
                return new Input(8192);
            }
        };

        this.outputPool = new Pool<Output>(true, false) {
            @Override
            protected Output create() {
                return new Output(8192, -1);
            }
        };
    }

    private final Decoder<Object> decoder = new Decoder<Object>() {
        @Override
        public Object decode(ByteBuf buf, State state) throws IOException {
            Kryo kryo = kryoPool.obtain();
            Input input = inputPool.obtain();
            try {
                input.setInputStream(new ByteBufInputStream(buf));
                return kryo.readClassAndObject(input);
            } finally {
                kryoPool.free(kryo);
                inputPool.free(input);
            }
        }
    };

    private final Encoder encoder = new Encoder() {
        @Override
        @SuppressWarnings("IllegalCatch")
        public ByteBuf encode(Object in) throws IOException {
            Kryo kryo = kryoPool.obtain();
            Output output = outputPool.obtain();
            ByteBuf out = ByteBufAllocator.DEFAULT.buffer();
            try {
                ByteBufOutputStream baos = new ByteBufOutputStream(out);
                output.setOutputStream(baos);
                kryo.writeClassAndObject(output, in);
                output.flush();
                return baos.buffer();
            } catch (RuntimeException e) {
                out.release();
                throw e;
            } finally {
                kryoPool.free(kryo);
                outputPool.free(output);
            }
        }
    };

    public List<Class<?>> getClasses() {
        return classes;
    }

    @Override
    public Decoder<Object> getValueDecoder() {
        return decoder;
    }

    @Override
    public Encoder getValueEncoder() {
        return encoder;
    }
}
