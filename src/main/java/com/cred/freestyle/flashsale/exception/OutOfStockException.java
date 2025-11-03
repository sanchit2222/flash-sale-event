package com.cred.freestyle.flashsale.exception;

/**
 * Exception thrown when a product is out of stock.
 * This is a business logic exception indicating insufficient inventory.
 *
 * @author Flash Sale Team
 */
public class OutOfStockException extends RuntimeException {

    private final String skuId;
    private final Integer requestedQuantity;
    private final Integer availableQuantity;

    public OutOfStockException(String skuId, Integer requestedQuantity, Integer availableQuantity) {
        super(String.format("Product %s is out of stock. Requested: %d, Available: %d",
                skuId, requestedQuantity, availableQuantity));
        this.skuId = skuId;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    public String getSkuId() {
        return skuId;
    }

    public Integer getRequestedQuantity() {
        return requestedQuantity;
    }

    public Integer getAvailableQuantity() {
        return availableQuantity;
    }
}
