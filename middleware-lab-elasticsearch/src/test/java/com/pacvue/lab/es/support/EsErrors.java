package com.pacvue.lab.es.support;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;

/**
 * Digs the real Elasticsearch error out of an exception.
 *
 * <p>Spring Data wraps failures in {@code UncategorizedElasticsearchException} whose message is
 * often just {@code [es/search] failed: [search_phase_execution_exception] all shards failed} -
 * the part that says <em>why</em> (fielddata disabled, strict mapping, version conflict, result
 * window exceeded) lives in the {@link ErrorCause} tree underneath.
 */
public final class EsErrors {

    private EsErrors() {
    }

    /** HTTP status of the underlying Elasticsearch response, or -1 if this was not an ES error. */
    public static int statusOf(Throwable throwable) {
        ElasticsearchException ese = find(throwable);
        return ese == null ? -1 : ese.status();
    }

    /** Most specific error type, e.g. {@code illegal_argument_exception}, {@code version_conflict_engine_exception}. */
    public static String typeOf(Throwable throwable) {
        ElasticsearchException ese = find(throwable);
        if (ese == null || ese.error() == null) {
            return "";
        }
        ErrorCause root = deepestRootCause(ese.error());
        return root.type() == null ? "" : root.type();
    }

    /**
     * Every type and reason in the error tree, flattened - assert against this rather than
     * against {@code getMessage()}.
     */
    public static String detailOf(Throwable throwable) {
        ElasticsearchException ese = find(throwable);
        if (ese == null) {
            return String.valueOf(throwable == null ? null : throwable.getMessage());
        }
        StringBuilder sb = new StringBuilder();
        append(ese.error(), sb);
        return sb.toString();
    }

    private static ElasticsearchException find(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof ElasticsearchException ese) {
                return ese;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private static ErrorCause deepestRootCause(ErrorCause cause) {
        if (cause.rootCause() != null && !cause.rootCause().isEmpty()) {
            return cause.rootCause().get(0);
        }
        return cause.causedBy() != null ? deepestRootCause(cause.causedBy()) : cause;
    }

    private static void append(ErrorCause cause, StringBuilder sb) {
        if (cause == null) {
            return;
        }
        sb.append(cause.type()).append(": ").append(cause.reason()).append('\n');
        if (cause.rootCause() != null) {
            cause.rootCause().forEach(c -> append(c, sb));
        }
        append(cause.causedBy(), sb);
    }
}
