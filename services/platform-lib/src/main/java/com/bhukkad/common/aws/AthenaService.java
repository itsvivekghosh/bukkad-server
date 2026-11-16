package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;

import java.util.List;

/**
 * AWS Athena service for analytics queries on S3 data.
 * <p>
 * Provides operations for running ad-hoc SQL queries against data stored
 * in S3 (e.g., order events, restaurant analytics, customer behavior data).
 * <p>
 * Use cases in Bhukkad:
 * - Daily order analytics (revenue, order volume by restaurant)
 * - Customer behavior analysis (favorite cuisines, ordering patterns)
 * - Delivery performance metrics (delivery time, agent performance)
 * - Business intelligence dashboards
 * <p>
 * Fully testable against Floci emulator locally.
 */
@Service
public class AthenaService {

    private final AthenaClient athenaClient;
    private final AwsProperties awsProperties;

    public AthenaService(AthenaClient athenaClient, AwsProperties awsProperties) {
        this.athenaClient = athenaClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Executes an Athena query against S3 data.
     */
    public StartQueryExecutionResponse startQueryExecution(
            String query, String database, String outputLocation) {
        return athenaClient.startQueryExecution(StartQueryExecutionRequest.builder()
                .queryString(query)
                .queryExecutionContext(QueryExecutionContext.builder()
                        .database(database)
                        .build())
                .resultConfiguration(ResultConfiguration.builder()
                        .outputLocation(outputLocation)
                        .build())
                .build());
    }

    /**
     * Gets the status of a query execution.
     */
    public GetQueryExecutionResponse getQueryExecution(String queryExecutionId) {
        return athenaClient.getQueryExecution(GetQueryExecutionRequest.builder()
                .queryExecutionId(queryExecutionId)
                .build());
    }

    /**
     * Gets query results.
     */
    public GetQueryResultsResponse getQueryResults(String queryExecutionId) {
        return athenaClient.getQueryResults(GetQueryResultsRequest.builder()
                .queryExecutionId(queryExecutionId)
                .build());
    }

    /**
     * Stops a running query execution.
     */
    public StopQueryExecutionResponse stopQueryExecution(String queryExecutionId) {
        return athenaClient.stopQueryExecution(StopQueryExecutionRequest.builder()
                .queryExecutionId(queryExecutionId)
                .build());
    }

    /**
     * Lists all workgroups.
     */
    public ListWorkGroupsResponse listWorkGroups() {
        return athenaClient.listWorkGroups(ListWorkGroupsRequest.builder().build());
    }

    /**
     * Runs the daily order revenue query.
     */
    public StartQueryExecutionResponse runDailyRevenueQuery(String database, String outputLocation, int days) {
        String query = String.format(
                "SELECT order_date, COUNT(*) as order_count, SUM(order_total) as revenue " +
                "FROM bhukkad_orders " +
                "WHERE order_date >= date_sub(current_date, %d) " +
                "GROUP BY order_date " +
                "ORDER BY order_date DESC", days);
        return startQueryExecution(query, database, outputLocation);
    }

    /**
     * Runs the top restaurants query.
     */
    public StartQueryExecutionResponse runTopRestaurantsQuery(String database, String outputLocation, int limit) {
        String query = String.format(
                "SELECT restaurant_id, restaurant_name, COUNT(*) as order_count, " +
                "SUM(order_total) as revenue " +
                "FROM bhukkad_orders " +
                "GROUP BY restaurant_id, restaurant_name " +
                "ORDER BY order_count DESC " +
                "LIMIT %d", limit);
        return startQueryExecution(query, database, outputLocation);
    }
}