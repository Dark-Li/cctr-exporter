package top.kagg886.cctr.api.modules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestionTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testOptionsDeserializationArray() {
        val jsonString = """
            {
                "questionid": "1",
                "questiontypename": "Type1",
                "subjecthtml_svg": "base64subject",
                "answerhtml_svg": "base64answer",
                "answer": "Answer",
                "options": [
                    { "istrue": 1, "questionoptionhtml_svg": "base64opt1" },
                    { "istrue": 0, "questionoptionhtml_svg": "base64opt2" }
                ]
            }
        """.trimIndent()
        
        val question = json.decodeFromString<Question>(jsonString)
        
        assertTrue(question.hasOptions)
        assertEquals(2, question.options.size)
        assertTrue(question.options[0].isTrue)
        assertEquals("base64opt1", question.options[0].questionoptionhtml_svg)
    }

    @Test
    fun testOptionsDeserializationObject() {
        val jsonString = """
            {
                "questionid": "2",
                "questiontypename": "Type2",
                "subjecthtml_svg": "base64subject",
                "answerhtml_svg": "base64answer",
                "answer": "Answer",
                "options": {
                    "0": { "istrue": 1, "questionoptionhtml_svg": "base64opt1" },
                    "1": { "istrue": 0, "questionoptionhtml_svg": "base64opt2" }
                }
            }
        """.trimIndent()

        val question = json.decodeFromString<Question>(jsonString)

        assertTrue(question.hasOptions)
        assertEquals(2, question.options.size)
        // Note: order might not be guaranteed if map iteration is not deterministic, 
        // but for values retrieval it should contain both.
        // In this implementation map { decode } preserves order of values collection.
        // For standard JSON implementations keys are usually iterated in definition order or alphanumeric.
        val opt1 = question.options.find { it.questionoptionhtml_svg == "base64opt1" }
        val opt2 = question.options.find { it.questionoptionhtml_svg == "base64opt2" }
        
        assertTrue(opt1 != null)
        assertTrue(opt2 != null)
        assertTrue(opt1!!.isTrue)
        assertTrue(!opt2!!.isTrue)
    }

    @Test
    fun testOptionsDeserializationObjectWithGarbage() {
        // PHP backend sometimes returns non-array (map) with garbage keys or primitives
        val jsonString = """
            {
                "questionid": "3",
                "questiontypename": "Type3",
                "subjecthtml_svg": "base64subject",
                "answerhtml_svg": "base64answer",
                "answer": "Answer",
                "options": {
                    "0": { "istrue": 1, "questionoptionhtml_svg": "base64opt1" },
                    "1": { "istrue": 0, "questionoptionhtml_svg": "base64opt2" },
                    "length": 2,
                    "info": "some info"
                }
            }
        """.trimIndent()

        val question = json.decodeFromString<Question>(jsonString)

        assertTrue(question.hasOptions)
        assertEquals(2, question.options.size)
        val opt1 = question.options.find { it.questionoptionhtml_svg == "base64opt1" }
        assertTrue(opt1 != null)
    }
}