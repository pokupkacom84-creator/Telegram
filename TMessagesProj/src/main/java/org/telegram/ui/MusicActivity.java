/*
 * This is the source code of FluxGram for Android.
 * MusicActivity — dedicated music tab screen.
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SharedAudioCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class MusicActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    // Section types for the adapter
    private static final int VIEW_TYPE_LIKED_BANNER     = 0;
    private static final int VIEW_TYPE_SECTION_HEADER   = 1;
    private static final int VIEW_TYPE_AUDIO_CELL       = 2;
    private static final int VIEW_TYPE_PLAYLIST_ADD     = 3;
    private static final int VIEW_TYPE_EMPTY_SPACE      = 4;

    private RecyclerListView listView;
    private MusicAdapter adapter;

    // Data
    private ArrayList<MessageObject> recentlyPlayed = new ArrayList<>();
    private ArrayList<MessageObject> fromChats = new ArrayList<>();

    // Section positions (computed in buildRows)
    private int likedBannerRow;
    private int recentlyHeaderRow;
    private int recentlyFirstRow;
    private int recentlyLastRow; // exclusive
    private int playlistsHeaderRow;
    private int playlistsAddRow;
    private int fromChatsHeaderRow;
    private int fromChatsFirstRow;
    private int fromChatsLastRow; // exclusive
    private int totalRows;

    public MusicActivity(Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(getString(R.string.MainTabsMusic));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setClipToPadding(false);
        listView.setAdapter(adapter = new MusicAdapter(context));
        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position >= recentlyFirstRow && position < recentlyLastRow) {
                int index = position - recentlyFirstRow;
                if (index < recentlyPlayed.size()) {
                    playAt(recentlyPlayed, index);
                }
            } else if (position >= fromChatsFirstRow && position < fromChatsLastRow) {
                int index = position - fromChatsFirstRow;
                if (index < fromChats.size()) {
                    playAt(fromChats, index);
                }
            }
        });

        loadData();
        return fragmentView;
    }

    private void playAt(ArrayList<MessageObject> list, int index) {
        MessageObject msg = list.get(index);
        MediaController.getInstance().setPlaylist(list, msg, msg.getDialogId());
    }

    private void loadData() {
        // Recently played: take from MediaController's current playlist (music only)
        recentlyPlayed.clear();
        ArrayList<MessageObject> playlist = MediaController.getInstance().getPlaylist();
        if (playlist != null) {
            for (MessageObject mo : playlist) {
                if (mo.isMusic()) {
                    recentlyPlayed.add(mo);
                    if (recentlyPlayed.size() >= 10) break;
                }
            }
        }

        // "From your chats" — load saved music list
        fromChats.clear();
        MessagesController.SavedMusicList savedList = MediaController.getInstance().getMusicList();
        if (savedList != null) {
            fromChats.addAll(savedList.list);
        }

        buildRows();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void buildRows() {
        int row = 0;
        likedBannerRow = row++;

        recentlyHeaderRow = -1;
        recentlyFirstRow = -1;
        recentlyLastRow  = -1;
        if (!recentlyPlayed.isEmpty()) {
            recentlyHeaderRow = row++;
            recentlyFirstRow  = row;
            row += recentlyPlayed.size();
            recentlyLastRow = row;
        }

        playlistsHeaderRow = row++;
        playlistsAddRow    = row++;

        fromChatsHeaderRow = -1;
        fromChatsFirstRow  = -1;
        fromChatsLastRow   = -1;
        if (!fromChats.isEmpty()) {
            fromChatsHeaderRow = row++;
            fromChatsFirstRow  = row;
            row += fromChats.size();
            fromChatsLastRow = row;
        }

        totalRows = row;
    }

    @Override
    public void onResume() {
        super.onResume();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.musicDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        loadData();
    }

    @Override
    public void onPause() {
        super.onPause();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidFinish);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.musicDidLoad ||
            id == NotificationCenter.messagePlayingDidStart ||
            id == NotificationCenter.messagePlayingDidFinish) {
            loadData();
        }
    }

    // ─────────────────────────────────────────────
    //  Adapter
    // ─────────────────────────────────────────────

    private class MusicAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final Context mContext;

        MusicAdapter(Context context) {
            this.mContext = context;
        }

        @Override
        public int getItemCount() {
            return totalRows;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == likedBannerRow)        return VIEW_TYPE_LIKED_BANNER;
            if (position == playlistsAddRow)        return VIEW_TYPE_PLAYLIST_ADD;
            if (position == recentlyHeaderRow ||
                position == playlistsHeaderRow ||
                position == fromChatsHeaderRow)     return VIEW_TYPE_SECTION_HEADER;
            if (position >= recentlyFirstRow && recentlyFirstRow >= 0 && position < recentlyLastRow)
                return VIEW_TYPE_AUDIO_CELL;
            if (position >= fromChatsFirstRow && fromChatsFirstRow >= 0 && position < fromChatsLastRow)
                return VIEW_TYPE_AUDIO_CELL;
            return VIEW_TYPE_EMPTY_SPACE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_LIKED_BANNER:
                    view = createLikedBanner(mContext);
                    break;
                case VIEW_TYPE_SECTION_HEADER:
                    view = createSectionHeader(mContext);
                    break;
                case VIEW_TYPE_PLAYLIST_ADD:
                    view = createPlaylistAdd(mContext);
                    break;
                case VIEW_TYPE_AUDIO_CELL:
                    view = new SharedAudioCell(mContext, SharedAudioCell.VIEW_TYPE_DEFAULT, null) {
                        @Override
                        protected boolean needPlayMessage(MessageObject messageObject) {
                            return true;
                        }
                    };
                    break;
                default:
                    view = new View(mContext);
                    view.setLayoutParams(new RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT, dp(8)));
                    break;
            }
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = getItemViewType(position);

            if (type == VIEW_TYPE_SECTION_HEADER) {
                TextView tv = (TextView) holder.itemView;
                if (position == recentlyHeaderRow) {
                    tv.setText(getString(R.string.MusicRecentlyPlayed));
                } else if (position == playlistsHeaderRow) {
                    tv.setText(getString(R.string.MusicPlaylists));
                } else if (position == fromChatsHeaderRow) {
                    tv.setText(getString(R.string.MusicFromChats));
                }
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));

            } else if (type == VIEW_TYPE_AUDIO_CELL) {
                SharedAudioCell cell = (SharedAudioCell) holder.itemView;
                MessageObject mo = null;
                boolean divider = false;
                if (recentlyFirstRow >= 0 && position >= recentlyFirstRow && position < recentlyLastRow) {
                    int idx = position - recentlyFirstRow;
                    mo = recentlyPlayed.get(idx);
                    divider = (position < recentlyLastRow - 1);
                } else if (fromChatsFirstRow >= 0 && position >= fromChatsFirstRow && position < fromChatsLastRow) {
                    int idx = position - fromChatsFirstRow;
                    mo = fromChats.get(idx);
                    divider = (position < fromChatsLastRow - 1);
                }
                if (mo != null) {
                    cell.setMessageObject(mo, divider);
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    //  View factories
    // ─────────────────────────────────────────────

    /** Dark teal "Понравившиеся" banner, matching the screenshots */
    private static View createLikedBanner(Context context) {
        FrameLayout card = new FrameLayout(context);
        card.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT));
        card.setPadding(dp(16), dp(12), dp(16), dp(4));

        LinearLayout inner = new LinearLayout(context);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setBackground(Theme.createRoundRectDrawable(dp(16),
                0xFF2D6A5E /* teal accent */));
        inner.setPadding(dp(16), dp(18), dp(16), dp(18));
        inner.setGravity(Gravity.CENTER_VERTICAL);

        // Heart icon
        ImageView heart = new ImageView(context);
        heart.setImageResource(R.drawable.media_like);
        heart.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        heart.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        inner.addView(heart);

        // Text block
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);

        TextView title = new TextView(context);
        title.setText(getString(R.string.MusicLiked));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(0xFFFFFFFF);

        TextView subtitle = new TextView(context);
        subtitle.setText(getString(R.string.MusicLikedHint));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(0xCCFFFFFF);
        subtitle.setMaxLines(3);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);

        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        inner.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        card.addView(inner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return card;
    }

    /** Section header label */
    private static View createSectionHeader(Context context) {
        TextView tv = new TextView(context);
        tv.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT));
        tv.setPadding(dp(16), dp(20), dp(16), dp(8));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTypeface(AndroidUtilities.bold());
        return tv;
    }

    /** "New Playlist" card with + icon */
    private static View createPlaylistAdd(Context context) {
        FrameLayout card = new FrameLayout(context);
        card.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                dp(96)));
        card.setPadding(dp(16), 0, dp(16), dp(12));

        FrameLayout inner = new FrameLayout(context);
        inner.setBackground(Theme.createRoundRectDrawable(dp(12),
                Theme.getColor(Theme.key_windowBackgroundWhite)));

        // Plus icon
        ImageView plus = new ImageView(context);
        plus.setImageResource(R.drawable.msg_add);
        plus.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_windowBackgroundWhiteGreenText2), PorterDuff.Mode.SRC_IN));
        inner.addView(plus, LayoutHelper.createFrame(28, 28, Gravity.CENTER));

        card.addView(inner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return card;
    }

    @Override
    public boolean isSwipeBackEnabled(android.view.MotionEvent event) {
        return false;
    }
}
