package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Response body for {@code GET /api/v1/players/{playerId}/wallet}.
 *
 * @param wallet wallet snapshot slice
 */
public record GetWalletResponse(WalletSnapshot wallet) {
}
