package com.cloakedcent.app.domain.model

import com.cloakedcent.app.core.money.Money
import java.time.LocalDate

// Refunds are separate REFUND ledger entries linked to the original expense, which is
// never modified. Each refund is allocated to categories (and, for receipts, to the
// purchased items), so budgets and analysis subtract exactly what was returned.

enum class RefundState { NONE, PARTIAL, FULL }

data class RefundEntry(
    val transactionId: Long,
    val amount: Money,
    val occurredOn: LocalDate,
    /** Set for item refunds; null for refunds of the whole expense. */
    val itemDescription: String?,
)

/** One purchased item of a receipt-linked expense and how much of it has been refunded. */
data class RefundableItem(
    val itemId: Long,
    val description: String,
    val quantityMilli: Long,
    val unit: MeasureUnit,
    /** The item's share of the amount paid, tax and receipt discounts included. */
    val paid: Money,
    val refunded: Money,
    val refundedQuantityMilli: Long,
) {
    val remaining: Money get() = paid - refunded
    val remainingQuantityMilli: Long get() = (quantityMilli - refundedQuantityMilli).coerceAtLeast(0)
    val state: RefundState get() = RefundMath.state(paid, refunded)
}

data class RefundSummary(
    val transactionId: Long,
    val original: Money,
    val refunded: Money,
    val refunds: List<RefundEntry>,
    /** Empty unless the expense came from a confirmed receipt. */
    val items: List<RefundableItem>,
) {
    val remaining: Money get() = original - refunded
    val state: RefundState get() = RefundMath.state(original, refunded)
}

sealed class RefundException(message: String) : Exception(message) {
    class NotRefundable : RefundException("Only completed expenses can be refunded")
    class InvalidAmount : RefundException("Enter a refund amount greater than zero")
    class ExceedsRemaining(val remaining: Money) : RefundException("The refund is more than the remaining refundable amount")
    class InvalidQuantity : RefundException("Enter a quantity greater than zero and no more than was bought")
}

object RefundMath {
    fun state(original: Money, refunded: Money): RefundState = when {
        !refunded.isPositive -> RefundState.NONE
        refunded >= original -> RefundState.FULL
        else -> RefundState.PARTIAL
    }

    /** [value] x [numerator] / [denominator], half up, never more than [cap]. */
    fun proportion(value: Money, numerator: Long, denominator: Long, cap: Money): Money {
        require(denominator > 0 && numerator >= 0)
        val scaled = (Math.multiplyExact(value.cents, numerator) + denominator / 2) / denominator
        return Money(minOf(scaled, cap.cents))
    }

    /**
     * Refund for returning [quantityMilli] of an item bought as [item]. The last of the
     * quantity takes exactly what remains, so rounding never leaves or exceeds a cent.
     */
    fun amountForQuantity(item: RefundableItem, quantityMilli: Long): Money {
        require(quantityMilli > 0 && quantityMilli <= item.remainingQuantityMilli)
        return if (quantityMilli == item.remainingQuantityMilli) {
            item.remaining
        } else {
            proportion(item.paid, quantityMilli, item.quantityMilli, item.remaining)
        }
    }

    /**
     * The pre-tax item value ([net]) behind refunding [amount] of [paid]; used so product
     * analysis (which is pre-tax) is reduced consistently. Refunding the rest returns
     * exactly the rest.
     */
    fun netPart(amount: Money, paid: Money, remainingPaid: Money, net: Money, remainingNet: Money): Money =
        when {
            amount >= remainingPaid -> remainingNet
            !paid.isPositive -> Money.ZERO
            else -> proportion(net, amount.cents, paid.cents, remainingNet)
        }

    /**
     * Spreads a refund over items in proportion to what each still has refundable
     * (largest remainder), so a whole-expense refund is reflected per item and category.
     * [remaining] must be non-negative; [amount] at most their sum.
     */
    fun spread(amount: Money, remaining: List<Money>): List<Money> {
        val total = remaining.fold(0L) { sum, it -> sum + it.cents }
        require(amount.isPositive && amount.cents <= total)
        val shares = remaining.map { Math.multiplyExact(it.cents, amount.cents) }
        val floors = shares.map { it / total }.toMutableList()
        var leftover = amount.cents - floors.sum()
        shares.indices.sortedWith(compareByDescending<Int> { shares[it] % total }.thenBy { it }).forEach { i ->
            if (leftover > 0 && floors[i] < remaining[i].cents) {
                floors[i]++
                leftover--
            }
        }
        return floors.map(::Money)
    }
}
