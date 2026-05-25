package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for updating player settings via PATCH.
 *
 * <p>All preference fields are nullable. Only non-null values are applied,
 * enabling partial updates without sending the full settings object.
 *
 * @param notificationsEnabled optional toggle for push notifications
 * @param preferredLanguage    optional language code
 * @param profileVisibility    optional visibility ({@code PUBLIC}, {@code FRIENDS_ONLY}, {@code PRIVATE})
 * @param expectedVersion      required optimistic lock version from the last read
 */
public record UpdatePlayerSettingsRequest(
        Boolean notificationsEnabled,
        String preferredLanguage,
        String profileVisibility,
        @NotNull Long expectedVersion) {
}
