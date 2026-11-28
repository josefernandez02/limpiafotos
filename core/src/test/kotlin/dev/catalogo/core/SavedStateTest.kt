package dev.catalogo.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phone keeps its whole catalogue in one JSON file. Its field names are a contract with the data already
 * saved: renaming a Kotlin property must keep the stored name (@SerialName), or the app would lose it.
 */
class SavedStateTest {
    // the same settings as the app's StateStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val saved = """{
        "deviceId":"p",
        "parent":{
            "self":{"id":"p","name":"Samsung","prefix":"SH","secret":"","account":"tienda","vintedId":"111"},
            "children":[{"id":"c1","name":"Poco","prefix":"SA","account":"otra"}],
            "configVersion":4,
            "session":{"number":3,"deviceId":"c1","startedAt":7},
            "articles":[{"number":3,"createdAt":1,"desk":{"ref":"SH0003","status":"borrador"},
                "vinted":{"p":{"id":"900","status":"active","checkedAt":9}}}],
            "sales":[{"number":2,"deviceId":"c1","price":1000,"cost":null,"lotId":null,"soldAt":5}],
            "retired":[{"ref":"SH0001","status":"publicado"}]
        }
    }"""

    @Test
    fun `a catalogue saved by earlier versions loads whole`() {
        val state = json.decodeFromString(AppState.serializer(), saved)
        val catalog = state.catalog!!
        assertEquals("p", state.deviceId)
        assertEquals("tienda", catalog.mainAccount.vintedName)
        assertEquals("otra", catalog.otherAccounts.single().vintedName)
        assertEquals("c1", catalog.session!!.accountId)
        assertEquals("SH0003", catalog.articles.single().pcTask!!.ref)
        assertEquals("900", catalog.articles.single().vinted.getValue("p").id)
        assertEquals("c1", catalog.sales.single().accountId)
        assertEquals("SH0001", catalog.retired.single().ref)
    }

    @Test
    fun `it is written back with the same field names`() {
        val written = json.encodeToString(AppState.serializer(), json.decodeFromString(AppState.serializer(), saved))
        val parent = json.parseToJsonElement(written).jsonObject.getValue("parent").jsonObject
        assertEquals("tienda", parent.getValue("self").jsonObject.getValue("account").jsonPrimitive.content)
        assertEquals(1, parent.getValue("children").jsonArray.size)
        assertEquals("c1", parent.getValue("session").jsonObject.getValue("deviceId").jsonPrimitive.content)
        assertEquals("c1", parent.getValue("sales").jsonArray[0].jsonObject.getValue("deviceId").jsonPrimitive.content)
        assertEquals("SH0003", parent.getValue("articles").jsonArray[0].jsonObject.getValue("desk").jsonObject.getValue("ref").jsonPrimitive.content)
    }
}
