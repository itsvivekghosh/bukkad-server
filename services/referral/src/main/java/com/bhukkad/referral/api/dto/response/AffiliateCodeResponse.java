package com.bhukkad.referral.api.dto.response;

/** Affiliate code admin view. */
public class AffiliateCodeResponse {

    private Long id;
    private String code;
    private String name;
    private String channel;
    private Double rewardAmount;
    private Boolean isActive;
    private String createdAt;

    public AffiliateCodeResponse() {
    }

    public AffiliateCodeResponse(Long id, String code, String name, String channel,
                                 Double rewardAmount, Boolean isActive, String createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.channel = channel;
        this.rewardAmount = rewardAmount;
        this.isActive = isActive;
        this.createdAt = createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Double getRewardAmount() {
        return rewardAmount;
    }

    public void setRewardAmount(Double rewardAmount) {
        this.rewardAmount = rewardAmount;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public static class Builder {
        private Long id;
        private String code;
        private String name;
        private String channel;
        private Double rewardAmount;
        private Boolean isActive;
        private String createdAt;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder channel(String channel) {
            this.channel = channel;
            return this;
        }

        public Builder rewardAmount(Double rewardAmount) {
            this.rewardAmount = rewardAmount;
            return this;
        }

        public Builder isActive(Boolean isActive) {
            this.isActive = isActive;
            return this;
        }

        public Builder createdAt(String createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public AffiliateCodeResponse build() {
            return new AffiliateCodeResponse(id, code, name, channel, rewardAmount, isActive, createdAt);
        }
    }
}