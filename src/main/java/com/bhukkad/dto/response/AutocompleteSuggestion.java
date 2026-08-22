package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AutocompleteSuggestion {
    public static final String TYPE_RESTAURANT = "RESTAURANT";
    public static final String TYPE_MENU_ITEM = "MENU_ITEM";

    private String text;
    private String type; // RESTAURANT | MENU_ITEM
}
