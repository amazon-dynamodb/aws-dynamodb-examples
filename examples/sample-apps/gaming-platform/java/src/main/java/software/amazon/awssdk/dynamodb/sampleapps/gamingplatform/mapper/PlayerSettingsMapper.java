package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.SettingsSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;

/**
 * Maps between {@link PlayerSettings} domain model and API DTOs, and provides
 * a factory for default settings created during registration.
 */
@Component
public class PlayerSettingsMapper {

    /** Default language for new accounts. */
    private static final String DEFAULT_LANGUAGE = "en";

    /** Default profile visibility for new accounts. */
    private static final String DEFAULT_VISIBILITY = "PUBLIC";

    /**
     * Creates a default {@link PlayerSettings} for a new player registration.
     *
     * @param playerId the player id
     * @return settings with sensible defaults (notifications on, English, public)
     */
    public PlayerSettings defaultSettings(String playerId) {
        PlayerSettings settings = new PlayerSettings();
        settings.setPartitionKey(PlayerProfile.PK_PREFIX + playerId);
        settings.setSortKey(PlayerSettings.SK_SETTINGS);
        settings.setEntityType(PlayerSettings.ENTITY_TYPE);
        settings.setPlayerId(playerId);
        settings.setNotificationsEnabled(true);
        settings.setPreferredLanguage(DEFAULT_LANGUAGE);
        settings.setProfileVisibility(DEFAULT_VISIBILITY);
        return settings;
    }

    /**
     * Converts a settings model to its snapshot DTO.
     *
     * @param settings domain settings
     * @return settings snapshot for API responses
     */
    public SettingsSnapshot toSnapshot(PlayerSettings settings) {
        return new SettingsSnapshot(
                settings.isNotificationsEnabled(),
                settings.getPreferredLanguage(),
                settings.getProfileVisibility(),
                settings.getVersion());
    }
}
