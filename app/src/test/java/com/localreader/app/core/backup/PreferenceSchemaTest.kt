package com.localreader.app.core.backup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreferenceSchemaTest {
    private fun doc(file: String,key: String,type: String,value: Any) = JSONObject().put(file,JSONObject().put(key,JSONObject().put("t",type).put("v",value)))
    private fun valid(root: JSONObject) = runCatching { PreferenceSchema.validate(root) }.isSuccess

    @Test fun wellFormedKnownValuesAreAccepted() {
        assertTrue(valid(doc("ui_prefs","brightness","f",0.5)))
        assertTrue(valid(doc("ui_prefs","shelf_sort","s","TITLE")))
        // Packages from older versions carry the removed "imported" order and a format filter; they remain acceptable.
        assertTrue(valid(doc("ui_prefs","shelf_sort","s","IMPORTED")))
        assertTrue(valid(doc("ui_prefs","shelf_filter","s","EPUB")))
        assertFalse(valid(doc("ui_prefs","shelf_filter","s","PDF")))
        assertTrue(valid(doc("ui_prefs","shelf_category","l",-1L)))
        assertTrue(valid(doc("ui","tab","i",2)))
        assertTrue(valid(JSONObject()))
    }
    @Test fun aWrongTypeForAKnownKeyIsRefused() {
        assertFalse(valid(doc("ui_prefs","brightness","s","wrong-type")))
        assertFalse(valid(doc("ui_prefs","shelf_category","s","3")))
        assertFalse(valid(doc("ui_prefs","night_mode","i",1)))
        assertFalse(valid(doc("ui","tab","s","1")))
    }
    @Test fun outOfRangeAndUnknownEnumValuesAreRefused() {
        assertFalse(valid(doc("ui_prefs","brightness","f",1.5)))
        assertFalse(valid(doc("ui_prefs","brightness","f",-0.1)))
        assertFalse(valid(doc("ui_prefs","shelf_sort","s","NEWEST")))
        assertFalse(valid(doc("ui_prefs","shelf_category","l",-5L)))
        assertFalse(valid(doc("ui","tab","i",9)))
        assertFalse(valid(doc("ui_prefs","page_turn","s","")))
    }
    @Test fun unknownTypeLettersAndMalformedEntriesAreRefused() {
        assertFalse(valid(doc("ui_prefs","brightness","x",0.5)))
        assertFalse(valid(JSONObject().put("ui_prefs",JSONObject().put("brightness",JSONObject().put("t","f")))))
    }
    @Test fun onlyValidKnownKeysAreEverApplied() {
        val root = JSONObject().put("ui_prefs",JSONObject()
            .put("brightness",JSONObject().put("t","f").put("v",0.25))
            .put("shelf_sort",JSONObject().put("t","s").put("v","NOPE"))
            .put("injected",JSONObject().put("t","s").put("v","x")))
        val accepted = PreferenceSchema.accepted(root,"ui_prefs")
        assertEquals(setOf("brightness"),accepted.keys);assertEquals(0.25f,accepted["brightness"])
        assertTrue(PreferenceSchema.accepted(root,"ui").isEmpty())
    }
}
