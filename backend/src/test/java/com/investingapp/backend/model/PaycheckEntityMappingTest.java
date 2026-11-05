package com.investingapp.backend.model;

import com.investingapp.backend.repository.PaycheckCycleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.parser.PartTree;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

/**
 * Pins every Phase 1 mapping against the V6 text, without a database. ddl-auto=validate (the
 * BackendApplicationTests gate) proves a mapped column exists with a compatible type; it never
 * checks length, nullability, precision, or that a column is mapped at all. This test checks those,
 * and parses every derived repository method against its entity the way Spring Data does at boot.
 */
class PaycheckEntityMappingTest {

    private static final String FILE = "db/migration/V6__paycheck_autopilot.sql";
    private static final Pattern CREATE = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS `(\\w+)` \\((.*?)\\n\\) ENGINE");
    private static final Pattern CREATE_COLUMN = Pattern.compile("(?m)^\\s+`(\\w+)` (.+?),?$");
    private static final Pattern ALTER_COLUMN = Pattern.compile("(?m)'ALTER TABLE (\\w+) ADD COLUMN (\\w+) (.*)',$");
    private static final Pattern SIZED = Pattern.compile("(\\w+)\\((\\d+)(?:,(\\d+))?\\)");

    private static final Map<Class<?>, Class<?>> REPOSITORIES = Map.of(
            PaycheckStreamRepository.class, PaycheckStream.class,
            PaycheckEventRepository.class, PaycheckEvent.class,
            PaycheckCycleRepository.class, PaycheckCycle.class);

    private static final Map<String, Map<String, String>> created = new HashMap<>();
    private static final Map<String, Map<String, String>> added = new HashMap<>();

    @BeforeAll
    static void load() throws Exception {
        String sql;
        try (InputStream in = PaycheckEntityMappingTest.class.getClassLoader().getResourceAsStream(FILE)) {
            assertThat(in).as(FILE + " must exist on the classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r", "");
        }
        Matcher table = CREATE.matcher(sql);
        while (table.find()) {
            Map<String, String> columns = new LinkedHashMap<>();
            Matcher column = CREATE_COLUMN.matcher(table.group(2));
            while (column.find()) {
                columns.put(column.group(1), column.group(2).toUpperCase(Locale.ROOT));
            }
            created.put(table.group(1), columns);
        }
        Matcher alter = ALTER_COLUMN.matcher(sql);
        while (alter.find()) {
            added.computeIfAbsent(alter.group(1), t -> new LinkedHashMap<>())
                    .put(alter.group(2), alter.group(3).toUpperCase(Locale.ROOT));
        }
    }

    @Test
    void paycheckStreamsMappedColumnForColumn() {
        assertTableMapped("paycheck_streams", PaycheckStream.class);
    }

    @Test
    void paycheckEventsMappedColumnForColumn() {
        assertTableMapped("paycheck_events", PaycheckEvent.class);
    }

    @Test
    void paycheckCyclesMappedColumnForColumn() {
        assertTableMapped("paycheck_cycles", PaycheckCycle.class);
    }

    @Test
    void everyV6UserColumnIsMappedAndMatches() {
        Map<String, Field> mapped = mappedColumns(User.class);
        Map<String, String> v6 = added.get("users");
        assertThat(v6).as("users additions in V6").hasSize(14);
        v6.forEach((column, definition) -> {
            assertThat(mapped).as("users." + column + " is mapped").containsKey(column);
            assertMatches("users." + column, mapped.get(column), definition);
        });
    }

    @Test
    void scheduleMapsTheDetectionMirrorAndLeavesTheRestUnmapped() {
        Map<String, Field> mapped = mappedColumns(InvestmentSchedule.class);
        Map<String, String> v6 = added.get("investment_schedules");
        Set<String> expected = Set.of("mode", "hold_reason", "detection_state", "primary_stream_id",
                "next_expected_date", "missed_count", "dormant_since", "last_cadence_check_at", "detection_started_at");
        assertThat(v6.keySet().stream().filter(mapped::containsKey).toList())
                .as("V6 investment_schedules columns mapped (decision 20: anchors, authorization, snooze stay unmapped)")
                .containsExactlyInAnyOrderElementsOf(expected);
        expected.forEach(column -> assertMatches("investment_schedules." + column, mapped.get(column), v6.get(column)));
    }

    @Test
    void repositoriesCarryThePhaseOneFindersAndEveryDerivedNameResolves() {
        assertThat(methodNames(PaycheckStreamRepository.class)).contains("findByUserIdOrderByIdAsc",
                "findByUserIdAndState", "findByUserIdAndPlaidAccountIdAndPayerToken", "deleteByUserId");
        assertThat(methodNames(PaycheckEventRepository.class)).contains("findByPlaidTransactionId",
                "findByPendingTransactionId", "findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc",
                "findByStreamIdAndStatusOrderByPostedDateDesc", "deleteByUserId", "deleteByUserIdAndPlaidAccountId",
                "deleteByPostedDateBefore");
        assertThat(methodNames(PaycheckCycleRepository.class)).contains("findByStreamIdAndPeriodKey",
                "findByUserIdAndState", "findByStateAndWindowEndBefore", "findByStateAndConfirmedAtBefore",
                "findByUserIdAndSkipReasonAndConfirmedAtAfter", "deleteByUserId");
        REPOSITORIES.forEach((repository, entity) -> derivedMethods(repository).forEach(method ->
                assertThatCode(() -> new PartTree(method.getName(), entity))
                        .as(repository.getSimpleName() + "." + method.getName() + " resolves against " + entity.getSimpleName())
                        .doesNotThrowAnyException()));
    }

