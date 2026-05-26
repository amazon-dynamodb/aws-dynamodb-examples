package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.SettingsSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerSettingsMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;

/**
 * Unit tests for {@link PlayerSettingsMapper}.
 *
 * <p>Verifies default settings seeding and mapping between {@link PlayerSettings} and
 * {@link SettingsSnapshot}.
 */
@Tag("unit")
class PlayerSettingsMapperTest {

    private final PlayerSettingsMapper mapper = new PlayerSettingsMapper();

    @Test
    void defaultSettings_whenNewPlayerIdProvided_shouldSetExpectedDefaultsAndKeys() {
        PlayerSettings settings = mapper.defaultSettings("player-1");

        assertThat(settings.getPartitionKey()).isEqualTo(PlayerProfile.PK_PREFIX + "player-1");
        assertThat(settings.getSortKey()).isEqualTo(PlayerSettings.SK_SETTINGS);
        assertThat(settings.getEntityType()).isEqualTo(PlayerSettings.ENTITY_TYPE);
        assertThat(settings.getPlayerId()).isEqualTo("player-1");
        assertThat(settings.isNotificationsEnabled()).isTrue();
        assertThat(settings.getPreferredLanguage()).isEqualTo("en");
        assertThat(settings.getProfileVisibility()).isEqualTo("PUBLIC");
    }

    @Test
    void toSnapshot_whenSettingsPresent_shouldMapFieldsWithoutPlayerId() {
        PlayerSettings settings = new PlayerSettings();
        settings.setPlayerId("player-1");
        settings.setNotificationsEnabled(false);
        settings.setPreferredLanguage("de");
        settings.setProfileVisibility("PRIVATE");
        settings.setVersion(4);

        SettingsSnapshot response = mapper.toSnapshot(settings);

        assertThat(response.notificationsEnabled()).isFalse();
        assertThat(response.preferredLanguage()).isEqualTo("de");
        assertThat(response.profileVisibility()).isEqualTo("PRIVATE");
        assertThat(response.version()).isEqualTo(4);
    }
}
