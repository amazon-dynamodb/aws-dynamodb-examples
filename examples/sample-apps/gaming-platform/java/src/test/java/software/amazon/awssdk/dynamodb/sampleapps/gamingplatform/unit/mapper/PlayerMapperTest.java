package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlayerSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlayerSummary;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProfileSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerSettingsMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerWalletMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;

/**
 * Unit tests for {@link PlayerMapper}.
 *
 * <p>Verifies profile draft creation, snapshot assembly, and player id generation from platform
 * identity pairs.
 */
@Tag("unit")
class PlayerMapperTest {

    private final PlayerWalletMapper walletMapper = new PlayerWalletMapper();

    private final PlayerSettingsMapper settingsMapper = new PlayerSettingsMapper();

    private final PlayerMapper mapper = new PlayerMapper(walletMapper, settingsMapper);

    @Test
    void generatePlayerId_whenSamePlatformIdentity_shouldReturnSameId() {
        String id1 = mapper.generatePlayerId("PC", "user-1");
        String id2 = mapper.generatePlayerId("PC", "user-1");
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void generatePlayerId_whenPlatformOrUserChanges_shouldReturnDifferentIds() {
        String a = mapper.generatePlayerId("PC", "user-1");
        String b = mapper.generatePlayerId("IOS", "user-1");
        String c = mapper.generatePlayerId("PC", "user-2");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toProfile_whenRegistrationRequestProvided_shouldMapFieldsAndKeys() {
        RegisterPlayerRequest request = new RegisterPlayerRequest("PC", "steam-x", "Neo");
        PlayerProfile profile = mapper.toProfile(request);

        assertThat(profile.getPlayerId()).isEqualTo(mapper.generatePlayerId("PC", "steam-x"));
        assertThat(profile.getPartitionKey()).isEqualTo(PlayerProfile.PK_PREFIX + profile.getPlayerId());
        assertThat(profile.getSortKey()).isEqualTo(PlayerProfile.SK_PROFILE);
        assertThat(profile.getPlatform()).isEqualTo("PC");
        assertThat(profile.getPlatformUserId()).isEqualTo("steam-x");
        assertThat(profile.getPlayerName()).isEqualTo("Neo");
        assertThat(profile.getCurrentLevel()).isEqualTo(1);
        assertThat(profile.getTotalExperience()).isZero();
        assertThat(profile.getVersion()).isZero();
        assertThat(profile.getLastUpdatedAt()).isNotNull();
    }

    @Test
    void toProfileSnapshot_whenProfilePresent_shouldMapFieldsWithoutPlayerId() {
        PlayerProfile profile = new PlayerProfile();
        profile.setPlayerId("pid");
        profile.setPlayerName("Name");
        profile.setPlatform("ANDROID");
        profile.setCurrentLevel(5);
        profile.setTotalExperience(99);
        profile.setLastUpdatedAt("2026-01-01T00:00:00Z");
        profile.setVersion(3);

        ProfileSnapshot dto = mapper.toProfileSnapshot(profile);
        assertThat(dto.playerName()).isEqualTo("Name");
        assertThat(dto.platform()).isEqualTo("ANDROID");
        assertThat(dto.currentLevel()).isEqualTo(5);
        assertThat(dto.totalExperience()).isEqualTo(99);
        assertThat(dto.lastUpdatedAt()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(dto.version()).isEqualTo(3);
    }

    @Test
    void toPlayerSnapshot_whenEntitiesPresent_shouldComposeSiblingSnapshotsAtRoot() {
        PlayerProfile profile = mapper.toProfile(new RegisterPlayerRequest("PC", "u", "Neo"));
        PlayerWallet wallet = walletMapper.defaultWallet(profile.getPlayerId());
        PlayerSettings settings = settingsMapper.defaultSettings(profile.getPlayerId());

        PlayerSnapshot snapshot = mapper.toPlayerSnapshot(profile, wallet, settings);

        assertThat(snapshot.playerId()).isEqualTo(profile.getPlayerId());
        assertThat(snapshot.profile().playerName()).isEqualTo("Neo");
        assertThat(snapshot.wallet().currencyBalance()).isEqualTo(PlayerWalletMapper.INITIAL_BALANCE);
        assertThat(snapshot.settings().preferredLanguage()).isEqualTo("en");
    }

    @Test
    void toRegisterResponse_whenEntitiesPresent_shouldWrapFullSnapshotAndCreatedFlag() {
        PlayerProfile profile = mapper.toProfile(new RegisterPlayerRequest("PC", "u", "N"));
        PlayerWallet wallet = walletMapper.defaultWallet(profile.getPlayerId());
        PlayerSettings settings = settingsMapper.defaultSettings(profile.getPlayerId());
        RegisterPlayerResponse response = mapper.toRegisterResponse(profile, wallet, settings, true);
        assertThat(response.created()).isTrue();
        assertThat(response.playerId()).isEqualTo(profile.getPlayerId());
        assertThat(response.profile().playerName()).isEqualTo("N");
        assertThat(response.wallet().currencyBalance()).isEqualTo(PlayerWalletMapper.INITIAL_BALANCE);
        assertThat(response.settings().preferredLanguage()).isEqualTo("en");
    }

    @Test
    void toSummary_whenProfilePresent_shouldMapLightweightFields() {
        PlayerProfile profile = mapper.toProfile(new RegisterPlayerRequest("IOS", "a", "Summ"));
        profile.setCurrentLevel(7);
        PlayerSummary summary = mapper.toSummary(profile);
        assertThat(summary.playerId()).isEqualTo(profile.getPlayerId());
        assertThat(summary.playerName()).isEqualTo("Summ");
        assertThat(summary.currentLevel()).isEqualTo(7);
        assertThat(summary.lastUpdatedAt()).isEqualTo(profile.getLastUpdatedAt());
    }
}
