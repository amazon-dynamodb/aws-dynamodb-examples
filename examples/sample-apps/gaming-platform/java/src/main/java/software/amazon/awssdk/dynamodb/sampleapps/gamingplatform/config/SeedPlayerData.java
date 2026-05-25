package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config;

import java.util.List;
import java.util.Map;

import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Provides seed player profiles, settings, and wallets for local development and integration testing.
 *
 * <p>These rows support flows that need existing players (lobby batch summaries, leaderboard reads,
 * and {@code GSI_PLATFORM_PLAYERS} browse) without relying on prior registration calls.
 */
public final class SeedPlayerData {

    /** First deterministic seed player id. */
    public static final String SEED_PLAYER_1 = "seed-player-1";

    /** Second deterministic seed player id. */
    public static final String SEED_PLAYER_2 = "seed-player-2";

    /** Third deterministic seed player id. */
    public static final String SEED_PLAYER_3 = "seed-player-3";

    /** Fourth deterministic seed player id. */
    public static final String SEED_PLAYER_4 = "seed-player-4";

    /** Fifth deterministic seed player id. */
    public static final String SEED_PLAYER_5 = "seed-player-5";

    /** Not instantiated. */
    private SeedPlayerData() {
    }

    /**
     * Returns seed player profiles as DynamoDB item maps for {@code PutItem} operations.
     *
     * @return list of attribute maps, one per seed profile
     */
    public static List<Map<String, AttributeValue>> samplePlayerProfilesAsMaps() {
        return List.of(
                buildPlayerMap(SEED_PLAYER_1, "PC", "steam-user-001", "AlphaWolf",
                        10, 3500, 1, "2026-01-15T10:00:00Z"),
                buildPlayerMap(SEED_PLAYER_2, "IOS", "apple-user-002", "BraveFox",
                        13, 4200, 1, "2026-02-20T14:30:00Z"),
                buildPlayerMap(SEED_PLAYER_3, "ANDROID", "google-user-003", "CosmicRay",
                        5, 1200, 1, "2026-03-10T08:15:00Z"),
                buildPlayerMap(SEED_PLAYER_4, "IOS", "apple-user-004", "DeltaStrike",
                        8, 2800, 1, "2026-03-25T16:45:00Z"),
                buildPlayerMap(SEED_PLAYER_5, "PC", "steam-user-005", "EchoNova",
                        20, 8000, 1, "2026-04-01T12:00:00Z")
        );
    }

    /**
     * Returns default settings rows for each seed player.
     *
     * @return list of attribute maps, one per seed settings row
     */
    public static List<Map<String, AttributeValue>> samplePlayerSettingsAsMaps() {
        return List.of(
                buildSettingsMap(SEED_PLAYER_1),
                buildSettingsMap(SEED_PLAYER_2),
                buildSettingsMap(SEED_PLAYER_3),
                buildSettingsMap(SEED_PLAYER_4),
                buildSettingsMap(SEED_PLAYER_5)
        );
    }

    /**
     * Returns wallet rows for each seed player.
     *
     * @return list of attribute maps, one per seed wallet row
     */
    public static List<Map<String, AttributeValue>> samplePlayerWalletsAsMaps() {
        return List.of(
                buildWalletMap(SEED_PLAYER_1, 1200),
                buildWalletMap(SEED_PLAYER_2, 950),
                buildWalletMap(SEED_PLAYER_3, 500),
                buildWalletMap(SEED_PLAYER_4, 750),
                buildWalletMap(SEED_PLAYER_5, 2000)
        );
    }

    /**
     * Builds one PlayerState item map aligned with {@link PlayerProfile} attribute names.
     *
     * @param playerId        business player id
     * @param platform        platform label stored on the profile and GSI
     * @param platformUserId  opaque platform identity
     * @param playerName      display name
     * @param level           current level
     * @param xp              total experience
     * @param version         initial optimistic-lock version for low-level seed writes
     * @param lastUpdatedAt   ISO-8601 UTC sort key segment for the GSI
     * @return attribute map for {@code PutItem}
     */
    private static Map<String, AttributeValue> buildPlayerMap(String playerId, String platform,
                                                               String platformUserId, String playerName,
                                                               int level, long xp,
                                                               long version, String lastUpdatedAt) {
        return Map.ofEntries(
                Map.entry("PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + playerId)),
                Map.entry("SK", AttributeValue.fromS(PlayerProfile.SK_PROFILE)),
                Map.entry("entityType", AttributeValue.fromS(PlayerProfile.ENTITY_TYPE)),
                Map.entry("playerId", AttributeValue.fromS(playerId)),
                Map.entry("platform", AttributeValue.fromS(platform)),
                Map.entry("platformUserId", AttributeValue.fromS(platformUserId)),
                Map.entry("playerName", AttributeValue.fromS(playerName)),
                Map.entry("currentLevel", AttributeValue.fromN(String.valueOf(level))),
                Map.entry("totalExperience", AttributeValue.fromN(String.valueOf(xp))),
                Map.entry("version", AttributeValue.fromN(String.valueOf(version))),
                Map.entry("lastUpdatedAt", AttributeValue.fromS(lastUpdatedAt))
        );
    }

    /**
     * Builds a default settings item for a seed player.
     *
     * @param playerId the seed player id
     * @return attribute map for {@code PutItem}
     */
    private static Map<String, AttributeValue> buildSettingsMap(String playerId) {
        return Map.ofEntries(
                Map.entry("PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + playerId)),
                Map.entry("SK", AttributeValue.fromS(PlayerSettings.SK_SETTINGS)),
                Map.entry("entityType", AttributeValue.fromS(PlayerSettings.ENTITY_TYPE)),
                Map.entry("playerId", AttributeValue.fromS(playerId)),
                Map.entry("notificationsEnabled", AttributeValue.fromBool(true)),
                Map.entry("preferredLanguage", AttributeValue.fromS("en")),
                Map.entry("profileVisibility", AttributeValue.fromS("PUBLIC")),
                Map.entry("version", AttributeValue.fromN("1"))
        );
    }

    /**
     * Builds a wallet item for a seed player with the given balance.
     *
     * @param playerId the seed player id
     * @param balance  initial currency balance
     * @return attribute map for {@code PutItem}
     */
    private static Map<String, AttributeValue> buildWalletMap(String playerId, long balance) {
        return Map.ofEntries(
                Map.entry("PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + playerId)),
                Map.entry("SK", AttributeValue.fromS(PlayerWallet.SK_WALLET)),
                Map.entry("entityType", AttributeValue.fromS(PlayerWallet.ENTITY_TYPE)),
                Map.entry("playerId", AttributeValue.fromS(playerId)),
                Map.entry("currencyBalance", AttributeValue.fromN(String.valueOf(balance))),
                Map.entry("version", AttributeValue.fromN("1"))
        );
    }
}