    @Test
    void everyDerivedDeleteRunsInAReadWriteTransaction() {
        REPOSITORIES.keySet().forEach(repository -> derivedMethods(repository).stream()
                .filter(m -> m.getName().startsWith("delete"))
                .forEach(m -> {
                    Transactional tx = m.getAnnotation(Transactional.class);
                    assertThat(tx).as(repository.getSimpleName() + "." + m.getName() + " is @Transactional").isNotNull();
                    assertThat(tx.readOnly()).as(repository.getSimpleName() + "." + m.getName() + " is read-write").isFalse();
                }));
    }

    private static void assertTableMapped(String table, Class<?> entity) {
        Map<String, String> columns = created.get(table);
        assertThat(columns).as(table + " in V6").isNotEmpty();
        Map<String, Field> mapped = mappedColumns(entity);
        long fields = Arrays.stream(entity.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()).count();
        assertThat(mapped).as(entity.getSimpleName() + " names a column on every field").hasSize((int) fields);
        assertThat(mapped.keySet()).as(entity.getSimpleName() + " maps every V6 column and nothing else")
                .containsExactlyInAnyOrderElementsOf(columns.keySet());
        columns.forEach((column, definition) -> assertMatches(table + "." + column, mapped.get(column), definition));
    }

    private static Map<String, Field> mappedColumns(Class<?> entity) {
        Map<String, Field> mapped = new LinkedHashMap<>();
        for (Field f : entity.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || f.isAnnotationPresent(Transient.class)) {
                continue;
            }
            String name = f.isAnnotationPresent(Id.class) ? "id"
                    : Optional.ofNullable(f.getAnnotation(Column.class)).map(Column::name).orElse("");
            if (!name.isEmpty()) {
                mapped.put(name, f);
            }
        }
        return mapped;
    }

    private static void assertMatches(String where, Field field, String definition) {
        String type = definition.split(" ")[0];
        Matcher sized = SIZED.matcher(type);
        boolean hasSize = sized.matches();
        String base = hasSize ? sized.group(1) : type;
        Class<?> javaType = field.getType();
        Column column = field.getAnnotation(Column.class);
        if (!field.isAnnotationPresent(Id.class)) {
            assertThat(column.nullable()).as(where + " nullable").isEqualTo(!definition.contains("NOT NULL"));
        }
        switch (base) {
            case "VARCHAR" -> {
                assertThat(javaType == String.class || javaType.isEnum()).as(where + " is a String or an enum").isTrue();
                assertThat(column.length()).as(where + " length").isEqualTo(Integer.parseInt(sized.group(2)));
                if (javaType.isEnum()) {
                    Enumerated enumerated = field.getAnnotation(Enumerated.class);
                    assertThat(enumerated).as(where + " @Enumerated").isNotNull();
                    assertThat(enumerated.value()).as(where + " stored by name").isEqualTo(EnumType.STRING);
                    int longest = Arrays.stream(javaType.getEnumConstants())
                            .mapToInt(c -> ((Enum<?>) c).name().length()).max().orElse(0);
                    assertThat(longest).as(where + " longest enum name").isLessThanOrEqualTo(column.length());
                }
            }
            case "BIGINT" -> assertThat(javaType).as(where).isIn(Long.class, long.class);
            case "SMALLINT" -> assertThat(javaType).as(where).isIn(Short.class, short.class);
            case "TINYINT" -> {
                if (hasSize && "1".equals(sized.group(2))) {
                    assertThat(javaType).as(where).isIn(Boolean.class, boolean.class);
                    assertThat(column.columnDefinition()).as(where + " columnDefinition").startsWith("BOOLEAN");
                } else {
                    assertThat(javaType).as(where).isEqualTo(Short.class);
                    assertThat(column.columnDefinition()).as(where + " columnDefinition").isEqualTo("TINYINT");
                }
            }
            case "TEXT" -> {
                assertThat(javaType).as(where).isEqualTo(String.class);
                assertThat(column.columnDefinition()).as(where + " columnDefinition").isEqualTo("TEXT");
            }
            case "DATE" -> assertThat(javaType).as(where).isEqualTo(LocalDate.class);
            case "DATETIME" -> assertThat(javaType).as(where).isEqualTo(LocalDateTime.class);
            case "DECIMAL" -> {
                assertThat(javaType).as(where).isEqualTo(BigDecimal.class);
                assertThat(column.precision()).as(where + " precision").isEqualTo(Integer.parseInt(sized.group(2)));
                assertThat(column.scale()).as(where + " scale").isEqualTo(Integer.parseInt(sized.group(3)));
            }
            default -> fail(where + ": no mapping rule for V6 type " + type);
        }
    }

    private static List<Method> derivedMethods(Class<?> repository) {
        return Arrays.stream(repository.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !m.isDefault() && !m.isAnnotationPresent(Query.class))
                .toList();
    }

    private static List<String> methodNames(Class<?> repository) {
        return Arrays.stream(repository.getDeclaredMethods()).map(Method::getName).toList();
    }
}
