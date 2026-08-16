package com.bhukkad.live;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.live.sse.replay")
public class OrderLiveReplayProperties {

    private int maxEventsPerStream = 200;
    private long ttlSeconds = 3600;
}
