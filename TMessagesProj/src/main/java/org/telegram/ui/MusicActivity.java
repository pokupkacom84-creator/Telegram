/*
 * FluxGram — MusicActivity
 */
package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
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
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SharedAudioCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class MusicActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int VIEW_TYPE_LIKED_BANNER   = 0;
    private static final int VIEW_TYPE_SECTION_HEADER = 1;
    private static final int VIEW_TYPE_AUDIO_CELL     = 2;
    private static final int VIEW_TYPE_PLAYLIST_ADD   = 3;
    private static final int VIEW_TYPE_EMPTY_SPACE    = 4;

    private RecyclerListView listView;
    private MusicAdapter adapter;

    private ArrayList<MessageObject> recentlyPlayed = new ArrayList<>();
    private ArrayList<MessageObject> fromChats      = new ArrayList<>();

    private int likedBannerRow;
    private int recentlyHeaderRow, recentlyFirstRow, recentlyLastRow;
    private int playlistsHeaderRow, playlistsAddRow;
    private int fromChatsHeaderRow, fromChatsFirstRow, fromChatsLastRow;
    private int totalRows;

    public MusicActivity(Bundle args) { super(args); }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(getString(R.string.MainTabsMusic));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, dp(8));
        adapter = new MusicAdapter(context);
        listView.setAdapter(adapter);
        ((FrameLayout) fragmentView).addView(
                listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (recentlyFirstRow >= 0 && position >= recentlyFirstRow && position < recentlyLastRow) {
                playAt(recentlyPlayed, position - recentlyFirstRow);
            } else if (fromChatsFirstRow >= 0 && position >= fromChatsFirstRow && position < fromChatsLastRow) {
                playAt(fromChats, position - fromChatsFirstRow);
            }
        });

        loadData();
        return fragmentView;
    }

    // ── data ─────────────────────────────────────────────────────────────────

    private void loadData() {
        // Recently played = current MediaController playlist (music only), capped at 10
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

        // "From your chats" — query MessagesStorage for audio documents
        fromChats.clear();
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            ArrayList<MessageObject> loaded = new ArrayList<>();
            try {
                org.telegram.SQLite.SQLiteCursor cursor =
                        MessagesStorage.getInstance(currentAccount).getDatabase().queryFinalized(
                                "SELECT m.data, m.mid, m.uid FROM messages_v2 m " +
                                "INNER JOIN media_v4 mv ON mv.mid = m.mid AND mv.uid = m.uid " +
                                "WHERE mv.type = 4 " +   // type 2 = audio
                                "ORDER BY m.date DESC LIMIT 50");
                while (cursor.next()) {
                    try {
                        org.telegram.tgnet.NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data == null) continue;
                        TLRPC.Message msg = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                        if (msg == null || msg.media == null) continue;
                        msg.id   = cursor.intValue(1);
                        msg.dialog_id = cursor.longValue(2);
                        MessageObject mo = new MessageObject(currentAccount, msg, false, false);
                        if (mo.isMusic()) loaded.add(mo);
                    } catch (Exception ignored) {}
                }
                cursor.dispose();
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                fromChats.addAll(loaded);
                buildRows();
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        });

        buildRows();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void playAt(ArrayList<MessageObject> list, int index) {
        if (index < 0 || index >= list.size()) return;
        MessageObject msg = list.get(index);
        MediaController.getInstance().setPlaylist(list, msg, msg.getDialogId());
    }

    private void buildRows() {
        int row = 0;
        likedBannerRow = row++;

        recentlyHeaderRow = recentlyFirstRow = recentlyLastRow = -1;
        if (!recentlyPlayed.isEmpty()) {
            recentlyHeaderRow = row++;
            recentlyFirstRow  = row;
            row += recentlyPlayed.size();
            recentlyLastRow = row;
        }

        playlistsHeaderRow = row++;
        playlistsAddRow    = row++;

        fromChatsHeaderRow = fromChatsFirstRow = fromChatsLastRow = -1;
        if (!fromChats.isEmpty()) {
            fromChatsHeaderRow = row++;
            fromChatsFirstRow  = row;
            row += fromChats.size();
            fromChatsLastRow = row;
        }

        totalRows = row;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

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
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.musicDidLoad ||
            id == NotificationCenter.messagePlayingDidStart ||
            id == NotificationCenter.messagePlayingDidReset) {
            loadData();
        }
    }

    // ── adapter ───────────────────────────────────────────────────────────────

    private class MusicAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Context mCtx;
        MusicAdapter(Context ctx) { this.mCtx = ctx; }

        @Override public int getItemCount() { return totalRows; }

        @Override
        public int getItemViewType(int p) {
            if (p == likedBannerRow)   return VIEW_TYPE_LIKED_BANNER;
            if (p == playlistsAddRow)  return VIEW_TYPE_PLAYLIST_ADD;
            if (p == recentlyHeaderRow || p == playlistsHeaderRow || p == fromChatsHeaderRow)
                return VIEW_TYPE_SECTION_HEADER;
            if (recentlyFirstRow  >= 0 && p >= recentlyFirstRow  && p < recentlyLastRow)
                return VIEW_TYPE_AUDIO_CELL;
            if (fromChatsFirstRow >= 0 && p >= fromChatsFirstRow && p < fromChatsLastRow)
                return VIEW_TYPE_AUDIO_CELL;
            return VIEW_TYPE_EMPTY_SPACE;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v;
            switch (viewType) {
                case VIEW_TYPE_LIKED_BANNER:   v = createLikedBanner(mCtx); break;
                case VIEW_TYPE_SECTION_HEADER: v = createSectionHeader(mCtx); break;
                case VIEW_TYPE_PLAYLIST_ADD:   v = createPlaylistAdd(mCtx); break;
                case VIEW_TYPE_AUDIO_CELL:
                    v = new SharedAudioCell(mCtx, SharedAudioCell.VIEW_TYPE_DEFAULT, null) {
                        @Override protected boolean needPlayMessage(MessageObject mo) { return true; }
                    };
                    break;
                default:
                    v = new View(mCtx);
                    v.setLayoutParams(new RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT, dp(8)));
                    break;
            }
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int p) {
            int type = getItemViewType(p);
            if (type == VIEW_TYPE_SECTION_HEADER) {
                TextView tv = (TextView) h.itemView;
                if (p == recentlyHeaderRow)   tv.setText(getString(R.string.MusicRecentlyPlayed));
                else if (p == playlistsHeaderRow) tv.setText(getString(R.string.MusicPlaylists));
                else if (p == fromChatsHeaderRow) tv.setText(getString(R.string.MusicFromChats));
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            } else if (type == VIEW_TYPE_AUDIO_CELL) {
                SharedAudioCell cell = (SharedAudioCell) h.itemView;
                MessageObject mo = null;
                boolean divider = false;
                if (recentlyFirstRow >= 0 && p >= recentlyFirstRow && p < recentlyLastRow) {
                    int i = p - recentlyFirstRow;
                    mo = recentlyPlayed.get(i);
                    divider = p < recentlyLastRow - 1;
                } else if (fromChatsFirstRow >= 0 && p >= fromChatsFirstRow && p < fromChatsLastRow) {
                    int i = p - fromChatsFirstRow;
                    mo = fromChats.get(i);
                    divider = p < fromChatsLastRow - 1;
                }
                if (mo != null) cell.setMessageObject(mo, divider);
            }
        }
    }

    // ── view factories ────────────────────────────────────────────────────────

    private static View createLikedBanner(Context ctx) {
        FrameLayout card = new FrameLayout(ctx);
        card.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        card.setPadding(dp(16), dp(12), dp(16), dp(4));

        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setBackground(Theme.createRoundRectDrawable(dp(16), 0xFF2D6A5E));
        inner.setPadding(dp(16), dp(18), dp(16), dp(18));
        inner.setGravity(Gravity.CENTER_VERTICAL);

        ImageView heart = new ImageView(ctx);
        heart.setImageResource(R.drawable.media_like);
        heart.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        inner.addView(heart, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);

        TextView title = new TextView(ctx);
        title.setText(getString(R.string.MusicLiked));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(0xFFFFFFFF);

        TextView sub = new TextView(ctx);
        sub.setText(getString(R.string.MusicLikedHint));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sub.setTextColor(0xCCFFFFFF);
        sub.setMaxLines(3);
        sub.setEllipsize(TextUtils.TruncateAt.END);

        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        texts.addView(sub,   LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        inner.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        card.addView(inner,  LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return card;
    }

    private static View createSectionHeader(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        tv.setPadding(dp(16), dp(20), dp(16), dp(8));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTypeface(AndroidUtilities.bold());
        return tv;
    }

    private static View createPlaylistAdd(Context ctx) {
        FrameLayout card = new FrameLayout(ctx);
        card.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, dp(96)));
        card.setPadding(dp(16), 0, dp(16), dp(12));

        FrameLayout inner = new FrameLayout(ctx);
        inner.setBackground(Theme.createRoundRectDrawable(dp(12),
                Theme.getColor(Theme.key_windowBackgroundWhite)));

        ImageView plus = new ImageView(ctx);
        plus.setImageResource(R.drawable.msg_add);
        plus.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_windowBackgroundWhiteGreenText2), PorterDuff.Mode.SRC_IN));
        inner.addView(plus, LayoutHelper.createFrame(28, 28, Gravity.CENTER));
        card.addView(inner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return card;
    }

    @Override public boolean isSwipeBackEnabled(MotionEvent e) { return false; }
}
