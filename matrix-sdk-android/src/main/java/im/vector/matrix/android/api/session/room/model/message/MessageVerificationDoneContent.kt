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
import im.vector.matrix.android.internal.crypto.verification.VerificationInfo

@JsonClass(generateAdapter = true)
internal data class MessageVerificationDoneContent(
        @Json(name = "m.relates_to") val relatesTo: RelationDefaultContent?
) : VerificationInfo {

    override val transactionID: String?
        get() = relatesTo?.eventId

    override fun isValid() = transactionID?.isNotEmpty() == true

    override fun toEventContent(): Content? = toContent()
}
