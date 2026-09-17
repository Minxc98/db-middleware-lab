package com.pacvue.lab.es.behavior.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.pacvue.lab.es.fixture.StockDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import com.pacvue.lab.es.support.EsErrors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.query.SeqNoPrimaryTerm;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.elasticsearch.core.query.UpdateResponse;

/**
 * 03-写入机制 §6.2 / 面试题 Q2.2 — optimistic concurrency control.
 *
 * <p>Elasticsearch has no locks and no multi-document transactions. Concurrent updates are
 * made safe by sending back the {@code _seq_no} / {@code _primary_term} you read; a mismatch
 * means someone else wrote first and the request is rejected with 409.
 */
class OptimisticLockingIT extends AbstractBehaviorIT {

    @Override
    protected String indexName() {
        return "it-optimistic-locking";
    }

    @Override
    protected void createIndex() {
        createIndexWithMappingOf(StockDoc.class);
    }

    private StockDoc seedOne() {
        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                .save(new StockDoc("1", "SKU-1", 100), index());
        return operations.get("1", StockDoc.class, index());
    }

    @Test
    @DisplayName("reads carry _seq_no and _primary_term")
    void readExposesSeqNoAndPrimaryTerm() {
        StockDoc doc = seedOne();

        SeqNoPrimaryTerm version = doc.getSeqNoPrimaryTerm();
        assertThat(version).isNotNull();
        assertThat(version.sequenceNumber()).isNotNegative();
        assertThat(version.primaryTerm()).isPositive();
    }

    @Test
    @DisplayName("every write advances _seq_no")
    void seqNoAdvancesOnEveryWrite() {
        StockDoc first = seedOne();
        long firstSeqNo = first.getSeqNoPrimaryTerm().sequenceNumber();

        first.setStock(99);
        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).save(first, index());

        StockDoc second = operations.get("1", StockDoc.class, index());
        assertThat(second.getSeqNoPrimaryTerm().sequenceNumber()).isGreaterThan(firstSeqNo);
    }

    @Test
    @DisplayName("updating with a stale _seq_no is rejected with 409, the lost-update case")
    void staleSeqNoIsRejected() {
        StockDoc read = seedOne();
        SeqNoPrimaryTerm stale = read.getSeqNoPrimaryTerm();

        // Someone else updates the document first; our copy is now out of date.
        StockDoc other = operations.get("1", StockDoc.class, index());
        other.setStock(50);
        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).save(other, index());

        UpdateQuery staleUpdate = UpdateQuery.builder("1")
                .withDocument(Document.from(java.util.Map.of("stock", 77)))
                .withIfSeqNo((int) stale.sequenceNumber())
                .withIfPrimaryTerm((int) stale.primaryTerm())
                .build();

        Throwable thrown = catchThrowable(() -> operations.update(staleUpdate, index()));

        // Spring Data translates Elasticsearch's 409 into the Spring DAO exception, so callers
        // can handle it the same way they would a JPA optimistic-locking failure.
        assertThat(thrown).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(EsErrors.detailOf(thrown)).contains("seq_no+primary_term conflict");

        // The other writer's value survived; ours was refused rather than silently overwriting.
        assertThat(operations.get("1", StockDoc.class, index()).getStock()).isEqualTo(50);
    }

    @Test
    @DisplayName("the current _seq_no is accepted - read, modify, write succeeds")
    void currentSeqNoSucceeds() {
        StockDoc read = seedOne();
        SeqNoPrimaryTerm current = read.getSeqNoPrimaryTerm();

        UpdateQuery update = UpdateQuery.builder("1")
                .withDocument(Document.from(java.util.Map.of("stock", 77)))
                .withIfSeqNo((int) current.sequenceNumber())
                .withIfPrimaryTerm((int) current.primaryTerm())
                .withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                .build();

        UpdateResponse response = operations.update(update, index());

        assertThat(response.getResult()).isEqualTo(UpdateResponse.Result.UPDATED);
        assertThat(operations.get("1", StockDoc.class, index()).getStock()).isEqualTo(77);
    }

    @Test
    @DisplayName("retry_on_conflict lets Elasticsearch redo the update instead of failing")
    void retryOnConflictAbsorbsTheRace() {
        seedOne();

        UpdateQuery update = UpdateQuery.builder("1")
                .withDocument(Document.from(java.util.Map.of("stock", 5)))
                .withRetryOnConflict(3)
                .withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                .build();

        assertThat(operations.update(update, index()).getResult())
                .isEqualTo(UpdateResponse.Result.UPDATED);
        assertThat(operations.get("1", StockDoc.class, index()).getStock()).isEqualTo(5);
    }
}
