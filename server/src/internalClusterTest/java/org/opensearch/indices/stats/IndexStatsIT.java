/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.indices.stats;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.lucene.tests.util.LuceneTestCase.SuppressCodecs;
import org.opensearch.action.DocWriteResponse;
import org.opensearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.forcemerge.ForceMergeResponse;
import org.opensearch.action.admin.indices.stats.CommonStats;
import org.opensearch.action.admin.indices.stats.CommonStatsFlags;
import org.opensearch.action.admin.indices.stats.CommonStatsFlags.Flag;
import org.opensearch.action.admin.indices.stats.IndexStats;
import org.opensearch.action.admin.indices.stats.IndicesStatsRequest;
import org.opensearch.action.admin.indices.stats.IndicesStatsRequestBuilder;
import org.opensearch.action.admin.indices.stats.IndicesStatsResponse;
import org.opensearch.action.admin.indices.stats.ShardStats;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexRequestBuilder;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.search.SearchType;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.UUIDs;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.support.DefaultShardOperationFailedException;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.index.IndexModule;
import org.opensearch.index.IndexService;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.MergeSchedulerConfig;
import org.opensearch.index.TieredMergePolicyProvider;
import org.opensearch.index.VersionType;
import org.opensearch.index.cache.query.QueryCacheStats;
import org.opensearch.index.engine.VersionConflictEngineException;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.remote.RemoteSegmentStats;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.translog.RemoteTranslogStats;
import org.opensearch.index.translog.Translog;
import org.opensearch.indices.IndicesQueryCache;
import org.opensearch.indices.IndicesRequestCache;
import org.opensearch.indices.IndicesService;
import org.opensearch.indices.replication.common.ReplicationType;
import org.opensearch.plugins.Plugin;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.test.InternalSettingsPlugin;
import org.opensearch.test.OpenSearchIntegTestCase.ClusterScope;
import org.opensearch.test.OpenSearchIntegTestCase.Scope;
import org.opensearch.test.ParameterizedStaticSettingsOpenSearchIntegTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_REPLICAS;
import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.opensearch.indices.IndicesService.CLUSTER_REPLICATION_TYPE_SETTING;
import static org.opensearch.search.SearchService.CLUSTER_CONCURRENT_SEGMENT_SEARCH_SETTING;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAllSuccessful;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.emptyCollectionOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@ClusterScope(scope = Scope.SUITE, numDataNodes = 2, numClientNodes = 0)
@SuppressCodecs("*") // requires custom completion format
public class IndexStatsIT extends ParameterizedStaticSettingsOpenSearchIntegTestCase {
    public IndexStatsIT(Settings settings) {
        super(settings);
    }

    @ParametersFactory
    public static Collection<Object[]> parameters() {
        return Arrays.asList(
            new Object[] { Settings.builder().put(CLUSTER_CONCURRENT_SEGMENT_SEARCH_SETTING.getKey(), false).build() },
            new Object[] { Settings.builder().put(CLUSTER_CONCURRENT_SEGMENT_SEARCH_SETTING.getKey(), true).build() },
            new Object[] { Settings.builder().put(CLUSTER_REPLICATION_TYPE_SETTING.getKey(), ReplicationType.SEGMENT).build() }
        );
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(InternalSettingsPlugin.class);
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        // Filter/Query cache is cleaned periodically, default is 60s, so make sure it runs often. Thread.sleep for 60s is bad
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put(IndicesService.INDICES_CACHE_CLEAN_INTERVAL_SETTING.getKey(), "1ms")
            .put(IndicesQueryCache.INDICES_QUERIES_CACHE_ALL_SEGMENTS_SETTING.getKey(), true)
            .build();
    }

    @Override
    public Settings indexSettings() {
        return Settings.builder()
            .put(super.indexSettings())
            .put(IndexModule.INDEX_QUERY_CACHE_EVERYTHING_SETTING.getKey(), true)
            .put(IndexModule.INDEX_QUERY_CACHE_ENABLED_SETTING.getKey(), true)
            .put(IndexSettings.INDEX_SOFT_DELETES_RETENTION_OPERATIONS_SETTING.getKey(), 0)
            .build();
    }

    private Settings.Builder settingsBuilder() {
        return Settings.builder().put(indexSettings());
    }

    public void testFieldDataStats() throws InterruptedException {
        assertAcked(
            client().admin()
                .indices()
                .prepareCreate("test")
                .setSettings(settingsBuilder().put("index.number_of_shards", 2))
                .setMapping("field", "type=text,fielddata=true", "field2", "type=text,fielddata=true")
                .get()
        );
        ensureGreen();
        client().prepareIndex("test").setId("1").setSource("field", "value1", "field2", "value1").execute().actionGet();
        client().prepareIndex("test").setId("2").setSource("field", "value2", "field2", "value2").execute().actionGet();
        refreshAndWaitForReplication();
        indexRandomForConcurrentSearch("test");

        NodesStatsResponse nodesStats = client().admin().cluster().prepareNodesStats("data:true").setIndices(true).execute().actionGet();
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getFieldData().getMemorySizeInBytes() + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getFieldData()
                .getMemorySizeInBytes(),
            equalTo(0L)
        );
        IndicesStatsResponse indicesStats = client().admin()
            .indices()
            .prepareStats("test")
            .clear()
            .setFieldData(true)
            .execute()
            .actionGet();
        assertThat(indicesStats.getTotal().getFieldData().getMemorySizeInBytes(), equalTo(0L));

        // sort to load it to field data...
        client().prepareSearch().addSort("field", SortOrder.ASC).execute().actionGet();
        client().prepareSearch().addSort("field", SortOrder.ASC).execute().actionGet();

