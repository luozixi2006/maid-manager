package com.miniichat.watch

import android.Manifest
import android.app.Application
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class SensingPermissionTest {
    private val dispatcher=StandardTestDispatcher()
    private val app get()=RuntimeEnvironment.getApplication()
    private lateinit var service:SensingService
    private lateinit var heart:Sensor
    private lateinit var step:Sensor
    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        WatchRuntime.sensing.value=false
        val sensors=shadowOf(app.getSystemService(SensorManager::class.java))
        heart=ShadowSensor.newInstance(Sensor.TYPE_HEART_RATE);sensors.addSensor(heart)
        step=ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER);sensors.addSensor(step)
        service=Robolectric.buildService(SensingService::class.java).create().get()
    }
    @After fun finish() {service.onDestroy();dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun registered(sensor:Sensor)=shadowOf(app.getSystemService(SensorManager::class.java)).hasListener(service,sensor)
    @Test fun bodyPermissionGrantedAfterServiceStartStartsSamplingWithoutRestart() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        shadowOf(app).denyPermissions(Manifest.permission.BODY_SENSORS)
        service.onStartCommand(Intent(),0,1);dispatcher.scheduler.runCurrent()
        assertTrue(WatchRuntime.sensing.value)
        assertTrue(registered(step));assertFalse(registered(heart))
        shadowOf(app).grantPermissions(Manifest.permission.BODY_SENSORS)
        dispatcher.scheduler.advanceTimeBy(5001);dispatcher.scheduler.runCurrent()
        assertTrue("A late grant must not require restarting sensing",registered(heart))
    }
    @Test fun heartSensorStillListeningAfterOldTenSecondCutoff() {
        shadowOf(app).grantPermissions(Manifest.permission.BODY_SENSORS,Manifest.permission.ACTIVITY_RECOGNITION)
        service.onStartCommand(Intent(),0,1);dispatcher.scheduler.runCurrent()
        assertTrue(registered(heart))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        dispatcher.scheduler.advanceTimeBy(20_001);dispatcher.scheduler.runCurrent()
        assertTrue("Allow an optical sensor time to warm up",registered(heart))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        dispatcher.scheduler.advanceTimeBy(11_000);dispatcher.scheduler.runCurrent()
        assertFalse("The sensor must not stay enabled indefinitely",registered(heart))
    }
}
