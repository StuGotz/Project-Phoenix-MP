package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.PolarHrMeasurement
import com.devil.phoenixproject.domain.model.PolarPmdMeasurement
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.seedExercise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end telemetry persistence test verifying that raw Polar beat and ECG records
 * survive foreign-key constraints (PRAGMA foreign_keys = ON) via in-memory buffering
 * prior to session row creation and asynchronous persistence through [WorkoutRepository].
 */
class PolarH10PersistenceTest {

    private lateinit var database: PhoenixDatabase
    private lateinit var workoutRepository: WorkoutRepository
    private lateinit var testScope: CoroutineScope
    private var simulatedTime: Long = 1_000_000L

    @BeforeTest
    fun setUp() {
        database = createTestDatabase()
        workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        database.seedExercise("bench")
    }

    @AfterTest
    fun tearDown() {
        testScope.cancel()
    }

    private fun insertWorkoutSession(id: String) = runBlocking {
        workoutRepository.saveSession(
            WorkoutSession(
                id = id,
                exerciseId = "bench",
                exerciseName = "Bench Press",
                timestamp = 0L,
            )
        )
    }

    @Test
    fun `telemetry is buffered before session exists and flushed after session creation with foreign keys ON`() = runBlocking {
        val manager = PolarH10BleManager(
            workoutRepository = workoutRepository,
            scope = testScope,
            timeSource = { simulatedTime },
        )
        manager.setConnectedForTesting(true)
        manager.setPersistRawEcg(true)
        val sessionId = "session-fk-test"

        // 1. Begin recording before WorkoutSession row exists
        manager.startSessionRecording(sessionId = sessionId, phase = "WORKING_SET")

        // 2. Feed HR/RR beats and ECG segment while session does not exist in DB
        val hrMeasurement = PolarHrMeasurement(
            hr = 145,
            contactDetected = true,
            rrIntervalsMs = listOf(800.0, 820.0),
        )
        manager.processRrIntervals(hrMeasurement)

        val ecgMeasurement = PolarPmdMeasurement.Ecg(
            sensorTimestampNs = 123456789L,
            samplesMicrovolts = listOf(150, 250, 350, 450),
        )
        manager.processPmdMeasurement(ecgMeasurement)

        // 3. Verify records are held in memory buffer and not yet in SQLite
        assertEquals(2, manager.getBufferedBeatSamplesForTesting().size)
        assertEquals(1, manager.getBufferedEcgSegmentsForTesting().size)
        val beatRowsBeforeCommit = database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession(sessionId).executeAsList()
        val ecgRowsBeforeCommit = database.phoenixDatabaseQueries.selectPolarEcgSegmentsBySession(sessionId).executeAsList()
        assertTrue(beatRowsBeforeCommit.isEmpty())
        assertTrue(ecgRowsBeforeCommit.isEmpty())

        // 4. Now commit the parent WorkoutSession row
        insertWorkoutSession(sessionId)

        // 5. Flush buffered telemetry
        manager.flushSessionTelemetry(sessionId)
        manager.awaitPendingPersistenceForTesting()

        // 6. Verify in-memory buffers are cleared and rows are durable in SQLite with foreign keys ON
        assertEquals(0, manager.getBufferedBeatSamplesForTesting().size)
        assertEquals(0, manager.getBufferedEcgSegmentsForTesting().size)

        val beatRowsAfterCommit = database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession(sessionId).executeAsList()
        val ecgRowsAfterCommit = database.phoenixDatabaseQueries.selectPolarEcgSegmentsBySession(sessionId).executeAsList()

        assertEquals(2, beatRowsAfterCommit.size)
        assertEquals(1, ecgRowsAfterCommit.size)

        val firstBeat = beatRowsAfterCommit[0]
        assertEquals(sessionId, firstBeat.sessionId)
        assertEquals(145L, firstBeat.hrBpm)
        assertEquals(800.0, firstBeat.rrMs)
        assertEquals("WORKING_SET", firstBeat.phase)

        val ecgSeg = ecgRowsAfterCommit[0]
        assertEquals(sessionId, ecgSeg.sessionId)
        assertEquals(123456789L, ecgSeg.sensorTimestampNs)
        assertEquals("150,250,350,450", ecgSeg.samplesUvCsv)

        // 7. Post-workout cooldown: subsequent beats are buffered in memory to prevent autocommit storm and flushed when cooldown ends
        manager.triggerCooldown(sessionId)
        val cooldownBeat = PolarHrMeasurement(
            hr = 130,
            contactDetected = true,
            rrIntervalsMs = listOf(860.0),
        )
        manager.processRrIntervals(cooldownBeat)

        assertEquals(1, manager.getBufferedBeatSamplesForTesting().size)
        manager.stopCooldown()
        manager.awaitPendingPersistenceForTesting()
        assertEquals(0, manager.getBufferedBeatSamplesForTesting().size)

        val beatRowsDuringCooldown = database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession(sessionId).executeAsList()
        assertEquals(3, beatRowsDuringCooldown.size)
        assertEquals(130L, beatRowsDuringCooldown[2].hrBpm)

        // Verify early stopCooldown (<60s) did not fabricate hrr60 or hrr300, and working set HR summary remained 145
        val sessionRow = database.phoenixDatabaseQueries.selectSessionById(sessionId).executeAsOne()
        assertEquals(145L, sessionRow.avgHeartRateBpm)
        assertEquals(145L, sessionRow.minHeartRateBpm)
        assertEquals(145L, sessionRow.maxHeartRateBpm)
        assertNull(sessionRow.hrr60Bpm)
        assertNull(sessionRow.hrr300Bpm)
    }

