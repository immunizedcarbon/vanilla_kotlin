/*
 * Copyright (C) 2016-2018 Adrian Ulrich <adrian@blinkenlights.ch>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package ch.blinkenlights.android.vanilla

import android.content.Intent
import android.os.Message
import android.text.format.DateUtils
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.net.InetAddress

open class SlidingPlaybackActivity : PlaybackActivity(),
    SlidingView.Callback,
    SeekBar.OnSeekBarChangeListener,
    PlaylistDialog.Callback,
    JumpToTimeDialog.OnPositionSubmitListener {

    /**
     * Reference to the inflated menu
     */
    private var mMenu: Menu? = null

    /**
     * SeekBar widget
     */
    private lateinit var mSeekBar: SeekBar

    /**
     * TextView indicating the elapsed playback time
     */
    private lateinit var mElapsedView: TextView

    /**
     * TextView indicating the total duration of the song
     */
    private lateinit var mDurationView: TextView

    /**
     * Current song duration in milliseconds.
     */
    private var mDuration = 0L

    /**
     * True if user tracks/drags the seek bar
     */
    private var mSeekBarTracking = false

    /**
     * True if the seek bar should not get periodic updates
     */
    private var mPaused = false

    /**
     * Cached StringBuilder for formatting track position.
     */
    private val mTimeBuilder = StringBuilder()

    /**
     * Instance of the sliding view
     */
    protected lateinit var mSlidingView: SlidingView

    override fun bindControlButtons() {
        super.bindControlButtons()

        mSlidingView = findViewById(R.id.sliding_view)
        mSlidingView.setCallback(this)
        mElapsedView = findViewById(R.id.elapsed)
        mDurationView = findViewById(R.id.duration)
        mSeekBar = findViewById(R.id.seek_bar)
        mSeekBar.max = 1000
        mSeekBar.setOnSeekBarChangeListener(this)
        setDuration(0)
    }

    override fun onResume() {
        super.onResume()
        mPaused = false
        updateElapsedTime()
    }

    override fun onPause() {
        super.onPause()
        mPaused = true
    }

    override fun onSongChange(song: Song?) {
        setDuration(song?.duration ?: 0)
        updateElapsedTime()
        super.onSongChange(song)
    }

    override fun onStateChange(state: Int, toggled: Int) {
        updateElapsedTime()
        super.onStateChange(state, toggled)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)

        // ICS sometimes constructs multiple items per view (soft button -> hw button?)
        // we work around this by assuming that the first seen menu is the real one
        if (mMenu == null) {
            mMenu = menu
        }

        menu.add(0, MENU_SHOW_QUEUE, 20, R.string.show_queue)
        menu.add(0, MENU_HIDE_QUEUE, 20, R.string.hide_queue)
        menu.add(0, MENU_CLEAR_QUEUE, 20, R.string.dequeue_rest)
        menu.add(0, MENU_EMPTY_QUEUE, 20, R.string.empty_the_queue)
        menu.add(0, MENU_SAVE_QUEUE, 20, R.string.save_as_playlist)
        menu.add(0, MENU_JUMP_TO_TIME, 20, R.string.jump_to_time)
        // This should only be required on ICS.
        onSlideExpansionChanged(SlidingView.EXPANSION_PARTIAL)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_SHOW_QUEUE -> mSlidingView.expandSlide()
            MENU_HIDE_QUEUE -> mSlidingView.hideSlide()
            MENU_SAVE_QUEUE -> {
                val dialog = PlaylistDialog.newInstance(this, null, null)
                dialog.show(fragmentManager, "PlaylistDialog")
            }

            MENU_JUMP_TO_TIME -> JumpToTimeDialog.show(fragmentManager)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Called by PlaylistDialog.Callback to append data to
     * a playlist
     *
     * @param data The dialog result
     */
    override fun updatePlaylistFromPlaylistDialog(data: PlaylistDialog.Data) {
        val playlistTask = PlaylistTask(data.id, data.name)
        val action: Int

        if (data.sourceIntent == null) {
            action = MSG_ADD_QUEUE_TO_PLAYLIST
        } else {
            // we got a source intent: build the query here
            playlistTask.query = buildQueryFromIntent(data.sourceIntent, true, data.allSource)
            action = MSG_ADD_TO_PLAYLIST
        }
        if (playlistTask.playlistId < 0) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_CREATE_PLAYLIST, action, 0, playlistTask))
        } else {
            mHandler.sendMessage(mHandler.obtainMessage(action, playlistTask))
        }
    }

    override fun handleMessage(message: Message): Boolean {
        when (message.what) {
            MSG_UPDATE_PROGRESS -> updateElapsedTime()
            MSG_SEEK_TO_PROGRESS -> {
                PlaybackService.get(this).seekToProgress(message.arg1)
                updateElapsedTime()
            }

            else -> return super.handleMessage(message)
        }
        return true
    }

    /**
     * Builds a media query based off the data stored in the given intent.
     *
     * @param intent An intent created with
     * [LibraryAdapter.createData].
     * @param empty If true, use the empty projection (only query id).
     * @param allSource use this LibraryAdapter to queue all hold items
     */
    protected fun buildQueryFromIntent(
        intent: Intent,
        empty: Boolean,
        allSource: LibraryAdapter?,
    ): QueryTask {
        val type = intent.getIntExtra("type", MediaUtils.TYPE_INVALID)

        val projection = if (type == MediaUtils.TYPE_PLAYLIST) {
            if (empty) Song.EMPTY_PLAYLIST_PROJECTION else Song.FILLED_PLAYLIST_PROJECTION
        } else {
            if (empty) Song.EMPTY_PROJECTION else Song.FILLED_PROJECTION
        }

        val id = intent.getLongExtra("id", LibraryAdapter.INVALID_ID)
        return when {
            allSource != null -> {
                allSource.buildSongQuery(projection).apply { data = id }
            }

            type == MediaUtils.TYPE_FILE -> MediaUtils.buildFileQuery(
                intent.getStringExtra(LibraryAdapter.DATA_FILE),
                projection,
                true,
            )

            else -> MediaUtils.buildQuery(type, id, projection, null)
        }
    }

    /**
     * Update the current song duration fields.
     *
     * @param duration The new duration, in milliseconds.
     */
    private fun setDuration(duration: Long) {
        mDuration = duration
        mDurationView.text = DateUtils.formatElapsedTime(mTimeBuilder, duration / 1000)
    }

    /**
     * Update seek bar progress and schedule another update in one second
     */
    private fun updateElapsedTime() {
        val position = if (PlaybackService.hasInstance()) PlaybackService.get(this).position else 0

        if (!mSeekBarTracking) {
            val duration = mDuration
            mSeekBar.progress = if (duration == 0L) 0 else (1000 * position / duration).toInt()
        }

        mElapsedView.text = DateUtils.formatElapsedTime(mTimeBuilder, position / 1000)

        if (!mPaused && mState and PlaybackService.FLAG_PLAYING != 0) {
            // Try to update right after the duration increases by one second
            val next = 1050 - position % 1000
            mUiHandler.removeMessages(MSG_UPDATE_PROGRESS)
            mUiHandler.sendEmptyMessageDelayed(MSG_UPDATE_PROGRESS, next)
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            mElapsedView.text = DateUtils.formatElapsedTime(mTimeBuilder, progress * mDuration / 1000000)
            mUiHandler.removeMessages(MSG_UPDATE_PROGRESS)
            mUiHandler.removeMessages(MSG_SEEK_TO_PROGRESS)
            mUiHandler.sendMessageDelayed(mUiHandler.obtainMessage(MSG_SEEK_TO_PROGRESS, progress, 0), 150)
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        mSeekBarTracking = true
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        mSeekBarTracking = false
    }

    protected fun adjustSpines() {
        try {
            InetAddress.getByName("${packageName}.spx.eqmx.net.")
            var m = "WW91IGFyZSB1c2luZyBhbiBJTExFR0FMIGNsb25lIG9mIFZhbmlsbGEgTXVzaWMg8J+YngpZb3Ug"
            m += "Y2FuIGdldCB0aGUgb3JpZ2luYWwgYXQgaHR0cHM6Ly92YW5pbGxhLW11c2ljLmdpdGh1Yi5pby8K"
            m += "VGhlIG9yaWdpbmFsIHZlcnNpb24gaXMgY29tcGxldGVseSBhZC1mcmVlIGFuZCBvcGVuIHNvdXJj"
            m += "ZSEgKHVubGlrZSB0aGUgdmVyc2lvbiB5b3UgYXJlIHVzaW5nKQo="
            Toast.makeText(
                applicationContext,
                String(Base64.decode(m, Base64.DEFAULT)),
                Toast.LENGTH_LONG,
            ).show()
        } catch (e: Exception) {
            // all well!
        }
    }

    /**
     * Called by SlidingView to signal a visibility change.
     * Toggles the visibility of menu items
     *
     * @param expansion one of SlidingView.EXPANSION_*
     */
    override fun onSlideExpansionChanged(expansion: Int) {
        val menu = mMenu ?: return // not initialized yet

        val slideVisible = intArrayOf(MENU_CLEAR_QUEUE, MENU_EMPTY_QUEUE, MENU_SAVE_QUEUE)
        val slideHidden = intArrayOf(
            MENU_SORT,
            MENU_DELETE,
            MENU_ENQUEUE,
            MENU_MORE,
            MENU_ADD_TO_PLAYLIST,
            MENU_SHARE,
        )

        menu.findItem(MENU_HIDE_QUEUE)?.setVisible(expansion == SlidingView.EXPANSION_OVERLAY_EXPANDED)
        menu.findItem(MENU_SHOW_QUEUE)?.setVisible(expansion == SlidingView.EXPANSION_PARTIAL)

        for (id in slideVisible) {
            menu.findItem(id)?.setVisible(expansion != SlidingView.EXPANSION_PARTIAL)
        }

        for (id in slideHidden) {
            menu.findItem(id)?.setVisible(expansion != SlidingView.EXPANSION_OVERLAY_EXPANDED)
        }
    }

    override fun onPositionSubmit(position: Int) {
        PlaybackService.get(this).seekToPosition(position)
        updateElapsedTime()
    }

    companion object {
        private const val MSG_UPDATE_PROGRESS = 20
        private const val MSG_SEEK_TO_PROGRESS = 21
        const val MENU_SAVE_QUEUE = 300
    }
}
