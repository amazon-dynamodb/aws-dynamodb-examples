package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Settings fields from the {@code SK = SETTINGS} item.
 *
 * @param notificationsEnabled whether push notifications are on
 * @param preferredLanguage   display language code
 * @param profileVisibility   visibility level ({@code PUBLIC}, {@code FRIENDS_ONLY}, {@code PRIVATE})
 * @param version             optimistic lock version for the settings item
 */
public record SettingsSnapshot(
        boolean notificationsEnabled,
        String preferredLanguage,
        String profileVisibility,
        long version) {
}
