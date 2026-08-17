package com.fabrice.plansms.logic

import com.fabrice.plansms.data.ScheduledMessage
import com.fabrice.plansms.data.Template
import org.json.JSONArray
import org.json.JSONObject

/** Export/Import JSON des programmations et templates (100% local). */
object JsonBackup {

    fun export(messages: List<ScheduledMessage>, templates: List<Template>): String {
        val root = JSONObject()
        val msgs = JSONArray()
        for (m in messages) {
            msgs.put(JSONObject()
                .put("phone", m.phone)
                .put("text", m.text)
                .put("targetDate", m.targetDate)
                .put("hourOfDay", m.hourOfDay)
                .put("minuteOfHour", m.minuteOfHour)
                .put("repeatRule", m.repeatRule.name)
                .put("weekDays", m.weekDays)
                .put("noSendStart", m.noSendStart)
                .put("noSendEnd", m.noSendEnd)
                .put("status", m.status.name)
                .put("channel", m.channel.name)
                .put("groupId", m.groupId))
        }
        val tmpls = JSONArray()
        for (t in templates) {
            tmpls.put(JSONObject().put("name", t.name).put("body", t.body))
        }
        root.put("version", 1)
        root.put("messages", msgs)
        root.put("templates", tmpls)
        return root.toString(2)
    }

    data class Backup(val messages: List<ScheduledMessage>, val templates: List<Template>)

    fun parse(json: String): Backup? = try {
        val root = JSONObject(json)
        val msgs = mutableListOf<ScheduledMessage>()
        val arr = root.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            msgs.add(
                ScheduledMessage(
                    phone = o.optString("phone"),
                    text = o.optString("text"),
                    targetDate = o.optLong("targetDate"),
                    hourOfDay = o.optInt("hourOfDay"),
                    minuteOfHour = o.optInt("minuteOfHour"),
                    repeatRule = runCatching { com.fabrice.plansms.data.RepeatRule.valueOf(o.optString("repeatRule")) }
                        .getOrDefault(com.fabrice.plansms.data.RepeatRule.ONCE),
                    weekDays = o.optInt("weekDays"),
                    noSendStart = o.optInt("noSendStart", -1),
                    noSendEnd = o.optInt("noSendEnd", -1),
                    status = runCatching { com.fabrice.plansms.data.SmsStatus.valueOf(o.optString("status")) }
                        .getOrDefault(com.fabrice.plansms.data.SmsStatus.SCHEDULED),
                    channel = runCatching { com.fabrice.plansms.data.Channel.valueOf(o.optString("channel")) }
                        .getOrDefault(com.fabrice.plansms.data.Channel.SMS),
                    groupId = o.optLong("groupId", 0L)
                )
            )
        }
        val tmpls = mutableListOf<Template>()
        val tarr = root.optJSONArray("templates") ?: JSONArray()
        for (i in 0 until tarr.length()) {
            val o = tarr.getJSONObject(i)
            tmpls.add(Template(name = o.optString("name"), body = o.optString("body")))
        }
        Backup(msgs, tmpls)
    } catch (e: Exception) {
        null
    }
}
