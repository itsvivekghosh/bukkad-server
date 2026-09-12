package com.bhukkad.realtime.domain.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiveUpdateEvent {
    private String type;
    private Object payload;
}
