package com.cloakedcent.app.domain.model

import java.time.Instant

/** Health of the encrypted on-device store, shown to the user on launch. */
data class StorageStatus(
    val createdAt: Instant?,
    val cipherVersion: String,
)
