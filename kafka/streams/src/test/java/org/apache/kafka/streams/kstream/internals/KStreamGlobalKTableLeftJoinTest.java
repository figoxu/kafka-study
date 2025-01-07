/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValueTimestamp;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.TopologyWrapper;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.GlobalKTable;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.test.MockApiProcessor;
import org.apache.kafka.test.MockApiProcessorSupplier;
import org.apache.kafka.test.MockValueJoiner;
import org.apache.kafka.test.StreamsTestUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class KStreamGlobalKTableLeftJoinTest {
    private static final KeyValueTimestamp[] EMPTY = new KeyValueTimestamp[0];

    private final String streamTopic = "streamTopic";
    private final String globalTableTopic = "globalTableTopic";
    private TestInputTopic<Integer, String> inputStreamTopic;
    private TestInputTopic<String, String> inputTableTopic;
    private final int[] expectedKeys = {0, 1, 2, 3};

    private MockApiProcessor<Integer, String, Void, Void> processor;
    private TopologyTestDriver driver;
    private StreamsBuilder builder;

    @BeforeEach
    public void setUp() {
        // use un-versioned store by default
        init(Optional.empty());
    }

    private void initWithVersionedStore(final long historyRetentionMs) {
        init(Optional.of(historyRetentionMs));
    }

    private void init(final Optional<Long> versionedStoreHistoryRetentionMs) {
        builder = new StreamsBuilder();
        final KStream<Integer, String> stream;
        final GlobalKTable<String, String> table; // value of stream optionally contains key of table
        final KeyValueMapper<Integer, String, String> keyMapper;

        final MockApiProcessorSupplier<Integer, String, Void, Void> supplier = new MockApiProcessorSupplier<>();
        final Consumed<Integer, String> streamConsumed = Consumed.with(Serdes.Integer(), Serdes.String());
        final Consumed<String, String> tableConsumed = Consumed.with(Serdes.String(), Serdes.String());
        stream = builder.stream(streamTopic, streamConsumed);
        if (versionedStoreHistoryRetentionMs.isPresent()) {
            table = builder.globalTable(globalTableTopic, tableConsumed, Materialized.as(
                Stores.persistentVersionedKeyValueStore("table", Duration.ofMillis(versionedStoreHistoryRetentionMs.get()))));
        } else {
            table = builder.globalTable(globalTableTopic, tableConsumed);
        }
        keyMapper = (key, value) -> {
            final String[] tokens = value.split(",");
            // Value is comma delimited. If second token is present, it's the key to the global ktable.
            // If not present, use null to indicate no match
            return tokens.length > 1 ? tokens[1] : null;
        };
        stream.leftJoin(table, keyMapper, MockValueJoiner.TOSTRING_JOINER).process(supplier);

        final Properties props = StreamsTestUtils.getStreamsConfig(Serdes.Integer(), Serdes.String());
        driver = new TopologyTestDriver(builder.build(), props);

        processor = supplier.theCapturedProcessor();

        // auto-advance timestamps by default
        inputStreamTopic = driver.createInputTopic(streamTopic, new IntegerSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ofMillis(1L));
        inputTableTopic = driver.createInputTopic(globalTableTopic, new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ofMillis(1L));
    }

    @AfterEach
    public void cleanup() {
        driver.close();
    }

    private void pushToStream(final int messageCount, final String valuePrefix, final boolean includeForeignKey, final boolean includeNullKey) {
        for (int i = 0; i < messageCount; i++) {
            String value = valuePrefix + expectedKeys[i];
            if (includeForeignKey) {
                value = value + ",FKey" + expectedKeys[i];
            }
            Integer key = expectedKeys[i];
            if (includeNullKey && i == 0) {
                key = null;
            }
            inputStreamTopic.pipeInput(key, value);
        }
    }

    private void pushToGlobalTable(final int messageCount, final String valuePrefix) {
        for (int i = 0; i < messageCount; i++) {
            inputTableTopic.pipeInput("FKey" + expectedKeys[i], valuePrefix + expectedKeys[i]);
        }
    }

    private void pushNullValueToGlobalTable(final int messageCount) {
        for (int i = 0; i < messageCount; i++) {
            inputTableTopic.pipeInput("FKey" + expectedKeys[i], (String) null);
        }
    }

    @Test
    public void shouldNotRequireCopartitioning() {
        final Collection<Set<String>> copartitionGroups =
            TopologyWrapper.getInternalTopologyBuilder(builder.build()).copartitionGroups();

        assertEquals(0, copartitionGroups.size(), "KStream-GlobalKTable joins do not need to be co-partitioned");
    }

    @Test
    public void shouldNotJoinWithEmptyGlobalTableOnStreamUpdates() {

        // push two items to the primary stream. the globalTable is empty

        pushToStream(2, "X", true, false);
        processor.checkAndClearProcessResult(new KeyValueTimestamp<>(0, "X0,FKey0+null", 0),
                new KeyValueTimestamp<>(1, "X1,FKey1+null", 1));
    }

    @Test
    public void shouldNotJoinOnGlobalTableUpdates() {

        // push two items to the primary stream. the globalTable is empty

        pushToStream(2, "X", true, false);
        processor.checkAndClearProcessResult(new KeyValueTimestamp<>(0, "X0,FKey0+null", 0),
                new KeyValueTimestamp<>(1, "X1,FKey1+null", 1));

        // push two items to the globalTable. this should not produce any item.

        pushToGlobalTable(2, "Y");
        processor.checkAndClearProcessResult(EMPTY);

        // push all four items to the primary stream. this should produce four items.

        pushToStream(4, "X", true, false);
        processor.checkAndClearProcessResult(new KeyValueTimestamp<>(0, "X0,FKey0+Y0", 2),
                new KeyValueTimestamp<>(1, "X1,FKey1+Y1", 3),
                new KeyValueTimestamp<>(2, "X2,FKey2+null", 4),
                new KeyValueTimestamp<>(3, "X3,FKey3+null", 5));

        // push all items to the globalTable. this should not produce any item

        pushToGlobalTable(4, "YY");
        processor.checkAndClearProcessResult(EMPTY);

        // push all four items to the primary stream. this should produce four items.

        pushToStream(4, "X", true, false);
        processor.checkAndClearProcessResult(new KeyValueTimestamp<>(0, "X0,FKey0+YY0", 6),
                new KeyValueTimestamp<>(1, "X1,FKey1+YY1", 7),
                new KeyValueTimestamp<>(2, "X2,FKey2+YY2", 8),
                new KeyValueTimestamp<>(3, "X3,FKey3+YY3", 9));

        // push all items to the globalTable. this should not produce any item

        pushToGlobalTable(4, "YYY");
        processor.checkAndClearProcessResult(EMPTY);
    }

    @Test
    public void shouldJoinRegardlessIfMatchFoundOnStreamUpdates() {

        // push two items to the globalTable. this should not produce any item.

        pushToGlobalTable(2, "Y");
        processor.checkAndClearProcessResult(EMPTY);

        // push all four items to the primary stream. this should produce four items.

        pushToStream(4, "X", true, false);
        processor.checkAndClearProcessResult(new KeyValueTimestamp<>(0, "X0,FKey0+Y0", 0),
                new KeyValueTimestamp<>(1, "X1,FKey1+Y1", 1),
                new KeyValueTimestamp<>(2, "X2,FKey2+null", 2),
                new KeyValueTimestamp<>(3, "X3,FKey3+null", 3));

    }

    @Test
    public void shouldClearGlobalTableEntryOnNullValueUpdates() {

        // push all four items to the globalTable. this should not produce any item.

        pushToGlobalTable(4, "Y");
        processor.checkAndClearProcessResult(EMPTY);

        // push all four items to the primary stream. this should produce four items.

        pushToStream(4, "X", true, false);
        processor.checkAndClearProcessResult(new KeyValueTimestamp<>(0, "X0,FKey0+Y0", 0),
                new KeyValueTimestamp<>(1, "X1,FKey1+Y1", 1),
                new KeyValueTimestamp<>(2, "X2,FKey2+Y2", 2),
                new KeyValueTimestamp<>(3, "X3,FKey3+Y3", 3));

        // push two items with null to the globalTable as deletes. this should not produce any item.

        pushNullValueToGlobalTable(2);
        processor.checkAndClearProcessResult(EMPTY);

        // push all four items to the primary stream. this should produce four items.

        pushToStream(4, "XX", true, false);
        processor.checkAndClearProcessResult(new KeyValueTimestamp<>(0, "XX0,FKey0+null", 4),
                new KeyValueTimestamp<>(1, "XX1,FKey1+null", 5),
                new KeyValueTimestamp<>(2, "XX2,FKey2+Y2", 6),
                new KeyValueTimestamp<>(3, "XX3,FKey3+Y3", 7));
    }

    @Test
    public void shouldNotJoinOnNullKeyMapperValues() {

        // push all items to the globalTable. this should not produce any item

        pushToGlobalTable(4, "Y");
        processor.checkAndClearProcessResult(EMPTY);

        // push all four items to the primary stream with no foreign key, resulting in null keyMapper values.
        // this should not produce any item.

        pushToStream(4, "XXX", false, false);
        processor.checkAndClearProcessResult(EMPTY);
    }

    @Test
    public void shouldJoinOnNullKeyWithNonNullKeyMapperValues() {
        // push four items to the globalTable. this should not produce any item.

        pushToGlobalTable(4, "Y");
        processor.checkAndClearProcessResult(EMPTY);

        // push all four items to the primary stream. this should produce four items.

        pushToStream(4, "X", true, true);
        processor.checkAndClearProcessResult(new KeyValueTimestamp<>(null, "X0,FKey0+Y0", 0),
                new KeyValueTimestamp<>(1, "X1,FKey1+Y1", 1),
                new KeyValueTimestamp<>(2, "X2,FKey2+Y2", 2),
                new KeyValueTimestamp<>(3, "X3,FKey3+Y3", 3));
    }
}