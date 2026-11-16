package com.bhukkad.commerce.order.domain.authz;

import com.bhukkad.common.security.authz.AuthorizationRequest;
import com.bhukkad.common.security.authz.ResourceOwnershipPolicy;
import org.springframework.stereotype.Component;

/**
 * Order-domain read policy: a customer may read only their own order, while
 * an administrator may read any order.
 */
@Component
public class OrderOwnershipPolicy extends ResourceOwnershipPolicy {

    public static final String POLICY_ID = "order-ownership";
    public static final String RESOURCE_TYPE = "order";

    public OrderOwnershipPolicy() {
        super(POLICY_ID, RESOURCE_TYPE);
    }

    @Override
    public boolean supports(AuthorizationRequest request) {
        return request != null
                && "read".equalsIgnoreCase(request.action())
                && super.supports(request);
    }
}
