/*
 * FluxGram — MusicActivity (Material You redesign)
 */
package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SharedAudioCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class MusicActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int VIEW_TYPE_SEARCH        = 0;
    private static final int VIEW_TYPE_LIKED_BANNER  = 1;
    private static final int VIEW_TYPE_SECTION_HEADER= 2;
    private static final int VIEW_TYPE_AUDIO_CELL    = 3;
    private static final int VIEW_TYPE_PLAYLIST_ADD  = 4;
    private static final int VIEW_TYPE_RECENTLY_ROW  = 5;

    private static final int PAGE_SIZE = 50;

    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private MusicAdapter adapter;

    private final ArrayList<MessageObject> recentlyPlayed = new ArrayList<>();
    private final ArrayList<MessageObject> fromChats      = new ArrayList<>();
    private final ArrayList<MessageObject> filtered       = new ArrayList<>();
    private String searchQuery = "";
    private boolean isLoading  = false;
    private boolean hasMore    = true;
    private int     offset     = 0;

    // row indices
    private int searchRow, likedRow, recentlyRow;
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
        layoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(layoutManager);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, dp(8));
        listView.setAdapter(adapter = new MusicAdapter(context));
        ((FrameLayout) fragmentView).addView(listView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == fromChatsFirstRow - 1) return; // header
            if (!searchQuery.isEmpty()) {
                int idx = position - fromChatsFirstRow;
                if (idx >= 0 && idx < filtered.size()) playAt(filtered, idx);
                return;
            }
            if (fromChatsFirstRow >= 0 && position >= fromChatsFirstRow && position < fromChatsLastRow) {
                playAt(fromChats, position - fromChatsFirstRow);
            }
        });

        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!hasMore || isLoading) return;
                int last = layoutManager.findLastVisibleItemPosition();
                if (last >= totalRows - 8) loadMoreFromChats();
            }
        });

        loadInitial();
        return fragmentView;
    }

    // ── data ─────────────────────────────────────────────────────────────────

    private void loadInitial() {
        recentlyPlayed.clear();
        ArrayList<MessageObject> pl = MediaController.getInstance().getPlaylist();
        if (pl != null) {
            for (MessageObject mo : pl) {
                if (mo.isMusic()) {
                    recentlyPlayed.add(mo);
                    if (recentlyPlayed.size() >= 10) break;
                }
            }
        }
        offset  = 0;
        hasMore = true;
        fromChats.clear();
        loadMoreFromChats();
    }

    private void loadMoreFromChats() {
        if (isLoading || !hasMore) return;
        isLoading = true;
        final int currentOffset = offset;
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            ArrayList<MessageObject> loaded = new ArrayList<>();
            try {
                org.telegram.SQLite.SQLiteCursor c =
                    MessagesStorage.getInstance(currentAccount).getDatabase().queryFinalized(
                        "SELECT mv.data FROM media_v4 mv " +
                        "WHERE mv.type = 4 AND mv.mid > 0 AND mv.uid != 0 " +
                        "ORDER BY mv.date DESC, mv.mid DESC " +
                        "LIMIT " + PAGE_SIZE + " OFFSET " + currentOffset);
                while (c.next()) {
                    try {
                        NativeByteBuffer data = c.byteBufferValue(0);
                        if (data == null) continue;
                        TLRPC.Message msg = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                        if (msg == null || msg.media == null) continue;
                        MessageObject mo = new MessageObject(currentAccount, msg, false, false);
                        if (mo.isMusic()) loaded.add(mo);
                    } catch (Exception ignored) {}
                }
                c.dispose();
            } catch (Exception e) { FileLog.e(e); }

            AndroidUtilities.runOnUIThread(() -> {
                isLoading = false;
                if (loaded.size() < PAGE_SIZE) hasMore = false;
                offset += loaded.size();
                int prevSize = fromChats.size();
                fromChats.addAll(loaded);
                buildRows();
                if (prevSize == 0) {
                    adapter.notifyDataSetChanged();
                } else {
                    adapter.notifyItemRangeInserted(fromChatsFirstRow + prevSize, loaded.size());
                }
            });
        });
    }

    private void search(String query) {
        searchQuery = query.trim().toLowerCase();
        filtered.clear();
        if (!searchQuery.isEmpty()) {
            for (MessageObject mo : fromChats) {
                String title  = mo.getMusicTitle()  != null ? mo.getMusicTitle().toLowerCase()  : "";
                String author = mo.getMusicAuthor() != null ? mo.getMusicAuthor().toLowerCase() : "";
                if (title.contains(searchQuery) || author.contains(searchQuery)) filtered.add(mo);
            }
        }
        buildRows();
        adapter.notifyDataSetChanged();
    }

    private void playAt(ArrayList<MessageObject> list, int index) {
        if (index < 0 || index >= list.size()) return;
        MessageObject mo = list.get(index);
        MediaController.getInstance().setPlaylist(list, mo, mo.getDialogId());
    }

    private void buildRows() {
        int row = 0;
        searchRow = row++;

        if (!searchQuery.isEmpty()) {
            // search mode: only results
            likedRow = recentlyRow = -1;
            playlistsHeaderRow = playlistsAddRow = -1;
            fromChatsHeaderRow = row++;
            fromChatsFirstRow  = row;
            row += filtered.size();
            fromChatsLastRow = row;
        } else {
            likedRow   = row++;
            recentlyRow = recentlyPlayed.isEmpty() ? -1 : row++;
            playlistsHeaderRow = row++;
            playlistsAddRow    = row++;
            fromChatsHeaderRow = fromChats.isEmpty() ? -1 : row++;
            if (!fromChats.isEmpty()) {
                fromChatsFirstRow = row;
                row += fromChats.size();
                fromChatsLastRow = row;
            } else {
                fromChatsFirstRow = fromChatsLastRow = -1;
            }
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
        loadInitial();
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
            loadInitial();
        }
    }

    // ── adapter ───────────────────────────────────────────────────────────────

    private class MusicAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Context ctx;
        MusicAdapter(Context c) { ctx = c; }

        @Override public int getItemCount() { return totalRows; }

        @Override
        public int getItemViewType(int p) {
            if (p == searchRow)                    return VIEW_TYPE_SEARCH;
            if (p == likedRow)                     return VIEW_TYPE_LIKED_BANNER;
            if (p == recentlyRow)                  return VIEW_TYPE_RECENTLY_ROW;
            if (p == playlistsAddRow)              return VIEW_TYPE_PLAYLIST_ADD;
            if (p == playlistsHeaderRow || p == fromChatsHeaderRow) return VIEW_TYPE_SECTION_HEADER;
            if (fromChatsFirstRow >= 0 && p >= fromChatsFirstRow && p < fromChatsLastRow)
                return VIEW_TYPE_AUDIO_CELL;
            return VIEW_TYPE_SECTION_HEADER; // fallback
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v;
            switch (viewType) {
                case VIEW_TYPE_SEARCH:        v = createSearchBar(ctx); break;
                case VIEW_TYPE_LIKED_BANNER:  v = createLikedBanner(ctx); break;
                case VIEW_TYPE_RECENTLY_ROW:  v = createRecentlyRow(ctx); break;
                case VIEW_TYPE_PLAYLIST_ADD:  v = createPlaylistAdd(ctx); break;
                case VIEW_TYPE_AUDIO_CELL:
                    v = new SharedAudioCell(ctx, SharedAudioCell.VIEW_TYPE_DEFAULT, null) {
                        @Override protected boolean needPlayMessage(MessageObject mo) { return true; }
                    };
                    break;
                default:
                    v = createSectionHeader(ctx);
                    break;
            }
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int p) {
            int type = getItemViewType(p);
            if (type == VIEW_TYPE_SECTION_HEADER) {
                TextView tv = (TextView) h.itemView;
                if (p == playlistsHeaderRow)   tv.setText(getString(R.string.MusicPlaylists));
                else if (p == fromChatsHeaderRow) {
                    tv.setText(searchQuery.isEmpty()
                            ? getString(R.string.MusicFromChats)
                            : getString(R.string.SearchResults));
                }
            } else if (type == VIEW_TYPE_RECENTLY_ROW) {
                bindRecentlyRow((LinearLayout) ((FrameLayout) h.itemView).getChildAt(0));
            } else if (type == VIEW_TYPE_AUDIO_CELL) {
                SharedAudioCell cell = (SharedAudioCell) h.itemView;
                ArrayList<MessageObject> src = searchQuery.isEmpty() ? fromChats : filtered;
                int idx = p - fromChatsFirstRow;
                if (idx >= 0 && idx < src.size()) {
                    cell.setMessageObject(src.get(idx), idx < src.size() - 1);
                }
            }
        }
    }

    // ── view factories ────────────────────────────────────────────────────────

    private View createSearchBar(Context ctx) {
        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        wrap.setPadding(dp(16), dp(12), dp(16), dp(4));

        FrameLayout bar = new FrameLayout(ctx);
        bar.setBackground(Theme.createRoundRectDrawable(dp(12),
                Theme.getColor(Theme.key_windowBackgroundWhite)));
        bar.setPadding(dp(12), 0, dp(12), 0);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_ab_search);
        icon.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
        bar.addView(icon, LayoutHelper.createFrame(20, 20, Gravity.CENTER_VERTICAL | Gravity.START, 0, 0, 0, 0));

        EditText et = new EditText(ctx);
        et.setHint(getString(R.string.MusicSearch));
        et.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        et.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        et.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        et.setBackground(null);
        et.setSingleLine();
        et.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { search(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        bar.addView(et, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_VERTICAL, 32, 0, 0, 0));
        wrap.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
        return wrap;
    }

    private View createLikedBanner(Context ctx) {
        FrameLayout card = new FrameLayout(ctx);
        card.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        card.setPadding(dp(16), dp(8), dp(16), dp(4));

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
        card.addView(inner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return card;
    }

    /** Horizontal scroll row for recently played */
    private View createRecentlyRow(Context ctx) {
        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(16), dp(20), dp(16), dp(8));
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(ctx);
        label.setText(getString(R.string.MusicRecentlyPlayed));
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        header.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        LinearLayout scrollContent = new LinearLayout(ctx);
        scrollContent.setOrientation(LinearLayout.HORIZONTAL);
        scrollContent.setPadding(dp(12), 0, dp(12), dp(12));

        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(scrollContent);

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        container.addView(hsv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        wrap.addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        // store scrollContent tag for binding
        wrap.setTag(scrollContent);
        return wrap;
    }

    private void bindRecentlyRow(LinearLayout scrollContent) {
        scrollContent.removeAllViews();
        for (MessageObject mo : recentlyPlayed) {
            FrameLayout card = new FrameLayout(scrollContent.getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(140));
            lp.setMargins(dp(4), 0, dp(4), 0);
            card.setLayoutParams(lp);
            card.setBackground(Theme.createRoundRectDrawable(dp(12),
                    Theme.getColor(Theme.key_windowBackgroundWhite)));

            // album art placeholder
            ImageView art = new ImageView(scrollContent.getContext());
            art.setImageResource(R.drawable.music_note_small);
            art.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            art.setScaleType(ImageView.ScaleType.CENTER);
            art.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            card.addView(art, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 80));

            TextView name = new TextView(scrollContent.getContext());
            name.setText(mo.getMusicTitle());
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            name.setTypeface(AndroidUtilities.bold());
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(8), 0, dp(8), 0);
            card.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START, 0, 0, 0, 24));

            TextView author = new TextView(scrollContent.getContext());
            author.setText(mo.getMusicAuthor());
            author.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            author.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            author.setMaxLines(1);
            author.setEllipsize(TextUtils.TruncateAt.END);
            author.setPadding(dp(8), 0, dp(8), 0);
            card.addView(author, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START, 0, 0, 0, 8));

            final MessageObject finalMo = mo;
            card.setOnClickListener(v -> playAt(recentlyPlayed, recentlyPlayed.indexOf(finalMo)));
            scrollContent.addView(card);
        }
    }

    private View createSectionHeader(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        tv.setPadding(dp(16), dp(20), dp(16), dp(8));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        return tv;
    }

    private View createPlaylistAdd(Context ctx) {
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
