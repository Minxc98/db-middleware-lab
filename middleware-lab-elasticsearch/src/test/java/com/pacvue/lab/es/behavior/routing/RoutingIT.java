package com.pacvue.lab.es.behavior.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.data.elasticsearch.core.routing.RoutingResolver;

/**
 * 05-分片与路由 §1,§4 / 面试题 Q4.1 - routing.
 *
 * <p>{@code shard = hash(_routing) % number_of_primary_shards}, with {@code _routing}
 * defaulting to {@code _id}. Custom routing turns a scatter-gather search into a single-shard
 * one, at the price of having to carry the routing value everywhere - and of skew.
 */
class RoutingIT extends AbstractBehaviorIT {

    private static final int SHARDS = 4;

    @Override
    protected String indexName() {
        return "it-routing";
    }

    @Override
    protected void createIndex() {
        createIndexWithMappingOf(ArticleDoc.class, Map.of(
                "index.number_of_shards", SHARDS,
                "index.number_of_replicas", 0));
    }

    private ArticleDoc article(String id) {
        return new ArticleDoc(id, "title " + id, "body", "news", 1, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private void saveWithRouting(String id, String routing) {
        IndexQuery query = new IndexQueryBuilder()
                .withId(id)
                .withObject(article(id))
                .withRouting(routing)
                .build();
        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).index(query, index());
    }

    @Test
    @DisplayName("the same id always lands on the same shard")
    void routingIsDeterministic() throws IOException {
        var first = client.searchShards(s -> s.index(indexName()).routing("user-1001"));
        var second = client.searchShards(s -> s.index(indexName()).routing("user-1001"));

        assertThat(first.shards()).hasSize(1);
        assertThat(first.shards().get(0).get(0).shard())
                .isEqualTo(second.shards().get(0).get(0).shard());
    }

    @Test
    @DisplayName("a search without routing hits every shard; with routing, exactly one")
    void routingNarrowsTheSearchToOneShard() throws IOException {
        assertThat(client.searchShards(s -> s.index(indexName())).shards()).hasSize(SHARDS);
        assertThat(client.searchShards(s -> s.index(indexName()).routing("tenant-a")).shards()).hasSize(1);
    }

    @Test
    @DisplayName("a document written with custom routing cannot be fetched without it")
    void gettingWithoutRoutingMisses() throws IOException {
        String id = "order-1";
        // Pick a routing value that provably lands on a different shard than the id would.
        // Hard-coding one would be a coin flip: with 4 shards it collides 1 time in 4.
        String routing = routingLandingOnAnotherShardThan(id);

        saveWithRouting(id, routing);

        // Spring Data routes this get by _id, which points at the wrong shard: not found.
        // This is the classic custom-routing trap - the routing value has to travel with
        // every read, not just the write.
        ArticleDoc withoutRouting = operations.get(id, ArticleDoc.class, index());

        ArticleDoc withRouting = operations.withRouting(RoutingResolver.just(routing))
                .get(id, ArticleDoc.class, index());

        assertThat(withRouting).isNotNull();
        assertThat(withoutRouting)
                .as("routing '%s' and id '%s' resolve to different shards", routing, id)
                .isNull();
    }

    private int shardFor(String routing) throws IOException {
        return client.searchShards(s -> s.index(indexName()).routing(routing))
                .shards().get(0).get(0).shard();
    }

    private String routingLandingOnAnotherShardThan(String id) throws IOException {
        int idShard = shardFor(id);
        for (int i = 0; i < 100; i++) {
            String candidate = "tenant-" + i;
            if (shardFor(candidate) != idShard) {
                return candidate;
            }
        }
        throw new IllegalStateException("no routing value mapped to a different shard than " + id);
    }

    @Test
    @DisplayName("search still finds the document: it asks all shards, unlike get by id")
    void searchFindsItRegardless() {
        saveWithRouting("order-1", "tenant-a");

        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .build(), ArticleDoc.class, index());

        assertThat(hits.getTotalHits()).isEqualTo(1);
    }

    @Test
    @DisplayName("routing everything through one value concentrates the data on one shard")
    void customRoutingCausesSkew() throws IOException {
        for (int i = 0; i < 40; i++) {
            saveWithRouting("doc-" + i, "hot-tenant");
        }
        refresh();

        List<Long> docsPerShard = docsPerShard();

        assertThat(docsPerShard).hasSize(SHARDS);
        assertThat(docsPerShard.stream().filter(c -> c > 0).count())
                .as("every document went to the same shard")
                .isEqualTo(1);
        assertThat(docsPerShard.stream().mapToLong(Long::longValue).max().orElse(0)).isEqualTo(40);
    }

    @Test
    @DisplayName("without custom routing, ids spread across shards on their own")
    void defaultRoutingSpreadsDocuments() throws IOException {
        List<ArticleDoc> docs = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            docs.add(article("doc-" + i));
        }
        operations.save(docs, index());
        refresh();

        assertThat(docsPerShard().stream().filter(c -> c > 0).count())
                .as("hash(_id) distributes documents over the shards")
                .isEqualTo(SHARDS);
    }

    private List<Long> docsPerShard() throws IOException {
        var stats = client.indices().stats(s -> s.index(indexName()).level(
                co.elastic.clients.elasticsearch._types.Level.Shards));
        return stats.indices().get(indexName()).shards().values().stream()
                .map(shard -> shard.get(0).docs().count())
                .toList();
    }
}
