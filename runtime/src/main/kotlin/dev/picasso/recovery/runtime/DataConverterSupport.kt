package dev.picasso.recovery.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.PayloadConverter
import java.lang.reflect.Type
import java.util.Optional

/**
 * A JSON payload kept as its exact text: what goes out is these bytes, what comes in is the bytes as received, with no
 * Jackson round trip. The episode workflow talks to narrator's Python `diagnose` activity this way — the request keeps
 * its key order and explicit nulls (narrator contract 0.6 §2), and a duplicated key in the answer is still there to be
 * caught (core `parseDiagnosisResponse(String)`).
 *
 * Recognised only as a top-level payload (an activity argument or result): nested inside another object it goes through
 * Jackson like any other field.
 */
data class RawJson(val text: String = "")

/** The `json/plain` converter with one exception: [RawJson] passes through untouched. Everything else is [jackson]. */
class RawAwareJsonPayloadConverter(private val jackson: JacksonJsonPayloadConverter) : PayloadConverter {
    override fun getEncodingType(): String = jackson.encodingType

    override fun toData(value: Any?): Optional<Payload> =
        if (value is RawJson) Optional.of(
            Payload.newBuilder()
                .putMetadata(ENCODING_KEY, ByteString.copyFromUtf8(jackson.encodingType))
                .setData(ByteString.copyFromUtf8(value.text))
                .build(),
        )
        else jackson.toData(value)

    override fun <T> fromData(content: Payload, valueType: Class<T>, valueGenericType: Type): T =
        if (valueType == RawJson::class.java) valueType.cast(RawJson(content.data.toStringUtf8()))
        else jackson.fromData(content, valueType, valueGenericType)

    private companion object {
        /** Temporal's payload metadata key for the encoding (`EncodingKeys.METADATA_ENCODING_KEY`). */
        const val ENCODING_KEY = "encoding"
    }
}

/**
 * Kotlin-aware Temporal client options, carrying the episode workflow's [RawJson] payloads verbatim (episode design
 * §8.1). SDK 1.25.1's default JacksonJsonPayloadConverter cannot construct Kotlin data classes without a no-arg
 * constructor. Every worker and client must build its WorkflowClient with these options, so the same converter is used
 * on both sides.
 */
object DataConverterSupport {
    fun clientOptions(): WorkflowClientOptions = WorkflowClientOptions.newBuilder().setDataConverter(dataConverter()).build()

    /** The converter [clientOptions] uses, for code that needs it directly (a test environment, a replayer). */
    fun dataConverter(): DataConverter = DefaultDataConverter.newDefaultInstance()
        .withPayloadConverterOverrides(RawAwareJsonPayloadConverter(JacksonJsonPayloadConverter(jacksonObjectMapper())))
}
