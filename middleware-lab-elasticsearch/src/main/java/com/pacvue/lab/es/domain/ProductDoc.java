package com.pacvue.lab.es.domain;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * Sample document used by the lab. Intentionally small but covers the field types whose
 * behaviour is worth verifying: analysed text, keyword, numeric and date.
 */
@Document(indexName = "#{@indexNames.product()}")
public class ProductDoc {

    @Id
    private String id;

    /** Analysed: full-text matching. */
    @Field(type = FieldType.Text, analyzer = "standard")
    private String title;

    /** Not analysed: exact match, aggregations, sorting. */
    @Field(type = FieldType.Keyword)
    private String brand;

    @Field(type = FieldType.Keyword)
    private String asin;

    @Field(type = FieldType.Double)
    private BigDecimal price;

    /**
     * Several formats on purpose: the first is used when writing, all of them are tried when
     * reading. Declaring only {@code date_time} makes reads blow up on any document written by
     * something else that left the millis out ("2026-09-17T00:00:00Z") - exactly what happens
     * when an index is shared with another service. {@code DateFormat.date_optional_time} is
     * NOT enough here either, hence the explicit patterns.
     */
    @Field(type = FieldType.Date,
            format = {DateFormat.date_time, DateFormat.epoch_millis},
            pattern = {"uuuu-MM-dd'T'HH:mm:ssXXX", "uuuu-MM-dd'T'HH:mm:ss.SSSXXX"})
    private Instant updatedAt;

    public ProductDoc() {
    }

    public ProductDoc(String id, String title, String brand, String asin, BigDecimal price, Instant updatedAt) {
        this.id = id;
        this.title = title;
        this.brand = brand;
        this.asin = asin;
        this.price = price;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getAsin() {
        return asin;
    }

    public void setAsin(String asin) {
        this.asin = asin;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
