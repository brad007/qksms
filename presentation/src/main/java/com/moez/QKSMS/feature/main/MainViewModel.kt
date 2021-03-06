/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.feature.main

import com.moez.QKSMS.R
import com.moez.QKSMS.common.Navigator
import com.moez.QKSMS.common.androidxcompat.scope
import com.moez.QKSMS.common.base.QkViewModel
import com.moez.QKSMS.extensions.removeAccents
import com.moez.QKSMS.interactor.DeleteConversations
import com.moez.QKSMS.interactor.MarkAllSeen
import com.moez.QKSMS.interactor.MarkArchived
import com.moez.QKSMS.interactor.MarkBlocked
import com.moez.QKSMS.interactor.MarkRead
import com.moez.QKSMS.interactor.MarkUnarchived
import com.moez.QKSMS.interactor.MarkUnread
import com.moez.QKSMS.interactor.MigratePreferences
import com.moez.QKSMS.interactor.SyncMessages
import com.moez.QKSMS.manager.PermissionManager
import com.moez.QKSMS.manager.RatingManager
import com.moez.QKSMS.model.SyncLog
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.repository.SyncRepository
import com.uber.autodispose.kotlin.autoDisposable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.withLatestFrom
import io.reactivex.schedulers.Schedulers
import io.realm.Realm
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MainViewModel @Inject constructor(
        private val markAllSeen: MarkAllSeen,
        private val conversationRepo: ConversationRepository,
        private val deleteConversations: DeleteConversations,
        private val markRead: MarkRead,
        private val markUnread: MarkUnread,
        private val markArchived: MarkArchived,
        private val markUnarchived: MarkUnarchived,
        private val markBlocked: MarkBlocked,
        private val migratePreferences: MigratePreferences,
        private val navigator: Navigator,
        private val permissionManager: PermissionManager,
        private val ratingManager: RatingManager,
        private val syncMessages: SyncMessages,
        private val syncRepository: SyncRepository
) : QkViewModel<MainView, MainState>(MainState(page = Inbox(data = conversationRepo.getConversations()))) {

    init {
        disposables += deleteConversations
        disposables += markAllSeen
        disposables += markArchived
        disposables += markUnarchived
        disposables += migratePreferences
        disposables += syncMessages

        // Show the syncing UI
        disposables += syncRepository.syncProgress
                .distinctUntilChanged()
                .subscribe { syncing -> newState { copy(syncing = syncing) } }


        // Show the rating UI
        disposables += ratingManager.shouldShowRating
                .subscribe { show -> newState { copy(showRating = show) } }


        // Migrate the preferences from 2.7.3
        migratePreferences.execute(Unit)


        // If we have all permissions and we've never run a sync, run a sync. This will be the case
        // when upgrading from 2.7.3, or if the app's data was cleared
        val lastSync = Realm.getDefaultInstance().use { realm -> realm.where(SyncLog::class.java)?.max("date") ?: 0 }
        if (lastSync == 0 && permissionManager.isDefaultSms() && permissionManager.hasSmsAndContacts()) {
            syncMessages.execute(Unit)
        }

        ratingManager.addSession()
        markAllSeen.execute(Unit)
    }

    override fun bindView(view: MainView) {
        super.bindView(view)

        if (!permissionManager.hasSmsAndContacts()) {
            view.requestPermissions()
        }

        // If the default SMS state or permission states change, update the ViewState
        Observables.combineLatest(
                view.activityResumedIntent.map { permissionManager.isDefaultSms() }.distinctUntilChanged(),
                view.activityResumedIntent.map { permissionManager.hasSms() }.distinctUntilChanged(),
                view.activityResumedIntent.map { permissionManager.hasContacts() }.distinctUntilChanged())
        { defaultSms, smsPermission, contactPermission ->
            newState { copy(defaultSms = defaultSms, smsPermission = smsPermission, contactPermission = contactPermission) }
        }
                .autoDisposable(view.scope())
                .subscribe()

        // If the SMS permission state changes from false to true, sync messages
        view.activityResumedIntent
                .map { permissionManager.hasSms() }
                .distinctUntilChanged()
                .skip(1)
                .filter { hasSms -> hasSms }
                .take(1)
                .autoDisposable(view.scope())
                .subscribe {
                    syncMessages.execute(Unit)
                    if (!permissionManager.isDefaultSms()) {
                        navigator.showDefaultSmsDialog()
                    }
                }

        view.queryChangedIntent
                .debounce(200, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .map { query -> query.removeAccents() }
                .withLatestFrom(state) { query, state ->
                    if (query.isEmpty() && state.page is Searching) {
                        newState { copy(page = Inbox(data = conversationRepo.getConversations())) }
                    }
                    query
                }
                .filter { query -> query.length >= 2 }
                .doOnNext {
                    newState {
                        val page = (page as? Searching) ?: Searching()
                        copy(page = page.copy(loading = true))
                    }
                }
                .observeOn(Schedulers.io())
                .switchMap { query -> Observable.just(query).map { conversationRepo.searchConversations(it) } }
                .autoDisposable(view.scope())
                .subscribe { data -> newState { copy(page = Searching(loading = false, data = data)) } }

        view.composeIntent
                .autoDisposable(view.scope())
                .subscribe { navigator.showCompose() }

        view.homeIntent
                .withLatestFrom(state) { _, state ->
                    when {
                        state.page is Searching -> view.clearSearch()

                        state.page is Inbox && state.page.selected > 0 -> view.clearSelection()
                        state.page is Inbox && state.page.showClearButton -> view.clearSearch()

                        state.page is Archived && state.page.selected > 0 -> view.clearSelection()

                        else -> newState { copy(drawerOpen = true) }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.drawerOpenIntent
                .autoDisposable(view.scope())
                .subscribe { open -> newState { copy(drawerOpen = open) } }

        view.drawerItemIntent
                .doOnNext { newState { copy(drawerOpen = false) } }
                .doOnNext { if (it == DrawerItem.SCHEDULED) navigator.showScheduled() }
                .doOnNext { if (it == DrawerItem.BLOCKING) navigator.showBlockedConversations() }
                .doOnNext { if (it == DrawerItem.SETTINGS) navigator.showSettings() }
                .doOnNext { if (it == DrawerItem.PLUS) navigator.showQksmsPlusActivity() }
                .doOnNext { if (it == DrawerItem.HELP) navigator.showSupport() }
                .distinctUntilChanged()
                .doOnNext {
                    when (it) {
                        DrawerItem.INBOX -> newState { copy(page = Inbox(data = conversationRepo.getConversations())) }
                        DrawerItem.ARCHIVED -> newState { copy(page = Archived(data = conversationRepo.getConversations(true))) }
                        else -> {
                        } // Do nothing
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .withLatestFrom(view.conversationsSelectedIntent) { itemId, conversations ->
                    when (itemId) {
                        R.id.read -> {
                            markRead.execute(conversations)
                            view.clearSelection()
                        }

                        R.id.unread -> {
                            markUnread.execute(conversations)
                            view.clearSelection()
                        }

                        R.id.archive -> {
                            markArchived.execute(conversations)
                            view.clearSelection()
                        }

                        R.id.unarchive -> {
                            markUnarchived.execute(conversations)
                            view.clearSelection()
                        }

                        R.id.block -> {
                            markBlocked.execute(conversations)
                            view.clearSelection()
                        }

                        R.id.delete -> view.showDeleteDialog()
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.rateIntent
                .autoDisposable(view.scope())
                .subscribe {
                    navigator.showRating()
                    ratingManager.rate()
                }

        view.dismissRatingIntent
                .autoDisposable(view.scope())
                .subscribe { ratingManager.dismiss() }

        view.conversationsSelectedIntent
                .withLatestFrom(state) { selection, state ->
                    val read = selection
                            .mapNotNull(conversationRepo::getConversation)
                            .sumBy { if (it.read) -1 else 1 } >= 0
                    val selected = selection.size

                    when (state.page) {
                        is Inbox -> {
                            val page = state.page.copy(markRead = read, selected = selected, showClearButton = selected > 0)
                            newState { copy(page = page.copy(markRead = read, selected = selected, showClearButton = selected > 0)) }
                        }

                        is Archived -> {
                            val page = state.page.copy(markRead = read, selected = selected, showClearButton = selected > 0)
                            newState { copy(page = page) }
                        }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        // Delete the conversation
        view.confirmDeleteIntent
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations -> conversations }
                .autoDisposable(view.scope())
                .subscribe { conversations ->
                    deleteConversations.execute(conversations)
                    view.clearSelection()
                }

        view.swipeConversationIntent
                .withLatestFrom(state) { threadId, state ->
                    markArchived.execute(listOf(threadId)) {
                        if (state.page is Inbox) {
                            val page = state.page.copy(showArchivedSnackbar = true)
                            newState { copy(page = page) }
                        }
                    }
                }
                .switchMap { Observable.timer(2750, TimeUnit.MILLISECONDS) }
                .withLatestFrom(state) { threadId, state ->
                    markArchived.execute(listOf(threadId)) {
                        if (state.page is Inbox) {
                            val page = state.page.copy(showArchivedSnackbar = false)
                            newState { copy(page = page) }
                        }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.undoSwipeConversationIntent
                .withLatestFrom(view.swipeConversationIntent) { _, threadId -> threadId }
                .autoDisposable(view.scope())
                .subscribe { threadId -> markUnarchived.execute(listOf(threadId)) }

        view.snackbarButtonIntent
                .withLatestFrom(state) { _, state ->
                    when {
                        !state.smsPermission -> view.requestPermissions()
                        !state.defaultSms -> navigator.showDefaultSmsDialog()
                        !state.contactPermission -> view.requestPermissions()
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.backPressedIntent
                .withLatestFrom(state) { _, state ->
                    when {
                        state.drawerOpen -> newState { copy(drawerOpen = false) }

                        state.page is Searching -> view.clearSearch()

                        state.page is Inbox && state.page.selected > 0 -> view.clearSelection()
                        state.page is Inbox && state.page.showClearButton -> view.clearSearch()

                        state.page is Archived && state.page.selected > 0 -> view.clearSelection()

                        state.page !is Inbox -> newState { copy(page = Inbox(data = conversationRepo.getConversations())) }

                        else -> newState { copy(hasError = true) }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()
    }

}