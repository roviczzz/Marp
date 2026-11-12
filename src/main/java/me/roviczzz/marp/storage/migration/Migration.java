package me.roviczzz.marp.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;

/** One ordered, forward-only schema migration. */
public interface Migration {

    int version();

    void apply(Connection connection) throws SQLException;
}
