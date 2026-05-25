package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper;

import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LeaderboardEntryDto;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LeaderboardResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.LeaderboardEntry;

/**
 * Converts between {@link LeaderboardEntry} domain objects and the
 * {@link LeaderboardResponse} / {@link LeaderboardEntryDto} REST DTOs.
 *
 * <p>Entries arrive from the repository already sorted by score descending. This mapper
 * assigns 1-based rank numbers during conversion and assembles the final response wrapper
 * returned to API clients.
 *
 * @see LeaderboardEntry
 * @see LeaderboardResponse
 */
@Component
public class LeaderboardMapper {

    /**
     * Converts a leaderboard entry to its DTO with the given rank.
     *
     * @param entry the domain model
     * @param rank  1-based rank position
     * @return DTO combining the rank with the entry's player id, name, and score
     */
    public LeaderboardEntryDto toEntryDto(LeaderboardEntry entry, int rank) {
        return new LeaderboardEntryDto(rank, entry.getPlayerId(), entry.getPlayerName(), entry.getScore());
    }

    /**
     * Builds a full leaderboard response from a list of entries (already sorted by score descending).
     *
     * @param scope   echoed leaderboard scope
     * @param entries ordered entries from the repository
     * @return API response with 1-based ranks
     */
    public LeaderboardResponse toResponse(String scope, List<LeaderboardEntry> entries) {
        List<LeaderboardEntryDto> dtos = IntStream.range(0, entries.size())
                .mapToObj(i -> toEntryDto(entries.get(i), i + 1))
                .toList();
        return new LeaderboardResponse(scope, dtos);
    }
}
