package com.bhukkad.service;

import com.bhukkad.dto.request.DeviceTokenRequest;
import com.bhukkad.dto.response.DeviceTokenResponse;

public interface DeviceTokenService {
    DeviceTokenResponse registerToken(DeviceTokenRequest request);
    void unregisterToken(String token);
}
