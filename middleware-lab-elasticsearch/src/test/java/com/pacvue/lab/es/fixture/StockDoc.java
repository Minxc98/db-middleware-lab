package com.pacvue.lab.es.fixture;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.core.query.SeqNoPrimaryTerm;

/**
 * Fixture for concurrency control. The {@link SeqNoPrimaryTerm} property makes Spring Data
 * populate {@code _seq_no} / {@code _primary_term} on read and send them back on write, which
 * is what turns a plain update into an optimistic-locking update.
 */
@Document(indexName = "it-stock")
public class StockDoc {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String sku;

    @Field(type = FieldType.Integer)
    private Integer stock;

    private SeqNoPrimaryTerm seqNoPrimaryTerm;

    public StockDoc() {
    }

    public StockDoc(String id, String sku, Integer stock) {
        this.id = id;
        this.sku = sku;
        this.stock = stock;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public SeqNoPrimaryTerm getSeqNoPrimaryTerm() {
        return seqNoPrimaryTerm;
    }

    public void setSeqNoPrimaryTerm(SeqNoPrimaryTerm seqNoPrimaryTerm) {
        this.seqNoPrimaryTerm = seqNoPrimaryTerm;
    }
}
