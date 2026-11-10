package com.codealpha.meetapp.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Startup self-check component verifying database connectivity.
 * Safely logs only the host and database name without exposing credentials.
 */
@Component
public class DatabaseConnectionValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionValidator.java);
    private static final Pattern JDBC_HOST_PATTERN = Pattern.compile("jdbc:postgresql://([^:/]+)");

    private final DataSource dataSource;

    public DatabaseConnectionValidator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String rawUrl = metaData.getURL();
            String databaseName = connection.getCatalog();
            if (databaseName == null || databaseName.isBlank()) {
                databaseName = connection.getSchema();
            }

            String host = "unknown";
            if (rawUrl != null) {
                Matcher matcher = JDBC_HOST_PATTERN.matcher(rawUrl);
                if (matcher.find()) {
                    host = matcher.group(1);
                } else if (rawUrl.startsWith("jdbc:h2:")) {
                    host = "h2-in-memory";
                }
            }

            log.info("Database connection verified: connected to PostgreSQL host '{}', database '{}'", host, databaseName);
        } catch (Exception e) {
            log.error("Database connection failure: unable to verify database connection - {}", e.getMessage());
        }
    }
}