    @Test
    fun `raw ECG persistence is disabled by default while live ECG sparkline still updates`() = runBlocking {
        val manager = PolarH10BleManager(
            workoutRepository = workoutRepository,
            scope = testScope,
            timeSource = { simulatedTime },
        )
        manager.setConnectedForTesting(true)
        val sessionId = "session-ecg-opt-out"
        manager.startSessionRecording(sessionId = sessionId, phase = "WORKING_SET")
        manager.processRrIntervals(PolarHrMeasurement(hr = 140, contactDetected = true, rrIntervalsMs = listOf(800.0)))

        val ecgMeasurement = PolarPmdMeasurement.Ecg(
            sensorTimestampNs = 999L,
            samplesMicrovolts = listOf(100, 200, 300),
        )
        manager.processPmdMeasurement(ecgMeasurement)

        // Live sparkline state updates even when persistRawEcg is false
        assertNotNull(manager.latestEcg.value)
        assertEquals(0, manager.getBufferedEcgSegmentsForTesting().size)

        insertWorkoutSession(sessionId)
        manager.flushSessionTelemetry(sessionId)
        manager.awaitPendingPersistenceForTesting()

        val ecgRows = database.phoenixDatabaseQueries.selectPolarEcgSegmentsBySession(sessionId).executeAsList()
        assertTrue(ecgRows.isEmpty())
    }

    @Test
    fun `raw ECG persistence caps buffered and stored segments at MAX_ECG_SEGMENTS_PER_SESSION`() = runBlocking {
        val manager = PolarH10BleManager(
            workoutRepository = workoutRepository,
            scope = testScope,
            timeSource = { simulatedTime },
        )
        manager.setConnectedForTesting(true)
        manager.setPersistRawEcg(true)
        val sessionId = "session-ecg-cap"
        manager.startSessionRecording(sessionId = sessionId, phase = "WORKING_SET")
        manager.processRrIntervals(PolarHrMeasurement(hr = 140, contactDetected = true, rrIntervalsMs = listOf(800.0)))

        for (i in 1..650) {
            manager.processPmdMeasurement(
                PolarPmdMeasurement.Ecg(
                    sensorTimestampNs = i.toLong(),
                    samplesMicrovolts = listOf(100, 200, 300),
                )
            )
        }

        assertEquals(
            PolarH10BleManager.MAX_ECG_SEGMENTS_PER_SESSION,
            manager.getBufferedEcgSegmentsForTesting().size,
        )

        insertWorkoutSession(sessionId)
        manager.flushSessionTelemetry(sessionId)
        manager.awaitPendingPersistenceForTesting()

        val ecgRows = database.phoenixDatabaseQueries.selectPolarEcgSegmentsBySession(sessionId).executeAsList()
        assertEquals(PolarH10BleManager.MAX_ECG_SEGMENTS_PER_SESSION, ecgRows.size)
        assertEquals(51L, ecgRows.first().sensorTimestampNs)
        assertEquals(650L, ecgRows.last().sensorTimestampNs)
    }

    @Test
    fun `abandoned set discards buffered telemetry cleanly`() {
        val manager = PolarH10BleManager(
            workoutRepository = workoutRepository,
            scope = testScope,
            timeSource = { simulatedTime },
        )
        manager.setConnectedForTesting(true)
        val sessionId = "session-abandoned"

        manager.startSessionRecording(sessionId = sessionId, phase = "WORKING_SET")
        manager.processRrIntervals(
            PolarHrMeasurement(
                hr = 140,
                contactDetected = true,
                rrIntervalsMs = listOf(800.0),
            )
        )

        assertEquals(1, manager.getBufferedBeatSamplesForTesting().size)

        // Abandon set
        manager.discardActiveRecording()

        assertEquals(0, manager.getBufferedBeatSamplesForTesting().size)
        assertEquals(null, manager.currentRecordingSessionId)

        val beatRows = database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession(sessionId).executeAsList()
        assertTrue(beatRows.isEmpty())
    }
}
