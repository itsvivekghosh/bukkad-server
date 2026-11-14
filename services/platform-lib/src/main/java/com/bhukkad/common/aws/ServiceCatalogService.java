package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.servicecatalog.ServiceCatalogClient;
import software.amazon.awssdk.services.servicecatalog.model.*;

/**
 * Service Catalog service for service template management.
 * <p>
 * Provides operations for managing Service Catalog portfolios, products,
 * and provisioned products for standardized service deployment.
 * Fully testable against Floci emulator locally.
 */
@Service
public class ServiceCatalogService {

    private final ServiceCatalogClient serviceCatalogClient;
    private final AwsProperties awsProperties;

    public ServiceCatalogService(ServiceCatalogClient serviceCatalogClient, AwsProperties awsProperties) {
        this.serviceCatalogClient = serviceCatalogClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a Service Catalog portfolio.
     */
    public CreatePortfolioResponse createPortfolio(String portfolioName, String description) {
        return serviceCatalogClient.createPortfolio(CreatePortfolioRequest.builder()
                .displayName(portfolioName)
                .description(description)
                .build());
    }

    /**
     * Lists all portfolios.
     */
    public ListPortfoliosResponse listPortfolios() {
        return serviceCatalogClient.listPortfolios(ListPortfoliosRequest.builder().build());
    }

    /**
     * Describes a portfolio.
     */
    public DescribePortfolioResponse describePortfolio(String portfolioId) {
        return serviceCatalogClient.describePortfolio(DescribePortfolioRequest.builder()
                .id(portfolioId)
                .build());
    }

    /**
     * Creates a product in a portfolio.
     */
    public CreateProductResponse createProduct(
            String portfolioId, String productName, String owner, String description) {
        return serviceCatalogClient.createProduct(CreateProductRequest.builder()
                .name(productName)
                .owner(owner)
                .description(description)
                .productType(ProductType.CLOUD_FORMATION_TEMPLATE)
                .idempotencyToken(java.util.UUID.randomUUID().toString())
                .build());
    }

    /**
     * Describes a product view.
     */
    public DescribeProductViewResponse describeProductView(String productId) {
        return serviceCatalogClient.describeProductView(DescribeProductViewRequest.builder()
                .id(productId)
                .build());
    }
}