        nodesStats = client().admin().cluster().prepareNodesStats("data:true").setIndices(true).execute().actionGet();
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getFieldData().getMemorySizeInBytes() + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getFieldData()
                .getMemorySizeInBytes(),
            greaterThan(0L)
        );
        indicesStats = client().admin().indices().prepareStats("test").clear().setFieldData(true).execute().actionGet();
        assertThat(indicesStats.getTotal().getFieldData().getMemorySizeInBytes(), greaterThan(0L));

        // sort to load it to field data...
        client().prepareSearch().addSort("field2", SortOrder.ASC).execute().actionGet();
        client().prepareSearch().addSort("field2", SortOrder.ASC).execute().actionGet();

        // now check the per field stats
        nodesStats = client().admin()
            .cluster()
            .prepareNodesStats("data:true")
            .setIndices(new CommonStatsFlags().set(CommonStatsFlags.Flag.FieldData, true).fieldDataFields("*"))
            .execute()
            .actionGet();
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getFieldData().getMemorySizeInBytes() + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getFieldData()
                .getMemorySizeInBytes(),
            greaterThan(0L)
        );
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getFieldData().getFields().get("field") + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getFieldData()
                .getFields()
                .get("field"),
            greaterThan(0L)
        );
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getFieldData().getFields().get("field") + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getFieldData()
                .getFields()
                .get("field"),
            lessThan(
                nodesStats.getNodes().get(0).getIndices().getFieldData().getMemorySizeInBytes() + nodesStats.getNodes()
                    .get(1)
                    .getIndices()
                    .getFieldData()
                    .getMemorySizeInBytes()
            )
        );

        indicesStats = client().admin()
            .indices()
            .prepareStats("test")
            .clear()
            .setFieldData(true)
            .setFieldDataFields("*")
            .execute()
            .actionGet();
        assertThat(indicesStats.getTotal().getFieldData().getMemorySizeInBytes(), greaterThan(0L));
        assertThat(indicesStats.getTotal().getFieldData().getFields().get("field"), greaterThan(0L));
        assertThat(
            indicesStats.getTotal().getFieldData().getFields().get("field"),
            lessThan(indicesStats.getTotal().getFieldData().getMemorySizeInBytes())
        );

        client().admin().indices().prepareClearCache().setFieldDataCache(true).execute().actionGet();
        nodesStats = client().admin().cluster().prepareNodesStats("data:true").setIndices(true).execute().actionGet();
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getFieldData().getMemorySizeInBytes() + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getFieldData()
                .getMemorySizeInBytes(),
            equalTo(0L)
        );
        indicesStats = client().admin().indices().prepareStats("test").clear().setFieldData(true).execute().actionGet();
        assertThat(indicesStats.getTotal().getFieldData().getMemorySizeInBytes(), equalTo(0L));

    }

    public void testClearAllCaches() throws Exception {
        assertAcked(
            client().admin()
                .indices()
                .prepareCreate("test")
                .setSettings(settingsBuilder().put("index.number_of_replicas", 0).put("index.number_of_shards", 2))
                .setMapping("field", "type=text,fielddata=true")
                .get()
        );
        ensureGreen();
        client().admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client().prepareIndex("test").setId("1").setSource("field", "value1").execute().actionGet();
        client().prepareIndex("test").setId("2").setSource("field", "value2").execute().actionGet();
        refreshAndWaitForReplication();
        indexRandomForConcurrentSearch("test");

        NodesStatsResponse nodesStats = client().admin().cluster().prepareNodesStats("data:true").setIndices(true).execute().actionGet();
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getFieldData().getMemorySizeInBytes() + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getFieldData()
                .getMemorySizeInBytes(),
            equalTo(0L)
        );
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getQueryCache().getMemorySizeInBytes() + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getQueryCache()
                .getMemorySizeInBytes(),
            equalTo(0L)
        );

        IndicesStatsResponse indicesStats = client().admin()
            .indices()
            .prepareStats("test")
            .clear()
            .setFieldData(true)
            .setQueryCache(true)
            .execute()
            .actionGet();
        assertThat(indicesStats.getTotal().getFieldData().getMemorySizeInBytes(), equalTo(0L));
        assertThat(indicesStats.getTotal().getQueryCache().getMemorySizeInBytes(), equalTo(0L));

        // sort to load it to field data and filter to load filter cache
        client().prepareSearch()
            .setPostFilter(QueryBuilders.termQuery("field", "value1"))
            .addSort("field", SortOrder.ASC)
            .execute()
            .actionGet();
        client().prepareSearch()
            .setPostFilter(QueryBuilders.termQuery("field", "value2"))
            .addSort("field", SortOrder.ASC)
            .execute()
            .actionGet();

        nodesStats = client().admin().cluster().prepareNodesStats("data:true").setIndices(true).execute().actionGet();
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getFieldData().getMemorySizeInBytes() + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getFieldData()
                .getMemorySizeInBytes(),
            greaterThan(0L)
        );
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getQueryCache().getMemorySizeInBytes() + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getQueryCache()
                .getMemorySizeInBytes(),
            greaterThan(0L)
        );

        indicesStats = client().admin().indices().prepareStats("test").clear().setFieldData(true).setQueryCache(true).execute().actionGet();
        assertThat(indicesStats.getTotal().getFieldData().getMemorySizeInBytes(), greaterThan(0L));
        assertThat(indicesStats.getTotal().getQueryCache().getMemorySizeInBytes(), greaterThan(0L));

        client().admin().indices().prepareClearCache().execute().actionGet();
        Thread.sleep(100); // Make sure the filter cache entries have been removed...
        nodesStats = client().admin().cluster().prepareNodesStats("data:true").setIndices(true).execute().actionGet();
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getFieldData().getMemorySizeInBytes() + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getFieldData()
                .getMemorySizeInBytes(),
            equalTo(0L)
        );
        assertThat(
            nodesStats.getNodes().get(0).getIndices().getQueryCache().getMemorySizeInBytes() + nodesStats.getNodes()
                .get(1)
                .getIndices()
                .getQueryCache()
                .getMemorySizeInBytes(),
            equalTo(0L)
        );

        indicesStats = client().admin().indices().prepareStats("test").clear().setFieldData(true).setQueryCache(true).execute().actionGet();
        assertThat(indicesStats.getTotal().getFieldData().getMemorySizeInBytes(), equalTo(0L));
        assertThat(indicesStats.getTotal().getQueryCache().getMemorySizeInBytes(), equalTo(0L));
    }

    public void testQueryCache() throws Exception {
        assertAcked(
            client().admin()
                .indices()
                .prepareCreate("idx")
                .setSettings(Settings.builder().put(IndicesRequestCache.INDEX_CACHE_REQUEST_ENABLED_SETTING.getKey(), true))
                .get()
        );
        ensureGreen();

        // index docs until we have at least one doc on each shard, otherwise, our tests will not work
        // since refresh will not refresh anything on a shard that has 0 docs and its search response get cached
        int pageDocs = randomIntBetween(2, 100);
        int numDocs = 0;
        int counter = 0;
        while (true) {
            IndexRequestBuilder[] builders = new IndexRequestBuilder[pageDocs];
            for (int i = 0; i < pageDocs; ++i) {
                builders[i] = client().prepareIndex("idx")
                    .setId(Integer.toString(counter++))
                    .setSource(jsonBuilder().startObject().field("common", "field").field("str_value", "s" + i).endObject());
            }
            indexRandom(true, builders);
            numDocs += pageDocs;

            boolean allHaveDocs = true;
            for (ShardStats stats : client().admin().indices().prepareStats("idx").setDocs(true).get().getShards()) {
                if (stats.getStats().getDocs().getCount() == 0) {
                    allHaveDocs = false;
                    break;
                }
            }

            if (allHaveDocs) {
                break;
            }
        }

        assertThat(
            client().admin().indices().prepareStats("idx").setRequestCache(true).get().getTotal().getRequestCache().getMemorySizeInBytes(),
            equalTo(0L)
        );
        assertThat(
            client().admin().indices().prepareStats("idx").setRequestCache(true).get().getTotal().getRequestCache().getHitCount(),
            equalTo(0L)
        );
        assertThat(
            client().admin().indices().prepareStats("idx").setRequestCache(true).get().getTotal().getRequestCache().getMissCount(),
            equalTo(0L)
        );
        for (int i = 0; i < 10; i++) {
            assertThat(
                client().prepareSearch("idx").setSearchType(SearchType.QUERY_THEN_FETCH).setSize(0).get().getHits().getTotalHits().value(),
                equalTo((long) numDocs)
            );
            assertThat(
                client().admin()
                    .indices()
                    .prepareStats("idx")
                    .setRequestCache(true)
                    .get()
                    .getTotal()
                    .getRequestCache()
                    .getMemorySizeInBytes(),
                greaterThan(0L)
            );
        }
        assertThat(
            client().admin().indices().prepareStats("idx").setRequestCache(true).get().getTotal().getRequestCache().getHitCount(),
            greaterThan(0L)
        );
        assertThat(
            client().admin().indices().prepareStats("idx").setRequestCache(true).get().getTotal().getRequestCache().getMissCount(),
            greaterThan(0L)
        );

        // index the data again...
        IndexRequestBuilder[] builders = new IndexRequestBuilder[numDocs];
        for (int i = 0; i < numDocs; ++i) {
            builders[i] = client().prepareIndex("idx")
                .setId(Integer.toString(i))
                .setSource(jsonBuilder().startObject().field("common", "field").field("str_value", "s" + i).endObject());
        }
        indexRandom(true, builders);
        refresh();
        assertBusy(() -> {
            assertThat(
                client().admin()
                    .indices()
                    .prepareStats("idx")
                    .setRequestCache(true)
                    .get()
                    .getTotal()
                    .getRequestCache()
                    .getMemorySizeInBytes(),
                equalTo(0L)
            );
        });

        for (int i = 0; i < 10; i++) {
            assertThat(
                client().prepareSearch("idx").setSearchType(SearchType.QUERY_THEN_FETCH).setSize(0).get().getHits().getTotalHits().value(),
                equalTo((long) numDocs)
            );
            assertThat(
                client().admin()
                    .indices()
                    .prepareStats("idx")
                    .setRequestCache(true)
                    .get()
                    .getTotal()
                    .getRequestCache()
                    .getMemorySizeInBytes(),
                greaterThan(0L)
            );
        }

        client().admin().indices().prepareClearCache().setRequestCache(true).get(); // clean the cache
        assertThat(
            client().admin().indices().prepareStats("idx").setRequestCache(true).get().getTotal().getRequestCache().getMemorySizeInBytes(),
            equalTo(0L)
        );

        // test explicit request parameter

        assertThat(
            client().prepareSearch("idx")
                .setSearchType(SearchType.QUERY_THEN_FETCH)
                .setSize(0)
                .setRequestCache(false)
                .get()
                .getHits()
                .getTotalHits()
                .value(),
            equalTo((long) numDocs)
        );
        assertThat(
            client().admin().indices().prepareStats("idx").setRequestCache(true).get().getTotal().getRequestCache().getMemorySizeInBytes(),
            equalTo(0L)
        );

        assertThat(
            client().prepareSearch("idx")
                .setSearchType(SearchType.QUERY_THEN_FETCH)
                .setSize(0)
                .setRequestCache(true)
                .get()
                .getHits()
                .getTotalHits()
                .value(),
            equalTo((long) numDocs)
        );
        assertThat(
            client().admin().indices().prepareStats("idx").setRequestCache(true).get().getTotal().getRequestCache().getMemorySizeInBytes(),
            greaterThan(0L)
        );

        // set the index level setting to false, and see that the reverse works

        client().admin().indices().prepareClearCache().setRequestCache(true).get(); // clean the cache
        assertAcked(
            client().admin()
                .indices()
                .prepareUpdateSettings("idx")
                .setSettings(Settings.builder().put(IndicesRequestCache.INDEX_CACHE_REQUEST_ENABLED_SETTING.getKey(), false))
        );

        assertThat(
            client().prepareSearch("idx").setSearchType(SearchType.QUERY_THEN_FETCH).setSize(0).get().getHits().getTotalHits().value(),
            equalTo((long) numDocs)
        );
        assertThat(
            client().admin().indices().prepareStats("idx").setRequestCache(true).get().getTotal().getRequestCache().getMemorySizeInBytes(),
            equalTo(0L)
        );

        assertThat(
            client().prepareSearch("idx")
                .setSearchType(SearchType.QUERY_THEN_FETCH)
                .setSize(0)
                .setRequestCache(true)
                .get()
                .getHits()
                .getTotalHits()
                .value(),
            equalTo((long) numDocs)
        );
        assertThat(
            client().admin().indices().prepareStats("idx").setRequestCache(true).get().getTotal().getRequestCache().getMemorySizeInBytes(),
            greaterThan(0L)
        );
    }

    public void testNonThrottleStats() throws Exception {
        assertAcked(
            prepareCreate("test").setSettings(
                settingsBuilder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, "1")
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, "0")
                    .put(TieredMergePolicyProvider.INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE_SETTING.getKey(), "2")
                    .put(TieredMergePolicyProvider.INDEX_MERGE_POLICY_SEGMENTS_PER_TIER_SETTING.getKey(), "2")
                    .put(MergeSchedulerConfig.MAX_THREAD_COUNT_SETTING.getKey(), "1")
                    .put(MergeSchedulerConfig.MAX_MERGE_COUNT_SETTING.getKey(), "10000")
            )
        );
        ensureGreen();
        long termUpto = 0;
        IndicesStatsResponse stats;
        // Provoke slowish merging by making many unique terms:
        for (int i = 0; i < 100; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 100; j++) {
                sb.append(' ');
                sb.append(termUpto++);
                sb.append(" some random text that keeps repeating over and over again hambone");
            }
            client().prepareIndex("test").setId("" + termUpto).setSource("field" + (i % 10), sb.toString()).get();
        }
        refresh();
        stats = client().admin().indices().prepareStats().execute().actionGet();
        // nodesStats = client().admin().cluster().prepareNodesStats().setIndices(true).get();

        stats = client().admin().indices().prepareStats().execute().actionGet();
        assertThat(stats.getPrimaries().getIndexing().getTotal().getThrottleTime().millis(), equalTo(0L));
    }

    public void testThrottleStats() throws Exception {
        assertAcked(
            prepareCreate("test").setSettings(
                settingsBuilder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, "1")
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, "0")
                    .put(TieredMergePolicyProvider.INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE_SETTING.getKey(), "2")
                    .put(TieredMergePolicyProvider.INDEX_MERGE_POLICY_SEGMENTS_PER_TIER_SETTING.getKey(), "2")
                    .put(MergeSchedulerConfig.MAX_THREAD_COUNT_SETTING.getKey(), "1")
                    .put(MergeSchedulerConfig.MAX_MERGE_COUNT_SETTING.getKey(), "1")
                    .put(IndexSettings.INDEX_TRANSLOG_DURABILITY_SETTING.getKey(), Translog.Durability.ASYNC.name())
            )
        );
        ensureGreen();
        long termUpto = 0;
        IndicesStatsResponse stats;
        // make sure we see throttling kicking in:
        boolean done = false;
        long start = System.currentTimeMillis();
        while (!done) {
            for (int i = 0; i < 100; i++) {
                // Provoke slowish merging by making many unique terms:
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < 100; j++) {
                    sb.append(' ');
                    sb.append(termUpto++);
                }
                client().prepareIndex("test").setId("" + termUpto).setSource("field" + (i % 10), sb.toString()).get();
                if (i % 2 == 0) {
                    refresh();
                }
            }
            refresh();
            stats = client().admin().indices().prepareStats().execute().actionGet();
            // nodesStats = client().admin().cluster().prepareNodesStats().setIndices(true).get();
            done = stats.getPrimaries().getIndexing().getTotal().getThrottleTime().millis() > 0;
            if (System.currentTimeMillis() - start > 300 * 1000) { // Wait 5 minutes for throttling to kick in
                fail("index throttling didn't kick in after 5 minutes of intense merging");
            }
        }

        // Optimize & flush and wait; else we sometimes get a "Delete Index failed - not acked"
        // when OpenSearchIntegTestCase.after tries to remove indices created by the test:
        logger.info("test: now optimize");
        client().admin().indices().prepareForceMerge("test").get();
        flush();
        logger.info("test: test done");
    }

    public void testSimpleStats() throws Exception {
        createIndex("test1", "test2");
        ensureGreen();

        client().prepareIndex("test1").setId(Integer.toString(1)).setSource("field", "value").execute().actionGet();
        client().prepareIndex("test1").setId(Integer.toString(2)).setSource("field", "value").execute().actionGet();
        client().prepareIndex("test2").setId(Integer.toString(1)).setSource("field", "value").execute().actionGet();
        refreshAndWaitForReplication();

        NumShards test1 = getNumShards("test1");
        long test1ExpectedWrites = 2 * test1.dataCopies;
        NumShards test2 = getNumShards("test2");
        long test2ExpectedWrites = test2.dataCopies;
        long totalExpectedWrites = test1ExpectedWrites + test2ExpectedWrites;

        IndicesStatsResponse stats = client().admin().indices().prepareStats().execute().actionGet();
        assertThat(stats.getPrimaries().getDocs().getCount(), equalTo(3L));
        assertThat(stats.getTotal().getDocs().getCount(), equalTo(totalExpectedWrites));
        assertThat(stats.getPrimaries().getIndexing().getTotal().getIndexCount(), equalTo(3L));
        assertThat(stats.getPrimaries().getIndexing().getTotal().getIndexFailedCount(), equalTo(0L));
        assertThat(stats.getPrimaries().getIndexing().getTotal().isThrottled(), equalTo(false));
        assertThat(stats.getPrimaries().getIndexing().getTotal().getThrottleTime().millis(), equalTo(0L));

        // This assert should not be done on segrep enabled indices because we are asserting Indexing/Write operations count on
        // all primary and replica shards. But in case of segrep, Indexing/Write operation don't happen on replica shards. So we can
        // ignore this assert check for segrep enabled indices.
        if (isSegmentReplicationEnabledForIndex("test1") == false && isSegmentReplicationEnabledForIndex("test2") == false) {
            assertThat(stats.getTotal().getIndexing().getTotal().getIndexCount(), equalTo(totalExpectedWrites));
        }
        assertThat(stats.getTotal().getStore(), notNullValue());
        assertThat(stats.getTotal().getMerge(), notNullValue());
        assertThat(stats.getTotal().getFlush(), notNullValue());
        assertThat(stats.getTotal().getRefresh(), notNullValue());

        assertThat(stats.getIndex("test1").getPrimaries().getDocs().getCount(), equalTo(2L));
        assertThat(stats.getIndex("test1").getTotal().getDocs().getCount(), equalTo(test1ExpectedWrites));
        assertThat(stats.getIndex("test1").getPrimaries().getStore(), notNullValue());
        assertThat(stats.getIndex("test1").getPrimaries().getMerge(), notNullValue());
        assertThat(stats.getIndex("test1").getPrimaries().getFlush(), notNullValue());
        assertThat(stats.getIndex("test1").getPrimaries().getRefresh(), notNullValue());

        assertThat(stats.getIndex("test2").getPrimaries().getDocs().getCount(), equalTo(1L));
        assertThat(stats.getIndex("test2").getTotal().getDocs().getCount(), equalTo(test2ExpectedWrites));

        // make sure that number of requests in progress is 0
        assertThat(stats.getIndex("test1").getTotal().getIndexing().getTotal().getIndexCurrent(), equalTo(0L));
        assertThat(stats.getIndex("test1").getTotal().getIndexing().getTotal().getDeleteCurrent(), equalTo(0L));
        assertThat(stats.getIndex("test1").getTotal().getSearch().getTotal().getFetchCurrent(), equalTo(0L));
        assertThat(stats.getIndex("test1").getTotal().getSearch().getTotal().getQueryCurrent(), equalTo(0L));
        assertThat(stats.getIndex("test2").getTotal().getIndexing().getTotal().getIndexCurrent(), equalTo(0L));
        assertThat(stats.getIndex("test2").getTotal().getIndexing().getTotal().getDeleteCurrent(), equalTo(0L));
        assertThat(stats.getIndex("test2").getTotal().getSearch().getTotal().getFetchCurrent(), equalTo(0L));
        assertThat(stats.getIndex("test2").getTotal().getSearch().getTotal().getQueryCurrent(), equalTo(0L));

        // check flags
        stats = client().admin().indices().prepareStats().clear().setFlush(true).setRefresh(true).setMerge(true).execute().actionGet();

        assertThat(stats.getTotal().getDocs(), nullValue());
        assertThat(stats.getTotal().getStore(), nullValue());
        assertThat(stats.getTotal().getIndexing(), nullValue());
        assertThat(stats.getTotal().getMerge(), notNullValue());
        assertThat(stats.getTotal().getFlush(), notNullValue());
        assertThat(stats.getTotal().getRefresh(), notNullValue());

        // check get
        GetResponse getResponse = client().prepareGet("test2", "1").execute().actionGet();
        assertThat(getResponse.isExists(), equalTo(true));

        stats = client().admin().indices().prepareStats().execute().actionGet();
        assertThat(stats.getTotal().getGet().getCount(), equalTo(1L));
        assertThat(stats.getTotal().getGet().getExistsCount(), equalTo(1L));
        assertThat(stats.getTotal().getGet().getMissingCount(), equalTo(0L));

        // missing get
        getResponse = client().prepareGet("test2", "2").execute().actionGet();
        assertThat(getResponse.isExists(), equalTo(false));

        stats = client().admin().indices().prepareStats().execute().actionGet();
        assertThat(stats.getTotal().getGet().getCount(), equalTo(2L));
        assertThat(stats.getTotal().getGet().getExistsCount(), equalTo(1L));
        assertThat(stats.getTotal().getGet().getMissingCount(), equalTo(1L));

        // clear all
        stats = client().admin()
            .indices()
            .prepareStats()
            .setDocs(false)
            .setStore(false)
            .setIndexing(false)
            .setFlush(true)
            .setRefresh(true)
            .setMerge(true)
            .clear() // reset defaults
            .execute()
            .actionGet();

        assertThat(stats.getTotal().getDocs(), nullValue());
        assertThat(stats.getTotal().getStore(), nullValue());
        assertThat(stats.getTotal().getIndexing(), nullValue());
        assertThat(stats.getTotal().getGet(), nullValue());
        assertThat(stats.getTotal().getSearch(), nullValue());

        // index failed
        try {
            client().prepareIndex("test1")
                .setId(Integer.toString(1))
                .setSource("field", "value")
                .setVersion(1)
                .setVersionType(VersionType.EXTERNAL)
                .execute()
                .actionGet();
            fail("Expected a version conflict");
        } catch (VersionConflictEngineException e) {}
        try {
            client().prepareIndex("test2")
                .setId(Integer.toString(1))
                .setSource("field", "value")
                .setVersion(1)
                .setVersionType(VersionType.EXTERNAL)
                .execute()
                .actionGet();
            fail("Expected a version conflict");
        } catch (VersionConflictEngineException e) {}
        try {
            client().prepareIndex("test2")
                .setId(Integer.toString(1))
                .setSource("field", "value")
                .setVersion(1)
                .setVersionType(VersionType.EXTERNAL)
                .execute()
                .actionGet();
            fail("Expected a version conflict");
        } catch (VersionConflictEngineException e) {}

        stats = client().admin().indices().prepareStats().execute().actionGet();
        assertThat(stats.getIndex("test2").getPrimaries().getIndexing().getTotal().getIndexFailedCount(), equalTo(2L));
        assertThat(stats.getPrimaries().getIndexing().getTotal().getIndexFailedCount(), equalTo(3L));
    }

    public void testMergeStats() {
        assertAcked(prepareCreate("test_index"));

        ensureGreen();

        // clear all
        IndicesStatsResponse stats = client().admin()
            .indices()
            .prepareStats()
            .setDocs(false)
            .setStore(false)
            .setIndexing(false)
            .setFlush(true)
            .setRefresh(true)
            .setMerge(true)
            .clear() // reset defaults
            .execute()
            .actionGet();

        assertThat(stats.getTotal().getDocs(), nullValue());
        assertThat(stats.getTotal().getStore(), nullValue());
        assertThat(stats.getTotal().getIndexing(), nullValue());
        assertThat(stats.getTotal().getGet(), nullValue());
        assertThat(stats.getTotal().getSearch(), nullValue());

        for (int i = 0; i < 20; i++) {
            client().prepareIndex("test_index").setId(Integer.toString(i)).setSource("field", "value").execute().actionGet();
            client().admin().indices().prepareFlush().execute().actionGet();
        }
        client().admin().indices().prepareForceMerge().setMaxNumSegments(1).execute().actionGet();
        stats = client().admin().indices().prepareStats().setMerge(true).execute().actionGet();

        refreshAndWaitForReplication();
        assertThat(stats.getTotal().getMerge(), notNullValue());
        assertThat(stats.getTotal().getMerge().getTotal(), greaterThan(0L));
    }

    public void testSegmentsStats() {
        assertAcked(
            prepareCreate("test_index").setSettings(
                Settings.builder()
                    .put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))
                    .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), TimeValue.MINUS_ONE)
            )
        );
        ensureGreen();

        NumShards test1 = getNumShards("test_index");

        for (int i = 0; i < 100; i++) {
            index("test_index", "_doc", Integer.toString(i), "field", "value");
        }

        IndicesStatsResponse stats = client().admin().indices().prepareStats().setSegments(true).get();
        assertThat(stats.getTotal().getSegments().getIndexWriterMemoryInBytes(), greaterThan(0L));
        assertThat(stats.getTotal().getSegments().getVersionMapMemoryInBytes(), greaterThan(0L));

        client().admin().indices().prepareFlush().get();
        client().admin().indices().prepareForceMerge().setMaxNumSegments(1).execute().actionGet();
        refreshAndWaitForReplication();
        stats = client().admin().indices().prepareStats().setSegments(true).get();

        assertThat(stats.getTotal().getSegments(), notNullValue());
        assertThat(stats.getTotal().getSegments().getCount(), equalTo((long) test1.totalNumShards));
    }

    public void testAllFlags() throws Exception {
        // rely on 1 replica for this tests
        assertAcked(prepareCreate("test_index"));
        createIndex("test_index_2");

        ensureGreen();

        client().prepareIndex("test_index").setId(Integer.toString(1)).setSource("field", "value").execute().actionGet();
        client().prepareIndex("test_index").setId(Integer.toString(2)).setSource("field", "value").execute().actionGet();
        client().prepareIndex("test_index_2").setId(Integer.toString(1)).setSource("field", "value").execute().actionGet();

        refreshAndWaitForReplication();
        IndicesStatsRequestBuilder builder = client().admin().indices().prepareStats();
        Flag[] values = CommonStatsFlags.Flag.values();
        for (Flag flag : values) {
            set(flag, builder, false);
        }

        IndicesStatsResponse stats = builder.execute().actionGet();
        for (Flag flag : values) {
            assertThat(isSet(flag, stats.getPrimaries()), equalTo(false));
            assertThat(isSet(flag, stats.getTotal()), equalTo(false));
        }

        for (Flag flag : values) {
            set(flag, builder, true);
        }
        stats = builder.execute().actionGet();
        for (Flag flag : values) {
            assertThat(isSet(flag, stats.getPrimaries()), equalTo(true));
            assertThat(isSet(flag, stats.getTotal()), equalTo(true));
        }
        Random random = random();
        EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
        for (Flag flag : values) {
            if (random.nextBoolean()) {
                flags.add(flag);
            }
        }

        for (Flag flag : values) {
            set(flag, builder, false); // clear all
        }

        for (Flag flag : flags) { // set the flags
            set(flag, builder, true);
        }
        stats = builder.execute().actionGet();
        for (Flag flag : flags) { // check the flags
            assertThat(isSet(flag, stats.getPrimaries()), equalTo(true));
            assertThat(isSet(flag, stats.getTotal()), equalTo(true));
        }

        for (Flag flag : EnumSet.complementOf(flags)) { // check the complement
            assertThat(isSet(flag, stats.getPrimaries()), equalTo(false));
            assertThat(isSet(flag, stats.getTotal()), equalTo(false));
        }

    }

    public void testEncodeDecodeCommonStats() throws IOException {
        CommonStatsFlags flags = new CommonStatsFlags();
        Flag[] values = CommonStatsFlags.Flag.values();
        assertThat(flags.anySet(), equalTo(true));

        for (Flag flag : values) {
            flags.set(flag, false);
        }
        assertThat(flags.anySet(), equalTo(false));
        for (Flag flag : values) {
            flags.set(flag, true);
        }
        assertThat(flags.anySet(), equalTo(true));
        Random random = random();
        flags.set(values[random.nextInt(values.length)], false);
        assertThat(flags.anySet(), equalTo(true));

        {
            BytesStreamOutput out = new BytesStreamOutput();
            flags.writeTo(out);
            out.close();
            BytesReference bytes = out.bytes();
            CommonStatsFlags readStats = new CommonStatsFlags(bytes.streamInput());
            for (Flag flag : values) {
                assertThat(flags.isSet(flag), equalTo(readStats.isSet(flag)));
            }
        }

        {
            for (Flag flag : values) {
                flags.set(flag, random.nextBoolean());
            }
            BytesStreamOutput out = new BytesStreamOutput();
            flags.writeTo(out);
            out.close();
            BytesReference bytes = out.bytes();
            CommonStatsFlags readStats = new CommonStatsFlags(bytes.streamInput());
            for (Flag flag : values) {
                assertThat(flags.isSet(flag), equalTo(readStats.isSet(flag)));
            }
        }
    }

    public void testFlagOrdinalOrder() {
        Flag[] flags = new Flag[] {
            Flag.Store,
            Flag.Indexing,
            Flag.Get,
            Flag.Search,
            Flag.Merge,
            Flag.Flush,
            Flag.Refresh,
            Flag.QueryCache,
            Flag.FieldData,
            Flag.Docs,
            Flag.Warmer,
            Flag.Completion,
            Flag.Segments,
            Flag.Translog,
            Flag.RequestCache,
            Flag.Recovery };

        assertThat(flags.length, equalTo(Flag.values().length));
        for (int i = 0; i < flags.length; i++) {
            assertThat("ordinal has changed - this breaks the wire protocol. Only append to new values", i, equalTo(flags[i].ordinal()));
        }
    }

    public void testMultiIndex() throws Exception {
        assertAcked(prepareCreate("test1"));
        createIndex("test2");

        ensureGreen();

        client().prepareIndex("test1").setId(Integer.toString(1)).setSource("field", "value").execute().actionGet();
        client().prepareIndex("test1").setId(Integer.toString(2)).setSource("field", "value").execute().actionGet();
        client().prepareIndex("test2").setId(Integer.toString(1)).setSource("field", "value").execute().actionGet();
        refresh();

        int numShards1 = getNumShards("test1").totalNumShards;
        int numShards2 = getNumShards("test2").totalNumShards;

        IndicesStatsRequestBuilder builder = client().admin().indices().prepareStats();
        IndicesStatsResponse stats = builder.execute().actionGet();

        assertThat(stats.getTotalShards(), equalTo(numShards1 + numShards2));

        stats = builder.setIndices("_all").execute().actionGet();
        assertThat(stats.getTotalShards(), equalTo(numShards1 + numShards2));

        stats = builder.setIndices("_all").execute().actionGet();
        assertThat(stats.getTotalShards(), equalTo(numShards1 + numShards2));

        stats = builder.setIndices("*").execute().actionGet();
        assertThat(stats.getTotalShards(), equalTo(numShards1 + numShards2));

        stats = builder.setIndices("test1").execute().actionGet();
        assertThat(stats.getTotalShards(), equalTo(numShards1));

        stats = builder.setIndices("test1", "test2").execute().actionGet();
        assertThat(stats.getTotalShards(), equalTo(numShards1 + numShards2));

        stats = builder.setIndices("*2").execute().actionGet();
        assertThat(stats.getTotalShards(), equalTo(numShards2));

    }

    public void testCompletionFieldsParam() throws Exception {
        assertAcked(
            prepareCreate("test1").setMapping(
                "{ \"properties\": { \"bar\": { \"type\": \"text\", \"fields\": { \"completion\": { \"type\": \"completion\" }}}"
                    + ",\"baz\": { \"type\": \"text\", \"fields\": { \"completion\": { \"type\": \"completion\" }}}}}"
            )
        );
        ensureGreen();

        client().prepareIndex("test1")
            .setId(Integer.toString(1))
            .setSource("{\"bar\":\"bar\",\"baz\":\"baz\"}", MediaTypeRegistry.JSON)
            .get();
        refresh();

        IndicesStatsRequestBuilder builder = client().admin().indices().prepareStats();
        IndicesStatsResponse stats = builder.execute().actionGet();

        assertThat(stats.getTotal().completion.getSizeInBytes(), greaterThan(0L));
        assertThat(stats.getTotal().completion.getFields(), is(nullValue()));

        stats = builder.setCompletionFields("bar.completion").execute().actionGet();
        assertThat(stats.getTotal().completion.getSizeInBytes(), greaterThan(0L));
        assertThat(stats.getTotal().completion.getFields().containsField("bar.completion"), is(true));
        assertThat(stats.getTotal().completion.getFields().get("bar.completion"), greaterThan(0L));
        assertThat(stats.getTotal().completion.getFields().containsField("baz.completion"), is(false));

        stats = builder.setCompletionFields("bar.completion", "baz.completion").execute().actionGet();
        assertThat(stats.getTotal().completion.getSizeInBytes(), greaterThan(0L));
        assertThat(stats.getTotal().completion.getFields().containsField("bar.completion"), is(true));
        assertThat(stats.getTotal().completion.getFields().get("bar.completion"), greaterThan(0L));
        assertThat(stats.getTotal().completion.getFields().containsField("baz.completion"), is(true));
        assertThat(stats.getTotal().completion.getFields().get("baz.completion"), greaterThan(0L));

        stats = builder.setCompletionFields("*").execute().actionGet();
        assertThat(stats.getTotal().completion.getSizeInBytes(), greaterThan(0L));
        assertThat(stats.getTotal().completion.getFields().containsField("bar.completion"), is(true));
        assertThat(stats.getTotal().completion.getFields().get("bar.completion"), greaterThan(0L));
        assertThat(stats.getTotal().completion.getFields().containsField("baz.completion"), is(true));
        assertThat(stats.getTotal().completion.getFields().get("baz.completion"), greaterThan(0L));

        stats = builder.setCompletionFields("*r*").execute().actionGet();
        assertThat(stats.getTotal().completion.getSizeInBytes(), greaterThan(0L));
        assertThat(stats.getTotal().completion.getFields().containsField("bar.completion"), is(true));
        assertThat(stats.getTotal().completion.getFields().get("bar.completion"), greaterThan(0L));
        assertThat(stats.getTotal().completion.getFields().containsField("baz.completion"), is(false));

    }

    public void testGroupsParam() throws Exception {
        createIndex("test1");

        ensureGreen();

        client().prepareIndex("test1").setId(Integer.toString(1)).setSource("foo", "bar").execute().actionGet();
        refresh();

        client().prepareSearch("_all").setStats("bar", "baz").execute().actionGet();

        IndicesStatsRequestBuilder builder = client().admin().indices().prepareStats();
        IndicesStatsResponse stats = builder.execute().actionGet();

        assertThat(stats.getTotal().search.getTotal().getQueryCount(), greaterThan(0L));
        assertThat(stats.getTotal().search.getGroupStats(), is(nullValue()));

        stats = builder.setGroups("bar").execute().actionGet();
        assertThat(stats.getTotal().search.getGroupStats().get("bar").getQueryCount(), greaterThan(0L));
        assertThat(stats.getTotal().search.getGroupStats().containsKey("baz"), is(false));

        stats = builder.setGroups("bar", "baz").execute().actionGet();
        assertThat(stats.getTotal().search.getGroupStats().get("bar").getQueryCount(), greaterThan(0L));
        assertThat(stats.getTotal().search.getGroupStats().get("baz").getQueryCount(), greaterThan(0L));

        stats = builder.setGroups("*").execute().actionGet();
        assertThat(stats.getTotal().search.getGroupStats().get("bar").getQueryCount(), greaterThan(0L));
        assertThat(stats.getTotal().search.getGroupStats().get("baz").getQueryCount(), greaterThan(0L));

        stats = builder.setGroups("*r").execute().actionGet();
        assertThat(stats.getTotal().search.getGroupStats().get("bar").getQueryCount(), greaterThan(0L));
        assertThat(stats.getTotal().search.getGroupStats().containsKey("baz"), is(false));

    }

    private static void set(Flag flag, IndicesStatsRequestBuilder builder, boolean set) {
        switch (flag) {
            case Docs:
                builder.setDocs(set);
                break;
            case FieldData:
                builder.setFieldData(set);
                break;
            case QueryCache:
                builder.setQueryCache(set);
                break;
            case Flush:
                builder.setFlush(set);
                break;
            case Get:
                builder.setGet(set);
                break;
            case Indexing:
                builder.setIndexing(set);
                break;
            case Merge:
                builder.setMerge(set);
                break;
            case Refresh:
                builder.setRefresh(set);
                break;
            case Search:
                builder.setSearch(set);
                break;
            case Store:
                builder.setStore(set);
                break;
            case Warmer:
                builder.setWarmer(set);
                break;
            case Completion:
                builder.setCompletion(set);
                break;
            case Segments:
                builder.setSegments(set);
                break;
            case Translog:
                builder.setTranslog(set);
                break;
            case RequestCache:
                builder.setRequestCache(set);
                break;
            case Recovery:
                builder.setRecovery(set);
                break;
            default:
                fail("new flag? " + flag);
                break;
        }
    }

    private static boolean isSet(Flag flag, CommonStats response) {
        switch (flag) {
            case Docs:
                return response.getDocs() != null;
            case FieldData:
                return response.getFieldData() != null;
            case QueryCache:
                return response.getQueryCache() != null;
            case Flush:
                return response.getFlush() != null;
            case Get:
                return response.getGet() != null;
            case Indexing:
                return response.getIndexing() != null;
            case Merge:
                return response.getMerge() != null;
            case Refresh:
                return response.getRefresh() != null;
            case Search:
                return response.getSearch() != null;
            case Store:
                return response.getStore() != null;
            case Warmer:
                return response.getWarmer() != null;
            case Completion:
                return response.getCompletion() != null;
            case Segments:
                return response.getSegments() != null;
            case Translog:
                return response.getTranslog() != null;
            case RequestCache:
                return response.getRequestCache() != null;
            case Recovery:
                return response.getRecoveryStats() != null;
            default:
                fail("new flag? " + flag);
                return false;
        }
    }

    private void assertEquals(QueryCacheStats stats1, QueryCacheStats stats2) {
        assertEquals(stats1.getCacheCount(), stats2.getCacheCount());
        assertEquals(stats1.getCacheSize(), stats2.getCacheSize());
        assertEquals(stats1.getEvictions(), stats2.getEvictions());
        assertEquals(stats1.getHitCount(), stats2.getHitCount());
        assertEquals(stats2.getMemorySizeInBytes(), stats2.getMemorySizeInBytes());
        assertEquals(stats1.getMissCount(), stats2.getMissCount());
        assertEquals(stats1.getTotalCount(), stats2.getTotalCount());
    }

    private void assertCumulativeQueryCacheStats(IndicesStatsResponse response) {
        assertAllSuccessful(response);
        QueryCacheStats total = response.getTotal().queryCache;
        QueryCacheStats indexTotal = new QueryCacheStats();
        QueryCacheStats shardTotal = new QueryCacheStats();
        for (IndexStats indexStats : response.getIndices().values()) {
            indexTotal.add(indexStats.getTotal().queryCache);
            for (ShardStats shardStats : response.getShards()) {
                shardTotal.add(shardStats.getStats().queryCache);
            }
        }
        assertEquals(total, indexTotal);
        assertEquals(total, shardTotal);
    }

    public void testFilterCacheStats() throws Exception {
        Settings settings = Settings.builder()
            .put(indexSettings())
            .put("number_of_replicas", 0)
            .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), "200ms")
            .build();
        assertAcked(prepareCreate("index").setSettings(settings).get());
        indexRandom(
            false,
            true,
            client().prepareIndex("index").setId("1").setSource("foo", "bar"),
            client().prepareIndex("index").setId("2").setSource("foo", "baz")
        );
        persistGlobalCheckpoint("index"); // Need to persist the global checkpoint for the soft-deletes retention MP.
        refresh();
        ensureGreen();

        IndicesStatsResponse response = client().admin().indices().prepareStats("index").setQueryCache(true).get();
        assertCumulativeQueryCacheStats(response);
        assertEquals(0, response.getTotal().queryCache.getCacheSize());

        // the query cache has an optimization that disables it automatically if there is contention,
        // so we run it in an assertBusy block which should eventually succeed
        assertBusy(() -> {
            assertSearchResponse(
                client().prepareSearch("index").setQuery(QueryBuilders.constantScoreQuery(QueryBuilders.matchQuery("foo", "baz"))).get()
            );
            IndicesStatsResponse stats = client().admin().indices().prepareStats("index").setQueryCache(true).get();
            assertCumulativeQueryCacheStats(stats);
            assertThat(stats.getTotal().queryCache.getHitCount(), equalTo(0L));
            assertThat(stats.getTotal().queryCache.getMissCount(), greaterThan(0L));
            assertThat(stats.getTotal().queryCache.getCacheSize(), greaterThan(0L));
        });

        assertBusy(() -> {
            assertSearchResponse(
                client().prepareSearch("index").setQuery(QueryBuilders.constantScoreQuery(QueryBuilders.matchQuery("foo", "baz"))).get()
            );
            IndicesStatsResponse stats = client().admin().indices().prepareStats("index").setQueryCache(true).get();
            assertCumulativeQueryCacheStats(stats);
            assertThat(stats.getTotal().queryCache.getHitCount(), greaterThan(0L));
            assertThat(stats.getTotal().queryCache.getMissCount(), greaterThan(0L));
            assertThat(stats.getTotal().queryCache.getCacheSize(), greaterThan(0L));
        });

        assertEquals(DocWriteResponse.Result.DELETED, client().prepareDelete("index", "1").get().getResult());
        assertEquals(DocWriteResponse.Result.DELETED, client().prepareDelete("index", "2").get().getResult());
        // Here we are testing that a fully deleted segment should be dropped and its cached is evicted.
        // In order to instruct the merge policy not to keep a fully deleted segment,
        // we need to flush and make that commit safe so that the SoftDeletesPolicy can drop everything.
        persistGlobalCheckpoint("index");
        assertBusy(() -> {
            for (final ShardStats shardStats : client().admin().indices().prepareStats("index").get().getIndex("index").getShards()) {
                final long maxSeqNo = shardStats.getSeqNoStats().getMaxSeqNo();
                assertTrue(
                    shardStats.getRetentionLeaseStats()
                        .retentionLeases()
                        .leases()
                        .stream()
                        .allMatch(retentionLease -> retentionLease.retainingSequenceNumber() == maxSeqNo + 1)
                );
            }
        });
        flush("index");
        logger.info("--> force merging to a single segment");
        ForceMergeResponse forceMergeResponse = client().admin()
            .indices()
            .prepareForceMerge("index")
            .setFlush(true)
            .setMaxNumSegments(1)
            .get();
        assertAllSuccessful(forceMergeResponse);
        logger.info("--> refreshing");
        refresh();

        logger.info("--> verifying that cache size is 0");
        response = client().admin().indices().prepareStats("index").setQueryCache(true).get();
        assertCumulativeQueryCacheStats(response);
        assertThat(response.getTotal().queryCache.getHitCount(), greaterThan(0L));
        assertThat(response.getTotal().queryCache.getEvictions(), greaterThan(0L));
        assertThat(response.getTotal().queryCache.getCacheSize(), equalTo(0L));
        assertThat(response.getTotal().queryCache.getCacheCount(), greaterThan(0L));

        indexRandom(
            true,
            client().prepareIndex("index").setId("1").setSource("foo", "bar"),
            client().prepareIndex("index").setId("2").setSource("foo", "baz")
        );

        assertBusy(() -> {
            assertSearchResponse(
                client().prepareSearch("index").setQuery(QueryBuilders.constantScoreQuery(QueryBuilders.matchQuery("foo", "baz"))).get()
            );
            IndicesStatsResponse stats = client().admin().indices().prepareStats("index").setQueryCache(true).get();
            assertCumulativeQueryCacheStats(stats);
            assertThat(stats.getTotal().queryCache.getHitCount(), greaterThan(0L));
            assertThat(stats.getTotal().queryCache.getEvictions(), greaterThan(0L));
            assertThat(stats.getTotal().queryCache.getMissCount(), greaterThan(0L));
            assertThat(stats.getTotal().queryCache.getCacheSize(), greaterThan(0L));
            assertThat(stats.getTotal().queryCache.getMemorySizeInBytes(), greaterThan(0L));
        });

        assertAllSuccessful(client().admin().indices().prepareClearCache("index").setQueryCache(true).get());
        response = client().admin().indices().prepareStats("index").setQueryCache(true).get();
        assertCumulativeQueryCacheStats(response);
        assertThat(response.getTotal().queryCache.getHitCount(), greaterThan(0L));
        assertThat(response.getTotal().queryCache.getEvictions(), greaterThan(0L));
        assertThat(response.getTotal().queryCache.getMissCount(), greaterThan(0L));
        assertThat(response.getTotal().queryCache.getCacheSize(), equalTo(0L));
        assertThat(response.getTotal().queryCache.getMemorySizeInBytes(), equalTo(0L));
    }

    /**
     * Test that we can safely concurrently index and get stats. This test was inspired by a serialization issue that arose due to a race
     * getting doc stats during heavy indexing. The race could lead to deleted docs being negative which would then be serialized as a
     * variable-length long. Since serialization of negative longs using a variable-length format was unsupported
     * ({@link StreamOutput#writeVLong(long)}), the stream would become corrupted. Here, we want to test
     * that we can continue to get stats while indexing.
     */
    public void testConcurrentIndexingAndStatsRequests() throws BrokenBarrierException, InterruptedException, ExecutionException {
        final AtomicInteger idGenerator = new AtomicInteger();
        final int numberOfIndexingThreads = Runtime.getRuntime().availableProcessors();
        final int numberOfStatsThreads = 4 * numberOfIndexingThreads;
        final CyclicBarrier barrier = new CyclicBarrier(1 + numberOfIndexingThreads + numberOfStatsThreads);
        final AtomicBoolean stop = new AtomicBoolean();
        final List<Thread> threads = new ArrayList<>(numberOfIndexingThreads + numberOfIndexingThreads);

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean failed = new AtomicBoolean();
        final AtomicReference<List<DefaultShardOperationFailedException>> shardFailures = new AtomicReference<>(
            new CopyOnWriteArrayList<>()
        );
        final AtomicReference<List<Exception>> executionFailures = new AtomicReference<>(new CopyOnWriteArrayList<>());

        // increasing the number of shards increases the number of chances any one stats request will hit a race
        final CreateIndexRequest createIndexRequest = new CreateIndexRequest(
            "test",
            Settings.builder().put("index.number_of_shards", 10).build()
        );
        client().admin().indices().create(createIndexRequest).get();

        // start threads that will index concurrently with stats requests
        for (int i = 0; i < numberOfIndexingThreads; i++) {
            final Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                } catch (final BrokenBarrierException | InterruptedException e) {
                    failed.set(true);
                    executionFailures.get().add(e);
                    latch.countDown();
                }
                while (!stop.get()) {
                    final String id = Integer.toString(idGenerator.incrementAndGet());
                    final IndexResponse response = client().prepareIndex("test").setId(id).setSource("{}", MediaTypeRegistry.JSON).get();
                    assertThat(response.getResult(), equalTo(DocWriteResponse.Result.CREATED));
                }
            });
            thread.setName("indexing-" + i);
            threads.add(thread);
            thread.start();
        }

        // start threads that will get stats concurrently with indexing
        for (int i = 0; i < numberOfStatsThreads; i++) {
            final Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                } catch (final BrokenBarrierException | InterruptedException e) {
                    failed.set(true);
                    executionFailures.get().add(e);
                    latch.countDown();
                }
                final IndicesStatsRequest request = new IndicesStatsRequest();
                request.all();
                request.indices(new String[0]);
                while (!stop.get()) {
                    try {
                        final IndicesStatsResponse response = client().admin().indices().stats(request).get();
                        if (response.getFailedShards() > 0) {
                            failed.set(true);
                            shardFailures.get().addAll(Arrays.asList(response.getShardFailures()));
                            latch.countDown();
                        }
                    } catch (final ExecutionException | InterruptedException e) {
                        failed.set(true);
                        executionFailures.get().add(e);
                        latch.countDown();
                    }
                }
            });
            thread.setName("stats-" + i);
            threads.add(thread);
            thread.start();
        }

        // release the hounds
        barrier.await();

        // wait for a failure, or for fifteen seconds to elapse
        latch.await(15, TimeUnit.SECONDS);

        // stop all threads and wait for them to complete
        stop.set(true);
        for (final Thread thread : threads) {
            thread.join();
        }

        assertThat(shardFailures.get(), emptyCollectionOf(DefaultShardOperationFailedException.class));
        assertThat(executionFailures.get(), emptyCollectionOf(Exception.class));
    }

    public void testZeroRemoteStoreStatsOnNonRemoteStoreIndex() {
        String indexName = "test-index";
        createIndex(indexName, Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0).build());
        ensureGreen(indexName);
        assertEquals(
            RestStatus.CREATED,
            client().prepareIndex(indexName)
                .setId(UUIDs.randomBase64UUID())
                .setSource("field", "value1", "field2", "value1")
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get()
                .status()
        );
        refreshAndWaitForReplication();
        ShardStats shard = client().admin().indices().prepareStats(indexName).setSegments(true).setTranslog(true).get().getShards()[0];
        RemoteSegmentStats remoteSegmentStatsFromIndexStats = shard.getStats().getSegments().getRemoteSegmentStats();
        assertZeroRemoteSegmentStats(remoteSegmentStatsFromIndexStats);
        RemoteTranslogStats remoteTranslogStatsFromIndexStats = shard.getStats().getTranslog().getRemoteTranslogStats();
        assertZeroRemoteTranslogStats(remoteTranslogStatsFromIndexStats);

        NodesStatsResponse nodesStatsResponse = client().admin().cluster().prepareNodesStats(primaryNodeName(indexName)).get();
        RemoteSegmentStats remoteSegmentStatsFromNodesStats = nodesStatsResponse.getNodes()
            .get(0)
            .getIndices()
            .getSegments()
            .getRemoteSegmentStats();
        assertZeroRemoteSegmentStats(remoteSegmentStatsFromNodesStats);
        RemoteTranslogStats remoteTranslogStatsFromNodesStats = nodesStatsResponse.getNodes()
            .get(0)
            .getIndices()
            .getTranslog()
            .getRemoteTranslogStats();
        assertZeroRemoteTranslogStats(remoteTranslogStatsFromNodesStats);
    }

    private void assertZeroRemoteSegmentStats(RemoteSegmentStats remoteSegmentStats) {
        // Compare with fresh object because all values default to 0 in default fresh object
        assertEquals(new RemoteSegmentStats(), remoteSegmentStats);
    }

    private void assertZeroRemoteTranslogStats(RemoteTranslogStats remoteTranslogStats) {
        // Compare with fresh object because all values default to 0 in default fresh object
        assertEquals(new RemoteTranslogStats(), remoteTranslogStats);
    }

    /**
     * Persist the global checkpoint on all shards of the given index into disk.
     * This makes sure that the persisted global checkpoint on those shards will equal to the in-memory value.
     */
    private void persistGlobalCheckpoint(String index) throws Exception {
        final Set<String> nodes = internalCluster().nodesInclude(index);
        for (String node : nodes) {
            final IndicesService indexServices = internalCluster().getInstance(IndicesService.class, node);
            for (IndexService indexService : indexServices) {
                for (IndexShard indexShard : indexService) {
                    indexShard.sync();
                    assertThat(indexShard.getLastSyncedGlobalCheckpoint(), equalTo(indexShard.getLastKnownGlobalCheckpoint()));
                }
            }
        }
    }

    public void testSegmentReplicationStats() {
        String indexName = "test-index";
        createIndex(
            indexName,
            Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1).put(SETTING_NUMBER_OF_REPLICAS, 1).build()
        );

        ensureGreen(indexName);

        IndicesStatsRequestBuilder builder = client().admin().indices().prepareStats();
        IndicesStatsResponse stats = builder.execute().actionGet();

        // document replication enabled index should return empty segment replication stats
        assertNotNull(stats.getIndex(indexName).getTotal().getSegments().getReplicationStats());

        indexName = "test-index2";
        createIndex(
            indexName,
            Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                .put(IndexMetadata.SETTING_REPLICATION_TYPE, ReplicationType.SEGMENT)
                .build()
        );
        ensureGreen(indexName);

        builder = client().admin().indices().prepareStats();
        stats = builder.execute().actionGet();

        // segment replication enabled index should return segment replication stats
        assertNotNull(stats.getIndex(indexName).getTotal().getSegments().getReplicationStats());
    }
}
