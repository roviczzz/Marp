package me.roviczzz.marp.storage.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import me.roviczzz.marp.storage.StorageException;

/**
 * Applies pending migrations in order on the database thread and refuses to
 * open a database written by a newer plugin version.
 */
public final class MigrationRunner {

    private final List<Migration> migrations;

    public MigrationRunner(List<Migration> migrations) {
        this.migrations = migrations.stream()
                .sorted(Comparator.comparingInt(Migration::version))
                .toList();
    }

    public static MigrationRunner standard() {
        return new MigrationRunner(List.of(new V1InitialSchema()));
    }

    public int migrate(Connection connection) throws SQLException {
        ensureSchemaTable(connection);
        int current = readVersion(connection);
        int latest = migrations.stream().mapToInt(Migration::version).max().orElse(0);
        if (current > latest) {
            throw new StorageException(
                    "Database schema version " + current + " is newer than supported version " + latest);
        }

        boolean autoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            for (Migration migration : migrations) {
                if (migration.version() <= current) {
                    continue;
                }
                try {
                    migration.apply(connection);
                    writeVersion(connection, migration.version());
                    connection.commit();
                    current = migration.version();
                } catch (SQLException | RuntimeException exception) {
                    connection.rollback();
                    throw exception;
                }
            }
            return current;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    public static int readVersion(Connection connection) throws SQLException {
        ensureSchemaTable(connection);
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT version FROM marp_schema LIMIT 1")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void ensureSchemaTable(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS marp_schema (version INTEGER NOT NULL)");
        }
    }

    private static void writeVersion(Connection connection, int version) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DELETE FROM marp_schema");
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO marp_schema (version) VALUES (?)")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }
}
