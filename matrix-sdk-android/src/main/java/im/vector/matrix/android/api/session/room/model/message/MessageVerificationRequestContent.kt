/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.matrix.android.api.session.room.model.message

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import im.vector.matrix.android.api.session.events.model.Content
import im.vector.matrix.android.api.session.events.model.toContent
import im.vector.matrix.android.api.session.room.model.relation.RelationDefaultContent
import im.vector.matrix.android.internal.crypto.verification.VerificationInfoRequest

@JsonClass(generateAdapter = true)
data class MessageVerificationRequestContent(
        @Json(name = "msgtype") override val msgType: String = MessageType.MSGTYPE_VERIFICATION_REQUEST,
        @Json(name = "body") override val body: String,
        @Json(name = "from_device") override val fromDevice: String?,
        @Json(name = "methods") override val methods: List<String>,
        @Json(name = "to") val toUserId: String,
        @Json(name = "timestamp") override val timestamp: Long?,
        @Json(name = "format") val format: String? = null,
        @Json(name = "formatted_body") val formattedBody: String? = null,
        @Json(name = "m.relates_to") override val relatesTo: RelationDefaultContent? = null,
        @Json(name = "m.new_content") override val newContent: Content? = null
) : MessageContent, VerificationInfoRequest {

    override fun isValid(): Boolean {
        if (transactionID.isNullOrBlank() || methods.isNullOrEmpty() || fromDevice.isNullOrEmpty()) {
            return false
        }
        return true
    }

    override val transactionID: String?
        get() = relatesTo?.eventId

    override fun toEventContent() = toContent()
}
