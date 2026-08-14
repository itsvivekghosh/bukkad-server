package com.bhukkad.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class ReadReplicaRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return ReadReplicaContext.get();
    }
}
