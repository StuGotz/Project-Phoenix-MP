package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.domain.model.PolarAccSample
import com.devil.phoenixproject.domain.model.PolarPmdMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolarProtocolParserTest {

    // ==================== Heart Rate (0x2A37) Tests ====================

    @Test
    fun `parsePolarHrMeasurement returns null for empty or truncated payload`() {
        assertNull(parsePolarHrMeasurement(byteArrayOf()))
        // Flag claims 16-bit HR (bit 0 = 1), but payload is only 2 bytes total
        assertNull(parsePolarHrMeasurement(byteArrayOf(0x01, 0x48)))
        // Flag claims energy present (bit 3 = 1), but energy bytes missing
        assertNull(parsePolarHrMeasurement(byteArrayOf(0x08, 0x48)))
    }

    @Test
    fun `parsePolarHrMeasurement parses basic 8-bit HR with contact detected`() {
        // Flags: 0x06 (bit 1=1, bit 2=1 -> contact detected), HR: 72 bpm
        val data = byteArrayOf(0x06, 72)
        val result = parsePolarHrMeasurement(data)

        assertNotNull(result)
        assertEquals(72, result.hr)
        assertTrue(result.contactDetected)
        assertNull(result.energyExpendedKj)
        assertTrue(result.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `parsePolarHrMeasurement handles contact status correctly`() {
        // Flags: 0x04 (contact supported but NOT detected -> bits 1..2 = 2)
        val notDetected = parsePolarHrMeasurement(byteArrayOf(0x04, 60))
        assertNotNull(notDetected)
        assertFalse(notDetected.contactDetected)

        // Flags: 0x00 (contact not supported -> bits 1..2 = 0, but hr = 60 > 0 -> contact OK)
        val notSupportedWithHr = parsePolarHrMeasurement(byteArrayOf(0x00, 60))
        assertNotNull(notSupportedWithHr)
        assertTrue(notSupportedWithHr.contactDetected)

        // Flags: 0x00 (contact not supported, hr = 0 -> contact false)
        val notSupportedZeroHr = parsePolarHrMeasurement(byteArrayOf(0x00, 0))
        assertNotNull(notSupportedZeroHr)
        assertFalse(notSupportedZeroHr.contactDetected)

        // Flags: 0x02 (contact not supported -> bits 1..2 = 1, hr = 75 > 0 -> contact OK)
        val altNotSupportedWithHr = parsePolarHrMeasurement(byteArrayOf(0x02, 75))
        assertNotNull(altNotSupportedWithHr)
        assertTrue(altNotSupportedWithHr.contactDetected)

        // Flags: 0x06 (contact supported and detected -> bits 1..2 = 3)
        val detected = parsePolarHrMeasurement(byteArrayOf(0x06, 60))
        assertNotNull(detected)
        assertTrue(detected.contactDetected)
    }

    @Test
    fun `parsePolarHrMeasurement parses 16-bit HR`() {
        // Flags: 0x01 (16-bit HR format) | 0x06 (contact detected) = 0x07
        // HR: 280 bpm (0x0118 -> LE [0x18, 0x01])
        val data = byteArrayOf(0x07, 0x18, 0x01)
        val result = parsePolarHrMeasurement(data)

        assertNotNull(result)
        assertEquals(280, result.hr)
        assertTrue(result.contactDetected)
    }

    @Test
    fun `parsePolarHrMeasurement parses energy expended`() {
        // Flags: 0x08 (energy present) | 0x06 (contact detected) = 0x0E
        // HR: 80 bpm, Energy: 1500 kJ (0x05DC -> LE [0xDC, 0x05])
        val data = byteArrayOf(0x0E, 80, 0xDC.toByte(), 0x05)
        val result = parsePolarHrMeasurement(data)

        assertNotNull(result)
        assertEquals(80, result.hr)
        assertEquals(1500, result.energyExpendedKj)
        assertTrue(result.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `parsePolarHrMeasurement parses RR intervals accurately in milliseconds`() {
        // Flags: 0x10 (RR present) | 0x06 (contact detected) = 0x16
        // HR: 60 bpm
        // RR 1: 1024 raw units = exactly 1000.0 ms (0x0400 -> LE [0x00, 0x04])
        // RR 2: 819 raw units = (819 * 1000.0) / 1024.0 ≈ 799.8046875 ms (0x0333 -> LE [0x33, 0x03])
        val data = byteArrayOf(
            0x16,
            60,
            0x00, 0x04,
            0x33, 0x03,
        )
        val result = parsePolarHrMeasurement(data)

        assertNotNull(result)
        assertEquals(60, result.hr)
        assertEquals(2, result.rrIntervalsMs.size)
        assertEquals(1000.0, result.rrIntervalsMs[0], 0.001)
        assertEquals((819 * 1000.0) / 1024.0, result.rrIntervalsMs[1], 0.001)
    }

    @Test
    fun `parsePolarHrMeasurement parses complete packet with 16-bit HR and energy and multiple RR intervals`() {
        // Flags: 0x01 (16-bit HR) | 0x06 (contact detected) | 0x08 (energy) | 0x10 (RR) = 0x1F
        // HR: 120 bpm (0x0078 -> [0x78, 0x00])
        // Energy: 250 kJ (0x00FA -> [0xFA, 0x00])
        // RR 1: 512 raw = 500.0 ms (0x0200 -> [0x00, 0x02])
        val data = byteArrayOf(
            0x1F,
            0x78, 0x00,
            0xFA.toByte(), 0x00,
            0x00, 0x02,
        )
        val result = parsePolarHrMeasurement(data)

        assertNotNull(result)
        assertEquals(120, result.hr)
        assertTrue(result.contactDetected)
        assertEquals(250, result.energyExpendedKj)
        assertEquals(1, result.rrIntervalsMs.size)
        assertEquals(500.0, result.rrIntervalsMs[0], 0.001)
    }

    // ==================== Battery Level (0x2A19) Tests ====================

    @Test
    fun `parsePolarBatteryLevel parses valid percentage`() {
        assertNull(parsePolarBatteryLevel(byteArrayOf()))
        assertEquals(100, parsePolarBatteryLevel(byteArrayOf(100)))
        assertEquals(85, parsePolarBatteryLevel(byteArrayOf(85)))
        assertEquals(0, parsePolarBatteryLevel(byteArrayOf(0)))
        // Clamping out-of-range values
        assertEquals(100, parsePolarBatteryLevel(byteArrayOf(120)))
    }

    // ==================== Bit Unpacking & 64-bit LE Tests ====================

    @Test
    fun `getInt64LE parses 64-bit little endian timestamp`() {
        val data = byteArrayOf(
            0x00, // offset padding
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        )
        val expected = 0x0807060504030201L
        assertEquals(expected, getInt64LE(data, 1))
    }

    @Test
    fun `readSignedBits parses positive and negative signed integers across bit boundaries`() {
        // Byte 0: 0xFA (1111 1010)
        // 4-bit signed:
        // bits 0..3: 1010b = -6
        // bits 4..7: 1111b = -1
        val data = byteArrayOf(0xFA.toByte())
        assertEquals(-6, readSignedBits(data, 0, 0, 4))
        assertEquals(-1, readSignedBits(data, 0, 4, 4))

        // 8-bit signed:
        assertEquals(-6, readSignedBits(byteArrayOf(0xFA.toByte()), 0, 0, 8))
        assertEquals(125, readSignedBits(byteArrayOf(125), 0, 0, 8))

        // 10-bit signed spanning across byte boundaries:
        // Value: -5 (10-bit: 0x3FB -> 11 1111 1011b)
        // LE bytes: Byte 0 = 0xFB (1111 1011b), Byte 1 = 0x03 (0000 0011b)
        val tenBitData = byteArrayOf(0xFB.toByte(), 0x03)
        assertEquals(-5, readSignedBits(tenBitData, 0, 0, 10))
    }

    // ==================== PMD ECG (FB005C82) Tests ====================

    @Test
    fun `parsePolarPmdData rejects packets with invalid length or unknown type`() {
        // Less than 10 bytes
        assertNull(parsePolarPmdData(ByteArray(9)))
        // Unknown measurement type 0xFF
        val unknownType = ByteArray(10).apply { this[0] = 0xFF.toByte() }
        assertNull(parsePolarPmdData(unknownType))
    }

    @Test
    fun `parsePolarPmdData parses uncompressed 24-bit ECG samples in microvolts`() {
        // Type 0x00 (ECG), Timestamp: 1000_000_000 ns (0x3B9ACA00), FrameType: 0x00
        // Samples:
        // Sample 0: 0 µV ([0x00, 0x00, 0x00])
        // Sample 1: +1000 µV (0x0003E8 -> [0xE8, 0x03, 0x00])
        // Sample 2: -1000 µV (24-bit two's complement: 0x1000000 - 1000 = 0xFFFF18 -> [0x18, 0xFF, 0xFF])
        val bytes = ByteArray(10 + 9).apply {
            this[0] = 0x00 // ECG
            // Timestamp ns: 1000 ns (0x00000000000003E8)
            this[1] = 0xE8.toByte()
            this[2] = 0x03
            // FrameType: 0x00 (uncompressed)
            this[9] = 0x00

            // Sample 0: 0 µV
            this[10] = 0x00
            this[11] = 0x00
            this[12] = 0x00

            // Sample 1: +1000 µV
            this[13] = 0xE8.toByte()
            this[14] = 0x03
            this[15] = 0x00

            // Sample 2: -1000 µV
            this[16] = 0x18
            this[17] = 0xFC.toByte()
            this[18] = 0xFF.toByte()
        }

        val result = parsePolarPmdData(bytes)
        assertNotNull(result)
        val ecg = assertIs<PolarPmdMeasurement.Ecg>(result)
        assertEquals(1000L, ecg.sensorTimestampNs)
        assertEquals(3, ecg.samplesMicrovolts.size)
        assertEquals(0, ecg.samplesMicrovolts[0])
        assertEquals(1000, ecg.samplesMicrovolts[1])
        assertEquals(-1000, ecg.samplesMicrovolts[2])
    }

    // ==================== PMD ACC (FB005C82) Tests ====================

    @Test
    fun `parsePolarPmdData parses uncompressed 16-bit ACC samples with frameTypes 0x00 and 0x01`() {
        listOf(0x00.toByte(), 0x01.toByte()).forEach { frameType ->
            val bytes = ByteArray(16).apply {
                this[0] = 0x02 // ACC
                this[1] = 0x88.toByte()
                this[2] = 0x13 // 5000L
                this[9] = frameType
                this[10] = 0x64; this[11] = 0x00 // X = 100
                this[12] = 0x38; this[13] = 0xFF.toByte() // Y = -200
                this[14] = 0xD4.toByte(); this[15] = 0x03 // Z = 980
            }
            val acc = assertIs<PolarPmdMeasurement.Acc>(assertNotNull(parsePolarPmdData(bytes)))
            assertEquals(5000L, acc.sensorTimestampNs)
            assertEquals(1, acc.samples.size)
            assertEquals(PolarAccSample(xMg = 100, yMg = -200, zMg = 980), acc.samples[0])
        }
    }

    @Test
    fun `parsePolarPmdData parses delta-compressed ACC samples with frameTypes 0x80 and 0x81`() {
        listOf(0x80.toByte(), 0x81.toByte()).forEach { frameType ->
            val bytes = ByteArray(24).apply {
                this[0] = 0x02; this[1] = 0xD0.toByte(); this[2] = 0x07; this[9] = frameType
                this[10] = 0xE8.toByte(); this[11] = 0x03 // X = 1000
                this[12] = 0x00; this[13] = 0x00 // Y = 0
                this[14] = 0x18; this[15] = 0xFC.toByte() // Z = -1000
                this[16] = 8; this[17] = 2 // deltaSizeBits = 8, sampleCount = 2
                this[18] = 5; this[19] = (-10).toByte(); this[20] = 20
                this[21] = (-5).toByte(); this[22] = 15; this[23] = (-20).toByte()
            }
            val acc = assertIs<PolarPmdMeasurement.Acc>(assertNotNull(parsePolarPmdData(bytes)))
            assertEquals(2000L, acc.sensorTimestampNs)
            assertEquals(3, acc.samples.size)
            assertEquals(PolarAccSample(1000, 0, -1000), acc.samples[0])
            assertEquals(PolarAccSample(1005, -10, -980), acc.samples[1])
            assertEquals(PolarAccSample(1000, 5, -1000), acc.samples[2])
        }
    }

    @Test
    fun `parsePolarPmdData handles delta-compressed ACC with zero deltas`() {
        val bytes = ByteArray(18).apply {
            this[0] = 0x02
            this[9] = 0x80.toByte()
            this[10] = 50; this[11] = 0
            this[12] = 50; this[13] = 0
            this[14] = 50; this[15] = 0
            this[16] = 0 // deltaSizeBits = 0
            this[17] = 0 // sampleCount = 0
        }

        val result = parsePolarPmdData(bytes)
        assertNotNull(result)
        val acc = assertIs<PolarPmdMeasurement.Acc>(result)
        assertEquals(1, acc.samples.size)
        assertEquals(PolarAccSample(50, 50, 50), acc.samples[0])
    }

    @Test
    fun `parsePolarPmdData parses multi-block delta-compressed ACC samples across block boundaries`() {
        // Type 0x02 (ACC), Timestamp: 3000L, FrameType: 0x81 (delta-compressed)
        // Reference sample: X=500, Y=-100, Z=1000
        // Block 1 (offset 16): deltaSizeBits = 8, sampleCount = 2 -> 6 delta bytes (18..23)
        //   Sample 1 deltas: dX=10, dY=-5, dZ=20 -> (510, -105, 1020)
        //   Sample 2 deltas: dX=-4, dY=15, dZ=-10 -> (506, -90, 1010)
        // Block 2 (offset 24): deltaSizeBits = 4, sampleCount = 2 -> 24 bits = 3 delta bytes (26..28)
        //   Sample 3 deltas (4-bit signed): dX=3 (0x3), dY=-2 (0xE), dZ=1 (0x1) -> (509, -92, 1011)
        //   Sample 4 deltas (4-bit signed): dX=-3 (0xD), dY=2 (0x2), dZ=-1 (0xF) -> (506, -90, 1010)
        //   Packed 4-bit nibbles (LSB first):
        //     Byte 26: low=dX3(0x3), high=dY3(0xE) -> 0xE3
        //     Byte 27: low=dZ3(0x1), high=dX4(0xD) -> 0xD1
        //     Byte 28: low=dY4(0x2), high=dZ4(0xF) -> 0xF2
        val bytes = ByteArray(29).apply {
            this[0] = 0x02
            this[1] = 0xB8.toByte()
            this[2] = 0x0B // 3000L = 0x0BB8
            this[9] = 0x81.toByte()

            // Ref X = 500 (0x01F4)
            this[10] = 0xF4.toByte()
            this[11] = 0x01
            // Ref Y = -100 (0xFF9C)
            this[12] = 0x9C.toByte()
            this[13] = 0xFF.toByte()
            // Ref Z = 1000 (0x03E8)
            this[14] = 0xE8.toByte()
            this[15] = 0x03

            // Block 1 header
            this[16] = 8 // deltaSizeBits = 8
            this[17] = 2 // sampleCount = 2
            // Block 1 sample 1: (10, -5, 20)
            this[18] = 10
            this[19] = (-5).toByte()
            this[20] = 20
            // Block 1 sample 2: (-4, 15, -10)
            this[21] = (-4).toByte()
            this[22] = 15
            this[23] = (-10).toByte()

            // Block 2 header
            this[24] = 4 // deltaSizeBits = 4
            this[25] = 2 // sampleCount = 2
            // Block 2 packed 4-bit deltas
            this[26] = 0xE3.toByte()
            this[27] = 0xD1.toByte()
            this[28] = 0xF2.toByte()
        }

        val result = parsePolarPmdData(bytes)
        assertNotNull(result)
        val acc = assertIs<PolarPmdMeasurement.Acc>(result)
        assertEquals(3000L, acc.sensorTimestampNs)
        assertEquals(5, acc.samples.size)
        assertEquals(PolarAccSample(500, -100, 1000), acc.samples[0])
        assertEquals(PolarAccSample(510, -105, 1020), acc.samples[1])
        assertEquals(PolarAccSample(506, -90, 1010), acc.samples[2])
        assertEquals(PolarAccSample(509, -92, 1011), acc.samples[3])
        assertEquals(PolarAccSample(506, -90, 1010), acc.samples[4])
    }
}
