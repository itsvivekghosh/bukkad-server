package com.bhukkad.config;

import com.bhukkad.live.OrderLiveReplayProperties;
import com.bhukkad.storage.ImageStorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        OrderLiveReplayProperties.class,
        StompBrokerProperties.class,
        ImageStorageProperties.class
})
public class LiveStreamConfig {
}
