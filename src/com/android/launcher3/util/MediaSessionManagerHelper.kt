/*
 * Copyright (C) 2023-2024 The risingOS Android Project
 * Copyright (C) 2025 The AxionAOSP Project
 * Copyright (C) 2026 YAAP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.util

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.annotation.MainThread

/** Main-thread-owned media subscription, active only while QuickSpace has visible listeners. */
@MainThread
class MediaSessionManagerHelper private constructor(private val context: Context) {
    interface MediaMetadataListener {
        fun onMediaMetadataChanged() {}

        fun onPlaybackStateChanged() {}
    }

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listeners = mutableSetOf<MediaMetadataListener>()
    private val controllers = mutableListOf<MediaController>()
    private var tracking = false
    private var activeController: MediaController? = null
    private var metadata: MediaMetadata? = null
    private var playing = false
    private var appIcon: Drawable? = null
    private var iconPackage: String? = null
    private var lastPackage: String? = null
    private var generation = 0
    private val callback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = update()

            override fun onPlaybackStateChanged(state: PlaybackState?) = update()

            override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = update()

            override fun onSessionDestroyed() = refresh(null)
        }
    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { refresh(it) }

    private fun start() {
        if (tracking || manager == null) return
        try {
            manager.addOnActiveSessionsChangedListener(
                sessionsChanged,
                null,
                Executors.MAIN_EXECUTOR.handler,
            )
            tracking = true
            refresh(manager.getActiveSessions(null))
        } catch (e: RuntimeException) {
            stop()
        }
    }

    private fun stop() {
        generation++
        if (tracking)
            runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        tracking = false
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers.clear()
        activeController = null
        metadata = null
        playing = false
        appIcon = null
        iconPackage = null
    }

    private fun refresh(sessions: List<MediaController>?) {
        if (!tracking) return
        val current =
            sessions ?: runCatching { manager?.getActiveSessions(null) }.getOrNull().orEmpty()
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers.clear()
        current.forEach {
            if (
                runCatching { it.registerCallback(callback, Executors.MAIN_EXECUTOR.handler) }
                    .isSuccess
            ) {
                controllers.add(it)
            }
        }
        update()
    }

    private fun update() {
        if (!tracking) return
        val playingControllers =
            controllers.filter {
                runCatching { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    .getOrDefault(false)
            }
        // Determine remote packages first: session ordering must not hide a second local player.
        val remotePackages =
            playingControllers
                .filter {
                    runCatching { it.playbackInfo?.playbackType }.getOrNull() ==
                        MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
                }
                .map { it.packageName }
                .toSet()
        activeController =
            playingControllers.firstOrNull {
                runCatching { it.playbackInfo?.playbackType }.getOrNull() ==
                    MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL &&
                    it.packageName !in remotePackages
            }
        metadata = runCatching { activeController?.metadata }.getOrNull()
        playing = activeController != null
        val pkg = activeController?.packageName
        if (pkg != null) lastPackage = pkg
        if (iconPackage != pkg) {
            iconPackage = pkg
            appIcon = null
            val request = ++generation
            if (pkg != null)
                Executors.MODEL_EXECUTOR.execute {
                    val icon =
                        runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
                    Executors.MAIN_EXECUTOR.execute {
                        if (tracking && generation == request) {
                            appIcon = icon
                            notifyListeners()
                        }
                    }
                }
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.toList().forEach {
            it.onMediaMetadataChanged()
            it.onPlaybackStateChanged()
        }
    }

    fun addMediaMetadataListener(listener: MediaMetadataListener) {
        if (!listeners.add(listener)) return
        if (listeners.size == 1) start()
        listener.onMediaMetadataChanged()
        listener.onPlaybackStateChanged()
    }

    fun removeMediaMetadataListener(listener: MediaMetadataListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) stop()
    }

    fun getCurrentMediaMetadata(): MediaMetadata? = metadata

    fun isMediaPlaying(): Boolean = playing

    fun getMediaAppIcon(): Drawable? = appIcon

    fun toggleMediaPlaybackState() {
        val controller = activeController ?: return
        runCatching {
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        }
    }

    fun launchMediaApp() {
        val pkg = lastPackage ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        @Volatile private var instance: MediaSessionManagerHelper? = null

        fun getInstance(context: Context): MediaSessionManagerHelper =
            instance
                ?: synchronized(this) {
                    instance
                        ?: MediaSessionManagerHelper(context.applicationContext).also {
                            instance = it
                        }
                }
    }
}
