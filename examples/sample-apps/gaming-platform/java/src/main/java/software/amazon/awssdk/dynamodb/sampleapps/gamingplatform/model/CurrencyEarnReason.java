package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model;

/**
 * Classifies the origin of a soft-currency credit recorded in the GameEvent table.
 *
 * <p>Every {@link GameEventType#CURRENCY_GRANT} event carries one of these reasons so the
 * economy audit trail is self-describing and can be filtered by downstream analytics.
 */
public enum CurrencyEarnReason {

    /** Bonus awarded when a player wins a PvP match. */
    MATCH_WIN,

    /** Milestone bonus awarded when a player reaches a new level. */
    LEVEL_UP_BONUS,

    /** Daily login reward granted once per calendar day. */
    DAILY_LOGIN,

    /** Manual grant issued by an admin or customer-support agent. */
    ADMIN_GRANT
}

