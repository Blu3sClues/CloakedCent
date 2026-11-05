package com.cloakedcent.app.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreKeyWrapperTest {

    // A test-only alias so the app's real wrapping key is never touched.
    private val alias = "cloaked_cent_test_wrapper"
    private val wrapper = AndroidKeystoreKeyWrapper(alias)

    @After
    fun deleteTestKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun wrapThenUnwrapReturnsOriginal() {
        val secret = ByteArray(32) { it.toByte() }

        assertArrayEquals(secret, wrapper.unwrap(wrapper.wrap(secret)))
    }

    @Test
    fun wrappingUsesAFreshIvEachTime() {
        val secret = ByteArray(32) { 7 }

        assertFalse(wrapper.wrap(secret).contentEquals(wrapper.wrap(secret)))
    }

    @Test
    fun tamperedBlobIsRejectedAsCorrupted() {
        val wrapped = wrapper.wrap(ByteArray(32) { 1 })
        wrapped[wrapped.size - 1] = (wrapped.last() + 1).toByte()

        assertThrows(DatabaseKeyException.KeyCorrupted::class.java) { wrapper.unwrap(wrapped) }
    }

    @Test
    fun unwrapWithoutKeystoreKeyReportsMissing() {
        val wrapped = wrapper.wrap(ByteArray(32) { 2 })
        deleteTestKey()

        assertThrows(DatabaseKeyException.KeyMissing::class.java) { wrapper.unwrap(wrapped) }
    }
}
