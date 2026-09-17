package com.pacvue.lab.mysql.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of the lab's order table.
 *
 * <p>The column set is chosen to exercise the notes: an auto-increment {@code BIGINT} primary
 * key (clustered index), a unique secondary key, and a three-column composite index
 * {@code (user_id, status, created_at)} for leftmost-prefix, covering-index and ICP tests.
 *
 * @param id        auto-increment primary key - the clustered index
 * @param orderNo   unique secondary key
 * @param userId    leading column of the composite index
 * @param status    second column of the composite index
 * @param amount    payload, deliberately not indexed
 * @param remark    payload, deliberately not indexed
 * @param createdAt third column of the composite index
 */
public record OrderRow(
        Long id,
        String orderNo,
        long userId,
        int status,
        BigDecimal amount,
        String remark,
        LocalDateTime createdAt) {
}
