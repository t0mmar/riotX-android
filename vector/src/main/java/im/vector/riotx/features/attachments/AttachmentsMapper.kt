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

package im.vector.riotx.features.attachments

import com.kbeanie.multipicker.api.entity.ChosenAudio
import com.kbeanie.multipicker.api.entity.ChosenContact
import com.kbeanie.multipicker.api.entity.ChosenFile
import com.kbeanie.multipicker.api.entity.ChosenImage
import com.kbeanie.multipicker.api.entity.ChosenVideo
import im.vector.matrix.android.api.session.content.ContentAttachmentData
import timber.log.Timber

fun ChosenContact.toContactAttachment(): ContactAttachment {
    return ContactAttachment(
            displayName = displayName,
            photoUri = photoUri,
            emails = emails.toList(),
            phones = phones.toList()
    )
}

fun ChosenFile.toContentAttachmentData(): ContentAttachmentData {
    if (mimeType == null) Timber.w("No mimeType")
    return ContentAttachmentData(
            path = originalPath,
            mimeType = mimeType,
            type = mapType(),
            size = size,
            date = createdAt?.time ?: System.currentTimeMillis(),
            name = displayName,
            queryUri = queryUri
    )
}

fun ChosenAudio.toContentAttachmentData(): ContentAttachmentData {
    if (mimeType == null) Timber.w("No mimeType")
    return ContentAttachmentData(
            path = originalPath,
            mimeType = mimeType,
            type = mapType(),
            size = size,
            date = createdAt?.time ?: System.currentTimeMillis(),
            name = displayName,
            duration = duration,
            queryUri = queryUri
    )
}

private fun ChosenFile.mapType(): ContentAttachmentData.Type {
    return when {
        mimeType?.startsWith("image/") == true -> ContentAttachmentData.Type.IMAGE
        mimeType?.startsWith("video/") == true -> ContentAttachmentData.Type.VIDEO
        mimeType?.startsWith("audio/") == true -> ContentAttachmentData.Type.AUDIO
        else                                   -> ContentAttachmentData.Type.FILE
    }
}

fun ChosenImage.toContentAttachmentData(): ContentAttachmentData {
    if (mimeType == null) Timber.w("No mimeType")
    return ContentAttachmentData(
            path = originalPath,
            mimeType = mimeType,
            type = mapType(),
            name = displayName,
            size = size,
            height = height.toLong(),
            width = width.toLong(),
            exifOrientation = orientation,
            date = createdAt?.time ?: System.currentTimeMillis(),
            queryUri = queryUri
    )
}

fun ChosenVideo.toContentAttachmentData(): ContentAttachmentData {
    if (mimeType == null) Timber.w("No mimeType")
    return ContentAttachmentData(
            path = originalPath,
            mimeType = mimeType,
            type = ContentAttachmentData.Type.VIDEO,
            size = size,
            date = createdAt?.time ?: System.currentTimeMillis(),
            height = height.toLong(),
            width = width.toLong(),
            duration = duration,
            name = displayName,
            queryUri = queryUri
    )
}
