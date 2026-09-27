/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.encoding;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class GzipResourceEncodingProviderTest {

    private static final int THREADS = 16;
    private static final int ROUNDS = 100;

    private final byte[] content = "body { color: red; }\n".repeat(4096).getBytes(StandardCharsets.UTF_8);

    private File cacheDir;

    @Before
    public void before() throws Exception {
        cacheDir = Files.createTempDirectory("gzip-cache-test").toFile();
    }

    @After
    public void after() throws Exception {
        FileUtils.deleteDirectory(cacheDir);
    }

    @Test
    public void concurrentRequestsForUncachedResourceAllGetEncodedStream() throws Exception {
        GzipResourceEncodingProvider provider = new GzipResourceEncodingProvider(cacheDir);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String resource = "style-" + round + ".css";
                CyclicBarrier barrier = new CyclicBarrier(THREADS);
                List<Future<byte[]>> results = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    results.add(executor.submit(() -> {
                        barrier.await();
                        InputStream encoded = provider.getEncodedStream(() -> new ByteArrayInputStream(content), "login", "test", resource);
                        if (encoded == null) {
                            return null;
                        }
                        try (InputStream is = new GZIPInputStream(encoded)) {
                            return is.readAllBytes();
                        }
                    }));
                }
                for (Future<byte[]> result : results) {
                    byte[] decoded = result.get(30, TimeUnit.SECONDS);
                    Assert.assertNotNull("encoded stream missing for " + resource, decoded);
                    Assert.assertArrayEquals(content, decoded);
                }
            }
        } finally {
            executor.shutdownNow();
        }

        try (Stream<Path> files = Files.walk(cacheDir.toPath())) {
            Assert.assertEquals("temporary files left in cache", 0, files.filter(p -> p.toString().endsWith("tmp")).count());
        }
    }
}
