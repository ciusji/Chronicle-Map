/*
 * Copyright 2012-2018 Chronicle Map Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.map;

import com.google.common.collect.Lists;
import net.openhft.chronicle.bytes.NoBytesStore;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.values.IntValue;
import net.openhft.chronicle.hash.impl.util.Cleaner;
import net.openhft.chronicle.hash.impl.util.CleanerUtils;
import net.openhft.chronicle.hash.serialization.impl.StringSizedReader;
import net.openhft.chronicle.hash.serialization.impl.StringUtf8DataAccess;
import net.openhft.chronicle.values.Values;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;

@RunWith(Parameterized.class)
public class MemoryLeaksTest {

    /**
     * Accounting {@link CountedStringReader} creation and finalization. All serializers,
     * created since the map creation, should become unreachable after map.close() or collection by
     * Cleaner, it means that map contexts (referencing serializers) are collected by the GC
     */
    private final AtomicInteger serializerCount = new AtomicInteger();
    private final List<WeakReference<CountedStringReader>> serializers = new ArrayList<>();
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final boolean persisted;
    private final ChronicleMapBuilder<IntValue, String> builder;
    private final boolean closeWithinContext;

    public MemoryLeaksTest(String testType, boolean replicated, boolean persisted, boolean closeWithinContext) {
        this.persisted = persisted;
        this.closeWithinContext = closeWithinContext;
        builder = ChronicleMap
                .of(IntValue.class, String.class).constantKeySizeBySample(Values.newHeapInstance(IntValue.class))
                .valueReaderAndDataAccess(new CountedStringReader(this), new StringUtf8DataAccess());
        if (replicated)
            builder.replication((byte) 1);
        builder.entries(1).averageValueSize(10);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        List<Boolean> booleans = Arrays.asList(false, true);
        // Test with all possible combinations of three boolean parameters.
        return Lists.cartesianProduct(booleans, booleans, booleans)
                .stream().map(flags -> {
                    final ArrayList<Object> namedFlags = new ArrayList<>(Collections.singletonList(named(flags)));
                    namedFlags.addAll(flags);
                    return namedFlags;
                }).map(List::toArray).collect(Collectors.toList());
    }

    private static String named(final List<Boolean> flags) {
        return (!flags.get(0) ? "not " : "") + "replicated, " +
                (!flags.get(1) ? "not " : "") + "persisted, " +
                (!flags.get(2) ? "not " : "") + "closed within context";
    }

    @Before
    public void initNoBytesStore() {
        Assert.assertNotEquals(0, NoBytesStore.NO_PAGE);
    }

    @Before
    public void resetSerializerCount() {
        System.err.println("This test is expect to print 'ChronicleMap ... is not closed manually, cleaned up from Cleaner'");
        serializerCount.set(0);
    }

    @Test(timeout = 10_000)
    public void testChronicleMapCollectedAndDirectMemoryReleased() throws IOException {
        assumeFalse(OS.isMacOSX());
        // This test is flaky in Linux and Mac OS apparently because some native memory from
        // running previous/concurrent tests is released during this test, that infers with
        // the (*) check below. The aim of this test is to check that native memory is not
        // leaked and it is proven if it succeeds at least sometimes at least in some OSes.
        // This tests is successful always in Windows and successful in Linux and OS X when run
        // alone, rather than along all other Chronicle Map's tests.

        System.gc();
        Jvm.pause(100);

        long nativeMemoryUsedBeforeMap = nativeMemoryUsed();
        int serializersBeforeMap = serializerCount.get();
        // the purpose of the test is to find maps which are not closed properly.
        ChronicleMap<IntValue, String> map = getMap();
        long expectedNativeMemory = nativeMemoryUsedBeforeMap + map.offHeapMemoryUsed();
        assertEquals(expectedNativeMemory, nativeMemoryUsed());
        tryCloseFromContext(map);
        WeakReference<ChronicleMap<IntValue, String>> ref = new WeakReference<>(map);
        Assert.assertNotNull(ref.get());
        //noinspection UnusedAssignment
        map = null;

        // Wait until Map is collected by GC
        // Wait until Cleaner is called and memory is returned to the system
        for (int i = 1; i <= 10; i++) {
            if (ref.get() == null &&
                    nativeMemoryUsedBeforeMap >= nativeMemoryUsed() && // (*)
                    serializerCount.get() == serializersBeforeMap) {
                break;
            }
            System.gc();
            Jvm.pause(i * 10);
            System.out.println("ref.get()=" + (ref.get() == null));
            System.out.println(nativeMemoryUsedBeforeMap + " <=> " + nativeMemoryUsed());
            System.out.println(serializerCount.get() + " <=> " + serializersBeforeMap);
        }
        if (nativeMemoryUsedBeforeMap < nativeMemoryUsed())
            Assert.assertEquals(nativeMemoryUsedBeforeMap, nativeMemoryUsed());
        Assert.assertEquals(serializersBeforeMap, serializerCount.get());
    }

    private long nativeMemoryUsed() {
        if (persisted) {
            return OS.memoryMapped();
        } else {
            return OS.memory().nativeMemoryUsed();
        }
    }

    @Test(timeout = 60_000)
    public void testExplicitChronicleMapCloseReleasesMemory()
            throws IOException, InterruptedException {
        long nativeMemoryUsedBeforeMap = nativeMemoryUsed();
        int serializersBeforeMap = serializerCount.get();
        try (ChronicleMap<IntValue, String> map = getMap()) {
            // One serializer should be copied to the map's valueReader field, another is copied from
            // the map's valueReader field to the context
            Assert.assertTrue(serializerCount.get() >= serializersBeforeMap + 2);
            Assert.assertNotEquals(0, map.offHeapMemoryUsed());
            try {
                long expectedNativeMemory = nativeMemoryUsedBeforeMap + map.offHeapMemoryUsed();
                assertEquals(String.format(
                        "used before map: %d, used by map: %d, expected used: %d, actual used: %d",
                        nativeMemoryUsedBeforeMap,
                        map.offHeapMemoryUsed(),
                        expectedNativeMemory, nativeMemoryUsed()),
                        expectedNativeMemory, nativeMemoryUsed());
            } finally {
                tryCloseFromContext(map);
                map.close();
            }

            if (closeWithinContext) {
                // Fails because of https://github.com/OpenHFT/Chronicle-Map/issues/153
                return;
            } else {
                assertEquals(nativeMemoryUsedBeforeMap, nativeMemoryUsed());
            }

            // Wait until chronicle map context (hence serializers) is collected by the GC
            for (int i = 0; i < 6_000; i++) {
                if (serializerCount.get() == serializersBeforeMap)
                    break;
                System.gc();
                byte[] garbage = new byte[50_000_000];
                Thread.sleep(1);
            }
            Assert.assertTrue(serializerCount.get() == serializersBeforeMap);
            // This assertion ensures GC doesn't reclaim the map before or during the loop iteration
            // above, to ensure that we test that the direct memory and contexts are released because
            // of the manual map.close(), despite the "leak" of the map object itself.

            // Assertion disabled because a closed map now guards offHeapMemoryUsed()
            //Assert.assertEquals(0, map.offHeapMemoryUsed());
        }
    }

    private ChronicleMap<IntValue, String> getMap() throws IOException {
        VanillaChronicleMap<IntValue, String, ?> map;
        if (persisted) {
            map = (VanillaChronicleMap<IntValue, String, ?>)
                    builder.createPersistedTo(folder.newFile());
        } else {
            map = (VanillaChronicleMap<IntValue, String, ?>) builder.create();
        }
        IntValue key = Values.newHeapInstance(IntValue.class);
        int i = 0;
        while (!map.hasExtraTierBulks()) {
            key.setValue(i++);
            map.put(key, "string" + i);
        }
        return map;
    }

    private void tryCloseFromContext(ChronicleMap<IntValue, String> map) {
        // Test that the map could still be successfully closed and no leaks are introduced
        // by an attempt to close the map from within context.
        if (closeWithinContext) {
            IntValue key = Values.newHeapInstance(IntValue.class);
            try (ExternalMapQueryContext<IntValue, String, ?> c = map.queryContext(key)) {
                c.updateLock().lock();
                try {
                    map.close();
                } catch (IllegalStateException expected) {
                    // expected
                }
            }
        }
    }

    private static final class CountedStringReader extends StringSizedReader {
        private transient MemoryLeaksTest memoryLeaksTest;
        private final String creationStackTrace;
        private final Cleaner cleaner;

        CountedStringReader(MemoryLeaksTest memoryLeaksTest) {
            this.memoryLeaksTest = memoryLeaksTest;
            this.memoryLeaksTest.serializerCount.incrementAndGet();
            this.memoryLeaksTest.serializers.add(new WeakReference<>(this));
            cleaner = CleanerUtils.createCleaner(this, this.memoryLeaksTest.serializerCount::decrementAndGet);
            try (StringWriter stringWriter = new StringWriter();
                 PrintWriter printWriter = new PrintWriter(stringWriter)) {
                new Exception().printStackTrace(printWriter);
                printWriter.flush();
                creationStackTrace = stringWriter.toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.memoryLeaksTest = memoryLeaksTest;
        }

        @Override
        public CountedStringReader copy() {
            return new CountedStringReader(this.memoryLeaksTest);
        }
    }
}