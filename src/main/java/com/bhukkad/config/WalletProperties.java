package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.wallet")
public class WalletProperties {
    private boolean allowDirectTopUp = false;
}
