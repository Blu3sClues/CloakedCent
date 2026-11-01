package com.pocketbudget.app.ui.feature.home

import com.pocketbudget.app.domain.model.StorageStatus
import com.pocketbudget.app.domain.repository.StorageRepository
import com.pocketbudget.app.domain.repository.StorageUnavailableException
import com.pocketbudget.app.domain.repository.StorageUnavailableException.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts loading then shows storage status`() = runTest(dispatcher) {
        val status = StorageStatus(Instant.ofEpochMilli(1_000), "4.x community")
        val viewModel = HomeViewModel(FakeStorageRepository { status })

        assertEquals(HomeUiState.Loading, viewModel.uiState.value)
        advanceUntilIdle()
        assertEquals(HomeUiState.Ready(status), viewModel.uiState.value)
    }

    @Test
    fun `storage failure is surfaced with its reason`() = runTest(dispatcher) {
        val viewModel = HomeViewModel(
            FakeStorageRepository { throw StorageUnavailableException(Reason.KEY_MISSING) },
        )

        advanceUntilIdle()
        assertEquals(HomeUiState.Error(Reason.KEY_MISSING), viewModel.uiState.value)
    }

    @Test
    fun `retry re-opens storage after a failure`() = runTest(dispatcher) {
        val status = StorageStatus(null, "4.x community")
        var attempts = 0
        val viewModel = HomeViewModel(
            FakeStorageRepository {
                attempts++
                if (attempts == 1) throw StorageUnavailableException(Reason.KEYSTORE_UNAVAILABLE)
                status
            },
        )
        advanceUntilIdle()
        assertEquals(HomeUiState.Error(Reason.KEYSTORE_UNAVAILABLE), viewModel.uiState.value)

        viewModel.retry()
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)
        advanceUntilIdle()
        assertEquals(HomeUiState.Ready(status), viewModel.uiState.value)
        assertEquals(2, attempts)
    }

    private class FakeStorageRepository(
        private val open: suspend () -> StorageStatus,
    ) : StorageRepository {
        override suspend fun openStorage(): StorageStatus = open()
    }
}
