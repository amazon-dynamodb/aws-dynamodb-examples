package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Unit tests for {@link SeedPlayerData}.
 *
 * <p>Verifies sample profile, settings, and wallet map builders expose the expected keys, counts,
 * and constant ordering used by table seeding.
 */
@Tag("unit")
class SeedPlayerDataTest {

    @Test
    void samplePlayerProfilesAsMaps_whenCalled_shouldContainFivePlayersWithExpectedKeys() {
        List<Map<String, AttributeValue>> maps = SeedPlayerData.samplePlayerProfilesAsMaps();

        assertThat(maps).hasSize(5);
        Map<String, AttributeValue> first = maps.getFirst();
        assertThat(first.get("playerId").s()).isEqualTo(SeedPlayerData.SEED_PLAYER_1);
        assertThat(first.get("PK").s()).isEqualTo(PlayerProfile.PK_PREFIX + SeedPlayerData.SEED_PLAYER_1);
        assertThat(first.get("SK").s()).isEqualTo(PlayerProfile.SK_PROFILE);
        assertThat(first.get("entityType").s()).isEqualTo(PlayerProfile.ENTITY_TYPE);
        assertThat(first.get("platform").s()).isEqualTo("PC");
        assertThat(first.get("playerName").s()).isEqualTo("AlphaWolf");
        assertThat(first.keySet()).contains("currentLevel", "totalExperience",
                "version", "platformUserId", "lastUpdatedAt");
    }

    @Test
    void seedConstants_whenComparedToFirstSample_shouldMatchOrder() {
        List<Map<String, AttributeValue>> maps = SeedPlayerData.samplePlayerProfilesAsMaps();
        assertThat(maps.get(0).get("playerId").s()).isEqualTo(SeedPlayerData.SEED_PLAYER_1);
        assertThat(maps.get(4).get("playerId").s()).isEqualTo(SeedPlayerData.SEED_PLAYER_5);
    }

    @Test
    void samplePlayerSettingsAsMaps_whenCalled_shouldContainFiveSettingsWithDefaults() {
        List<Map<String, AttributeValue>> maps = SeedPlayerData.samplePlayerSettingsAsMaps();

        assertThat(maps).hasSize(5);
        Map<String, AttributeValue> first = maps.getFirst();
        assertThat(first.get("PK").s()).isEqualTo(PlayerProfile.PK_PREFIX + SeedPlayerData.SEED_PLAYER_1);
        assertThat(first.get("SK").s()).isEqualTo(PlayerSettings.SK_SETTINGS);
        assertThat(first.get("entityType").s()).isEqualTo(PlayerSettings.ENTITY_TYPE);
        assertThat(first.get("playerId").s()).isEqualTo(SeedPlayerData.SEED_PLAYER_1);
        assertThat(first.get("notificationsEnabled").bool()).isTrue();
        assertThat(first.get("preferredLanguage").s()).isEqualTo("en");
        assertThat(first.get("profileVisibility").s()).isEqualTo("PUBLIC");
    }

    @Test
    void samplePlayerWalletsAsMaps_whenCalled_shouldContainFiveWalletsWithBalance() {
        List<Map<String, AttributeValue>> maps = SeedPlayerData.samplePlayerWalletsAsMaps();

        assertThat(maps).hasSize(5);
        Map<String, AttributeValue> first = maps.getFirst();
        assertThat(first.get("PK").s()).isEqualTo(PlayerProfile.PK_PREFIX + SeedPlayerData.SEED_PLAYER_1);
        assertThat(first.get("SK").s()).isEqualTo(PlayerWallet.SK_WALLET);
        assertThat(first.get("entityType").s()).isEqualTo(PlayerWallet.ENTITY_TYPE);
        assertThat(first.get("playerId").s()).isEqualTo(SeedPlayerData.SEED_PLAYER_1);
        assertThat(first.get("currencyBalance").n()).isEqualTo("1200");
        assertThat(first.get("version").n()).isEqualTo("1");
    }
}
