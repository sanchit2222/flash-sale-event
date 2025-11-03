package com.cred.freestyle.flashsale.exception;

/**
 * Exception thrown when a user attempts to purchase more than their allowed limit.
 * Flash sale rule: Each user can purchase at most 1 unit of each product.
 *
 * @author Flash Sale Team
 */
public class UserLimitExceededException extends RuntimeException {

    private final String userId;
    private final String skuId;

    public UserLimitExceededException(String userId, String skuId) {
        super(String.format("User %s has already purchased product %s. Purchase limit: 1 unit per product",
                userId, skuId));
        this.userId = userId;
        this.skuId = skuId;
    }

    public String getUserId() {
        return userId;
    }

    public String getSkuId() {
        return skuId;
    }
}
