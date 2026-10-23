package me.bili.unrestrict.data.model

import kotlinx.serialization.json.*
import me.bili.unrestrict.data.db.CommentFraudRecord
import org.junit.Assert.*
import org.junit.Test

class CommentBackupTest {
    @Test fun `shared merge cases and repeat import`() {
        val cases = Json.parseToJsonElement(javaClass.getResource("/comment-backup-cases.json")!!.readText()).jsonArray
        for (case in cases) {
            val obj = case.jsonObject
            val old = CommentBackup.decode(obj.getValue("local").toString())
            val incoming = CommentBackup.decode(obj.getValue("incoming").toString())
            val plan = CommentBackup.merge(old, incoming)
            val result = plan.records.single()
            assertEquals(obj.getValue("status").jsonPrimitive.content, result.status)
            assertEquals(obj.getValue("time").jsonPrimitive.long, result.timestamp)
            assertEquals(obj.getValue("changes").jsonPrimitive.int, plan.changes.size)
            assertEquals(obj.getValue("kept").jsonPrimitive.int, plan.kept)
            assertEquals(plan.records, CommentBackup.merge(plan.records, incoming).records)
            assertEquals(plan.records, CommentBackup.decode(CommentBackup.encode(plan.records)))
        }
    }

    @Test fun `export remains readable by the old module decoder`() {
        // The record schema is unchanged from v1.0.7; exercise its exact decode path.
        val rows = listOf(CommentFraudRecord(rpid = Long.MAX_VALUE, oid = Long.MAX_VALUE - 1,
            root = Long.MAX_VALUE - 2, parent = 7, uid = 8, status = "NORMAL", timestamp = 123))
        val text = CommentBackup.encode(rows)
        assertTrue(Json.parseToJsonElement(text) is JsonArray)
        assertFalse(Json.parseToJsonElement(text).jsonArray[0].jsonObject.getValue("rpid").jsonPrimitive.isString)
        val legacy = Json { ignoreUnknownKeys = true; isLenient = true }
        assertEquals(rows, legacy.decodeFromString<List<CommentFraudRecord>>(text))
        assertEquals(rows, CommentBackup.decode(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CommentFraudRecord.serializer()), rows)))
    }

    @Test fun `invalid input and conflicting identity abort merge`() {
        val old = CommentBackup.decode("""[{"rpid":2,"oid":1}]""")
        for (text in listOf(
            """[{"rpid":"9223372036854775808","oid":1}]""",
            """[{"rpid":2,"oid":1},{"rpid":2,"oid":1}]""",
            """[{"rpid":2,"oid":1,"status":"typo"}]""",
            """[{"rpid":2,"oid":1,"timestamp":9007199254740992}]""",
            """[{"rpid":2,"oid":1,"message":42}]""")) {
            assertThrows(IllegalArgumentException::class.java) { CommentBackup.decode(text) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommentBackup.merge(old, CommentBackup.decode("""[{"rpid":2,"oid":99}]"""))
        }
        assertEquals(1L, old.single().oid)
    }

    @Test fun `metadata cannot be erased and initial valid status remains`() {
        val old = CommentBackup.decode("""[{"rpid":2,"oid":1,"message":"original","post_time":100,"initial_status":"NORMAL"}]""")
        val next = CommentBackup.decode("""[{"rpid":2,"oid":1,"message":"","post_time":0,"status":"DELETED"}]""")
        val result = CommentBackup.merge(old, next).records.single()
        assertEquals("original", result.message)
        assertEquals(100L, result.post_time)
        assertEquals("NORMAL", result.initial_status)
        assertEquals("DELETED", result.status)
    }
}
