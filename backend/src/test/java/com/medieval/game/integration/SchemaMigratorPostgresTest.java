package com.medieval.game.integration;

import com.medieval.game.config.SchemaMigrator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.*;

/**
 * [HARDENING P2-5] Fecha a lacuna de cobertura do CI: tanto o job H2 quanto o pgtest usam
 * ddl-auto=create-drop, então o schema SEMPRE nasce com o check de enum completo — nunca reproduz o
 * cenário REAL de prod (ddl-auto=update sobre banco persistente, com um check ANTIGO faltando um valor
 * novo do enum). Resultado: um drift de enum passa verde no CI e só quebra no Railway. Este teste SEMEIA
 * um check defasado e prova que {@link SchemaMigrator#dropStaleEnumCheckConstraints()} o derruba E que o
 * valor novo volta a ser aceito. Postgres-only (usa pg_constraint / normalização de IN→ANY(ARRAY)); sob
 * H2 (mvn test padrão) é SKIPADO via assumeTrue, não falha. Roda de verdade com `mvn test -Ppostgres`.
 */
@DisplayName("SchemaMigrator — sweep derruba check de enum defasado (regressão do drift de prod)")
class SchemaMigratorPostgresTest extends BaseIntegrationTest {

    @Autowired SchemaMigrator schemaMigrator;
    @Autowired JdbcTemplate   jdbc;

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((Connection c) ->
                c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    @Test
    @DisplayName("check 'col IN (...)' defasado é derrubado e o valor novo passa a ser aceito")
    void sweepDropsStaleEnumCheckAndAllowsNewValue() {
        Assumptions.assumeTrue(isPostgres(), "Postgres-only (H2 não tem pg_constraint / DO $$)");

        jdbc.execute("DROP TABLE IF EXISTS enum_sweep_probe");
        jdbc.execute("CREATE TABLE enum_sweep_probe (id bigint, status varchar(20))");
        // Check 'antigo' com 2+ valores → o Postgres normaliza p/ '= ANY(ARRAY[...])' (a marca que o
        // sweep procura), faltando o valor 'C' (o que um deploy futuro adicionaria ao enum).
        jdbc.execute("ALTER TABLE enum_sweep_probe ADD CONSTRAINT enum_sweep_probe_status_check "
                + "CHECK (status IN ('A','B'))");

        // ANTES do sweep: inserir o valor novo viola o check defasado — exatamente o 500 (23514) que só
        // aconteceria em prod, e que o CI com create-drop nunca reproduz.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO enum_sweep_probe(id, status) VALUES (1, 'C')"))
                .isInstanceOf(Exception.class);

        // A defesa real de prod:
        schemaMigrator.dropStaleEnumCheckConstraints();

        // O check defasado sumiu...
        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'enum_sweep_probe_status_check'",
                Integer.class);
        assertThat(remaining).isZero();

        // ...e agora o valor novo é aceito (a regressão que o sweep existe pra cobrir).
        int inserted = jdbc.update("INSERT INTO enum_sweep_probe(id, status) VALUES (2, 'C')");
        assertThat(inserted).isEqualTo(1);

        jdbc.execute("DROP TABLE IF EXISTS enum_sweep_probe");
    }
}
