package com.kylecorry.trail_sense.navigation.ui

import android.view.View
import com.kylecorry.trail_sense.astronomy.domain.AstronomyService
import com.kylecorry.trail_sense.navigation.domain.Beacon
import com.kylecorry.trail_sense.navigation.domain.LocationMath
import com.kylecorry.trail_sense.navigation.domain.NavigationService
import com.kylecorry.trail_sense.navigation.domain.NavigationVector
import com.kylecorry.trail_sense.navigation.domain.compass.DeclinationCalculator
import com.kylecorry.trail_sense.navigation.infrastructure.BeaconDB
import com.kylecorry.trail_sense.shared.Coordinate
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.shared.math.deltaAngle
import com.kylecorry.trail_sense.shared.sensors.DeviceOrientation
import com.kylecorry.trail_sense.shared.sensors.IAltimeter
import com.kylecorry.trail_sense.shared.sensors.ICompass
import com.kylecorry.trail_sense.shared.sensors.IGPS
import kotlin.math.abs
import kotlin.math.roundToInt

class NavigationViewModel(
    private val compass: ICompass,
    private val gps: IGPS,
    private val altimeter: IAltimeter,
    private val orientation: DeviceOrientation,
    prefs: UserPreferences,
    beaconDB: BeaconDB
) {

    private val declinationCalculator = DeclinationCalculator()
    private val useTrueNorth = prefs.navigation.useTrueNorth
    private val distanceUnits = prefs.distanceUnits
    private val prefShowLinearCompass = prefs.navigation.showLinearCompass
    private val beacons = beaconDB.beacons
    private val showNearbyBeacons = prefs.navigation.showMultipleBeacons
    private val visibleBeacons = prefs.navigation.numberOfVisibleBeacons
    private val showSunAndMoon = prefs.astronomy.showOnCompass
    private val astronomyService = AstronomyService()

    val rulerScale = prefs.navigation.rulerScale

    val azimuth: Float
        get() {
            if (useTrueNorth) {
                val declination = declinationCalculator.calculate(gps.location, gps.altitude)
                compass.declination = declination
            } else {
                compass.declination = 0f
            }
            return compass.bearing.value
        }

    val azimuthTxt: String
        get() = "${(azimuth.roundToInt() % 360).toString().padStart(3, ' ')}°"

    val azimuthDirection: String
        get() {
            if (useTrueNorth) {
                val declination = declinationCalculator.calculate(gps.location, gps.altitude)
                compass.declination = declination
            } else {
                compass.declination = 0f
            }
            return compass.bearing.direction.symbol
        }

    val location: String
        get() = gps.location.getFormattedString()

    val altitude: String
        get() {
            return if (distanceUnits == UserPreferences.DistanceUnits.Meters) {
                "${altimeter.altitude.roundToInt()} m"
            } else {
                "${LocationMath.convertToBaseUnit(altimeter.altitude, distanceUnits)
                    .roundToInt()} ft"
            }
        }

    val showLinearCompass: Boolean
        get() = prefShowLinearCompass && orientation.orientation == DeviceOrientation.Orientation.Portrait

    var beacon: Beacon? = null

    val destination: String
        get() {
            beacon?.apply {
                val vector = NavigationService().navigate(gps.location, this.coordinate)
                val declination = declinationCalculator.calculate(gps.location, gps.altitude)
                val bearing =
                    if (!useTrueNorth) vector.direction.withDeclination(-declination).value else vector.direction.value
                return "${this.name}    (${bearing.roundToInt()}°)\n${LocationMath.distanceToReadableString(
                    vector.distance,
                    distanceUnits
                )}"
            }
            return ""
        }

    private val destinationBearing: Float?
        get() {
            beacon?.apply {
                val vector = NavigationService().navigate(gps.location, this.coordinate)
                val declination = declinationCalculator.calculate(gps.location, gps.altitude)
                return if (!useTrueNorth) vector.direction.withDeclination(-declination).value else vector.direction.value
            }
            return null
        }

    val showDestination: Boolean
        get() = beacon != null

    val shareableLocation: Coordinate
        get() = gps.location

    private fun isFacingBeacon(beacon: Beacon): Boolean {
        val vector = NavigationService().navigate(gps.location, beacon.coordinate)
        val declination = declinationCalculator.calculate(gps.location, gps.altitude)
        val direction =
            if (!useTrueNorth) vector.direction.withDeclination(-declination).value else vector.direction.value
        return abs(deltaAngle(direction, azimuth)) < 20
    }

    val nearestBeacons: List<Float>
        get() {
            if (showDestination) {
                return listOf(sunBearing, moonBearing, destinationBearing ?: 0f)
            }

            if (!showNearbyBeacons) {
                return listOf(sunBearing, moonBearing)
            }

            val declination = declinationCalculator.calculate(gps.location, gps.altitude)

            val sunAndMoon = listOf(sunBearing, moonBearing)

            val beacons = _nearestVisibleBeacons
                .map {
                    val direction =
                        if (!useTrueNorth) it.second.direction.withDeclination(-declination).value else it.second.direction.value
                    direction
                }.toList()

            return sunAndMoon + beacons
        }

    private val _nearestVisibleBeacons: List<Pair<Beacon, NavigationVector>>
        get() {
            val navigationService = NavigationService()
            return beacons.asSequence()
                .filter { it.visible }
                .map {
                    Pair(it, navigationService.navigate(gps.location, it.coordinate))
                }
                .sortedBy { it.second.distance }
                .take(visibleBeacons)
                .toList()
        }

    val navigation: String
        get() {
            if (showDestination) {
                return destination
            }

            if (!showNearbyBeacons) {
                return ""
            }

            val declination = declinationCalculator.calculate(gps.location, gps.altitude)

            val vectors = _nearestVisibleBeacons

            val nearestBeacon = vectors.minBy {
                val direction =
                    if (!useTrueNorth) it.second.direction.withDeclination(-declination).value else it.second.direction.value
                abs(deltaAngle(direction, azimuth))
            }
            nearestBeacon?.apply {
                if (!isFacingBeacon(this.first)) return ""
                val direction =
                    if (!useTrueNorth) this.second.direction.withDeclination(-declination).value else this.second.direction.value
                return "${this.first.name}    (${direction.roundToInt() % 360}°)\n${LocationMath.distanceToReadableString(
                    this.second.distance,
                    distanceUnits
                )}"
            }
            return ""
        }

    val moonBeaconVisibility: Int
        get() {
            if (!showSunAndMoon || !astronomyService.isMoonUp(gps.location)){
                return View.INVISIBLE
            }

            return View.VISIBLE
        }

    val sunBeaconVisibility: Int
        get() {
            if (!showSunAndMoon || !astronomyService.isSunUp(gps.location)){
                return View.INVISIBLE
            }

            return View.VISIBLE
        }

    private val sunBearing: Float
        get() {
            val declination = if (!useTrueNorth) declinationCalculator.calculate(gps.location, gps.altitude) else 0f
            return astronomyService.getSunAzimuth(gps.location).withDeclination(-declination).value
        }

    private val moonBearing: Float
        get() {
            val declination = if (!useTrueNorth) declinationCalculator.calculate(gps.location, gps.altitude) else 0f
            return astronomyService.getMoonAzimuth(gps.location).withDeclination(-declination).value
        }

}