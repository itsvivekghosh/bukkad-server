package com.bhukkad.search.config;

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

    @Value("${app.search.elasticsearch.enabled:false}")
    private boolean elasticsearchEnabled;

    @Value("${app.search.elasticsearch.host:localhost}")
    private String host;

    @Value("${app.search.elasticsearch.port:9200}")
    private int port;

    @Value("${app.search.elasticsearch.username:}")
    private String username;

    @Value("${app.search.elasticsearch.password:}")
    private String password;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        if (!elasticsearchEnabled) {
            return null;
        }

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
