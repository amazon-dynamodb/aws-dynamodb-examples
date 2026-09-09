package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model;

/**
 * Types of game events that can be recorded in the event history.
 */
public enum GameEventType {

    /** Level or experience progression. */
    LEVEL_PROGRESS,

    /** In-game purchase using soft currency. */
    PURCHASE,

    /** Player versus player match outcome. Projects to the leaderboard when streamed. */
    PVP_MATCH,

    /** Admin or promotional currency grant. */
    CURRENCY_GRANT
}
