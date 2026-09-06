package com.bhukkad.support.wallet;

import com.bhukkad.support.wallet.WalletCreditClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WalletCreditClientImpl
implements WalletCreditClient {
    private static final Logger log = LoggerFactory.getLogger(WalletCreditClientImpl.class);

    public void credit(Long userId, double amount, String refId, Long paymentId, String description) {
        log.info("Stub wallet credit: userId={}, amount={}, refId={}, paymentId={}, description={}", new Object[]{userId, amount, refId, paymentId, description});
    }
}

