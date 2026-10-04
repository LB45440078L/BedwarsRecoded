# Database migrations

Schema changes are **forward-only** and **idempotent**. They are applied on startup
by `dev.bedwars.core.persistence.SchemaMigrator`, tracked in a `schema_version`
table, and run inside a transaction each — a migration is recorded only if every
statement in it succeeded.

## How it works

1. On boot, `SchemaMigrator.migrate(Migrations.all())` creates `schema_version` if
   absent.
2. It reads the current version (`MAX(version)`).
3. For every migration with a higher version, it runs the statements in a
   transaction, then inserts `(version, description)`.
4. Re-running is a no-op: applied versions are skipped.

Migrations live in `dev.bedwars.core.persistence.Migrations#all()`.

## Adding a migration

Append a new `Migration` with the **next** version number. Never edit an applied
migration — deployed databases will not re-run it, so edits silently diverge.

```java
Migration.of(4, "add player_stats.killstreak_best",
        "ALTER TABLE player_stats ADD COLUMN killstreak_best INT NOT NULL DEFAULT 0")
```

Rules:

- **Ordered** — versions must be strictly increasing; `MigrationsTest` enforces this.
- **Idempotent where possible** — prefer `CREATE TABLE IF NOT EXISTS`,
  `ADD COLUMN IF NOT EXISTS` (or guard in code) so a partially-applied environment
  can recover.
- **One logical change per migration** — keeps rollbacks and review simple.
- **No data loss** — additive changes only; dropping columns requires a deprecation
  migration first.
- **Test it** — `MigrationsTest` asserts ordering, non-empty statements, and that
  `latestVersion()` matches the highest entry.

## Versioning convention

| Range | Owner |
|---|---|
| 1–999 | core schema (player_stats and related) |
| 1000+ | component-specific extensions |

## Rolling back

There are no down-migrations by design. To undo a change, add a new forward
migration that reverses it. This keeps every environment moving in one direction and
avoids the "which down-migration ran?" class of bugs.