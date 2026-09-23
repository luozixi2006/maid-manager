package com.miniichat.companion

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class DeviceContextTextTest {
    private val now=1_700_000_000_000L
    private val minute=60_000L
    private val hour=60*minute
    private val stamp=Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")

    @Test fun timeHelpersUseFixedInputNotWallClock() {
        assertEquals("无记录",DeviceContextText.time(0))
        assertTrue(DeviceContextText.time(now).matches(stamp))
        assertTrue(DeviceContextText.fresh(now-1,now))
        assertTrue(DeviceContextText.fresh(now,now))
        assertFalse(DeviceContextText.fresh(now+1,now))
        assertFalse(DeviceContextText.fresh(now-DeviceContextText.LIVE_AGE_MS-1,now))
    }

    @Test fun freshWatchPulseWithMeasurementAndBodyTimesIsReported() {
        val text=DeviceContextText.watch(JSONObject().put("at",now-30_000).put("heartRate",72)
            .put("heartRateAt",now-minute).put("worn",true).put("activity","walking"),now)
        assertTrue(text.contains("近期记录（非持续实时监测）"))
        assertTrue(text.contains("最近测得心率：72.0 次/分"))
        assertTrue(text.contains("测量时间：${DeviceContextText.time(now-minute)}。"))
        assertTrue(text.contains("活动状态：可能在走路"))
        assertTrue(text.contains("佩戴状态：佩戴中"))
        assertFalse(text.contains("当前心率：未知"))
    }

    @Test fun phoneContextWithoutWatchDoesNotInventPulseOrSteps() {
        val phone=JSONObject().put("source","phone").put("at",now-minute).put("marker","phone-env-9f2")
        val text=DeviceContextText.render(listOf(phone),emptyList(),now)
        assertTrue(text.contains("手表身体数据：手机尚未收到采集记录。"))
        assertTrue(text.contains("phone-env-9f2"))
        assertFalse(text.contains("次/分"))
        assertFalse(text.contains("记录步数"))
        assertFalse(text.contains("当前心率："))
        assertTrue(text.contains("暂无事件记录，不代表没有活动。"))
    }

    @Test fun expiredPulseNumberIsNotPresentedAsCurrent() {
        val body=JSONObject().put("at",now-minute).put("heartRate",133).put("heartRateAt",now-20*minute).put("worn",true)
        val text=DeviceContextText.watch(body,now)
        assertTrue(text.contains("当前心率：未知"))
        assertTrue(text.contains("上次有效测量时间：${DeviceContextText.time(now-20*minute)}。"))
        assertFalse(text.contains("133"))
        assertFalse(text.contains("次/分"))
    }

    @Test fun futureTimestampRejectsCurrentStatus() {
        val text=DeviceContextText.watch(JSONObject().put("at",now+minute).put("heartRate",149)
            .put("heartRateAt",now+minute).put("worn",true),now)
        assertTrue(text.contains("记录已过期或设备时钟不一致，不能当作当前状态"))
        assertTrue(text.contains("当前心率：未知"))
        assertFalse(text.contains("149"))
        assertFalse(text.contains("次/分"))
    }

    @Test fun missingPulseAndStepCounterAreClearlyUnknownNotZero() {
        val text=DeviceContextText.watch(JSONObject().put("at",now-minute),now)
        assertTrue(text.contains("当前心率：未知"))
        assertTrue(text.contains("步数：尚未收到计步器读数。"))
        assertFalse(text.contains("记录步数"))
        assertTrue(text.contains("活动状态：未知"))
        assertTrue(text.contains("佩戴状态：未知"))
    }

    @Test fun recordedCounterAndZeroObservedStepsAreShownAsRecorded() {
        val zero=DeviceContextText.watch(JSONObject().put("at",now-minute).put("counter",1).put("dailySteps",0).put("day","今天"),now)
        assertTrue(zero.contains("记录步数：0；仅为 今天 开始感知后记录到的步数，不是全天总步数。"))
        assertFalse(zero.contains("尚未收到计步器读数"))
        val counted=DeviceContextText.watch(JSONObject().put("at",now-minute).put("counter",7).put("dailySteps",1284).put("day","周三"),now)
        assertTrue(counted.contains("记录步数：1284；仅为 周三 开始感知后记录到的步数，不是全天总步数。"))
        val nullCounter=DeviceContextText.watch(JSONObject().put("at",now-minute).put("counter",JSONObject.NULL).put("dailySteps",99),now)
        assertTrue(nullCounter.contains("步数：尚未收到计步器读数。"))
    }

    @Test fun staleWatchRecordIsMarkedAndFreshPhoneContextStillShown() {
        val stale=JSONObject().put("source","watch").put("at",now-30*minute).put("heartRate",151)
            .put("heartRateAt",now-30*minute).put("worn",true)
        val freshPhone=JSONObject().put("source","phone").put("at",now-90_000).put("marker","phone-fresh-4c1")
        val stalePhone=JSONObject().put("source","phone").put("at",now-30*minute).put("marker","phone-stale-7d3")
        val text=DeviceContextText.render(listOf(stale,freshPhone,stalePhone),emptyList(),now)
        assertTrue(text.contains("记录已过期或设备时钟不一致，不能当作当前状态"))
        assertTrue(text.contains("phone-fresh-4c1"))
        assertFalse(text.contains("phone-stale-7d3"))
        assertFalse(text.contains("151"))
        assertFalse(text.contains("次/分"))
    }

    @Test fun eventsOutsideWindowAreExcludedAndNewestThirtyAreSorted() {
        val events=(1..35).map{i->JSONObject().put("at",now-(36-i)*minute).put("summary","evt-%02d".format(i)).put("confidence","high")}+
            listOf(JSONObject().put("at",now-24*hour-1).put("summary","too-old-ever"),
                JSONObject().put("at",now+minute).put("summary","future-ever"))
        val text=DeviceContextText.render(emptyList(),events,now)
        assertFalse(text.contains("too-old-ever"))
        assertFalse(text.contains("future-ever"))
        for(i in 1..5) assertFalse("oldest dropped evt-$i",text.contains("evt-%02d".format(i)))
        assertTrue(text.contains("evt-06"))
        assertTrue(text.contains("evt-35"))
        assertEquals(30,text.lines().count{it.contains("evt-")})
        assertTrue(text.indexOf("evt-06")<text.indexOf("evt-07"))
        assertTrue(text.indexOf("evt-34")<text.indexOf("evt-35"))
        assertFalse(text.contains("暂无事件记录"))
    }

    @Test fun eventWindowBoundaryIsInclusiveAtNowButNotFuture() {
        val events=listOf(JSONObject().put("at",now).put("summary","boundary-now"),
            JSONObject().put("at",now-24*hour).put("summary","boundary-24h"),
            JSONObject().put("at",now-24*hour-1).put("summary","beyond-24h"),
            JSONObject().put("at",now+1).put("summary","just-future"))
        val text=DeviceContextText.render(emptyList(),events,now)
        assertTrue(text.contains("boundary-now"))
        assertTrue(text.contains("boundary-24h"))
        assertFalse(text.contains("beyond-24h"))
        assertFalse(text.contains("just-future"))
    }

    @Test fun nonWatchAndNonPhoneSourcesAreIgnoredAndOnlyNewestIsUsed() {
        val text=DeviceContextText.render(listOf(
            JSONObject().put("source","watch").put("at",now-5*minute).put("counter",1).put("dailySteps",10).put("day","older-watch-8a2"),
            JSONObject().put("source","watch").put("at",now-minute).put("counter",2).put("dailySteps",20).put("day","newer-watch-3b7"),
            JSONObject().put("source","phone").put("at",now-5*minute).put("marker","old-phone-1e9"),
            JSONObject().put("source","phone").put("at",now-minute).put("marker","new-phone-5f4"),
            JSONObject().put("source","tablet").put("at",now-minute).put("marker","tablet-ignore-me"),
            JSONObject().put("source","watch2").put("at",now-minute).put("marker","watch2-ignore-me")),emptyList(),now)
        assertTrue(text.contains("newer-watch-3b7"))
        assertFalse(text.contains("older-watch-8a2"))
        assertTrue(text.contains("new-phone-5f4"))
        assertFalse(text.contains("old-phone-1e9"))
        assertFalse(text.contains("tablet-ignore-me"))
        assertFalse(text.contains("watch2-ignore-me"))
    }

    @Test fun veryLongRecordsStayBoundedAndWatchSummarySurvivesManyEvents() {
        val watch=JSONObject().put("source","watch").put("at",now-minute).put("heartRate",72)
            .put("heartRateAt",now-minute).put("worn",true)
        val phone=JSONObject().put("source","phone").put("at",now-30_000)
            .put("blob","p".repeat(4000)+"TAIL-PHONE-MARK")
        val events=(1..40).map{i->JSONObject().put("at",now-i*minute)
            .put("summary","e%02d".format(i)+"s".repeat(4000)+"TAIL-EVENT-MARK").put("confidence","c".repeat(200))}
        val text=DeviceContextText.render(listOf(watch,phone),events,now)
        assertTrue("watch summary missing",text.contains("手表采集时间：")&&text.contains("最近测得心率：72.0 次/分"))
        assertTrue(text.contains("手机环境记录："))
        assertFalse(text.contains("TAIL-PHONE-MARK"))
        assertFalse(text.contains("TAIL-EVENT-MARK"))
        assertEquals(30,text.lines().count{it.contains("[c")})
        assertTrue("output too small to prove bounding",text.length>5000)
        assertTrue("output must stay bounded, was ${text.length}",text.length<14000)
    }

    @Test fun emptyInputsProduceExplicitPlaceholdersWithoutInventedReadings() {
        val text=DeviceContextText.render(emptyList(),emptyList(),now)
        assertTrue(text.contains("当前手机时间：${DeviceContextText.time(now)}"))
        assertTrue(text.contains("手机尚未收到采集记录。"))
        assertTrue(text.contains("暂无事件记录，不代表没有活动。"))
        assertFalse(text.contains("次/分"))
        assertFalse(text.contains("记录步数"))
    }

    @Test fun acquisitionFailureReasonsRemainDistinct() {
        val reasons=mapOf("permission_missing" to "身体传感器权限未允许",
            "unavailable" to "设备未向此应用开放", "warming_up" to "正在等待",
            "no_reading" to "没有收到心率读数", "registration_failed" to "没有允许启动",
            "unreliable" to "不可靠读数", "invalid" to "超出有效范围")
        reasons.forEach{(status,expected)->
            val text=DeviceContextText.watch(JSONObject().put("at",now).put("heart_rate_status",status),now)
            assertTrue(text.contains(expected));assertTrue(text.contains("当前心率：未知"))
        }
    }

    @Test fun offBodyReadingIsNotExposedAsCurrentPulse() {
        val text=DeviceContextText.watch(JSONObject().put("at",now).put("heartRateAt",now)
            .put("heartRate",72).put("worn",false).put("heart_rate_status","measured"),now)
        assertTrue(text.contains("佩戴传感器报告未佩戴"))
        assertFalse(text.contains("72.0 次/分"))
    }
}
