package com.codealpha.meetapp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolated database connectivity test running under the 'test' profile.
 * Uses in-memory H2 in PostgreSQL mode to guarantee tests never touch external databases.
 */
@SpringBootTest
@ActiveProfiles("test")
class DatabaseConnectionTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Context loads and connects strictly to in-memory test database")
    void testDatabaseConnectionUnderIsolatedProfile() throws Exception {
        assertNotNull(dataSource, "DataSource bean must be configured");

        try (Connection conn = dataSource.getConnection()) {
            assertNotNull(conn, "Connection must not be null");
            String url = conn.getMetaData().getURL();
            assertTrue(url.startsWith("jdbc:h2:"), "Automated test must strictly run against in-memory H2 database, not Supabase: " + url);
        }
    }
}
