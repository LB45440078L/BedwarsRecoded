package dev.bedwars.core.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationsTest {

    @Test
    void versionsAreStrictlyIncreasingAndUnique() {
        List<Migration> migrations = Migrations.all();
        int previous = 0;
        for (Migration migration : migrations) {
            assertThat(migration.version()).isGreaterThan(previous);
            previous = migration.version();
        }
    }

    @Test
    void latestVersionMatchesHighestEntry() {
        assertThat(Migrations.latestVersion()).isEqualTo(Migrations.all().getLast().version());
    }

    @Test
    void everyMigrationHasStatements() {
        assertThat(Migrations.all()).allSatisfy(m -> assertThat(m.statements()).isNotEmpty());
    }
}