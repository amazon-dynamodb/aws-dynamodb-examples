package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.BatchGetReservationsRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.BatchGetReservationsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.GetAccountResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ReservationResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.AccountNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.InvalidBatchGetReservationsRequestException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.AccountPartitionQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.BatchGetReservationsResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.BatchGetItemHelper;

/**
 * Read-only access to accounts and their reservations for API queries.
 *
 * <p>Loads the account partition (account + reservations) via
 * {@link PaymentRepository#queryAccountPartition(String)} or batch-gets specific reservations
 * for an account via {@link PaymentRepository#batchGetReservations(String, List)}.
 */
@Service
public class AccountQueryService {

    /** Persistence port for account and reservation reads. */
    private final PaymentRepository paymentRepository;

    /** Maps domain models to HTTP DTOs. */
    private final PaymentMapper paymentMapper;

    /**
     * @param paymentRepository persistence for account queries
     * @param paymentMapper     model-to-DTO mapping
     */
    public AccountQueryService(PaymentRepository paymentRepository,
                               PaymentMapper paymentMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
    }

    /**
     * Returns the account read model with associated reservations.
     *
     * @param accountId business account id
     * @return response DTO
     * @throws AccountNotFoundException if the ACCOUNT row is absent
     */
    public GetAccountResponse getAccount(String accountId) {
        AccountPartitionQueryResult partition = paymentRepository.queryAccountPartition(accountId).join();
        if (partition == null) {
            throw new AccountNotFoundException(accountId);
        }
        return paymentMapper.toGetAccountResponse(partition.account(), partition.reservations());
    }

    /**
     * Loads only the requested reservations for one account and maps them to API DTOs.
     *
     * <p>This method blocks on {@link CompletableFuture#join()} because the controller layer is
     * synchronous. HTTP status stays 200 when some ids are missing so clients
     * merge {@link BatchGetReservationsResponse#missingReservationIds()} with the request list.
     *
     * <p><strong>Example.</strong> After a payment completes you might know the deterministic id
     * formed as the {@code res_} prefix followed by the payment id from the outbound flow. Sending that
     * id together with unknown ids yields found rows in {@link BatchGetReservationsResponse#reservations()}
     * and misses in {@link BatchGetReservationsResponse#missingReservationIds()}.
     *
     * <p>Before the repository call, the service validates that at least one distinct reservation id
     * remains after first-seen-order deduplication. This keeps request-shape validation out of the
     * repository while preserving the API contract that duplicate ids are allowed.
     *
     * @param accountId business account id whose partition owns the reservations
     * @param request   validated body with {@link BatchGetReservationsRequest#reservationIds()}
     * @return response DTO with found rows and missing ids in deduplicated request order
     */
    public BatchGetReservationsResponse batchGetReservations(String accountId,
                                                             BatchGetReservationsRequest request) {
        List<String> distinctReservationIds = BatchGetItemHelper.distinctPreserveOrder(request.reservationIds());
        validateBatchGetReservationIds(distinctReservationIds);
        BatchGetReservationsResult batchResult =
                paymentRepository.batchGetReservations(accountId, distinctReservationIds).join();
        List<ReservationResponse> reservationResponses = batchResult.reservations().stream()
                .map(paymentMapper::toReservationResponse)
                .toList();
        return new BatchGetReservationsResponse(
                reservationResponses, batchResult.missingReservationIds());
    }

    /**
     * Enforces semantic request-shape rules that depend on the post-deduplication identifier set.
     *
     * @param distinctReservationIds reservation ids after first-seen-order deduplication
     * @throws InvalidBatchGetReservationsRequestException if no distinct id remains
     */
    private static void validateBatchGetReservationIds(List<String> distinctReservationIds) {
        if (distinctReservationIds.isEmpty()) {
            throw new InvalidBatchGetReservationsRequestException(
                    "reservationIds must contain at least one distinct reservation id");
        }
    }
}
