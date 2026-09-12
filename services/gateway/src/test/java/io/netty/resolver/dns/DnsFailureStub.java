package io.netty.resolver.dns;

/**
 * Test-only stand-in whose class NAME (not behavior) triggers the
 * name-based DNS-failure detection in the gateway's upstream handler.
 */
public class DnsFailureStub extends RuntimeException {

    public DnsFailureStub(String message) {
        super(message);
    }
}
