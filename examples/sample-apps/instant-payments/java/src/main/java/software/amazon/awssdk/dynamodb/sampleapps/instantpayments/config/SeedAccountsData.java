package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config;

import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Single source of truth for seeded {@code ACCOUNT} items in the single-table design.
 *
 * <p>Used by {@link DynamoDbTableInitializer} at application startup when seeding the single-table store.
 *
 * <p>Each map uses DynamoDB attribute names as keys. Values are strings or whole numbers. The table
 * initializer converts these to {@link AttributeValue}
 * maps for {@code PutItem}.
 */
public final class SeedAccountsData {

    /**
     * Not instantiable. Use {@link #accountRowsAsMaps()}.
     */
    private SeedAccountsData() {
    }

    /**
     * Returns a fixed list of ten demo accounts (five USD, five EUR) with balances suitable for local development and demos.
     *
     * <p>Each map contains: {@code PK}, {@code SK}, {@code entityType}, {@code accountId},
     * {@code status}, {@code currentBalance}, {@code availableBalance}, {@code currency}, {@code version}.
     *
     * @return immutable list of attribute maps (one map per account item)
     */
    public static List<Map<String, Object>> accountRowsAsMaps() {
        return List.of(
                accountRow("acc_usd_1", 10000, 10000, "USD"),
                accountRow("acc_usd_2", 5000, 5000, "USD"),
                accountRow("acc_usd_3", 500, 500, "USD"),
                accountRow("acc_usd_4", 100, 100, "USD"),
                accountRow("acc_usd_5", 0, 0, "USD"),
                accountRow("acc_eur_1", 10000, 10000, "EUR"),
                accountRow("acc_eur_2", 5000, 5000, "EUR"),
                accountRow("acc_eur_3", 500, 500, "EUR"),
                accountRow("acc_eur_4", 100, 100, "EUR"),
                accountRow("acc_eur_5", 0, 0, "EUR"));
    }

    /**
     * Builds one ACCOUNT item map using DynamoDB attribute names as keys.
     *
     * @param accountId        logical account id (e.g. {@code acc_usd_1})
     * @param currentBalance   stored current balance
     * @param availableBalance stored available balance
     * @param currency         ISO currency code
     * @return map suitable for conversion to {@code PutItem} attribute values
     */
    private static Map<String, Object> accountRow(String accountId, int currentBalance,
                                                  int availableBalance, String currency) {
        String pk = "ACCOUNT#" + accountId;
        return Map.of(
                "PK", pk,
                "SK", pk,
                "entityType", "ACCOUNT",
                "accountId", accountId,
                "status", "ACTIVE",
                "currentBalance", currentBalance,
                "availableBalance", availableBalance,
                "currency", currency,
                "version", 1);
    }
}
