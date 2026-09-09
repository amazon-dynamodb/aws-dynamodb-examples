package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.WalletTransactItemOrder;

/**
 * Guard tests for {@link WalletTransactItemOrder}.
 *
 * <p>The wallet transaction producers and the cancellation-reason consumers both rely on these fixed
 * indices, so this test pins them. A reordering that breaks the contract fails here.
 */
@Tag("unit")
class WalletTransactItemOrderTest {

    @Test
    void index_whenWallet_shouldBeZero() {
        assertThat(WalletTransactItemOrder.WALLET.index()).isZero();
    }

    @Test
    void index_whenEvent_shouldBeOne() {
        assertThat(WalletTransactItemOrder.EVENT.index()).isEqualTo(1);
    }

    @Test
    void values_whenListed_shouldHoldExactlyWalletThenEvent() {
        assertThat(WalletTransactItemOrder.values())
                .containsExactly(WalletTransactItemOrder.WALLET, WalletTransactItemOrder.EVENT);
    }
}

