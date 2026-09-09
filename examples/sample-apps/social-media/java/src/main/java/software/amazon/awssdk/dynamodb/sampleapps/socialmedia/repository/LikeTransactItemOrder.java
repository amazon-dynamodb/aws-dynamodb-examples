package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository;

/**
 * Ordered {@code TransactWriteItems} slots for an at-most-once like.
 *
 * <p>{@link ContentLikeTransaction} writes items in this order. {@code LikeService} reads
 * cancellation reasons through {@link #index()} so a failed guard on {@link #LIKE_PUT} maps to
 * {@code ALREADY_LIKED} and a failed guard on {@link #COUNTER_UPDATE} maps to {@code POST_NOT_FOUND}.
 */
public enum LikeTransactItemOrder {
    LIKE_PUT(0),
    COUNTER_UPDATE(1);

    private final int index;

    LikeTransactItemOrder(int index) {
        this.index = index;
    }

    /**
     * Returns the zero-based transaction item index for this slot.
     *
     * @return the item index
     */
    public int index() {
        return index;
    }
}
