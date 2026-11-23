package com.investingapp.backend.model;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 entities must mirror the migration DEFAULTs in Java (ops_alerts.occurrences 1,
 * ops_alerts.delivery_status 'PENDING'): Hibernate binds every mapped column on insert, so a
 * database DEFAULT never applies to a row the application writes, and an unset NOT NULL column
 * would fail every insert. ddl-auto=validate checks column existence and type only, never DEFAULT,
 * width or NOT NULL, so the column test pins what validate cannot see.
 */
class PhaseOneEntityDefaultsTest {

    @Test
    void opsAlertDefaultsMatchV11() {
        OpsAlert alert = new OpsAlert();
        assertThat(alert.getOccurrences()).isEqualTo(1);
        assertThat(alert.getDeliveryStatus()).isEqualTo("PENDING");
        assertThat(alert.getId()).isNull();
        assertThat(alert.getAlertType()).isNull();
        assertThat(alert.getSubjectId()).isNull();
        assertThat(alert.getAlertDay()).isNull();
        assertThat(alert.getMessage()).isNull();
        assertThat(alert.getContextJson()).isNull();
        assertThat(alert.getFirstRaisedAt()).isNull();
        assertThat(alert.getLastRaisedAt()).isNull();
    }

    @Test
    void adminActionStartsEmpty() {
        AdminAction action = new AdminAction();
        assertThat(action.getId()).isNull();
        assertThat(action.getActor()).isNull();
        assertThat(action.getUserId()).isNull();
        assertThat(action.getVerb()).isNull();
        assertThat(action.getTargetId()).isNull();
        assertThat(action.getBeforeJson()).isNull();
        assertThat(action.getAfterJson()).isNull();
        assertThat(action.getReason()).isNull();
        assertThat(action.getCreatedAt()).isNull();
    }

    @Test
    void columnsMirrorV11WidthsAndNullability() throws NoSuchFieldException {
        string(OpsAlert.class, "alertType", "alert_type", 64, false);
        string(OpsAlert.class, "subjectId", "subject_id", 80, false);
        plain(OpsAlert.class, "alertDay", "alert_day", LocalDate.class, false);
        string(OpsAlert.class, "message", "message", 500, false);
        text(OpsAlert.class, "contextJson", "context_json");
        plain(OpsAlert.class, "occurrences", "occurrences", int.class, false);
        plain(OpsAlert.class, "firstRaisedAt", "first_raised_at", LocalDateTime.class, false);
        plain(OpsAlert.class, "lastRaisedAt", "last_raised_at", LocalDateTime.class, false);
        string(OpsAlert.class, "deliveryStatus", "delivery_status", 12, false);

        string(AdminAction.class, "actor", "actor", 80, false);
        plain(AdminAction.class, "userId", "user_id", Long.class, true);
        string(AdminAction.class, "verb", "verb", 64, false);
        string(AdminAction.class, "targetId", "target_id", 80, true);
        text(AdminAction.class, "beforeJson", "before_json");
        text(AdminAction.class, "afterJson", "after_json");
        string(AdminAction.class, "reason", "reason", 255, true);
        plain(AdminAction.class, "createdAt", "created_at", LocalDateTime.class, false);
    }

    private static Column column(Class<?> type, String field) throws NoSuchFieldException {
        Column column = type.getDeclaredField(field).getAnnotation(Column.class);
        assertThat(column).as(type.getSimpleName() + "." + field + " carries @Column").isNotNull();
        return column;
    }

    private static void string(Class<?> type, String field, String name, int length, boolean nullable)
            throws NoSuchFieldException {
        String label = type.getSimpleName() + "." + field;
        Column column = column(type, field);
        assertThat(type.getDeclaredField(field).getType()).as(label).isEqualTo(String.class);
        assertThat(column.name()).as(label).isEqualTo(name);
        assertThat(column.length()).as(label + " length").isEqualTo(length);
        assertThat(column.nullable()).as(label + " nullable").isEqualTo(nullable);
    }

    private static void plain(Class<?> type, String field, String name, Class<?> javaType, boolean nullable)
            throws NoSuchFieldException {
        String label = type.getSimpleName() + "." + field;
        Column column = column(type, field);
        assertThat(type.getDeclaredField(field).getType()).as(label).isEqualTo(javaType);
        assertThat(column.name()).as(label).isEqualTo(name);
        assertThat(column.nullable()).as(label + " nullable").isEqualTo(nullable);
    }

    private static void text(Class<?> type, String field, String name) throws NoSuchFieldException {
        String label = type.getSimpleName() + "." + field;
        Column column = column(type, field);
        assertThat(type.getDeclaredField(field).getType()).as(label).isEqualTo(String.class);
        assertThat(column.name()).as(label).isEqualTo(name);
        assertThat(column.columnDefinition()).as(label + " columnDefinition").isEqualTo("TEXT");
        assertThat(column.nullable()).as(label + " nullable").isTrue();
    }
}
