package com.bhukkad.realtime.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LivePropertiesTest {

    @Test
    void defaultValues() {
        LiveProperties props = new LiveProperties();

        assertEquals(50, props.getMaxEmittersPerStream());
        assertEquals(2000, props.getMaxTotalEmitters());
        assertNotNull(props.getReplay());
    }

    @Test
    void replayPropertiesDefaults() {
        LiveProperties.ReplayProperties replay = new LiveProperties.ReplayProperties();

        assertEquals(3600, replay.getTtlSeconds());
        assertEquals(100, replay.getMaxEventsPerStream());
    }

    @Test
    void customValues() {
        LiveProperties props = new LiveProperties();
        props.setMaxEmittersPerStream(100);
        props.setMaxTotalEmitters(5000);

        LiveProperties.ReplayProperties replay = new LiveProperties.ReplayProperties();
        replay.setTtlSeconds(7200);
        replay.setMaxEventsPerStream(200);
        props.setReplay(replay);

        assertEquals(100, props.getMaxEmittersPerStream());
        assertEquals(5000, props.getMaxTotalEmitters());
        assertEquals(7200, props.getReplay().getTtlSeconds());
        assertEquals(200, props.getReplay().getMaxEventsPerStream());
    }
}
