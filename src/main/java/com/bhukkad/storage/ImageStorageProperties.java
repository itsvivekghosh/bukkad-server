package com.bhukkad.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.storage.s3")
public class ImageStorageProperties {

    private boolean enabled = false;
    private String bucket = "bhukkad-menu-images";
    private String region = "ap-south-1";
    private String keyPrefix = "menu-items";
    private long uploadUrlExpirySeconds = 900;
    private long downloadUrlExpirySeconds = 3600;
}
