package com.aasif.watchdog.core.engine

import com.aasif.watchdog.core.merchant.KnownMerchant
import com.aasif.watchdog.core.merchant.MerchantCatalog
import com.aasif.watchdog.core.model.PaymentEvent

/**
 * Whether an event read from a bank SMS is worth keeping.
 *
 * A bank texts about every debit, and nearly all of them are one-off spending. SMS earns its place
 * for the things only it carries: mandate registrations and revocations, pre-debit notices, and
 * debits that state their own AutoPay/mandate signal. A plain debit is kept only when the payee is
 * a service the catalog knows sells subscriptions; a plain debit to anyone else says nothing a
 * subscription could be built on, and is not stored at all.
 */
fun PaymentEvent.worthKeepingFromSms(catalog: Map<String, KnownMerchant> = MerchantCatalog.byId): Boolean =
    when (this) {
        is PaymentEvent.Debit -> viaMandate || merchantId in catalog
        else -> true
    }
