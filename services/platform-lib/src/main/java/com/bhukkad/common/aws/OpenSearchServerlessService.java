package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.opensearchserverless.OpenSearchServerlessClient;
import software.amazon.awssdk.services.opensearchserverless.model.*;

/**
 * AWS OpenSearch Serverless service for search and analytics.
 * <p>
 * Provides operations for managing OpenSearch Serverless collections,
 * indexes, and search capabilities.
 * <p>
 * Use cases in Bhukkad:
 * - Full-text search for restaurants, menu items, and cuisines
 * - Geospatial search for nearby restaurants
 * - Search analytics for popular search terms and trends
 * - Log analytics for order and delivery events
 * <p>
 * Fully testable against Floci emulator locally.
 */
@Service
public class OpenSearchServerlessService {

    private final OpenSearchServerlessClient openSearchServerlessClient;
    private final AwsProperties awsProperties;

    public OpenSearchServerlessService(OpenSearchServerlessClient openSearchServerlessClient,
                                       AwsProperties awsProperties) {
        this.openSearchServerlessClient = openSearchServerlessClient;
        this.awsProperties = awsProperties;
    }

    // ==================== Collection Management ====================

    /**
     * Creates an OpenSearch Serverless collection for Bhukkad data.
     * <p>
     * Collections are the top-level resource in OpenSearch Serverless
     * that contain indexes and data.
     */
    public CreateCollectionResponse createCollection(
            String collectionName, String description) {
        return openSearchServerlessClient.createCollection(CreateCollectionRequest.builder()
                .name(collectionName)
                .description(description)
                .build());
    }

    /**
     * Lists all OpenSearch Serverless collections.
     */
    public ListCollectionsResponse listCollections() {
        return openSearchServerlessClient.listCollections(ListCollectionsRequest.builder().build());
    }

    /**
     * Deletes an OpenSearch Serverless collection.
     */
    public DeleteCollectionResponse deleteCollection(String collectionId) {
        return openSearchServerlessClient.deleteCollection(DeleteCollectionRequest.builder()
                .id(collectionId)
                .build());
    }

    // ==================== Bhukkad Search Index Templates ====================

    /**
     * Returns a search index mapping for restaurants.
     * <p>
     * Includes fields for full-text search, filtering, and geospatial queries.
     */
    public String getRestaurantIndexMapping() {
        return """
                {
                  "settings": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0
                  },
                  "mappings": {
                    "properties": {
                      "restaurant_id": { "type": "keyword" },
                      "name": { "type": "text", "analyzer": "standard" },
                      "cuisine": { "type": "keyword" },
                      "description": { "type": "text" },
                      "address": { "type": "text" },
                      "location": { "type": "geo_point" },
                      "rating": { "type": "float" },
                      "delivery_time_minutes": { "type": "integer" },
                      "is_active": { "type": "boolean" },
                      "created_at": { "type": "date" }
                    }
                  }
                }
                """;
    }

    /**
     * Returns a search index mapping for menu items.
     */
    public String getMenuItemIndexMapping() {
        return """
                {
                  "settings": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0
                  },
                  "mappings": {
                    "properties": {
                      "menu_item_id": { "type": "keyword" },
                      "restaurant_id": { "type": "keyword" },
                      "name": { "type": "text", "analyzer": "standard" },
                      "description": { "type": "text" },
                      "category": { "type": "keyword" },
                      "price": { "type": "float" },
                      "is_veg": { "type": "boolean" },
                      "is_available": { "type": "boolean" },
                      "created_at": { "type": "date" }
                    }
                  }
                }
                """;
    }

    /**
     * Returns an encryption policy for data at rest.
     */
    public String getEncryptionPolicyDocument() {
        return """
                {
                  "Rules": [
                    {
                      "Resource": "collection/*",
                      "ResourceType": "collection"
                    }
                  ],
                  "AWSOwnedKey": true
                }
                """;
    }

    /**
     * Returns an access policy for collection access.
     */
    public String getAccessPolicyDocument() {
        return """
                {
                  "Rules": [
                    {
                      "Resource": ["collection/bhukkad-*"],
                      "Permission": [
                        "aoss:CreateCollectionItems",
                        "aoss:DescribeCollectionItems",
                        "aoss:DeleteCollectionItems",
                        "aoss:UpdateCollectionItems",
                        "aoss:ReadCollectionItems",
                        "aoss:WriteCollectionItems"
                      ],
                      "ResourceType": "collection"
                    },
                    {
                      "Resource": ["index/bhukkad-*/*"],
                      "Permission": [
                        "aoss:CreateIndex",
                        "aoss:DeleteIndex",
                        "aoss:UpdateIndex",
                        "aoss:DescribeIndex",
                        "aoss:ReadDocument",
                        "aoss:WriteDocument"
                      ],
                      "ResourceType": "index"
                    }
                  ],
                  "Principal": [
                    {
                      "AWS": ["arn:aws:iam::123456789012:role/bhukkad-catalog-service-role"]
                    }
                  ]
                }
                """;
    }
}