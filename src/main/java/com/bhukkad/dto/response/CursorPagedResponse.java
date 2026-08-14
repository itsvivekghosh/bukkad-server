package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CursorPagedResponse<T> {

    private List<T> items;
    private String nextCursor;
    private boolean hasNext;
    private int size;

    public static <T> CursorPagedResponse<T> of(List<T> items, String nextCursor, boolean hasNext) {
        return CursorPagedResponse.<T>builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .size(items != null ? items.size() : 0)
                .build();
    }
}
