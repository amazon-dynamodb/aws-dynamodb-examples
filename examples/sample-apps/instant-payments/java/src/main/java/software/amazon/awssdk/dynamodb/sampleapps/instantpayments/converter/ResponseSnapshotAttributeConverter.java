/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.converter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import software.amazon.awssdk.annotations.Immutable;
import software.amazon.awssdk.annotations.ThreadSafe;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentResponse;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.enhanced.dynamodb.internal.converter.TypeConvertingVisitor;
import software.amazon.awssdk.enhanced.dynamodb.internal.converter.attribute.EnhancedAttributeValue;
import software.amazon.awssdk.enhanced.dynamodb.internal.converter.attribute.InstantAsStringAttributeConverter;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Converts {@link CreateOutboundPaymentResponse} to and from a DynamoDB map attribute.
 *
 * <p>Stores the response as a native DynamoDB {@code M} type with keys
 * {@code paymentId}, {@code state}, {@code correlationId}, and {@code createdAtUtc}
 * (Instant as ISO-8601 string, same encoding as {@link InstantAsStringAttributeConverter}).
 *
 * <p>The type is stateless. {@link #create()} is equivalent to {@code new ResponseSnapshotAttributeConverter()} for readability.
 */
@ThreadSafe
@Immutable
public final class ResponseSnapshotAttributeConverter implements AttributeConverter<CreateOutboundPaymentResponse> {

    /**
     * Shared visitor that rebuilds {@link CreateOutboundPaymentResponse} from a DynamoDB map.
     */
    private static final Visitor VISITOR = new Visitor();

    /**
     * Delegates {@link Instant} encoding for {@link #CREATED_AT} the same way other enhanced converters do.
     */
    private static final InstantAsStringAttributeConverter INSTANT_CONVERTER =
            InstantAsStringAttributeConverter.create();

    /** Map key for {@link CreateOutboundPaymentResponse#paymentId()}. */
    private static final String PAYMENT_ID = "paymentId";

    /** Map key for {@link CreateOutboundPaymentResponse#state()}. */
    private static final String STATE = "state";

    /** Map key for {@link CreateOutboundPaymentResponse#correlationId()}. */
    private static final String CORRELATION_ID = "correlationId";

    /** Map key for {@link CreateOutboundPaymentResponse#createdAtUtc()}. */
    private static final String CREATED_AT = "createdAtUtc";

    /** Public no-arg constructor for {@code @DynamoDbConvertedBy} and tooling. Prefer {@link #create()}. */
    public ResponseSnapshotAttributeConverter() {}

    /**
     * Factory for a new converter instance (same as the public no-arg constructor).
     *
     * @return new stateless converter, safe to use concurrently across threads
     */
    public static ResponseSnapshotAttributeConverter create() {
        return new ResponseSnapshotAttributeConverter();
    }

    /**
     * @param input non-null response snapshot. All record components must be present
     * @return DynamoDB map attribute {@code M}
     */
    @Override
    public AttributeValue transformFrom(CreateOutboundPaymentResponse input) {
        Map<String, AttributeValue> map = new HashMap<>();
        map.put(PAYMENT_ID, AttributeValue.builder().s(input.paymentId()).build());
        map.put(STATE, AttributeValue.builder().s(input.state()).build());
        map.put(CORRELATION_ID, AttributeValue.builder().s(input.correlationId()).build());
        map.put(CREATED_AT, INSTANT_CONVERTER.transformFrom(input.createdAtUtc()));
        return AttributeValue.builder().m(map).build();
    }

    /**
     * @param input map attribute produced by {@link #transformFrom}. Invalid or incomplete maps yield
     *     {@link IllegalArgumentException}
     * @return reconstructed record
     *
     * @apiNote The enhanced client may invoke converters with either a top-level {@code M} value or a
     *     map extracted elsewhere. Both paths are handled so round-trips stay consistent regardless of
     *     nesting context.
     */
    @Override
    public CreateOutboundPaymentResponse transformTo(AttributeValue input) {
        try {
            if (input.m() != null && !input.m().isEmpty()) {
                return EnhancedAttributeValue.fromMap(input.m()).convert(VISITOR);
            }

            return EnhancedAttributeValue.fromAttributeValue(input).convert(VISITOR);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public EnhancedType<CreateOutboundPaymentResponse> type() {
        return EnhancedType.of(CreateOutboundPaymentResponse.class);
    }

    /** {@inheritDoc} */
    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.M;
    }

    /**
     * Parses the fixed-key map shape produced by {@link ResponseSnapshotAttributeConverter#transformFrom(CreateOutboundPaymentResponse)}.
     */
    private static final class Visitor extends TypeConvertingVisitor<CreateOutboundPaymentResponse> {
        /**
         * Registers the target type with the enhanced client's conversion framework.
         */
        private Visitor() {
            super(CreateOutboundPaymentResponse.class, ResponseSnapshotAttributeConverter.class);
        }

        /**
         * {@inheritDoc}
         *
         * @throws NullPointerException if a required map entry is missing
         */
        @Override
        public CreateOutboundPaymentResponse convertMap(Map<String, AttributeValue> map) {
            String paymentId = map.get(PAYMENT_ID).s();
            String state = map.get(STATE).s();
            String correlationId = map.get(CORRELATION_ID).s();
            Instant createdAtUtc = INSTANT_CONVERTER.transformTo(map.get(CREATED_AT));
            return new CreateOutboundPaymentResponse(paymentId, state, correlationId, createdAtUtc);
        }
    }
}
