package com.kylecorry.trail_sense.weather.domain.sealevel

import com.kylecorry.trail_sense.weather.domain.PressureAltitudeReading
import com.kylecorry.trail_sense.weather.domain.PressureReading

internal class NullPressureConverter :
    ISeaLevelPressureConverter {
    override fun convert(readings: List<PressureAltitudeReading>): List<PressureReading> {
        return readings.map {
            PressureReading(
                it.time,
                it.pressure
            )
        }
    }

}