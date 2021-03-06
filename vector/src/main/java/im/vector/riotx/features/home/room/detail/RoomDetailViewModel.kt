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

package im.vector.riotx.features.home.room.detail

import android.net.Uri
import androidx.annotation.IdRes
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.ViewModelContext
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.MatrixPatterns
import im.vector.matrix.android.api.NoOpMatrixCallback
import im.vector.matrix.android.api.query.QueryStringValue
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.isImageMessage
import im.vector.matrix.android.api.session.events.model.isTextMessage
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.file.FileService
import im.vector.matrix.android.api.session.homeserver.HomeServerCapabilities
import im.vector.matrix.android.api.session.room.members.roomMemberQueryParams
import im.vector.matrix.android.api.session.room.model.Membership
import im.vector.matrix.android.api.session.room.model.RoomMemberSummary
import im.vector.matrix.android.api.session.room.model.RoomSummary
import im.vector.matrix.android.api.session.room.model.message.MessageContent
import im.vector.matrix.android.api.session.room.model.message.MessageType
import im.vector.matrix.android.api.session.room.model.message.OptionItem
import im.vector.matrix.android.api.session.room.model.message.getFileUrl
import im.vector.matrix.android.api.session.room.model.tombstone.RoomTombstoneContent
import im.vector.matrix.android.api.session.room.read.ReadService
import im.vector.matrix.android.api.session.room.send.UserDraft
import im.vector.matrix.android.api.session.room.timeline.Timeline
import im.vector.matrix.android.api.session.room.timeline.TimelineEvent
import im.vector.matrix.android.api.session.room.timeline.TimelineSettings
import im.vector.matrix.android.api.session.room.timeline.getTextEditableContent
import im.vector.matrix.android.api.util.toOptional
import im.vector.matrix.android.internal.crypto.attachments.toElementToDecrypt
import im.vector.matrix.android.internal.crypto.model.event.EncryptedEventContent
import im.vector.matrix.rx.rx
import im.vector.matrix.rx.unwrap
import im.vector.riotx.R
import im.vector.riotx.core.extensions.exhaustive
import im.vector.riotx.core.platform.VectorViewModel
import im.vector.riotx.core.resources.StringProvider
import im.vector.riotx.core.resources.UserPreferencesProvider
import im.vector.riotx.core.utils.subscribeLogError
import im.vector.riotx.features.command.CommandParser
import im.vector.riotx.features.command.ParsedCommand
import im.vector.riotx.features.crypto.verification.supportedVerificationMethods
import im.vector.riotx.features.home.room.detail.composer.rainbow.RainbowGenerator
import im.vector.riotx.features.home.room.detail.timeline.helper.TimelineDisplayableEvents
import im.vector.riotx.features.home.room.typing.TypingHelper
import im.vector.riotx.features.settings.VectorPreferences
import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RoomDetailViewModel @AssistedInject constructor(@Assisted initialState: RoomDetailViewState,
                                                      userPreferencesProvider: UserPreferencesProvider,
                                                      private val vectorPreferences: VectorPreferences,
                                                      private val stringProvider: StringProvider,
                                                      private val typingHelper: TypingHelper,
                                                      private val rainbowGenerator: RainbowGenerator,
                                                      private val session: Session
) : VectorViewModel<RoomDetailViewState, RoomDetailAction, RoomDetailViewEvents>(initialState), Timeline.Listener {

    private val room = session.getRoom(initialState.roomId)!!
    private val eventId = initialState.eventId
    private val invisibleEventsObservable = BehaviorRelay.create<RoomDetailAction.TimelineEventTurnsInvisible>()
    private val visibleEventsObservable = BehaviorRelay.create<RoomDetailAction.TimelineEventTurnsVisible>()
    private val timelineSettings = if (userPreferencesProvider.shouldShowHiddenEvents()) {
        TimelineSettings(30,
                filterEdits = false,
                filterTypes = false,
                buildReadReceipts = userPreferencesProvider.shouldShowReadReceipts())
    } else {
        TimelineSettings(30,
                filterEdits = true,
                filterTypes = true,
                allowedTypes = TimelineDisplayableEvents.DISPLAYABLE_TYPES,
                buildReadReceipts = userPreferencesProvider.shouldShowReadReceipts())
    }

    private var timelineEvents = PublishRelay.create<List<TimelineEvent>>()
    var timeline = room.createTimeline(eventId, timelineSettings)
        private set

    // Slot to keep a pending action during permission request
    var pendingAction: RoomDetailAction? = null
    // Slot to keep a pending uri during permission request
    var pendingUri: Uri? = null
    // Slot to store if we want to prevent preview of attachment
    var preventAttachmentPreview = false

    private var trackUnreadMessages = AtomicBoolean(false)
    private var mostRecentDisplayedEvent: TimelineEvent? = null

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: RoomDetailViewState): RoomDetailViewModel
    }

    companion object : MvRxViewModelFactory<RoomDetailViewModel, RoomDetailViewState> {

        const val PAGINATION_COUNT = 50

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: RoomDetailViewState): RoomDetailViewModel? {
            val fragment: RoomDetailFragment = (viewModelContext as FragmentViewModelContext).fragment()

            return fragment.roomDetailViewModelFactory.create(state)
        }
    }

    init {
        timeline.start()
        timeline.addListener(this)
        observeRoomSummary()
        observeSummaryState()
        getUnreadState()
        observeSyncState()
        observeEventDisplayedActions()
        observeDrafts()
        observeUnreadState()
        observeMyRoomMember()
        room.getRoomSummaryLive()
        room.markAsRead(ReadService.MarkAsReadParams.READ_RECEIPT, NoOpMatrixCallback())
        room.rx().loadRoomMembersIfNeeded().subscribeLogError().disposeOnClear()
        // Inform the SDK that the room is displayed
        session.onRoomDisplayed(initialState.roomId)
    }

    private fun observeMyRoomMember() {
        val queryParams = roomMemberQueryParams {
            this.userId = QueryStringValue.Equals(session.myUserId, QueryStringValue.Case.SENSITIVE)
        }
        room.rx()
                .liveRoomMembers(queryParams)
                .map {
                    it.firstOrNull().toOptional()
                }
                .unwrap()
                .execute {
                    copy(myRoomMember = it)
                }
    }

    override fun handle(action: RoomDetailAction) {
        when (action) {
            is RoomDetailAction.UserIsTyping                     -> handleUserIsTyping(action)
            is RoomDetailAction.SaveDraft                        -> handleSaveDraft(action)
            is RoomDetailAction.SendMessage                      -> handleSendMessage(action)
            is RoomDetailAction.SendMedia                        -> handleSendMedia(action)
            is RoomDetailAction.TimelineEventTurnsVisible        -> handleEventVisible(action)
            is RoomDetailAction.TimelineEventTurnsInvisible      -> handleEventInvisible(action)
            is RoomDetailAction.LoadMoreTimelineEvents           -> handleLoadMore(action)
            is RoomDetailAction.SendReaction                     -> handleSendReaction(action)
            is RoomDetailAction.AcceptInvite                     -> handleAcceptInvite()
            is RoomDetailAction.RejectInvite                     -> handleRejectInvite()
            is RoomDetailAction.RedactAction                     -> handleRedactEvent(action)
            is RoomDetailAction.UndoReaction                     -> handleUndoReact(action)
            is RoomDetailAction.UpdateQuickReactAction           -> handleUpdateQuickReaction(action)
            is RoomDetailAction.ExitSpecialMode                  -> handleExitSpecialMode(action)
            is RoomDetailAction.EnterEditMode                    -> handleEditAction(action)
            is RoomDetailAction.EnterQuoteMode                   -> handleQuoteAction(action)
            is RoomDetailAction.EnterReplyMode                   -> handleReplyAction(action)
            is RoomDetailAction.DownloadFile                     -> handleDownloadFile(action)
            is RoomDetailAction.NavigateToEvent                  -> handleNavigateToEvent(action)
            is RoomDetailAction.HandleTombstoneEvent             -> handleTombstoneEvent(action)
            is RoomDetailAction.ResendMessage                    -> handleResendEvent(action)
            is RoomDetailAction.RemoveFailedEcho                 -> handleRemove(action)
            is RoomDetailAction.ClearSendQueue                   -> handleClearSendQueue()
            is RoomDetailAction.ResendAll                        -> handleResendAll()
            is RoomDetailAction.MarkAllAsRead                    -> handleMarkAllAsRead()
            is RoomDetailAction.ReportContent                    -> handleReportContent(action)
            is RoomDetailAction.IgnoreUser                       -> handleIgnoreUser(action)
            is RoomDetailAction.EnterTrackingUnreadMessagesState -> startTrackingUnreadMessages()
            is RoomDetailAction.ExitTrackingUnreadMessagesState  -> stopTrackingUnreadMessages()
            is RoomDetailAction.ReplyToOptions                   -> handleReplyToOptions(action)
            is RoomDetailAction.AcceptVerificationRequest        -> handleAcceptVerification(action)
            is RoomDetailAction.DeclineVerificationRequest       -> handleDeclineVerification(action)
            is RoomDetailAction.RequestVerification              -> handleRequestVerification(action)
            is RoomDetailAction.ResumeVerification               -> handleResumeRequestVerification(action)
        }
    }

    private fun startTrackingUnreadMessages() {
        trackUnreadMessages.set(true)
        setState { copy(canShowJumpToReadMarker = false) }
    }

    private fun stopTrackingUnreadMessages() {
        if (trackUnreadMessages.getAndSet(false)) {
            mostRecentDisplayedEvent?.root?.eventId?.also {
                room.setReadMarker(it, callback = NoOpMatrixCallback())
            }
            mostRecentDisplayedEvent = null
        }
        setState { copy(canShowJumpToReadMarker = true) }
    }

    private fun handleEventInvisible(action: RoomDetailAction.TimelineEventTurnsInvisible) {
        invisibleEventsObservable.accept(action)
    }

    fun getMember(userId: String): RoomMemberSummary? {
        return room.getRoomMember(userId)
    }

    /**
     * Convert a send mode to a draft and save the draft
     */
    private fun handleSaveDraft(action: RoomDetailAction.SaveDraft) {
        withState {
            when (it.sendMode) {
                is SendMode.REGULAR -> room.saveDraft(UserDraft.REGULAR(action.draft), NoOpMatrixCallback())
                is SendMode.REPLY   -> room.saveDraft(UserDraft.REPLY(it.sendMode.timelineEvent.root.eventId!!, action.draft), NoOpMatrixCallback())
                is SendMode.QUOTE   -> room.saveDraft(UserDraft.QUOTE(it.sendMode.timelineEvent.root.eventId!!, action.draft), NoOpMatrixCallback())
                is SendMode.EDIT    -> room.saveDraft(UserDraft.EDIT(it.sendMode.timelineEvent.root.eventId!!, action.draft), NoOpMatrixCallback())
            }.exhaustive
        }
    }

    private fun observeDrafts() {
        room.rx().liveDrafts()
                .subscribe {
                    Timber.d("Draft update --> SetState")
                    setState {
                        val draft = it.lastOrNull() ?: UserDraft.REGULAR("")
                        copy(
                                // Create a sendMode from a draft and retrieve the TimelineEvent
                                sendMode = when (draft) {
                                    is UserDraft.REGULAR -> SendMode.REGULAR(draft.text)
                                    is UserDraft.QUOTE   -> {
                                        room.getTimeLineEvent(draft.linkedEventId)?.let { timelineEvent ->
                                            SendMode.QUOTE(timelineEvent, draft.text)
                                        }
                                    }
                                    is UserDraft.REPLY   -> {
                                        room.getTimeLineEvent(draft.linkedEventId)?.let { timelineEvent ->
                                            SendMode.REPLY(timelineEvent, draft.text)
                                        }
                                    }
                                    is UserDraft.EDIT    -> {
                                        room.getTimeLineEvent(draft.linkedEventId)?.let { timelineEvent ->
                                            SendMode.EDIT(timelineEvent, draft.text)
                                        }
                                    }
                                } ?: SendMode.REGULAR("")
                        )
                    }
                }
                .disposeOnClear()
    }

    private fun handleUserIsTyping(action: RoomDetailAction.UserIsTyping) {
        if (vectorPreferences.sendTypingNotifs()) {
            if (action.isTyping) {
                room.userIsTyping()
            } else {
                room.userStopsTyping()
            }
        }
    }

    private fun handleTombstoneEvent(action: RoomDetailAction.HandleTombstoneEvent) {
        val tombstoneContent = action.event.getClearContent().toModel<RoomTombstoneContent>() ?: return

        val roomId = tombstoneContent.replacementRoomId ?: ""
        val isRoomJoined = session.getRoom(roomId)?.roomSummary()?.membership == Membership.JOIN
        if (isRoomJoined) {
            setState { copy(tombstoneEventHandling = Success(roomId)) }
        } else {
            val viaServers = MatrixPatterns.extractServerNameFromId(action.event.senderId)
                    ?.let { listOf(it) }
                    .orEmpty()
            session.rx()
                    .joinRoom(roomId, viaServers = viaServers)
                    .map { roomId }
                    .execute {
                        copy(tombstoneEventHandling = it)
                    }
        }
    }

    fun isMenuItemVisible(@IdRes itemId: Int) = when (itemId) {
        R.id.clear_message_queue ->
            /* For now always disable on production, worker cancellation is not working properly */
            timeline.pendingEventCount() > 0 && vectorPreferences.developerMode()
        R.id.resend_all          -> timeline.failedToDeliverEventCount() > 0
        R.id.clear_all           -> timeline.failedToDeliverEventCount() > 0
        else                     -> false
    }

