package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupOrderResponse {

    private Long id;
    private Long hostUserId;
    private String title;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime placedAt;
    private List<MemberSplit> members;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberSplit {
        private Long id;
        private Long userId;
        private String invitePhone;
        private String status;
        private Double amountContribution;
        private Boolean paid;
        private LocalDateTime joinedAt;
    }
}