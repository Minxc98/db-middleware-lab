package com.pacvue.lab.es.behavior.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;

/**
 * 06-集群与高可用 §7 - snapshot and restore.
 *
 * <p>Backups are taken against the immutable segment files, which is what makes them
 * incremental. Copying the data directory by hand is not a backup; this is.
 *
 * <p>Requires {@code path.repo} on the node - see docker-compose.yml in this module.
 */
class SnapshotRestoreIT extends AbstractBehaviorIT {

    private static final String REPO = "lab-fs-repo";
    private static final String SNAPSHOT = "it-snapshot";
    private static final String REPO_LOCATION = "/usr/share/elasticsearch/backup";

    @Override
    protected String indexName() {
        return "it-snapshot-restore";
    }

    @Override
    protected void createIndex() {
        createIndexWithMappingOf(ArticleDoc.class, Map.of(
                "index.number_of_shards", 1,
                "index.number_of_replicas", 0));
    }

    private void seed(int count) {
        List<ArticleDoc> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            docs.add(new ArticleDoc("doc-" + i, "title " + i, "body", "news", i,
                    Instant.parse("2026-01-01T00:00:00Z")));
        }
        operations.save(docs, index());
        refresh();
    }

    private long liveCount() {
        return operations.count(NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build(),
                ArticleDoc.class, index());
    }

    @Test
    @DisplayName("snapshot to a filesystem repository, drop the index, restore it")
    void snapshotAndRestoreRoundTrip() throws IOException {
        seed(25);
        assertThat(liveCount()).isEqualTo(25);

        client.snapshot().createRepository(r -> r
                .name(REPO)
                .repository(rep -> rep.fs(fs -> fs.settings(st -> st.location(REPO_LOCATION)))));

        try {
            client.snapshot().delete(d -> d.repository(REPO).snapshot(SNAPSHOT));
        } catch (Exception ignored) {
            // first run: nothing to clean up
        }

        var created = client.snapshot().create(s -> s
                .repository(REPO).snapshot(SNAPSHOT)
                .indices(indexName())
                .includeGlobalState(false)
                .waitForCompletion(true));

        assertThat(created.snapshot().state()).isEqualTo("SUCCESS");
        assertThat(created.snapshot().shards().failed().intValue()).isZero();

        // Lose the index.
        indexOps().delete();
        assertThat(indexOps().exists()).isFalse();

        client.snapshot().restore(r -> r
                .repository(REPO).snapshot(SNAPSHOT)
                .indices(indexName())
                .waitForCompletion(true));

        assertThat(indexOps().exists()).isTrue();
        assertThat(liveCount()).isEqualTo(25);
        assertThat(operations.get("doc-7", ArticleDoc.class, index()).getTitle()).isEqualTo("title 7");
    }

    @Test
    @DisplayName("a second snapshot of unchanged data is incremental, not a full copy")
    void snapshotsAreIncremental() throws IOException {
        seed(25);

        client.snapshot().createRepository(r -> r
                .name(REPO)
                .repository(rep -> rep.fs(fs -> fs.settings(st -> st.location(REPO_LOCATION)))));

        for (String name : List.of("it-incremental-1", "it-incremental-2")) {
            try {
                client.snapshot().delete(d -> d.repository(REPO).snapshot(name));
            } catch (Exception ignored) {
                // not there yet
            }
        }

        var first = client.snapshot().create(s -> s.repository(REPO).snapshot("it-incremental-1")
                .indices(indexName()).includeGlobalState(false).waitForCompletion(true));

        var status = client.snapshot().status(s -> s.repository(REPO).snapshot("it-incremental-1"));
        long firstBytes = status.snapshots().get(0).stats().total().sizeInBytes();

        // Nothing changed in between, so the second snapshot references the same segments.
        var second = client.snapshot().create(s -> s.repository(REPO).snapshot("it-incremental-2")
                .indices(indexName()).includeGlobalState(false).waitForCompletion(true));

        var secondStatus = client.snapshot().status(s -> s.repository(REPO).snapshot("it-incremental-2"));
        long secondProcessed = secondStatus.snapshots().get(0).stats().incremental().sizeInBytes();

        assertThat(first.snapshot().state()).isEqualTo("SUCCESS");
        assertThat(second.snapshot().state()).isEqualTo("SUCCESS");
        assertThat(firstBytes).isPositive();
        assertThat(secondProcessed)
                .as("the second snapshot only had to store metadata, not the segments again")
                .isLessThan(firstBytes);
    }
}
