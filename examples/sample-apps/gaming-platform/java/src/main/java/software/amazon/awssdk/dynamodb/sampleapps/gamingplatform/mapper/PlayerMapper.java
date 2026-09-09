package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlayerSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProfileSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlayerSummary;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.enhanced.dynamodb.extensions.VersionedRecordExtension;

/**
 * Converts between {@link PlayerProfile} / {@link PlayerWallet} / {@link PlayerSettings} domain
 * objects and the REST DTOs used by player-facing API endpoints.
 *
 * <p>Responsibilities include:
 * <ul>
 *   <li>Creating a fresh {@link PlayerProfile} from a {@link RegisterPlayerRequest},
 *       with a deterministic player id derived from the platform identity</li>
 *   <li>Assembling snapshot responses ({@link PlayerSnapshot}, {@link RegisterPlayerResponse})
 *       that combine profile, wallet, and settings data</li>
 *   <li>Producing lightweight {@link PlayerSummary} rows for lobby and browse views</li>
 * </ul>
 *
 * @see PlayerProfile
 * @see PlayerWallet
 * @see RegisterPlayerRequest
 */
@Component
public class PlayerMapper {

    /** Maps wallet domain models to snapshot DTOs. */
    private final PlayerWalletMapper walletMapper;

    /** Maps settings domain models to snapshot DTOs. */
    private final PlayerSettingsMapper settingsMapper;

    /**
     * Creates the mapper with wallet and settings collaborators.
     *
     * @param walletMapper   wallet snapshot mapping
     * @param settingsMapper settings snapshot mapping
     */
    public PlayerMapper(PlayerWalletMapper walletMapper, PlayerSettingsMapper settingsMapper) {
        this.walletMapper = walletMapper;
        this.settingsMapper = settingsMapper;
    }

    /**
     * Creates a new {@link PlayerProfile} from a registration request.
     *
     * <p>Generates a deterministic player id from the platform identity using UUID v5-style
     * name-based hashing so retries produce the same id.
     *
     * <p>{@code version} is left at {@code 0} (the Java default). With the high-level DynamoDB
     * client and {@link VersionedRecordExtension},
     * version {@code 0} matches the extension's default {@code startAt} so the first Put uses
     * {@code attribute_not_exists(version) OR version = 0} and persists {@code 1}. Supplying a
     * non-zero version here would be treated as an update and break conditional registration.
     * The low-level repository sets {@code version = 1} explicitly before PutItem.
     *
     * @param request registration request
     * @return new profile not yet persisted
     */
    public PlayerProfile toProfile(RegisterPlayerRequest request) {
        String playerId = generatePlayerId(request.platform(), request.platformUserId());
        PlayerProfile profile = new PlayerProfile();
        profile.setPartitionKey(PlayerProfile.PK_PREFIX + playerId);
        profile.setSortKey(PlayerProfile.SK_PROFILE);
        profile.setEntityType(PlayerProfile.ENTITY_TYPE);
        profile.setPlayerId(playerId);
        profile.setPlatform(request.platform());
        profile.setPlatformUserId(request.platformUserId());
        profile.setPlayerName(request.playerName());
        profile.setCurrentLevel(1);
        profile.setTotalExperience(0);
        profile.setLastUpdatedAt(Instant.now().toString());
        return profile;
    }

    /**
     * Converts a profile domain model to its snapshot DTO.
     *
     * @param profile domain profile
     * @return profile snapshot for API responses
     */
    public ProfileSnapshot toProfileSnapshot(PlayerProfile profile) {
        return new ProfileSnapshot(
                profile.getPlayerName(),
                profile.getPlatform(),
                profile.getCurrentLevel(),
                profile.getTotalExperience(),
                profile.getLastUpdatedAt(),
                profile.getVersion());
    }

    /**
     * Builds a full player snapshot from the three domain entities.
     *
     * @param profile  domain profile
     * @param wallet   domain wallet
     * @param settings domain settings
     * @return composed player snapshot DTO
     */
    public PlayerSnapshot toPlayerSnapshot(PlayerProfile profile, PlayerWallet wallet, PlayerSettings settings) {
        return new PlayerSnapshot(
                profile.getPlayerId(),
                toProfileSnapshot(profile),
                walletMapper.toSnapshot(wallet),
                settingsMapper.toSnapshot(settings));
    }

    /**
     * Builds a registration response with the full player snapshot.
     *
     * @param profile  the player profile (new or existing)
     * @param wallet   the player wallet
     * @param settings the player settings
     * @param created  {@code true} if newly created, {@code false} for idempotent replay
     * @return response DTO containing the player id, snapshots, and creation flag
     */
    public RegisterPlayerResponse toRegisterResponse(PlayerProfile profile,
                                                     PlayerWallet wallet,
                                                     PlayerSettings settings,
                                                     boolean created) {
        PlayerSnapshot snapshot = toPlayerSnapshot(profile, wallet, settings);
        return new RegisterPlayerResponse(
                snapshot.playerId(),
                snapshot.profile(),
                snapshot.wallet(),
                snapshot.settings(),
                created);
    }

    /**
     * Converts a profile to a lightweight summary for lobby and platform browsing.
     *
     * @param profile domain profile
     * @return summary row
     */
    public PlayerSummary toSummary(PlayerProfile profile) {
        return new PlayerSummary(
                profile.getPlayerId(),
                profile.getPlayerName(),
                profile.getCurrentLevel(),
                profile.getLastUpdatedAt());
    }

    /**
     * Generates a deterministic player id from the platform identity.
     *
     * @param platform       platform name
     * @param platformUserId stable id from the platform
     * @return string player id
     * @apiNote Uses {@link UUID#nameUUIDFromBytes(byte[])} (UUID v3) so the same
     *          platform + platformUserId always produces the same player id.
     */
    public String generatePlayerId(String platform, String platformUserId) {
        return UUID.nameUUIDFromBytes((platform + "#" + platformUserId).getBytes()).toString();
    }
}
