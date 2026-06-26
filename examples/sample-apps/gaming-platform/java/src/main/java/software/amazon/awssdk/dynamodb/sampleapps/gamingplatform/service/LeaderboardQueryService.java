package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LeaderboardResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.LeaderboardMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.LeaderboardRepository;

/**
 * Provides read access to leaderboard rankings.
 *
 * <p>Delegates to the active {@link LeaderboardRepository} implementation to query
 * the top-N entries for a given scope, then maps the results to a response DTO.
 */
@Service
public class LeaderboardQueryService {

    private static final Logger logger = LoggerFactory.getLogger(LeaderboardQueryService.class);

    /** Minimum allowed limit for top-N queries. */
    private static final int MIN_LIMIT = 1;

    /** Maximum allowed limit for top-N queries. */
    private static final int MAX_LIMIT = 100;

    /** Leaderboard persistence implementation. */
    private final LeaderboardRepository leaderboardRepository;

    /** Maps entries to REST DTOs. */
    private final LeaderboardMapper leaderboardMapper;

    /**
     * Creates the service.
     *
     * @param leaderboardRepository the active {@link LeaderboardRepository} implementation
     * @param leaderboardMapper converts between domain models and DTOs
     */
    public LeaderboardQueryService(LeaderboardRepository leaderboardRepository, LeaderboardMapper leaderboardMapper) {
        this.leaderboardRepository = leaderboardRepository;
        this.leaderboardMapper = leaderboardMapper;
    }

    /**
     * Retrieves the top-N leaderboard entries for the given scope.
     *
     * @param scope the leaderboard scope (e.g. {@code SEASON#default#MODE#ranked})
     * @param limit requested number of entries, clamped to [{@value MIN_LIMIT}, {@value MAX_LIMIT}]
     * @return future of the leaderboard response with ranked entries
     */
    public CompletableFuture<LeaderboardResponse> getTopN(String scope, int limit) {
        int clampedLimit = Math.max(MIN_LIMIT, Math.min(limit, MAX_LIMIT));

        return leaderboardRepository.queryTopN(scope, clampedLimit).thenApply(entries -> {
            logger.debug("Queried leaderboard [scope={}, entryCount={}, limit={}]",
                    scope, entries.size(), clampedLimit);
            return leaderboardMapper.toResponse(scope, entries);
        });
    }
}
