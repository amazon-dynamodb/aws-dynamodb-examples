package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.MediaProperties;

/**
 * Unit coverage for media numeric configuration defaults and presign TTL range enforcement.
 *
 * <p>Confirms Spring property binding uses the named constants when the numeric keys are absent,
 * given only the required bucket name. An out-of-range {@code media.presign-ttl-seconds} fails
 * context refresh. No Docker is required.
 */
@Tag("unit")
class MediaPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MediaProperties.class)
            .withPropertyValues("s3.bucket-name=test-bucket");

    @Test
    void context_whenNumericPropertiesAbsent_defaultsToNamedConstants() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            MediaProperties properties = context.getBean(MediaProperties.class);
            assertThat(properties.presignTtlSeconds()).isEqualTo(MediaProperties.DEFAULT_PRESIGN_TTL_SECONDS);
            assertThat(properties.maxPerPost()).isEqualTo(MediaProperties.DEFAULT_MAX_PER_POST);
            assertThat(properties.maxImageBytes()).isEqualTo(MediaProperties.DEFAULT_MAX_IMAGE_BYTES);
            assertThat(properties.maxVideoBytes()).isEqualTo(MediaProperties.DEFAULT_MAX_VIDEO_BYTES);
        });
    }

    @Test
    void context_whenNumericPropertiesOverrideDefaults_bindsConfiguredValues() {
        contextRunner.withPropertyValues(
                        "media.presign-ttl-seconds=120",
                        "media.max-per-post=2",
                        "media.max-image-bytes=1024",
                        "media.max-video-bytes=2048")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MediaProperties properties = context.getBean(MediaProperties.class);
                    assertThat(properties.presignTtlSeconds()).isEqualTo(120);
                    assertThat(properties.maxPerPost()).isEqualTo(2);
                    assertThat(properties.maxImageBytes()).isEqualTo(1024L);
                    assertThat(properties.maxVideoBytes()).isEqualTo(2048L);
                });
    }

    @Test
    void context_whenPresignTtlIsFiftyNine_fails() {
        contextRunner.withPropertyValues("media.presign-ttl-seconds=59")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(MediaProperties.PRESIGN_TTL_OUT_OF_RANGE);
                });
    }

    @Test
    void context_whenPresignTtlIs3601_fails() {
        contextRunner.withPropertyValues("media.presign-ttl-seconds=3601")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(MediaProperties.PRESIGN_TTL_OUT_OF_RANGE);
                });
    }
}
