package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetSettingsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.UpdatePlayerSettingsRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.UpdatePlayerSettingsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.StaleVersionException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerSettingsMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerSettingsService;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Unit tests for {@link PlayerSettingsService}.
 *
 * <p>Uses a mocked {@link PlayerStateRepository} to verify settings reads, partial updates, stale
 * version handling, and missing-player errors.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PlayerSettingsServiceTest {

    @Mock
    private PlayerStateRepository repository;

    private PlayerSettingsService service;

    /**
     * Wires the service under test with a real mapper and mocked collaborators.
     */
    @BeforeEach
    void setUp() {
        service = new PlayerSettingsService(repository, new PlayerSettingsMapper());
    }

    @Test
    void getSettings_whenSettingsExist_shouldReturnSettingsSliceWrapper() {
        PlayerSettings settings = buildSettings("p1", 1);
        when(repository.getSettings("p1")).thenReturn(CompletableFuture.completedFuture(settings));

        GetSettingsResponse response = service.getSettings("p1").join();

        assertThat(response.settings().notificationsEnabled()).isTrue();
        assertThat(response.settings().preferredLanguage()).isEqualTo("en");
        assertThat(response.settings().profileVisibility()).isEqualTo("PUBLIC");
    }

    @Test
    void getSettings_whenSettingsMissing_shouldThrowPlayerNotFound() {
        when(repository.getSettings("missing")).thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> service.getSettings("missing").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PlayerNotFoundException.class);
    }

    @Test
    void updateSettings_whenPartialUpdateApplied_shouldReturnUpdatedSettings() {
        PlayerSettings current = buildSettings("p1", 1);
        PlayerSettings updated = buildSettings("p1", 2);
        updated.setPreferredLanguage("de");

        when(repository.getSettings("p1")).thenReturn(CompletableFuture.completedFuture(current));
        when(repository.updateSettings(any(PlayerSettings.class)))
                .thenReturn(CompletableFuture.completedFuture(updated));

        UpdatePlayerSettingsRequest request = new UpdatePlayerSettingsRequest(null, "de", null, 1L);
        UpdatePlayerSettingsResponse response = service.updateSettings("p1", request).join();

        assertThat(response.playerId()).isEqualTo("p1");
        assertThat(response.settings().preferredLanguage()).isEqualTo("de");
        assertThat(response.settings().version()).isEqualTo(2);
    }

    @Test
    void updateSettings_whenVersionStale_shouldThrowStaleVersion() {
        PlayerSettings current = buildSettings("p1", 1);
        when(repository.getSettings("p1")).thenReturn(CompletableFuture.completedFuture(current));

        CompletableFuture<PlayerSettings> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(ConditionalCheckFailedException.builder().message("version").build());
        when(repository.updateSettings(any(PlayerSettings.class))).thenReturn(failedFuture);

        UpdatePlayerSettingsRequest request = new UpdatePlayerSettingsRequest(false, null, null, 999L);
        assertThatThrownBy(() -> service.updateSettings("p1", request).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StaleVersionException.class);
    }

    @Test
    void updateSettings_whenSettingsMissing_shouldThrowPlayerNotFound() {
        when(repository.getSettings("missing")).thenReturn(CompletableFuture.completedFuture(null));

        UpdatePlayerSettingsRequest request = new UpdatePlayerSettingsRequest(true, null, null, 1L);
        assertThatThrownBy(() -> service.updateSettings("missing", request).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PlayerNotFoundException.class);
    }

    /**
     * Builds a {@link PlayerSettings} fixture with default preference values.
     *
     * @param playerId player identifier
     * @param version  optimistic-lock version
     * @return populated settings
     */
    private static PlayerSettings buildSettings(String playerId, long version) {
        PlayerSettings s = new PlayerSettings();
        s.setPartitionKey("USER#" + playerId);
        s.setSortKey(PlayerSettings.SK_SETTINGS);
        s.setEntityType(PlayerSettings.ENTITY_TYPE);
        s.setPlayerId(playerId);
        s.setNotificationsEnabled(true);
        s.setPreferredLanguage("en");
        s.setProfileVisibility("PUBLIC");
        s.setVersion(version);
        return s;
    }
}
