package com.pocketbudget.app.ui.feature.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketbudget.app.R
import com.pocketbudget.app.ui.components.BackTopBar

/** A licence whose full text ships with the app (res/raw). */
enum class License(val title: String, @RawRes val text: Int?) {
    APACHE_2("Apache License 2.0", R.raw.license_apache_2_0),
    SQLCIPHER("SQLCipher Community Edition licence (BSD-style)", R.raw.license_sqlcipher),
    BSD_LEPTONICA("Leptonica licence (BSD 2-clause style)", R.raw.license_leptonica),
    IJG("Independent JPEG Group licence", R.raw.license_libjpeg),
    LIBPNG("PNG Reference Library licence", R.raw.license_libpng),
    BSD_3_LIBYUV("BSD 3-clause licence", R.raw.license_libyuv),
    MIT_BOUNCY_CASTLE("MIT licence (Bouncy Castle)", R.raw.license_bouncycastle),
    LIBTOM("LibTom licence (public domain)", R.raw.license_libtomcrypt),
    PUBLIC_DOMAIN("Public domain", null),
}

/** Third-party software included in Pocket Budget. Names are proper nouns and not translated. */
data class Acknowledgement(
    val name: String,
    val version: String?,
    val copyright: String,
    val license: License,
    val use: String,
)

/**
 * Everything bundled in the app, from the release runtime classpath and the native
 * libraries inside those packages. Update versions here when dependencies change.
 */
val ACKNOWLEDGEMENTS = listOf(
    Acknowledgement(
        "SQLCipher for Android", "4.19.1", "Copyright (c) 2025, ZETETIC LLC. All rights reserved.",
        License.SQLCIPHER, "Encrypts the on-device database",
    ),
    Acknowledgement("SQLite", null, "Public domain (D. Richard Hipp and contributors)", License.PUBLIC_DOMAIN, "Database engine inside SQLCipher"),
    Acknowledgement("LibTomCrypt", null, "Tom St Denis and the LibTom Projects", License.LIBTOM, "Cryptographic primitives used by SQLCipher"),
    Acknowledgement(
        "Bouncy Castle (bcprov-jdk18on)", "1.86", "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)",
        License.MIT_BOUNCY_CASTLE, "Argon2id password hashing for backups",
    ),
    Acknowledgement("Tesseract OCR", "5.5.1", "Copyright Hewlett-Packard Company, Google Inc. and the Tesseract contributors", License.APACHE_2, "Reads receipt text on the device"),
    Acknowledgement("Tesseract4Android", "4.9.0", "Copyright 2019 Adaptech s.r.o., Robert Pösel", License.APACHE_2, "Tesseract for Android"),
    Acknowledgement("Tesseract English model (tessdata_fast)", null, "Copyright Google Inc. and the Tesseract contributors", License.APACHE_2, "Bundled OCR language data"),
    Acknowledgement("Leptonica", "1.85.0", "Copyright (C) 2001-2020 Leptonica. All rights reserved.", License.BSD_LEPTONICA, "Image processing used by Tesseract"),
    Acknowledgement(
        "libjpeg (Independent JPEG Group)", "9f",
        "This software is based in part on the work of the Independent JPEG Group. Copyright (C) 1991-2024, Thomas G. Lane, Guido Vollbeding.",
        License.IJG, "JPEG decoding used by Tesseract",
    ),
    Acknowledgement("libpng", "1.6.48", "Copyright (c) 1995-2025 The PNG Reference Library Authors and others", License.LIBPNG, "PNG decoding used by Tesseract"),
    Acknowledgement("libyuv", null, "Copyright 2011 The LibYuv Project Authors. All rights reserved.", License.BSD_3_LIBYUV, "Camera image conversion inside CameraX"),
    Acknowledgement(
        "Android Jetpack (AndroidX)", null, "Copyright The Android Open Source Project",
        License.APACHE_2,
        "Jetpack Compose, Material 3, Activity, Core, Lifecycle, Navigation, Room, SQLite, WorkManager, CameraX, ExifInterface, Startup and related libraries",
    ),
    Acknowledgement("Material Design icons", null, "Copyright Google LLC", License.APACHE_2, "Icons used in the app"),
    Acknowledgement(
        "Kotlin standard library, kotlinx.coroutines, kotlinx.serialization, atomicfu", "2.4.21 / 1.11.0 / 1.8.1 / 0.28.0",
        "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors", License.APACHE_2, "Language and concurrency libraries",
    ),
    Acknowledgement("JetBrains Java Annotations", "23.0.0", "Copyright JetBrains s.r.o.", License.APACHE_2, "Code annotations"),
    Acknowledgement("Dagger", "2.59", "Copyright The Dagger Authors", License.APACHE_2, "Used inside CameraX"),
    Acknowledgement("Guava ListenableFuture, AutoValue annotations", "1.0 / 1.6.3", "Copyright Google LLC", License.APACHE_2, "Used inside AndroidX and CameraX"),
    Acknowledgement("JSpecify", "1.0.0", "Copyright The JSpecify Authors", License.APACHE_2, "Nullness annotations"),
    Acknowledgement("javax.inject / Jakarta Inject", "1 / 2.0.1", "Copyright The JSR-330 Expert Group and the Eclipse Foundation", License.APACHE_2, "Dependency-injection annotations"),
)

@Composable
fun AcknowledgementsRoute(onBack: () -> Unit) {
    var showing by remember { mutableStateOf<License?>(null) }
    val license = showing
    if (license?.text != null) {
        LicenseText(license, onBack = { showing = null })
        return
    }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.acknowledgements_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(stringResource(R.string.acknowledgements_intro), style = MaterialTheme.typography.bodyMedium) }
            items(ACKNOWLEDGEMENTS) { item ->
                Card(Modifier.fillMaxWidth().clickable(enabled = item.license.text != null) { showing = item.license }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(listOfNotNull(item.name, item.version).joinToString(" "), style = MaterialTheme.typography.titleMedium)
                        Text(item.use, style = MaterialTheme.typography.bodySmall)
                        Text(item.copyright, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.license.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseText(license: License, onBack: () -> Unit) {
    val resources = LocalResources.current
    val text = remember(license) { resources.openRawResource(license.text!!).bufferedReader().use { it.readText() } }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { BackTopBar(license.title, onBack) }) { padding ->
        Text(
            text,
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
