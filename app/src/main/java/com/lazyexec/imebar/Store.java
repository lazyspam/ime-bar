package com.lazyexec.imebar;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.PersistableBundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Clipboard history + saved phrases for the keyboard bar.
 *
 * Lives in the keyboard's own process (the active IME may read the clipboard) and keeps
 * its data in the keyboard's private files dir. Sensitive clips (passwords marked with
 * ClipDescription.EXTRA_IS_SENSITIVE) are never recorded.
 */
final class Store {
    static final String TAG = "ImeBar";
    private static final int MAX_CLIPS = 30;
    private static final int MAX_LEN = 4000;

    static final class Clip {
        String text;
        boolean pinned;
        long time;

        Clip(String text, boolean pinned, long time) {
            this.text = text;
            this.pinned = pinned;
            this.time = time;
        }
    }

    private static Store sInstance;

    final List<Clip> clips = new ArrayList<>();
    final List<String> phrases = new ArrayList<>();
    private final File mFile;
    private final ClipboardManager mClipboard;

    static synchronized Store get(Context ctx) {
        if (sInstance == null) sInstance = new Store(ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx);
        return sInstance;
    }

    private Store(Context ctx) {
        mFile = new File(ctx.getFilesDir(), "ime_bar.json");
        mClipboard = ctx.getSystemService(ClipboardManager.class);
        load();
        // phrases saved in the PhraseEditor sheet (only apps signed like the bar may send)
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(PhraseEditor.ACTION);
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent i) {
                    String text = i.getStringExtra("text");
                    String old = i.getStringExtra("old");
                    if (old != null) replacePhrase(old, text);
                    else addPhrase(text);
                }
            }, f, PhraseEditor.PERMISSION, null, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "phrase receiver", t);
        }
        if (mClipboard != null) {
            mClipboard.addPrimaryClipChangedListener(this::captureCurrent);
            captureCurrent();
        }
    }

    /** Current clipboard text, or null. */
    String currentText() {
        try {
            if (mClipboard == null || !mClipboard.hasPrimaryClip()) return null;
            ClipData clip = mClipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            ClipDescription desc = clip.getDescription();
            if (desc != null) {
                PersistableBundle extras = desc.getExtras();
                if (extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)) {
                    return null;
                }
            }
            CharSequence cs = clip.getItemAt(0).getText();
            if (cs == null) return null;
            String s = cs.toString();
            if (s.trim().isEmpty()) return null;
            return s.length() > MAX_LEN ? s.substring(0, MAX_LEN) : s;
        } catch (Throwable t) {
            return null;
        }
    }

    void captureCurrent() {
        String s = currentText();
        if (s != null) addClip(s);
    }

    synchronized void addClip(String text) {
        boolean pinned = false;
        for (int i = clips.size() - 1; i >= 0; i--) {
            if (clips.get(i).text.equals(text)) {
                pinned = clips.get(i).pinned;
                clips.remove(i);
            }
        }
        clips.add(0, new Clip(text, pinned, System.currentTimeMillis()));
        int unpinned = 0;
        for (int i = 0; i < clips.size(); i++) {
            if (!clips.get(i).pinned && ++unpinned > MAX_CLIPS) {
                clips.remove(i--);
            }
        }
        save();
    }

    synchronized void togglePin(Clip c) {
        c.pinned = !c.pinned;
        save();
    }

    synchronized void removeClip(Clip c) {
        clips.remove(c);
        save();
    }

    synchronized void clearUnpinned() {
        clips.removeIf(c -> !c.pinned);
        save();
    }

    synchronized boolean addPhrase(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.isEmpty() || phrases.contains(s)) return false;
        phrases.add(0, s.length() > MAX_LEN ? s.substring(0, MAX_LEN) : s);
        save();
        return true;
    }

    synchronized void replacePhrase(String old, String text) {
        if (text == null || text.trim().isEmpty()) return;
        String s = text.trim();
        int i = phrases.indexOf(old);
        if (i >= 0) {
            phrases.set(i, s);
            for (int j = phrases.size() - 1; j >= 0; j--) {
                if (j != i && phrases.get(j).equals(s)) {
                    phrases.remove(j);
                    if (j < i) i--;
                }
            }
        } else if (!phrases.contains(s)) {
            phrases.add(0, s);
        }
        save();
    }

    synchronized void removePhrase(String text) {
        phrases.remove(text);
        save();
    }

    /** Pinned clips first, each group newest first. */
    synchronized List<Clip> orderedClips() {
        List<Clip> out = new ArrayList<>();
        for (Clip c : clips) if (c.pinned) out.add(c);
        for (Clip c : clips) if (!c.pinned) out.add(c);
        return out;
    }

    private void load() {
        try {
            if (!mFile.exists()) return;
            byte[] buf;
            try (FileInputStream in = new FileInputStream(mFile)) {
                buf = in.readAllBytes();
            }
            JSONObject root = new JSONObject(new String(buf, StandardCharsets.UTF_8));
            JSONArray c = root.optJSONArray("clips");
            if (c != null) {
                for (int i = 0; i < c.length(); i++) {
                    JSONObject o = c.getJSONObject(i);
                    clips.add(new Clip(o.getString("t"), o.optBoolean("p"), o.optLong("ts")));
                }
            }
            JSONArray p = root.optJSONArray("phrases");
            if (p != null) {
                for (int i = 0; i < p.length(); i++) phrases.add(p.getString(i));
            }
        } catch (Throwable t) {
            Log.w(TAG, "load failed", t);
        }
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray c = new JSONArray();
            for (Clip clip : clips) {
                JSONObject o = new JSONObject();
                o.put("t", clip.text);
                o.put("p", clip.pinned);
                o.put("ts", clip.time);
                c.put(o);
            }
            root.put("clips", c);
            root.put("phrases", new JSONArray(phrases));
            File tmp = new File(mFile.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(mFile)) Log.w(TAG, "rename failed");
        } catch (Throwable t) {
            Log.w(TAG, "save failed", t);
        }
    }
}
