package com.bhukkad.catalog.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch client configuration.
 *
 * <p>Connects to an Elasticsearch 8.x cluster using the Java API Client.
 * Falls back gracefully when the cluster is unavailable.</p>
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${app.search.elasticsearch.host:localhost}")
    private String host;

    @Value("${app.search.elasticsearch.port:9200}")
    private int port;

    @Value("${app.search.elasticsearch.username:}")
    private String username;

    @Value("${app.search.elasticsearch.password:}")
    private String password;

    /**
     * The ES client is only created when the feature is actually enabled.
     *
     * <p>It used to be created unconditionally and return {@code null} from
     * the body when {@code app.search.elasticsearch.enabled} was false. Spring
     * still registers a null-returning factory method as a bean definition, so
     * Boot kept probing {@code localhost:9200} through its Elasticsearch health
     * indicator and reported the whole catalog service DOWN — even though
     * search has a Postgres fallback and the feature was switched off.
     *
     * <p>Gating the bean with {@code @ConditionalOnProperty} means: flag off →
     * no client → no health contributor → service stays UP on the DB path.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.search.elasticsearch", name = "enabled", havingValue = "true")
    public ElasticsearchClient elasticsearchClient() {
        try {
            RestClient restClient = createRestClient();
            RestClientTransport transport = new RestClientTransport(
                    restClient, new JacksonJsonpMapper());
            return new ElasticsearchClient(transport);
        } catch (Exception ex) {
            // Graceful degradation: return null if ES is unavailable
            return null;
        }
    }

    private RestClient createRestClient() {
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();

        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));
        }

        return RestClient.builder(new HttpHost(host, port, "http"))
                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                    @Override
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                        return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    }
                })
                .build();
    }
}
