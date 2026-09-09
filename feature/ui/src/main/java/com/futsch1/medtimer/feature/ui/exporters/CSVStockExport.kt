package com.futsch1.medtimer.feature.ui.exporters

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.futsch1.medtimer.core.common.di.Dispatcher
import com.futsch1.medtimer.core.common.di.MedTimerDispatchers
import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.ui.MedicineStringFormatter
import com.futsch1.medtimer.core.ui.R
import com.futsch1.medtimer.core.ui.ReminderSummaryFormatter
import com.futsch1.medtimer.feature.reminders.api.SimulatedReminders
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDate

/**
 * Stock overview for refill planning: one row per medicine with what is left and when it runs out.
 *
 * Distinct from [CSVMedicineExport], which answers "what do you take" for a reader. This answers
 * "what do I have left", for a prescriber or pharmacist coordinating refills.
 */
class CSVStockExport @AssistedInject constructor(
    @Assisted private val medicines: List<Medicine>,
    @Assisted fragmentManager: FragmentManager,
    @param:ApplicationContext val context: Context,
    private val medicineStringFormatter: MedicineStringFormatter,
    private val reminderSummaryFormatter: ReminderSummaryFormatter,
    private val simulatedReminders: SimulatedReminders,
    private val timeAccess: TimeAccess,
    @param:Dispatcher(MedTimerDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) : Export(fragmentManager) {

    @AssistedFactory
    fun interface Factory {
        fun create(medicines: List<Medicine>, fragmentManager: FragmentManager): CSVStockExport
    }

    @Throws(ExporterException::class)
    public override suspend fun exportInternal(file: File) {
        val runOutDates = withWidenedSimulation { simulatedReminders.stockRunOutDates.value }
        try {
            withContext(ioDispatcher) {
                FileWriter(file).use { csvFile ->
                    csvFile.write(headerLine())
                    for (medicine in sortedBySoonestRunOut(runOutDates)) {
                        csvFile.write(medicineLine(medicine, runOutDates[medicine.id]))
                    }
                }
            }
        } catch (_: IOException) {
            throw ExporterException()
        }
    }

    /**
     * The default 28-day simulation window is too narrow for refill planning, and [SimulatedReminders.requestWindow]
     * only queues a recalculation — so wait for the simulation to reach the horizon before reading,
     * and always release, or a failed export would leave the app simulating a year ahead.
     *
     * The wait is bounded because the horizon may never be reached: a request that does not widen
     * the effective window triggers no recalculation, so an existing run that ended a day earlier
     * leaves the horizon permanently just out of reach. On timeout the report is written from
     * whatever has been simulated, which the run-out column labels honestly as "After <date>".
     */
    private suspend fun <T> withWidenedSimulation(block: () -> T): T {
        val horizon = timeAccess.localDate().plusDays(SIMULATION_DAYS)
        simulatedReminders.requestWindow(CONSUMER_ID, SIMULATION_DAYS)
        try {
            withTimeoutOrNull(SIMULATION_WAIT_MS) {
                simulatedReminders.simulatedThrough.first { it >= horizon }
            }
            return block()
        } finally {
            simulatedReminders.releaseWindow(CONSUMER_ID)
        }
    }

    // Medicines without stock tracking have no run-out date; they stay in the report so nothing
    // looks silently dropped, but they belong at the end rather than above what is running out.
    private fun sortedBySoonestRunOut(runOutDates: Map<Int, LocalDate?>): List<Medicine> =
        medicines.sortedWith(
            compareBy(
                { runOutDates[it.id] == null },
                { runOutDates[it.id] ?: LocalDate.MAX },
                { it.name })
        )

    private fun headerLine() = listOf(
        context.getString(R.string.tab_medicine),
        context.getString(R.string.dosage),
        context.getString(R.string.medicine_stock),
        context.getString(R.string.estimated_run_out_date)
    ).joinToString(";", postfix = "\n")

    private suspend fun medicineLine(medicine: Medicine, runOutDate: LocalDate?): String {
        val stock = if (medicine.isStockManagementActive()) {
            medicineStringFormatter.getStockText(medicine)
        } else {
            ""
        }
        val runOut = if (runOutDate != null) {
            medicineStringFormatter.getStockRunOutText(
                runOutDate,
                simulatedReminders.simulatedThrough.value
            )
        } else {
            ""
        }
        return listOf(medicine.name, dosage(medicine), stock, runOut).joinToString(";", postfix = "\n")
    }

    private suspend fun dosage(medicine: Medicine): String =
        medicine.reminders
            .filterNot { it.isOutOfStockOrExpirationReminder }
            // map is inline and joinToString is not, so the suspending summary call happens here.
            .map { reminder ->
                val amount = if (reminder.variableAmount) {
                    context.getString(R.string.variable_amount)
                } else {
                    reminder.amount
                }
                "$amount @ ${reminderSummaryFormatter.formatExportReminderSummary(reminder)}"
            }
            .joinToString(DOSAGE_SEPARATOR)

    override val extension = "csv"
    override val type = "Stock"

    companion object {
        /** Deliberate overshoot: refills run 3-6 months, so a year covers any realistic horizon. */
        private const val SIMULATION_DAYS = 365L
        private const val CONSUMER_ID = "stockExport"

        /** Comfortably over the simulation's own 2 s budget plus its 1 s debounce. */
        private const val SIMULATION_WAIT_MS = 10_000L

        /** Reminder summaries contain commas, so rows separate them with something else. */
        private const val DOSAGE_SEPARATOR = " | "
    }
}
