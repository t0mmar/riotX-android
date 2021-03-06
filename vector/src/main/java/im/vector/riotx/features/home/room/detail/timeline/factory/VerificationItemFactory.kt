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
package im.vector.riotx.features.home.room.detail.timeline.factory

import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.session.crypto.verification.CancelCode
import im.vector.matrix.android.api.session.crypto.verification.safeValueOf
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.RelationType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.message.MessageRelationContent
import im.vector.matrix.android.api.session.room.model.message.MessageVerificationCancelContent
import im.vector.matrix.android.api.session.room.model.message.MessageVerificationRequestContent
import im.vector.matrix.android.api.session.room.timeline.TimelineEvent
import im.vector.matrix.android.internal.session.room.VerificationState
import im.vector.riotx.core.epoxy.VectorEpoxyModel
import im.vector.riotx.core.resources.ColorProvider
import im.vector.riotx.core.resources.UserPreferencesProvider
import im.vector.riotx.features.home.room.detail.timeline.TimelineEventController
import im.vector.riotx.features.home.room.detail.timeline.helper.AvatarSizeProvider
import im.vector.riotx.features.home.room.detail.timeline.helper.MessageInformationDataFactory
import im.vector.riotx.features.home.room.detail.timeline.helper.MessageItemAttributesFactory
import im.vector.riotx.features.home.room.detail.timeline.item.VerificationRequestConclusionItem
import im.vector.riotx.features.home.room.detail.timeline.item.VerificationRequestConclusionItem_
import javax.inject.Inject

/**
 * Can creates verification conclusion items
 * Notice that not all KEY_VERIFICATION_DONE will be displayed in timeline,
 * several checks are made to see if this conclusion is attached to a known request
 */
class VerificationItemFactory @Inject constructor(
        private val colorProvider: ColorProvider,
        private val messageInformationDataFactory: MessageInformationDataFactory,
        private val messageItemAttributesFactory: MessageItemAttributesFactory,
        private val avatarSizeProvider: AvatarSizeProvider,
        private val noticeItemFactory: NoticeItemFactory,
        private val userPreferencesProvider: UserPreferencesProvider,
        private val session: Session
) {

    fun create(event: TimelineEvent,
               highlight: Boolean,
               callback: TimelineEventController.Callback?
    ): VectorEpoxyModel<*>? {
        if (event.root.eventId == null) return null

        val relContent: MessageRelationContent = event.root.content.toModel()
                ?: event.root.getClearContent().toModel()
                ?: return ignoredConclusion(event, highlight, callback)

        if (relContent.relatesTo?.type != RelationType.REFERENCE) return ignoredConclusion(event, highlight, callback)
        val refEventId = relContent.relatesTo?.eventId
                ?: return ignoredConclusion(event, highlight, callback)

        // If we cannot find the referenced request we do not display the done event
        val refEvent = session.getRoom(event.root.roomId ?: "")?.getTimeLineEvent(refEventId)
                ?: return ignoredConclusion(event, highlight, callback)

        // If it's not a request ignore this event
        if (refEvent.root.getClearContent().toModel<MessageVerificationRequestContent>() == null) return ignoredConclusion(event, highlight, callback)

        val referenceInformationData = messageInformationDataFactory.create(refEvent, null)

        val informationData = messageInformationDataFactory.create(event, null)
        val attributes = messageItemAttributesFactory.create(null, informationData, callback)

        when (event.root.getClearType()) {
            EventType.KEY_VERIFICATION_CANCEL -> {
                // Is the request referenced is actually really cancelled?
                val cancelContent = event.root.getClearContent().toModel<MessageVerificationCancelContent>()
                        ?: return ignoredConclusion(event, highlight, callback)

                when (safeValueOf(cancelContent.code)) {
                    CancelCode.MismatchedCommitment,
                    CancelCode.MismatchedKeys,
                    CancelCode.MismatchedSas -> {
                        // We should display these bad conclusions
                        return VerificationRequestConclusionItem_()
                                .attributes(
                                        VerificationRequestConclusionItem.Attributes(
                                                toUserId = informationData.senderId,
                                                toUserName = informationData.memberName.toString(),
                                                isPositive = false,
                                                informationData = informationData,
                                                avatarRenderer = attributes.avatarRenderer,
                                                colorProvider = colorProvider,
                                                emojiTypeFace = attributes.emojiTypeFace,
                                                itemClickListener = attributes.itemClickListener,
                                                itemLongClickListener = attributes.itemLongClickListener,
                                                reactionPillCallback = attributes.reactionPillCallback,
                                                readReceiptsCallback = attributes.readReceiptsCallback
                                        )
                                )
                                .highlighted(highlight)
                                .leftGuideline(avatarSizeProvider.leftGuideline)
                    }
                    else                     -> return ignoredConclusion(event, highlight, callback)
                }
            }
            EventType.KEY_VERIFICATION_DONE   -> {
                // Is the request referenced is actually really completed?
                if (referenceInformationData.referencesInfoData?.verificationStatus != VerificationState.DONE) {
                    return ignoredConclusion(event, highlight, callback)
                }
                // We only tale the one sent by me

                if (informationData.sentByMe) {
                    // We only display the done sent by the other user, the done send by me is ignored
                    return ignoredConclusion(event, highlight, callback)
                }
                return VerificationRequestConclusionItem_()
                        .attributes(
                                VerificationRequestConclusionItem.Attributes(
                                        toUserId = informationData.senderId,
                                        toUserName = informationData.memberName.toString(),
                                        isPositive = true,
                                        informationData = informationData,
                                        avatarRenderer = attributes.avatarRenderer,
                                        colorProvider = colorProvider,
                                        emojiTypeFace = attributes.emojiTypeFace,
                                        itemClickListener = attributes.itemClickListener,
                                        itemLongClickListener = attributes.itemLongClickListener,
                                        reactionPillCallback = attributes.reactionPillCallback,
                                        readReceiptsCallback = attributes.readReceiptsCallback
                                )
                        )
                        .highlighted(highlight)
                        .leftGuideline(avatarSizeProvider.leftGuideline)
            }
        }
        return null
    }

    private fun ignoredConclusion(event: TimelineEvent,
                                  highlight: Boolean,
                                  callback: TimelineEventController.Callback?
    ): VectorEpoxyModel<*>? {
        if (userPreferencesProvider.shouldShowHiddenEvents()) return noticeItemFactory.create(event, highlight, callback)
        return null
    }
}
