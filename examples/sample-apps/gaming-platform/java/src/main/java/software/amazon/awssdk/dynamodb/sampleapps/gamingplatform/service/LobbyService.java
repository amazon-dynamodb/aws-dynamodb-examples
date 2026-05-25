package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LobbySummariesRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LobbySummariesResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlatformPlayersResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlayerSummary;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.Platform;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;

/**
 * Lobby operations for multiplayer session setup and platform browsing.
 *
 * <p>Provides two main capabilities:
 * <ul>
 *   <li><strong>Lobby hydration</strong>: given a set of player ids (typically from a matchmaking
 *       service), batch-loads profile summaries so the lobby UI can render player names, avatars,
 *       and levels without N+1 round trips. Uses {@link PlayerStateRepository#batchGetPlayers} to
 *       read up to 100 items per DynamoDB {@code BatchGetItem} call.</li>
 *   <li><strong>Platform browse</strong>: queries the {@code GSI_PLATFORM_PLAYERS} global
 *       secondary index to list recently active players on a given {@link Platform}, ordered by
 *       {@code lastLoginAt} descending. Useful for social features and platform-specific leaderboards.</li>
 * </ul>
 *
 * <p>Both paths are read-only and do not mutate player state.
 */
@Service
public class LobbyService {

    private static final Logger logger = LoggerFactory.getLogger(LobbyService.class);

    /** Upper bound for platform browse page size. */
    private static final int MAX_PLATFORM_LIMIT = 50;

    /** Lower bound for platform browse page size. */
    private static final int MIN_PLATFORM_LIMIT = 1;

    /** PlayerState reads including batch get and GSI query. */
    private final PlayerStateRepository playerStateRepository;

    /** Maps profiles to summary DTOs. */
    private final PlayerMapper playerMapper;

    /**
     * Creates the service.
     *
     * @param playerStateRepository the active {@link PlayerStateRepository} implementation
     * @param playerMapper converts between domain models and DTOs
     */
    public LobbyService(PlayerStateRepository playerStateRepository, PlayerMapper playerMapper) {
        this.playerStateRepository = playerStateRepository;
        this.playerMapper = playerMapper;
    }

    /**
     * Batch-loads player summaries for a set of player ids (lobby hydration).
     *
     * <p>Returns summaries for all found players and a list of any ids that had
     * no matching profile in DynamoDB.
     *
     * @param request contains the player ids to look up
     * @return summaries plus missing ids
     */
    public LobbySummariesResponse getLobbySummaries(LobbySummariesRequest request) {
        List<PlayerProfile> profiles = playerStateRepository.batchGetPlayers(request.playerIds()).join();

        List<PlayerSummary> summaries = profiles.stream()
                .map(playerMapper::toSummary)
                .toList();

        Set<String> foundIds = profiles.stream()
                .map(PlayerProfile::getPlayerId)
                .collect(Collectors.toSet());

        List<String> missingIds = request.playerIds().stream()
                .filter(id -> !foundIds.contains(id))
                .toList();

        logger.debug("Loaded lobby summaries [summaryCount={}, missingCount={}]",
                summaries.size(), missingIds.size());
        return new LobbySummariesResponse(summaries, missingIds);
    }

    /**
     * Queries players on a given platform, ordered by most recently active first.
     *
     * @param platform the platform name (must match a {@link Platform} enum value)
     * @param limit    maximum number of results (clamped to [1, 50])
     * @return player summaries for the platform
     * @throws IllegalArgumentException if the platform is not a valid {@link Platform} value
     */
    public PlatformPlayersResponse getPlayersByPlatform(String platform, int limit) {
        try {
            Platform.valueOf(platform);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid platform: " + platform
                    + ". Valid values: " + Arrays.toString(Platform.values()));
        }

        int clampedLimit = Math.max(MIN_PLATFORM_LIMIT, Math.min(limit, MAX_PLATFORM_LIMIT));

        List<PlayerProfile> profiles = playerStateRepository.queryPlayersByPlatform(platform, clampedLimit).join();

        List<PlayerSummary> summaries = profiles.stream()
                .map(playerMapper::toSummary)
                .toList();

        logger.debug("Browsed platform players [platform={}, resultCount={}, limit={}]",
                platform, summaries.size(), clampedLimit);
        return new PlatformPlayersResponse(platform, summaries);
    }
}
