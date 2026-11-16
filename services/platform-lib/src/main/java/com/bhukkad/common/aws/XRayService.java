package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.xray.XRayClient;

/**
 * AWS X-Ray service for distributed tracing.
 * <p>
 * Provides operations for managing AWS X-Ray traces.
 * <p>
 * Use cases in Bhukkad:
 * - Distributed tracing across microservices
 *   <ul>
 *     <li>Fully testable against Floci emulator locally.</li>
 * </ul>
 */
@Service
public class XRayService {

    private final XRayClient xrayClient;

    public XRayService(XRayClient xrayClient) {
        this.xrayClient = xrayClient;
    }
}