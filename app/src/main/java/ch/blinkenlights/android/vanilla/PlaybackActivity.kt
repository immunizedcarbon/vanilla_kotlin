/*
 * Copyright (C) 2010, 2011 Christopher Eby <kreed@kreed.org>
 * Copyright (C) 2014-2016 Adrian Ulrich <adrian@blinkenlights.ch>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package ch.blinkenlights.android.vanilla

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process
import android.util.Log
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import java.io.File
import java.util.ArrayList

/**
 * Base activity for activities that contain playback controls. Handles
 * communication with the PlaybackService and response to state and song
 * changes.
 */
abstract class PlaybackActivity : Activity(),
    TimelineCallback,
    Handler.Callback,
    View.OnClickListener,
    CoverView.Callback {

    private lateinit var mUpAction: Action
    private lateinit var mDownAction: Action

    /**
     * A Handler running on the UI thread, in contrast with mHandler which runs
     * on a worker thread.
     */
    protected val mUiHandler: Handler = Handler(this)

    /**
     * A Handler running on a worker thread.
     */
    protected lateinit var mHandler: Handler

    /**
     * The looper for the worker thread.
     */
    protected lateinit var mLooper: Looper

    protected var mCoverView: CoverView? = null
    protected var mPlayPauseButton: ImageButton? = null
    protected var mShuffleButton: ImageButton? = null
    protected var mEndButton: ImageButton? = null

    protected var mState = 0
    private var mLastStateEvent = 0L
    private var mLastSongEvent = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        PlaybackService.addTimelineCallback(this)

        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        val thread = HandlerThread(javaClass.name, Process.THREAD_PRIORITY_LOWEST)
        thread.start()

        mLooper = thread.looper
        mHandler = Handler(mLooper, this)
    }

    override fun onDestroy() {
        PlaybackService.removeTimelineCallback(this)
        mLooper.quit()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()

        if (PlaybackService.hasInstance()) {
            onServiceReady()
        } else {
            startService(Intent(this, PlaybackService::class.java))
        }

        val prefs: SharedPreferences = SharedPrefHelper.getSettings(this)
        mUpAction = Action.getAction(prefs, PrefKeys.SWIPE_UP_ACTION, PrefDefaults.SWIPE_UP_ACTION)
        mDownAction = Action.getAction(prefs, PrefKeys.SWIPE_DOWN_ACTION, PrefDefaults.SWIPE_DOWN_ACTION)

        val window: Window = window

        // Set lockscreen preference
        if (prefs.getBoolean(PrefKeys.DISABLE_LOCKSCREEN, PrefDefaults.DISABLE_LOCKSCREEN)) {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            )
        } else {
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            )
        }

        // Set screen-on preference
        if (prefs.getBoolean(PrefKeys.KEEP_SCREEN_ON, PrefDefaults.KEEP_SCREEN_ON)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onResume() {
        super.onResume()
        if (PlaybackService.hasInstance()) {
            val service = PlaybackService.get(this)
            service.userActionTriggered()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return MediaButtonReceiver.processKey(this, event)
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return MediaButtonReceiver.processKey(this, event)
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun shiftCurrentSong(delta: Int) {
        setSong(PlaybackService.get(this).shiftCurrentSong(delta))
    }

    fun playPause() {
        val service = PlaybackService.get(this)
        val state = service.playPause(false)
        if (state and PlaybackService.FLAG_ERROR != 0) {
            showToast(service.errorMessage, Toast.LENGTH_LONG)
        }
        setState(state)
    }

    private fun rewindCurrentSong() {
        setSong(PlaybackService.get(this).rewindCurrentSong())
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.next -> shiftCurrentSong(SongTimeline.SHIFT_NEXT_SONG)
            R.id.play_pause -> playPause()
            R.id.previous -> rewindCurrentSong()
            R.id.end_action -> cycleFinishAction()
            R.id.shuffle -> cycleShuffle()
        }
    }

    /**
     * Called when the PlaybackService state has changed.
     *
     * @param state PlaybackService state
     * @param toggled The flags that have changed from the previous state
     */
    protected open fun onStateChange(state: Int, toggled: Int) {
        if (toggled and PlaybackService.FLAG_PLAYING != 0 && mPlayPauseButton != null) {
            mPlayPauseButton?.setImageResource(
                if (state and PlaybackService.FLAG_PLAYING == 0) R.drawable.play else R.drawable.pause,
            )
        }
        if (toggled and PlaybackService.MASK_FINISH != 0 && mEndButton != null) {
            mEndButton?.setImageResource(SongTimeline.FINISH_ICONS[PlaybackService.finishAction(state)])
        }
        if (toggled and PlaybackService.MASK_SHUFFLE != 0 && mShuffleButton != null) {
            mShuffleButton?.setImageResource(SongTimeline.SHUFFLE_ICONS[PlaybackService.shuffleMode(state)])
        }
    }

    protected fun setState(state: Int) {
        mLastStateEvent = System.nanoTime()

        if (mState != state) {
            val toggled = mState xor state
            mState = state
            runOnUiThread { onStateChange(state, toggled) }
        }
    }

    /**
     * Called by PlaybackService to update the state.
     */
    fun setState(uptime: Long, state: Int) {
        if (uptime > mLastStateEvent) {
            setState(state)
            mLastStateEvent = uptime
        }
    }

    /**
     * Sets up components when the PlaybackService is initialized and available to
     * interact with. Override to implement further post-initialization behavior.
     */
    protected open fun onServiceReady() {
        val service = PlaybackService.get(this)
        setSong(service.getSong(0))
        setState(service.state)
    }

    /**
     * Called when the current song changes.
     *
     * @param song The new song
     */
    protected open fun onSongChange(song: Song?) {
        mCoverView?.querySongs()
    }

    protected fun setSong(song: Song?) {
        mLastSongEvent = System.nanoTime()
        runOnUiThread { onSongChange(song) }
    }

    /**
     * Sets up onClick listeners for our common control buttons bar
     */
    protected open fun bindControlButtons() {
        val previousButton = findViewById<View>(R.id.previous)
        previousButton.setOnClickListener(this)
        mPlayPauseButton = findViewById<ImageButton>(R.id.play_pause).apply {
            setOnClickListener(this@PlaybackActivity)
        }
        val nextButton = findViewById<View>(R.id.next)
        nextButton.setOnClickListener(this)

        mShuffleButton = findViewById<ImageButton>(R.id.shuffle).apply {
            setOnClickListener(this@PlaybackActivity)
        }
        mShuffleButton?.let { registerForContextMenu(it) }
        mEndButton = findViewById<ImageButton>(R.id.end_action).apply {
            setOnClickListener(this@PlaybackActivity)
        }
        mEndButton?.let { registerForContextMenu(it) }
    }

    /**
     * Called by PlaybackService to update the current song.
     */
    fun setSong(uptime: Long, song: Song?) {
        if (uptime > mLastSongEvent) {
            setSong(song)
            mLastSongEvent = uptime
        }
    }

    /**
     * Called by PlaybackService to update an active song (next, previous, or
     * current).
     */
    override fun replaceSong(delta: Int, song: Song?) {
        mCoverView?.replaceSong(delta, song)
    }

    /**
     * Called when the song timeline position/size has changed.
     */
    override fun onPositionInfoChanged() = Unit

    /**
     * Called when the content of the media store has changed.
     */
    override fun onMediaChange() = Unit

    /**
     * Called when the timeline change has changed.
     */
    override fun onTimelineChanged() = Unit

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_PREFS, 10, R.string.settings).setIcon(R.drawable.ic_menu_preferences)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_PREFS -> startActivity(Intent(this, PreferencesActivity::class.java))
            MENU_CLEAR_QUEUE -> PlaybackService.get(this).clearQueue()
            MENU_EMPTY_QUEUE -> PlaybackService.get(this).emptyQueue()
            else -> return false
        }
        return true
    }

    override fun handleMessage(message: Message): Boolean {
        when (message.what) {
            MSG_CREATE_PLAYLIST -> {
                val playlistTask = message.obj as PlaylistTask
                val nextAction = message.arg1
                val playlistId = Playlist.createPlaylist(this, playlistTask.name)
                playlistTask.playlistId = playlistId
                mHandler.sendMessage(mHandler.obtainMessage(nextAction, playlistTask))
            }

            MSG_ADD_TO_PLAYLIST -> {
                val playlistTask = message.obj as PlaylistTask
                addToPlaylist(playlistTask)
            }

            MSG_ADD_QUEUE_TO_PLAYLIST -> {
                val playlistTask = message.obj as PlaylistTask
                playlistTask.audioIds = ArrayList()
                val service = PlaybackService.get(this)
                var i = 0
                while (true) {
                    val song = service.getSongByQueuePosition(i) ?: break
                    playlistTask.audioIds.add(song.id)
                    i++
                }
                addToPlaylist(playlistTask)
            }

            MSG_REMOVE_FROM_PLAYLIST -> {
                val playlistTask = message.obj as PlaylistTask
                removeFromPlaylist(playlistTask)
            }

            MSG_RENAME_PLAYLIST -> {
                val playlistTask = message.obj as PlaylistTask
                Playlist.renamePlaylist(applicationContext, playlistTask.playlistId, playlistTask.name)
            }

            MSG_DELETE -> delete(message.obj as Intent)

            MSG_NOTIFY_PLAYLIST_CHANGED -> Unit

            else -> return false
        }
        return true
    }

    /**
     * Add a set of songs represented by the playlistTask to a playlist. Displays a
     * Toast notifying of success.
     *
     * @param playlistTask The pending PlaylistTask to execute
     */
    protected fun addToPlaylist(playlistTask: PlaylistTask) {
        var count = 0

        playlistTask.query?.let {
            count += Playlist.addToPlaylist(this, playlistTask.playlistId, it)
        }

        playlistTask.audioIds?.let {
            count += Playlist.addToPlaylist(this, playlistTask.playlistId, it)
        }

        val message = resources.getQuantityString(
            R.plurals.added_to_playlist,
            count,
            count,
            playlistTask.name,
        )
        showToast(message, Toast.LENGTH_SHORT)
        mHandler.sendEmptyMessage(MSG_NOTIFY_PLAYLIST_CHANGED)
    }

    /**
     * Removes a set of songs represented by the playlistTask from a playlist. Displays a
     * Toast notifying of success.
     *
     * @param playlistTask The pending PlaylistTask to execute
     */
    private fun removeFromPlaylist(playlistTask: PlaylistTask) {
        var count = 0

        if (playlistTask.query != null) {
            throw IllegalArgumentException("Delete by query is not implemented yet")
        }

        playlistTask.audioIds?.let {
            count += Playlist.removeFromPlaylist(applicationContext, playlistTask.playlistId, it)
        }

        val message = resources.getQuantityString(
            R.plurals.removed_from_playlist,
            count,
            count,
            playlistTask.name,
        )
        showToast(message, Toast.LENGTH_SHORT)
        mHandler.sendEmptyMessage(MSG_NOTIFY_PLAYLIST_CHANGED)
    }

    /**
     * Delete the media represented by the given intent and show a Toast
     * informing the user of this.
     *
     * @param intent An intent created with
     * [LibraryAdapter.createData].
     */
    private fun delete(intent: Intent) {
        val type = intent.getIntExtra("type", MediaUtils.TYPE_INVALID)
        val id = intent.getLongExtra("id", LibraryAdapter.INVALID_ID)
        var message: String? = null
        val res: Resources = resources

        if (type == MediaUtils.TYPE_FILE) {
            val file = intent.getStringExtra("file") ?: ""
            val success = MediaUtils.deleteFile(File(file))
            if (!success) {
                message = res.getString(R.string.delete_file_failed, file)
            }
        } else if (type == MediaUtils.TYPE_PLAYLIST) {
            Playlist.deletePlaylist(this, id)
        } else {
            val count = PlaybackService.get(this).deleteMedia(type, id)
            message = res.getQuantityString(R.plurals.deleted, count, count)
        }

        if (message == null) {
            message = res.getString(R.string.deleted_item, intent.getStringExtra("title"))
        }

        showToast(message, Toast.LENGTH_SHORT)
    }

    /**
     * Creates and displays a new toast message
     */
    private fun showToast(message: String, duration: Int) {
        runOnUiThread {
            Toast.makeText(applicationContext, message, duration).show()
        }
    }

    /**
     * Cycle shuffle mode.
     */
    fun cycleShuffle() {
        setState(PlaybackService.get(this).cycleShuffle())
    }

    /**
     * Cycle the finish action.
     */
    fun cycleFinishAction() {
        setState(PlaybackService.get(this).cycleFinishAction())
    }

    /**
     * Open the library activity.
     *
     * @param song If non-null, will open the library focused on this song.
     * @param type the media type to switch to for 'song'
     */
    fun openLibrary(song: Song?, type: Int) {
        val intent = Intent(this, LibraryActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (song != null) {
            var id = -1L
            var folder: String? = null
            when (type) {
                MediaUtils.TYPE_ARTIST -> id = song.artistId
                MediaUtils.TYPE_ALBUM -> id = song.albumId
                MediaUtils.TYPE_GENRE -> id = MediaUtils.queryGenreForSong(this, song.id)
                MediaUtils.TYPE_FILE -> folder = File(song.path).parent
                else -> throw IllegalArgumentException("Invalid media type $type")
            }
            intent.putExtra("type", type)
            intent.putExtra("id", id)
            intent.putExtra("folder", folder)
        }
        startActivity(intent)
    }

    override fun upSwipe() {
        performAction(mUpAction)
    }

    override fun downSwipe() {
        performAction(mDownAction)
    }

    protected fun performAction(action: Action) {
        PlaybackService.get(this).performAction(action, this)
    }

    override fun onCreateContextMenu(menu: ContextMenu, view: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        if (view == mShuffleButton) {
            menu.add(CTX_MENU_GRP_SHUFFLE, SongTimeline.SHUFFLE_NONE, 0, R.string.no_shuffle)
            menu.add(CTX_MENU_GRP_SHUFFLE, SongTimeline.SHUFFLE_SONGS, 0, R.string.shuffle_songs)
            menu.add(CTX_MENU_GRP_SHUFFLE, SongTimeline.SHUFFLE_ALBUMS, 0, R.string.shuffle_albums)
        } else if (view == mEndButton) {
            menu.add(CTX_MENU_GRP_FINISH, SongTimeline.FINISH_STOP, 0, R.string.no_repeat)
            menu.add(CTX_MENU_GRP_FINISH, SongTimeline.FINISH_REPEAT, 0, R.string.repeat)
            menu.add(CTX_MENU_GRP_FINISH, SongTimeline.FINISH_REPEAT_CURRENT, 0, R.string.repeat_current_song)
            menu.add(CTX_MENU_GRP_FINISH, SongTimeline.FINISH_STOP_CURRENT, 0, R.string.stop_current_song)
            menu.add(CTX_MENU_GRP_FINISH, SongTimeline.FINISH_RANDOM, 0, R.string.random)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val group = item.groupId
        val id = item.itemId
        if (group == CTX_MENU_GRP_SHUFFLE) {
            setState(PlaybackService.get(this).setShuffleMode(id))
        } else if (group == CTX_MENU_GRP_FINISH) {
            setState(PlaybackService.get(this).setFinishAction(id))
        }
        return true
    }

    /**
     * Queries all plugin packages and shows plugin selection dialog.
     * @param intent intent with a song to send to plugins
     */
    protected fun showPluginMenu(intent: Intent) {
        val plugins: Map<String, ApplicationInfo> = PluginUtils.getPluginMap(this)
        val pluginNames = plugins.keys.toTypedArray()

        AlertDialog.Builder(this)
            .setItems(pluginNames) { _: DialogInterface, which: Int ->
                val id = intent.getLongExtra("id", LibraryAdapter.INVALID_ID)
                val song = MediaUtils.getSongByTypeId(this@PlaybackActivity, MediaUtils.TYPE_SONG, id)
                if (song != null) {
                    val selected = plugins[pluginNames[which]] ?: return@setItems
                    val request = Intent(PluginUtils.ACTION_LAUNCH_PLUGIN)
                    request.setPackage(selected?.packageName)
                    request.putExtra(PluginUtils.EXTRA_PARAM_URI, Uri.fromFile(File(song.path)))
                    request.putExtra(PluginUtils.EXTRA_PARAM_SONG_TITLE, song.title)
                    request.putExtra(PluginUtils.EXTRA_PARAM_SONG_ARTIST, song.artist)
                    request.putExtra(PluginUtils.EXTRA_PARAM_SONG_ALBUM, song.album)
                    if (request.resolveActivity(packageManager) != null) {
                        startActivity(request)
                    } else {
                        Log.e("PluginSystem", "Couldn't start plugin activity for $request")
                    }
                }
            }
            .create()
            .show()
    }

    companion object {
        const val MENU_SORT = 1
        const val MENU_PREFS = 2
        const val MENU_PLAYBACK = 3
        const val MENU_SEARCH = 4
        const val MENU_ENQUEUE = 7 // toplevel menu, has no action
        const val MENU_ENQUEUE_ALBUM = 8
        const val MENU_ENQUEUE_ARTIST = 9
        const val MENU_ENQUEUE_GENRE = 10
        const val MENU_CLEAR_QUEUE = 11
        const val MENU_SONG_FAVORITE = 12
        const val MENU_SHOW_QUEUE = 13
        const val MENU_HIDE_QUEUE = 14
        const val MENU_DELETE = 15
        const val MENU_EMPTY_QUEUE = 16
        const val MENU_ADD_TO_PLAYLIST = 17
        const val MENU_SHARE = 18
        const val MENU_GO_HOME = 19
        const val MENU_PLUGINS = 20 // used in FullPlaybackActivity
        const val MENU_MORE = 21 // toplevel menu, has no own action
        const val MENU_MORE_ALBUM = 22
        const val MENU_MORE_ARTIST = 23
        const val MENU_MORE_GENRE = 24
        const val MENU_MORE_FOLDER = 25
        const val MENU_JUMP_TO_TIME = 26

        /**
         * Same as MSG_ADD_TO_PLAYLIST but creates the new playlist on-the-fly (or overwrites an existing list)
         */
        const val MSG_CREATE_PLAYLIST = 0

        /**
         * Call renamePlaylist with the results from a NewPlaylistDialog stored in
         * obj.
         */
        const val MSG_RENAME_PLAYLIST = 1

        /**
         * Call addToPlaylist with data from the playlisttask object.
         */
        const val MSG_ADD_TO_PLAYLIST = 2

        /**
         * Call removeFromPlaylist with data from the playlisttask object.
         */
        const val MSG_REMOVE_FROM_PLAYLIST = 3

        /**
         * Removes a media object
         */
        const val MSG_DELETE = 4

        /**
         * Saves the current queue as a playlist
         */
        const val MSG_ADD_QUEUE_TO_PLAYLIST = 5

        /**
         * Notification that we changed some playlist members
         */
        const val MSG_NOTIFY_PLAYLIST_CHANGED = 6

        private const val CTX_MENU_GRP_SHUFFLE = 200
        private const val CTX_MENU_GRP_FINISH = 201
    }
}
