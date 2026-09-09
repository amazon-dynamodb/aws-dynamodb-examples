package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model;

/**
 * Supported gaming platforms for player registration and matchmaking.
 */
public enum Platform {

    /** Apple mobile platform. */
    IOS,

    /** Google Android mobile platform. */
    ANDROID,

    /** Desktop or other non-mobile clients. */
    PC
}
