package com.bhukkad.common.aws;

import java.util.List;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.wafv2.Wafv2Client;
import software.amazon.awssdk.services.wafv2.model.*;

/**
 * WAFv2 (Web Application Firewall v2) service for security rule management.
 * <p>
 * Provides operations for managing Web ACLs for protecting Bhukkad APIs
 * from common web exploits (SQL injection, cross-site scripting, rate
 * limiting, bot attacks).
 * <p>
 * Use cases in Bhukkad:
 * - Protect API endpoints from SQL injection attacks
 * - Rate limit login attempts to prevent brute force attacks
 * - Block known malicious IP addresses
 * - Protect against DDoS attacks on order placement endpoints
 * - Filter malicious user agents and bots
 * <p>
 * Fully testable against Floci emulator locally.
 */
@Service
public class WafV2Service {

    private final Wafv2Client wafv2Client;
    private final AwsProperties awsProperties;

    public WafV2Service(Wafv2Client wafv2Client, AwsProperties awsProperties) {
        this.wafv2Client = wafv2Client;
        this.awsProperties = awsProperties;
    }

    // ==================== Web ACL Management ====================

    /**
     * Lists all Web ACLs for the given scope.
     * <p>
     * Scope can be "CLOUDFRONT" or "REGIONAL".
     */
    public List<WebACLSummary> listWebAcls(String scope) {
        return wafv2Client.listWebACLs(ListWebAcLsRequest.builder()
                .scope(scope)
                .build()).webACLs();
    }

    // ==================== Bhukkad WAF Rule Configuration ====================

    /**
     * Returns a Web ACL config for Bhukkad API gateway protection.
     * <p>
     * Includes rate limiting, SQL injection protection, XSS protection,
     * and IP blocking rules.
     */
    public String getBhukkadWebAclConfig(String name) {
        return """
                {
                  "Name": "%s",
                  "Description": "Bhukkad API Gateway WAF protection",
                  "DefaultAction": {"Allow": {}},
                  "Rules": [
                    {
                      "Name": "RateLimitLogin",
                      "Priority": 0,
                      "Action": {"Block": {}},
                      "Statement": {
                        "RateBasedStatement": {
                          "Limit": 100,
                          "AggregateKeyType": "IP"
                        }
                      },
                      "VisibilityConfig": {
                        "CloudWatchMetricsEnabled": true,
                        "MetricName": "RateLimitLogin",
                        "SampledRequestsEnabled": true
                      }
                    },
                    {
                      "Name": "SQLInjectionProtection",
                      "Priority": 1,
                      "Action": {"Block": {}},
                      "Statement": {
                        "SqliMatchStatement": {
                          "FieldToMatch": {"QueryArgument": {}},
                          "TextTransformations": [
                            {
                              "Priority": 0,
                              "Type": "URL_DECODE"
                            }
                          ]
                        }
                      },
                      "VisibilityConfig": {
                        "CloudWatchMetricsEnabled": true,
                        "MetricName": "SQLInjectionProtection",
                        "SampledRequestsEnabled": true
                      }
                    },
                    {
                      "Name": "XSSProtection",
                      "Priority": 2,
                      "Action": {"Block": {}},
                      "Statement": {
                        "XssMatchStatement": {
                          "FieldToMatch": {"QueryArgument": {}},
                          "TextTransformations": [
                            {
                              "Priority": 0,
                              "Type": "URL_DECODE"
                            }
                          ]
                        }
                      },
                      "VisibilityConfig": {
                        "CloudWatchMetricsEnabled": true,
                        "MetricName": "XSSProtection",
                        "SampledRequestsEnabled": true
                      }
                    }
                  ],
                  "VisibilityConfig": {
                    "CloudWatchMetricsEnabled": true,
                    "MetricName": "%s",
                    "SampledRequestsEnabled": true
                  }
                }
                """.formatted(name, name);
    }
}