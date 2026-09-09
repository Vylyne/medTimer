package com.futsch1.medtimer

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.fragment.app.FragmentManager
import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.core.domain.model.ReminderTime
import com.futsch1.medtimer.core.domain.model.SimulatedReminder
import com.futsch1.medtimer.core.domain.model.UserPreferences
import com.futsch1.medtimer.core.domain.repository.ReminderRepository
import com.futsch1.medtimer.core.ui.MedicineStringFormatter
import com.futsch1.medtimer.core.ui.ReminderSummaryFormatter
import com.futsch1.medtimer.core.ui.TimeFormatter
import com.futsch1.medtimer.feature.reminders.api.SimulatedReminders
import com.futsch1.medtimer.feature.ui.exporters.CSVStockExport
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@HiltAndroidTest
class CSVStockExportUnitTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @BindValue
    val mockPreferenceDataSource: PreferencesDataSource = mock()

    @BindValue
    val boundAlarmManager: AlarmManager = mock()

    @BindValue
    val boundNotificationManager: NotificationManager = mock()

    private lateinit var context: Context
    private lateinit var timeFormatter: TimeFormatter
    private lateinit var medicineStringFormatter: MedicineStringFormatter
    private lateinit var reminderSummaryFormatter: ReminderSummaryFormatter
    private lateinit var mockReminderRepository: ReminderRepository

    private val today: LocalDate = LocalDate.of(2026, 1, 10)

    private val timeAccess = object : TimeAccess {
        override fun systemZone(): ZoneId = ZoneId.of("UTC")
        override fun localDate(): LocalDate = today
        override fun now(): Instant = today.atStartOfDay(ZoneId.of("UTC")).toInstant()
    }

    /** Records window requests so the test can assert the export widens the simulation horizon. */
    private class FakeSimulatedReminders(
        runOutDates: Map<Int, LocalDate?>,
        simulatedThrough: LocalDate
    ) : SimulatedReminders {
        val requested = mutableListOf<Pair<String, Long>>()
        val released = mutableListOf<String>()

        override val simulatedReminders: StateFlow<List<SimulatedReminder>> =
            MutableStateFlow(emptyList<SimulatedReminder>()).asStateFlow()
        private val _simulatedThrough = MutableStateFlow(simulatedThrough)
        override val simulatedThrough: StateFlow<LocalDate> = _simulatedThrough.asStateFlow()
        override val stockRunOutDates: StateFlow<Map<Int, LocalDate?>> =
            MutableStateFlow(runOutDates).asStateFlow()

        override fun requestWindow(consumerId: String, days: Long) {
            requested.add(consumerId to days)
        }

        override fun releaseWindow(consumerId: String) {
            released.add(consumerId)
        }
    }

    @Before
    fun setUp() {
        hiltRule.inject()
        context = RuntimeEnvironment.getApplication()
        mockReminderRepository = mock()
        val preferences = MutableStateFlow(UserPreferences.default())
        `when`(mockPreferenceDataSource.preferences).thenReturn(preferences)
        timeFormatter = TimeFormatter(context, mockPreferenceDataSource)
        medicineStringFormatter =
            MedicineStringFormatter(context, mockPreferenceDataSource, timeFormatter)
        reminderSummaryFormatter =
            ReminderSummaryFormatter(context, mockReminderRepository, timeFormatter)
    }

    private fun dailyReminder(medicineId: Int, amount: String, time: LocalTime) =
        Reminder.default().copy(
            medicineRelId = medicineId,
            amount = amount,
            time = ReminderTime(time)
        )

    @Test
    fun exportsHeaderAndOneRowPerMedicineSortedBySoonestRunOut() {
        val laterMedicine = Medicine.default().copy(
            name = "Medicine A",
            id = 1,
            amount = 30.0,
            unit = "tablets",
            reminders = listOf(dailyReminder(1, "1", LocalTime.of(8, 0)))
        )
        val soonerMedicine = Medicine.default().copy(
            name = "Medicine B",
            id = 2,
            amount = 5.0,
            unit = "tablets",
            reminders = listOf(dailyReminder(2, "2", LocalTime.of(9, 0)))
        )
        val simulated = FakeSimulatedReminders(
            runOutDates = mapOf(
                1 to LocalDate.of(2026, 3, 1),
                2 to LocalDate.of(2026, 1, 20)
            ),
            simulatedThrough = today.plusDays(365)
        )

        val lines = runExport(listOf(laterMedicine, soonerMedicine), simulated)

        assertEquals(
            "Medicine;Dosage;Medicine stock;Estimated run out date",
            lines[0]
        )
        assertTrue(lines[1].startsWith("Medicine B;"), "expected soonest run-out first, got ${lines[1]}")
        assertTrue(lines[2].startsWith("Medicine A;"), "expected later run-out second, got ${lines[2]}")
    }

    @Test
    fun untrackedMedicinesAreListedLastWithBlankStockCells() {
        val tracked = Medicine.default().copy(
            name = "Medicine A",
            id = 1,
            amount = 5.0,
            unit = "tablets",
            reminders = listOf(dailyReminder(1, "1", LocalTime.of(8, 0)))
        )
        val untracked = Medicine.default().copy(
            name = "Medicine B",
            id = 2,
            reminders = listOf(dailyReminder(2, "1", LocalTime.of(9, 0)))
        )
        val simulated = FakeSimulatedReminders(
            runOutDates = mapOf(1 to LocalDate.of(2026, 2, 1), 2 to null),
            simulatedThrough = today.plusDays(365)
        )

        val lines = runExport(listOf(untracked, tracked), simulated)

        assertTrue(lines[1].startsWith("Medicine A;"), "expected tracked medicine first, got ${lines[1]}")
        assertTrue(lines[2].endsWith(";;"), "expected blank stock and run-out cells, got ${lines[2]}")
    }

    @Test
    fun widensSimulationWindowToAYearAndReleasesIt() {
        val medicine = Medicine.default().copy(name = "Medicine A", id = 1, amount = 5.0)
        val simulated = FakeSimulatedReminders(
            runOutDates = mapOf(1 to LocalDate.of(2026, 6, 1)),
            simulatedThrough = today.plusDays(365)
        )

        runExport(listOf(medicine), simulated)

        assertEquals(listOf("stockExport" to 365L), simulated.requested)
        assertEquals(listOf("stockExport"), simulated.released)
    }

    @Test
    fun medicineRunningOutBeyondTheHorizonIsLabelledAfterTheSimulatedDate() {
        val medicine = Medicine.default().copy(
            name = "Medicine A",
            id = 1,
            amount = 500.0,
            unit = "tablets",
            reminders = listOf(dailyReminder(1, "1", LocalTime.of(8, 0)))
        )
        val simulatedThrough = today.plusDays(365)
        val simulated = FakeSimulatedReminders(
            // The repository parks tracked medicines that never hit zero at LocalDate.MAX.
            runOutDates = mapOf(1 to LocalDate.MAX),
            simulatedThrough = simulatedThrough
        )

        val lines = runExport(listOf(medicine), simulated)

        val expected = context.getString(
            com.futsch1.medtimer.core.ui.R.string.stock_after_simulation_end,
            timeFormatter.localDateToString(simulatedThrough)
        )
        assertTrue(lines[1].endsWith(";$expected"), "expected beyond-horizon label, got ${lines[1]}")
    }

    @Test
    fun medicineWithoutRemindersHasAnEmptyDosageCell() {
        val medicine = Medicine.default().copy(
            name = "Medicine A",
            id = 1,
            amount = 5.0,
            unit = "tablets",
            reminders = emptyList()
        )
        val simulated = FakeSimulatedReminders(
            runOutDates = mapOf(1 to LocalDate.of(2026, 2, 1)),
            simulatedThrough = today.plusDays(365)
        )

        val lines = runExport(listOf(medicine), simulated)

        assertEquals("Medicine A", lines[1].split(";")[0])
        assertEquals("", lines[1].split(";")[1])
    }

    private fun runExport(
        medicines: List<Medicine>,
        simulated: FakeSimulatedReminders
    ): List<String> {
        val file = mock<File>()
        val fragmentManager = mock<FragmentManager>()
        val written = StringBuilder()

        Mockito.mockConstruction(FileWriter::class.java) { writer, _ ->
            `when`(writer.append(Mockito.anyString())).thenAnswer { invocation ->
                written.append(invocation.getArgument<String>(0)); writer
            }
            Mockito.doAnswer { invocation ->
                written.append(invocation.getArgument<String>(0)); null
            }.`when`(writer).write(Mockito.anyString())
        }.use {
            val export = CSVStockExport(
                medicines,
                fragmentManager,
                context,
                medicineStringFormatter,
                reminderSummaryFormatter,
                simulated,
                timeAccess,
                Dispatchers.Unconfined
            )
            runBlocking { export.exportInternal(file) }
        }

        return written.toString().trim().lines()
    }
}