// PRIVATE METHODS *****************************************************************************

    private fun handleSendMessage(action: RoomDetailAction.SendMessage) {
        withState { state ->
            when (state.sendMode) {
                is SendMode.REGULAR -> {
                    when (val slashCommandResult = CommandParser.parseSplashCommand(action.text)) {
                        is ParsedCommand.ErrorNotACommand         -> {
                            // Send the text message to the room
                            room.sendTextMessage(action.text, autoMarkdown = action.autoMarkdown)
                            _viewEvents.post(RoomDetailViewEvents.MessageSent)
                            popDraft()
                        }
                        is ParsedCommand.ErrorSyntax              -> {
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandError(slashCommandResult.command))
                        }
                        is ParsedCommand.ErrorEmptySlashCommand   -> {
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandUnknown("/"))
                        }
                        is ParsedCommand.ErrorUnknownSlashCommand -> {
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandUnknown(slashCommandResult.slashCommand))
                        }
                        is ParsedCommand.SendPlainText            -> {
                            // Send the text message to the room, without markdown
                            room.sendTextMessage(slashCommandResult.message, autoMarkdown = false)
                            _viewEvents.post(RoomDetailViewEvents.MessageSent)
                            popDraft()
                        }
                        is ParsedCommand.Invite                   -> {
                            handleInviteSlashCommand(slashCommandResult)
                            popDraft()
                        }
                        is ParsedCommand.SetUserPowerLevel        -> {
                            // TODO
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandNotImplemented)
                        }
                        is ParsedCommand.ClearScalarToken         -> {
                            // TODO
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandNotImplemented)
                        }
                        is ParsedCommand.SetMarkdown              -> {
                            vectorPreferences.setMarkdownEnabled(slashCommandResult.enable)
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled(
                                    if (slashCommandResult.enable) R.string.markdown_has_been_enabled else R.string.markdown_has_been_disabled))
                            popDraft()
                        }
                        is ParsedCommand.UnbanUser                -> {
                            // TODO
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandNotImplemented)
                        }
                        is ParsedCommand.BanUser                  -> {
                            // TODO
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandNotImplemented)
                        }
                        is ParsedCommand.KickUser                 -> {
                            // TODO
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandNotImplemented)
                        }
                        is ParsedCommand.JoinRoom                 -> {
                            // TODO
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandNotImplemented)
                        }
                        is ParsedCommand.PartRoom                 -> {
                            // TODO
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandNotImplemented)
                        }
                        is ParsedCommand.SendEmote                -> {
                            room.sendTextMessage(slashCommandResult.message, msgType = MessageType.MSGTYPE_EMOTE)
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.SendRainbow              -> {
                            slashCommandResult.message.toString().let {
                                room.sendFormattedTextMessage(it, rainbowGenerator.generate(it))
                            }
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.SendRainbowEmote         -> {
                            slashCommandResult.message.toString().let {
                                room.sendFormattedTextMessage(it, rainbowGenerator.generate(it), MessageType.MSGTYPE_EMOTE)
                            }
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.SendSpoiler              -> {
                            room.sendFormattedTextMessage(
                                    "[${stringProvider.getString(R.string.spoiler)}](${slashCommandResult.message})",
                                    "<span data-mx-spoiler>${slashCommandResult.message}</span>"
                            )
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.SendShrug                -> {
                            val sequence = buildString {
                                append("¯\\_(ツ)_/¯")
                                if (slashCommandResult.message.isNotEmpty()) {
                                    append(" ")
                                    append(slashCommandResult.message)
                                }
                            }
                            room.sendTextMessage(sequence)
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.VerifyUser               -> {
                            session
                                    .cryptoService()
                                    .verificationService()
                                    .requestKeyVerificationInDMs(supportedVerificationMethods, slashCommandResult.userId, room.roomId)
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.SendPoll                 -> {
                            room.sendPoll(slashCommandResult.question, slashCommandResult.options.mapIndexed { index, s -> OptionItem(s, "$index. $s") })
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.ChangeTopic              -> {
                            handleChangeTopicSlashCommand(slashCommandResult)
                            popDraft()
                        }
                        is ParsedCommand.ChangeDisplayName        -> {
                            // TODO
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandNotImplemented)
                        }
                    }.exhaustive
                }
                is SendMode.EDIT    -> {
                    // is original event a reply?
                    val inReplyTo = state.sendMode.timelineEvent.root.getClearContent().toModel<MessageContent>()?.relatesTo?.inReplyTo?.eventId
                            ?: state.sendMode.timelineEvent.root.content.toModel<EncryptedEventContent>()?.relatesTo?.inReplyTo?.eventId
                    if (inReplyTo != null) {
                        // TODO check if same content?
                        room.getTimeLineEvent(inReplyTo)?.let {
                            room.editReply(state.sendMode.timelineEvent, it, action.text.toString())
                        }
                    } else {
                        val messageContent: MessageContent? =
                                state.sendMode.timelineEvent.annotations?.editSummary?.aggregatedContent.toModel()
                                        ?: state.sendMode.timelineEvent.root.getClearContent().toModel()
                        val existingBody = messageContent?.body ?: ""
                        if (existingBody != action.text) {
                            room.editTextMessage(state.sendMode.timelineEvent.root.eventId ?: "",
                                    messageContent?.msgType ?: MessageType.MSGTYPE_TEXT,
                                    action.text,
                                    action.autoMarkdown)
                        } else {
                            Timber.w("Same message content, do not send edition")
                        }
                    }
                    _viewEvents.post(RoomDetailViewEvents.MessageSent)
                    popDraft()
                }
                is SendMode.QUOTE   -> {
                    val messageContent: MessageContent? =
                            state.sendMode.timelineEvent.annotations?.editSummary?.aggregatedContent.toModel()
                                    ?: state.sendMode.timelineEvent.root.getClearContent().toModel()
                    val textMsg = messageContent?.body

                    val finalText = legacyRiotQuoteText(textMsg, action.text.toString())

                    // TODO check for pills?

                    // TODO Refactor this, just temporary for quotes
                    val parser = Parser.builder().build()
                    val document = parser.parse(finalText)
                    val renderer = HtmlRenderer.builder().build()
                    val htmlText = renderer.render(document)
                    if (finalText == htmlText) {
                        room.sendTextMessage(finalText)
                    } else {
                        room.sendFormattedTextMessage(finalText, htmlText)
                    }
                    _viewEvents.post(RoomDetailViewEvents.MessageSent)
                    popDraft()
                }
                is SendMode.REPLY   -> {
                    state.sendMode.timelineEvent.let {
                        room.replyToMessage(it, action.text.toString(), action.autoMarkdown)
                        _viewEvents.post(RoomDetailViewEvents.MessageSent)
                        popDraft()
                    }
                }
            }.exhaustive
        }
    }

    private fun popDraft() {
        room.deleteDraft(NoOpMatrixCallback())
    }

    private fun legacyRiotQuoteText(quotedText: String?, myText: String): String {
        val messageParagraphs = quotedText?.split("\n\n".toRegex())?.dropLastWhile { it.isEmpty() }?.toTypedArray()
        return buildString {
            if (messageParagraphs != null) {
                for (i in messageParagraphs.indices) {
                    if (messageParagraphs[i].isNotBlank()) {
                        append("> ")
                        append(messageParagraphs[i])
                    }

                    if (i != messageParagraphs.lastIndex) {
                        append("\n\n")
                    }
                }
            }
            append("\n\n")
            append(myText)
        }
    }

    private fun handleChangeTopicSlashCommand(changeTopic: ParsedCommand.ChangeTopic) {
        _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())

        room.updateTopic(changeTopic.topic, object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                _viewEvents.post(RoomDetailViewEvents.SlashCommandResultOk)
            }

            override fun onFailure(failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.SlashCommandResultError(failure))
            }
        })
    }

    private fun handleInviteSlashCommand(invite: ParsedCommand.Invite) {
        _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())

        room.invite(invite.userId, invite.reason, object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                _viewEvents.post(RoomDetailViewEvents.SlashCommandResultOk)
            }

            override fun onFailure(failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.SlashCommandResultError(failure))
            }
        })
    }

    private fun handleSendReaction(action: RoomDetailAction.SendReaction) {
        room.sendReaction(action.targetEventId, action.reaction)
    }

    private fun handleRedactEvent(action: RoomDetailAction.RedactAction) {
        val event = room.getTimeLineEvent(action.targetEventId) ?: return
        room.redactEvent(event.root, action.reason)
    }

    private fun handleUndoReact(action: RoomDetailAction.UndoReaction) {
        room.undoReaction(action.targetEventId, action.reaction)
    }

    private fun handleUpdateQuickReaction(action: RoomDetailAction.UpdateQuickReactAction) {
        if (action.add) {
            room.sendReaction(action.targetEventId, action.selectedReaction)
        } else {
            room.undoReaction(action.targetEventId, action.selectedReaction)
        }
    }

    private fun handleSendMedia(action: RoomDetailAction.SendMedia) {
        val attachments = action.attachments
        val homeServerCapabilities = session.getHomeServerCapabilities()
        val maxUploadFileSize = homeServerCapabilities.maxUploadFileSize

        if (maxUploadFileSize == HomeServerCapabilities.MAX_UPLOAD_FILE_SIZE_UNKNOWN) {
            // Unknown limitation
            room.sendMedias(attachments, action.compressBeforeSending, emptySet())
        } else {
            when (val tooBigFile = attachments.find { it.size > maxUploadFileSize }) {
                null -> room.sendMedias(attachments, action.compressBeforeSending, emptySet())
                else -> _viewEvents.post(RoomDetailViewEvents.FileTooBigError(
                        tooBigFile.name ?: tooBigFile.path,
                        tooBigFile.size,
                        maxUploadFileSize
                ))
            }
        }
    }

    private fun handleEventVisible(action: RoomDetailAction.TimelineEventTurnsVisible) {
        if (action.event.root.sendState.isSent()) { // ignore pending/local events
            visibleEventsObservable.accept(action)
        }
        // We need to update this with the related m.replace also (to move read receipt)
        action.event.annotations?.editSummary?.sourceEvents?.forEach {
            room.getTimeLineEvent(it)?.let { event ->
                visibleEventsObservable.accept(RoomDetailAction.TimelineEventTurnsVisible(event))
            }
        }
    }

    private fun handleLoadMore(action: RoomDetailAction.LoadMoreTimelineEvents) {
        timeline.paginate(action.direction, PAGINATION_COUNT)
    }

    private fun handleRejectInvite() {
        room.leave(null, NoOpMatrixCallback())
    }

    private fun handleAcceptInvite() {
        room.join(callback = NoOpMatrixCallback())
    }

    private fun handleEditAction(action: RoomDetailAction.EnterEditMode) {
        saveCurrentDraft(action.text)

        room.getTimeLineEvent(action.eventId)?.let { timelineEvent ->
            setState { copy(sendMode = SendMode.EDIT(timelineEvent, action.text)) }
            timelineEvent.root.eventId?.let {
                room.saveDraft(UserDraft.EDIT(it, timelineEvent.getTextEditableContent() ?: ""), NoOpMatrixCallback())
            }
        }
    }

    private fun handleQuoteAction(action: RoomDetailAction.EnterQuoteMode) {
        saveCurrentDraft(action.text)

        room.getTimeLineEvent(action.eventId)?.let { timelineEvent ->
            setState { copy(sendMode = SendMode.QUOTE(timelineEvent, action.text)) }
            withState { state ->
                // Save a new draft and keep the previously entered text, if it was not an edit
                timelineEvent.root.eventId?.let {
                    if (state.sendMode is SendMode.EDIT) {
                        room.saveDraft(UserDraft.QUOTE(it, ""), NoOpMatrixCallback())
                    } else {
                        room.saveDraft(UserDraft.QUOTE(it, action.text), NoOpMatrixCallback())
                    }
                }
            }
        }
    }

    private fun handleReplyAction(action: RoomDetailAction.EnterReplyMode) {
        saveCurrentDraft(action.text)

        room.getTimeLineEvent(action.eventId)?.let { timelineEvent ->
            setState { copy(sendMode = SendMode.REPLY(timelineEvent, action.text)) }
            withState { state ->
                // Save a new draft and keep the previously entered text, if it was not an edit
                timelineEvent.root.eventId?.let {
                    if (state.sendMode is SendMode.EDIT) {
                        room.saveDraft(UserDraft.REPLY(it, ""), NoOpMatrixCallback())
                    } else {
                        room.saveDraft(UserDraft.REPLY(it, action.text), NoOpMatrixCallback())
                    }
                }
            }
        }
    }

    private fun saveCurrentDraft(draft: String) {
        // Save the draft with the current text if any
        withState {
            if (draft.isNotBlank()) {
                when (it.sendMode) {
                    is SendMode.REGULAR -> room.saveDraft(UserDraft.REGULAR(draft), NoOpMatrixCallback())
                    is SendMode.REPLY   -> room.saveDraft(UserDraft.REPLY(it.sendMode.timelineEvent.root.eventId!!, draft), NoOpMatrixCallback())
                    is SendMode.QUOTE   -> room.saveDraft(UserDraft.QUOTE(it.sendMode.timelineEvent.root.eventId!!, draft), NoOpMatrixCallback())
                    is SendMode.EDIT    -> room.saveDraft(UserDraft.EDIT(it.sendMode.timelineEvent.root.eventId!!, draft), NoOpMatrixCallback())
                }
            }
        }
    }

    private fun handleExitSpecialMode(action: RoomDetailAction.ExitSpecialMode) {
        setState { copy(sendMode = SendMode.REGULAR(action.text)) }
        withState { state ->
            // For edit, just delete the current draft
            if (state.sendMode is SendMode.EDIT) {
                room.deleteDraft(NoOpMatrixCallback())
            } else {
                // Save a new draft and keep the previously entered text
                room.saveDraft(UserDraft.REGULAR(action.text), NoOpMatrixCallback())
            }
        }
    }

    private fun handleDownloadFile(action: RoomDetailAction.DownloadFile) {
        session.downloadFile(
                FileService.DownloadMode.TO_EXPORT,
                action.eventId,
                action.messageFileContent.getFileName(),
                action.messageFileContent.getFileUrl(),
                action.messageFileContent.encryptedFileInfo?.toElementToDecrypt(),
                object : MatrixCallback<File> {
                    override fun onSuccess(data: File) {
                        _viewEvents.post(RoomDetailViewEvents.DownloadFileState(
                                action.messageFileContent.getMimeType(),
                                data,
                                null
                        ))
                    }

                    override fun onFailure(failure: Throwable) {
                        _viewEvents.post(RoomDetailViewEvents.DownloadFileState(
                                action.messageFileContent.getMimeType(),
                                null,
                                failure
                        ))
                    }
                })
    }

    private fun handleNavigateToEvent(action: RoomDetailAction.NavigateToEvent) {
        stopTrackingUnreadMessages()
        val targetEventId: String = action.eventId
        val correctedEventId = timeline.getFirstDisplayableEventId(targetEventId) ?: targetEventId
        val indexOfEvent = timeline.getIndexOfEvent(correctedEventId)
        if (indexOfEvent == null) {
            // Event is not already in RAM
            timeline.restartWithEventId(targetEventId)
        }
        if (action.highlight) {
            setState { copy(highlightedEventId = correctedEventId) }
        }
        _viewEvents.post(RoomDetailViewEvents.NavigateToEvent(correctedEventId))
    }

    private fun handleResendEvent(action: RoomDetailAction.ResendMessage) {
        val targetEventId = action.eventId
        room.getTimeLineEvent(targetEventId)?.let {
            // State must be UNDELIVERED or Failed
            if (!it.root.sendState.hasFailed()) {
                Timber.e("Cannot resend message, it is not failed, Cancel first")
                return
            }
            when {
                it.root.isTextMessage()  -> room.resendTextMessage(it)
                it.root.isImageMessage() -> room.resendMediaMessage(it)
                else                     -> {
                    // TODO
                }
            }
        }
    }

    private fun handleRemove(action: RoomDetailAction.RemoveFailedEcho) {
        val targetEventId = action.eventId
        room.getTimeLineEvent(targetEventId)?.let {
            // State must be UNDELIVERED or Failed
            if (!it.root.sendState.hasFailed()) {
                Timber.e("Cannot resend message, it is not failed, Cancel first")
                return
            }
            room.deleteFailedEcho(it)
        }
    }

    private fun handleClearSendQueue() {
        room.clearSendingQueue()
    }

    private fun handleResendAll() {
        room.resendAllFailedMessages()
    }

    private fun observeEventDisplayedActions() {
        // We are buffering scroll events for one second
        // and keep the most recent one to set the read receipt on.
        visibleEventsObservable
                .buffer(1, TimeUnit.SECONDS)
                .filter { it.isNotEmpty() }
                .subscribeBy(onNext = { actions ->
                    val bufferedMostRecentDisplayedEvent = actions.maxBy { it.event.displayIndex }?.event ?: return@subscribeBy
                    val globalMostRecentDisplayedEvent = mostRecentDisplayedEvent
                    if (trackUnreadMessages.get()) {
                        if (globalMostRecentDisplayedEvent == null) {
                            mostRecentDisplayedEvent = bufferedMostRecentDisplayedEvent
                        } else if (bufferedMostRecentDisplayedEvent.displayIndex > globalMostRecentDisplayedEvent.displayIndex) {
                            mostRecentDisplayedEvent = bufferedMostRecentDisplayedEvent
                        }
                    }
                    bufferedMostRecentDisplayedEvent.root.eventId?.let { eventId ->
                        room.setReadReceipt(eventId, callback = NoOpMatrixCallback())
                    }
                })
                .disposeOnClear()
    }

    private fun handleMarkAllAsRead() {
        room.markAsRead(ReadService.MarkAsReadParams.BOTH, NoOpMatrixCallback())
    }

    private fun handleReportContent(action: RoomDetailAction.ReportContent) {
        room.reportContent(action.eventId, -100, action.reason, object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
            }

            override fun onFailure(failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ActionFailure(action, failure))
            }
        })
    }

    private fun handleIgnoreUser(action: RoomDetailAction.IgnoreUser) {
        if (action.userId.isNullOrEmpty()) {
            return
        }

        session.ignoreUserIds(listOf(action.userId), object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
            }

            override fun onFailure(failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ActionFailure(action, failure))
            }
        })
    }

    private fun handleAcceptVerification(action: RoomDetailAction.AcceptVerificationRequest) {
        Timber.v("## SAS handleAcceptVerification ${action.otherUserId},  roomId:${room.roomId}, txId:${action.transactionId}")
        if (session.cryptoService().verificationService().readyPendingVerificationInDMs(
                        supportedVerificationMethods,
                        action.otherUserId,
                        room.roomId,
                        action.transactionId)) {
            _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
        } else {
            // TODO
        }
    }

    private fun handleDeclineVerification(action: RoomDetailAction.DeclineVerificationRequest) {
        session.cryptoService().verificationService().declineVerificationRequestInDMs(
                action.otherUserId,
                action.transactionId,
                room.roomId)
    }

    private fun handleRequestVerification(action: RoomDetailAction.RequestVerification) {
        if (action.userId == session.myUserId) return
        _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
    }

    private fun handleResumeRequestVerification(action: RoomDetailAction.ResumeVerification) {
        // Check if this request is still active and handled by me
        session.cryptoService().verificationService().getExistingVerificationRequestInRoom(room.roomId, action.transactionId)?.let {
            if (it.handledByOtherSession) return
            if (!it.isFinished) {
                _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action.copy(
                        otherUserId = it.otherUserId
                )))
            }
        }
    }

    private fun handleReplyToOptions(action: RoomDetailAction.ReplyToOptions) {
        room.sendOptionsReply(action.eventId, action.optionIndex, action.optionValue)
    }

    private fun observeSyncState() {
        session.rx()
                .liveSyncState()
                .subscribe { syncState ->
                    setState {
                        copy(syncState = syncState)
                    }
                }
                .disposeOnClear()
    }

    private fun observeRoomSummary() {
        room.rx().liveRoomSummary()
                .unwrap()
                .execute { async ->
                    val typingRoomMembers =
                            typingHelper.toTypingRoomMembers(async.invoke()?.typingRoomMemberIds.orEmpty(), room)

                    copy(
                            asyncRoomSummary = async,
                            typingRoomMembers = typingRoomMembers,
                            typingMessage = typingHelper.toTypingMessage(typingRoomMembers)
                    )
                }
    }

    private fun getUnreadState() {
        Observable
                .combineLatest<List<TimelineEvent>, RoomSummary, UnreadState>(
                        timelineEvents.observeOn(Schedulers.computation()),
                        room.rx().liveRoomSummary().unwrap(),
                        BiFunction { timelineEvents, roomSummary ->
                            computeUnreadState(timelineEvents, roomSummary)
                        }
                )
                // We don't want live update of unread so we skip when we already had a HasUnread or HasNoUnread
                .distinctUntilChanged { previous, current ->
                    when {
                        previous is UnreadState.Unknown || previous is UnreadState.ReadMarkerNotLoaded -> false
                        current is UnreadState.HasUnread || current is UnreadState.HasNoUnread         -> true
                        else                                                                           -> false
                    }
                }
                .subscribe {
                    setState { copy(unreadState = it) }
                }
                .disposeOnClear()
    }

    private fun computeUnreadState(events: List<TimelineEvent>, roomSummary: RoomSummary): UnreadState {
        if (events.isEmpty()) return UnreadState.Unknown
        val readMarkerIdSnapshot = roomSummary.readMarkerId ?: return UnreadState.Unknown
        val firstDisplayableEventId = timeline.getFirstDisplayableEventId(readMarkerIdSnapshot)
        val firstDisplayableEventIndex = timeline.getIndexOfEvent(firstDisplayableEventId)
        if (firstDisplayableEventId == null || firstDisplayableEventIndex == null) {
            return if (timeline.isLive) {
                UnreadState.ReadMarkerNotLoaded(readMarkerIdSnapshot)
            } else {
                UnreadState.Unknown
            }
        }
        for (i in (firstDisplayableEventIndex - 1) downTo 0) {
            val timelineEvent = events.getOrNull(i) ?: return UnreadState.Unknown
            val eventId = timelineEvent.root.eventId ?: return UnreadState.Unknown
            val isFromMe = timelineEvent.root.senderId == session.myUserId
            if (!isFromMe) {
                return UnreadState.HasUnread(eventId)
            }
        }
        return UnreadState.HasNoUnread
    }

    private fun observeUnreadState() {
        selectSubscribe(RoomDetailViewState::unreadState) {
            Timber.v("Unread state: $it")
            if (it is UnreadState.HasNoUnread) {
                startTrackingUnreadMessages()
            }
        }
    }

    private fun observeSummaryState() {
        asyncSubscribe(RoomDetailViewState::asyncRoomSummary) { summary ->
            if (summary.membership == Membership.INVITE) {
                summary.inviterId?.let { inviterId ->
                    session.getUser(inviterId)
                }?.also {
                    setState { copy(asyncInviter = Success(it)) }
                }
            }
            room.getStateEvent(EventType.STATE_ROOM_TOMBSTONE, "")?.also {
                setState { copy(tombstoneEvent = it) }
            }
        }
    }

    override fun onTimelineUpdated(snapshot: List<TimelineEvent>) {
        timelineEvents.accept(snapshot)
    }

    override fun onTimelineFailure(throwable: Throwable) {
        // If we have a critical timeline issue, we get back to live.
        timeline.restartWithEventId(null)
        _viewEvents.post(RoomDetailViewEvents.Failure(throwable))
    }

    override fun onNewTimelineEvents(eventIds: List<String>) {
        Timber.v("On new timeline events: $eventIds")
        _viewEvents.post(RoomDetailViewEvents.OnNewTimelineEvents(eventIds))
    }

    override fun onCleared() {
        timeline.dispose()
        timeline.removeAllListeners()
        if (vectorPreferences.sendTypingNotifs()) {
            room.userStopsTyping()
        }
        super.onCleared()
    }
}
