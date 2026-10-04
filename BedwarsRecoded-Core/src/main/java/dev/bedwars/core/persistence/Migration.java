package dev.bedwars.core.persistence;

import java.util.List;

/**
 * One forward-only schema change. Migrations are ordered by {@code version} and
 * applied at most once (tracked in {@code schema_version}). Never edit an
 * applied migration — add a new one.
 */
public record Migration(int version, String description, List<String> statements) {

    public Migration {
        if (version < 1) {
            throw new IllegalArgumentException("migration version must be >= 1");
        }
        statements = List.copyOf(statements);
    }

    public static Migration of(int version, String description, String... statements) {
        return new Migration(version, description, List.of(statements));
    }
}