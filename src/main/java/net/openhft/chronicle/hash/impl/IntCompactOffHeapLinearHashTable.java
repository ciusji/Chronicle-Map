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

package net.openhft.chronicle.hash.impl;

import net.openhft.chronicle.core.OS;

import static net.openhft.chronicle.assertions.AssertUtil.SKIP_ASSERTIONS;
import static net.openhft.chronicle.map.internal.InternalAssertUtil.assertAddress;
import static net.openhft.chronicle.map.internal.InternalAssertUtil.assertPosition;

public final class IntCompactOffHeapLinearHashTable extends CompactOffHeapLinearHashTable {

    private static final long SCALE = 4L;

    /**
     * Must not store {@code h} in a field, to avoid memory leaks.
     *
     * @see net.openhft.chronicle.hash.impl.stage.hash.Chaining#initMap
     */
    IntCompactOffHeapLinearHashTable(VanillaChronicleHash h) {
        super(h);
    }

    @Override
    long indexToPos(long index) {
        return index * SCALE;
    }

    @Override
    public long step(long pos) {
        return (pos + SCALE) & capacityMask2;
    }

    @Override
    public long stepBack(long pos) {
        return (pos - SCALE) & capacityMask2;
    }

    @Override
    public long readEntry(final long address,
                          final long pos) {
        assert SKIP_ASSERTIONS || assertAddress(address);
        assert SKIP_ASSERTIONS || assertPosition(pos);
        return OS.memory().readInt(address + pos);
    }

    @Override
    public long readEntryVolatile(final long address,
                                  final long pos) {
        assert SKIP_ASSERTIONS || assertAddress(address);
        assert SKIP_ASSERTIONS || assertPosition(pos);
        return OS.memory().readVolatileInt(address + pos);
    }

    @Override
    public void writeEntryVolatile(final long address,
                                   final long pos,
                                   final long key,
                                   final long value) {
        assert SKIP_ASSERTIONS || assertAddress(address);
        assert SKIP_ASSERTIONS || assertPosition(pos);
        OS.memory().writeVolatileInt(address + pos, (int) entry(key, value));
    }

    @Override
    public void writeEntry(long address, long pos, long newEntry) {
        assert SKIP_ASSERTIONS || assertAddress(address);
        assert SKIP_ASSERTIONS || assertPosition(pos);
        OS.memory().writeInt(address + pos, (int) newEntry);
    }

    @Override
    public void clearEntry(long address, long pos) {
        assert SKIP_ASSERTIONS || assertAddress(address);
        assert SKIP_ASSERTIONS || assertPosition(pos);
        OS.memory().writeInt(address + pos, 0);
    }
}