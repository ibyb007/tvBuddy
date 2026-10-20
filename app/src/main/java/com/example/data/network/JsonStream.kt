package com.example.data.network

import android.util.JsonReader
import android.util.JsonToken

/**
 * Streaming reader for the huge JSON arrays portals return (tens of thousands of movies / shows):
 * every object is handed over as a flat string map and dropped again, so the whole response never has
 * to sit in memory as a parsed tree.
 */
internal object JsonStream {

    /** Reads a top-level array of objects. Anything that is not an array (error objects) is skipped. */
    fun forEachObject(reader: JsonReader, onObject: (Map<String, String>) -> Unit) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            onObject(readFlatObject(reader))
        }
        reader.endArray()
    }

    /** One object as name -> string. Nested arrays / objects are skipped, nulls are left out. */
    fun readFlatObject(reader: JsonReader): Map<String, String> {
        val map = HashMap<String, String>(16)
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> map[name] = reader.nextString()
                JsonToken.BOOLEAN -> map[name] = reader.nextBoolean().toString()
                JsonToken.NULL -> reader.nextNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return map
    }
}
