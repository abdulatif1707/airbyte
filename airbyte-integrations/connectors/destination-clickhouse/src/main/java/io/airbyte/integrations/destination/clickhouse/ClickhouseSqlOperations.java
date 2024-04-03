/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.clickhouse;

import com.clickhouse.client.ClickHouseFormat;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseStatement;
import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.cdk.db.jdbc.JdbcDatabase;
import io.airbyte.cdk.integrations.base.JavaBaseConstants;
import io.airbyte.cdk.integrations.base.TypingAndDedupingFlag;
import io.airbyte.cdk.integrations.destination.jdbc.JdbcSqlOperations;
import io.airbyte.cdk.integrations.destination_async.partial_messages.PartialAirbyteMessage;
import io.airbyte.commons.json.Jsons;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ClickhouseSqlOperations extends JdbcSqlOperations {

    private static final String COLUMN_NAME_TABLE_ID = "_table_id";
    private static final String COLUMN_NAME_TABLE_MODIFIED_AT = "_table_modified_at";

    private static final String JSON_PATH_ID = "id";
    private static final String JSON_PATH_MODIFIED_AT = "modified_at";

    private static final Logger LOGGER = LoggerFactory.getLogger(ClickhouseSqlOperations.class);

    @Override
    public void createSchemaIfNotExists(final JdbcDatabase database, final String schemaName) throws Exception {
        database.execute(String.format("CREATE DATABASE IF NOT EXISTS %s;\n", schemaName));
    }

    @Override
    public String createTableQuery(final JdbcDatabase database, final String schemaName, final String tableName) {
        return String.format("""
                        CREATE TABLE IF NOT EXISTS `%s`.`%s` (
                            %s String,
                            %s String,
                            %s String,
                            %s DateTime64(5, 'GMT'),
                            %s DateTime64(5, 'GMT') default now(),
                            %s Nullable(DateTime64(5, 'GMT'))
                        ) ENGINE = ReplacingMergeTree(%s)
                        PRIMARY KEY %s
                        ORDER BY %s;
                        """,
                schemaName,
                tableName,
                JavaBaseConstants.COLUMN_NAME_AB_RAW_ID,
                COLUMN_NAME_TABLE_ID,
                JavaBaseConstants.COLUMN_NAME_DATA,
                COLUMN_NAME_TABLE_MODIFIED_AT,
                JavaBaseConstants.COLUMN_NAME_AB_EXTRACTED_AT,
                JavaBaseConstants.COLUMN_NAME_AB_LOADED_AT,
                COLUMN_NAME_TABLE_MODIFIED_AT,
                COLUMN_NAME_TABLE_ID,
                COLUMN_NAME_TABLE_ID);
    }

    @Override
    protected void writeBatchToFile(File tmpFile, List<PartialAirbyteMessage> records) throws Exception {
        try (PrintWriter writer = new PrintWriter(tmpFile, StandardCharsets.UTF_8);
             CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT)) {

            for (PartialAirbyteMessage record : records) {
                JsonNode jsonNode = Jsons.deserializeExact(record.getSerialized());
                String uuid = UUID.randomUUID().toString();
                String id = extractByKey(jsonNode, JSON_PATH_ID);
                String jsonData = Jsons.serialize(this.formatData(jsonNode));
                Timestamp modifiedAt = toTimestamp(extractByKey(jsonNode, JSON_PATH_MODIFIED_AT));
                Timestamp extractedAt = Timestamp.from(Instant.ofEpochMilli(record.getRecord().getEmittedAt()));
                if (TypingAndDedupingFlag.isDestinationV2()) {
                    csvPrinter.printRecord(uuid, id, jsonData, modifiedAt, extractedAt, null);
                } else {
                    csvPrinter.printRecord(uuid, id, jsonData, modifiedAt, extractedAt);
                }
            }
        }
    }

    @Override
    public void executeTransaction(final JdbcDatabase database, final List<String> queries) throws Exception {
        // Note: ClickHouse does not support multi query
        for (final String query : queries) {
            database.execute(query);
        }
    }

    @Override
    public boolean isSchemaRequired() {
        return false;
    }

    @Override
    public void insertRecordsInternal(final JdbcDatabase database,
                                      final List<PartialAirbyteMessage> records,
                                      final String schemaName,
                                      final String tmpTableName)
            throws SQLException {
        LOGGER.info("actual size of batch: {}, tmpTableName: ", records.size(), tmpTableName);

        if (records.isEmpty()) {
            return;
        }

        database.execute(connection -> {
            File tmpFile = null;
            Exception primaryException = null;
            try {
                tmpFile = Files.createTempFile(tmpTableName + "-", ".tmp").toFile();
                writeBatchToFile(tmpFile, records);

                final ClickHouseConnection conn = connection.unwrap(ClickHouseConnection.class);
                final ClickHouseStatement sth = conn.createStatement();
                sth.write() // Write API entrypoint
                        .table(String.format("%s.%s", schemaName, tmpTableName)) // where to write data
                        .format(ClickHouseFormat.CSV) // set a format
                        .data(tmpFile.getAbsolutePath()) // specify input
                        .send()
                        .thenRun(() -> optimizeTable(conn, schemaName, tmpTableName));

            } catch (final Exception e) {
                primaryException = e;
                throw new RuntimeException(e);
            } finally {
                try {
                    if (tmpFile != null) {
                        Files.delete(tmpFile.toPath());
                    }
                } catch (final IOException e) {
                    if (primaryException != null)
                        e.addSuppressed(primaryException);
                    throw new RuntimeException(e);
                }
            }

        });

    }

    @Override
    protected void insertRecordsInternalV2(final JdbcDatabase database,
                                           final List<PartialAirbyteMessage> records,
                                           final String schemaName,
                                           final String tableName)
            throws Exception {
        insertRecordsInternal(database, records, schemaName, tableName);
    }

    private Timestamp toTimestamp(String data) {
        if (StringUtils.isBlank(data)) return Timestamp.from(Instant.now());
        try {
            return Timestamp.from(Instant.parse(data + "Z"));
        } catch (Throwable th) {
            LOGGER.error(th.getMessage(), th);
            return Timestamp.from(Instant.now());
        }
    }

    private String extractByKey(JsonNode node, String key) {
        if (node.has(StringUtils.lowerCase(key))) {
            return extractAsString(node, StringUtils.lowerCase(key));
        }
        if (node.has(StringUtils.upperCase(key))) {
            return extractAsString(node, StringUtils.upperCase(key));
        }
        return "";
    }

    private String extractAsString(JsonNode node, String key) {
        return node.findValuesAsText(key).stream().findFirst().orElse(null);
    }

    /**
     * Optimizes a table in the ClickHouse database.
     *
     * @param conn        The ClickHouse connection.
     * @param schemaName  The name of the schema.
     * @param tableName   The name of the table.
     */
    private void optimizeTable(final ClickHouseConnection conn,
                               final String schemaName,
                               final String tableName) {
        try {
            //noinspection SqlSourceToSinkFlow
            conn.createStatement()
                    .execute(optimizeTableQuery(schemaName, tableName));
        } catch (SQLException e) {
            LOGGER.error(e.getSQLState());
        }
    }

    /**
     * Generates an optimize table query for a given table in the database schema.
     *
     * @param schemaName The name of the schema.
     * @param tableName  The name of the table.
     * @return The optimize table query as a String.
     */
    protected String optimizeTableQuery(final String schemaName,
                                        final String tableName) {
        return String.format("optimize table `%s`.`%s` final deduplicate;\n", schemaName, tableName);
    }

}
