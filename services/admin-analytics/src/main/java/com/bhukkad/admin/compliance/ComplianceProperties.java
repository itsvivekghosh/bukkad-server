package com.bhukkad.admin.compliance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.compliance")
public class ComplianceProperties {
    private boolean enabled = true;
    private int retentionDays = 90;
    private long retentionCleanupMs = 86400000L;
}
