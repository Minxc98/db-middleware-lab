package com.pacvue.lab.es.fixture;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;

/**
 * Fixture covering the field types whose behaviour the notes care about:
 * analysed text, a multi-field text+keyword, a plain keyword, a number and a date.
 *
 * <p>The index name here is only a default - every test passes its own
 * {@code IndexCoordinates}, so one fixture serves many indices with different settings.
 */
@Document(indexName = "it-article")
public class ArticleDoc {

    @Id
    private String id;

    /** text for full-text search, {@code title.keyword} for exact match / sort / aggregate. */
    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "standard"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword))
    private String title;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String body;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Integer)
    private Integer views;

    @Field(type = FieldType.Date, format = DateFormat.date_optional_time)
    private Instant publishedAt;

    public ArticleDoc() {
    }

    public ArticleDoc(String id, String title, String body, String category, Integer views, Instant publishedAt) {
        this.id = id;
        this.title = title;
        this.body = body;
        this.category = category;
        this.views = views;
        this.publishedAt = publishedAt;
    }

    public static ArticleDoc of(String id, String title, String category) {
        return new ArticleDoc(id, title, title, category, 0, Instant.parse("2026-01-01T00:00:00Z"));
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

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getViews() {
        return views;
    }

    public void setViews(Integer views) {
        this.views = views;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
