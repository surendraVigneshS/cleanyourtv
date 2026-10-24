package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Switching the TV to a passthrough input (HDMI, AV), shared by MainActivity's Inputs menu and the glass Home. */
final class InputSwitch {
    private InputSwitch() {}

    /** `input_sources` in config.xml: "HW5=23" maps the framework input whose id ends in /HW5 to a vendor
     *  source number; "HW8=-" leaves that input out of the Inputs menu (a port the board reports but the set
     *  does not have). Returns the number, -1 for a left-out input, or null when the input is not listed. */
    static Integer mappedSource(Context ctx, String inputId) {
        if (inputId == null) return null;
        String hw = inputId.substring(inputId.lastIndexOf('/') + 1);
        for (String m : ctx.getResources().getStringArray(R.array.input_sources)) {
            int eq = m.indexOf('=');
            if (eq <= 0 || !m.substring(0, eq).trim().equals(hw)) continue;
            String v = m.substring(eq + 1).trim();
            if (v.equals("-")) return -1;
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Switches to a passthrough input (HDMI, AV). An input mapped in `input_sources` is opened by sending
     *  `input_action` with its source number as the int extra "input" (Xiaomi China firmware, whose tvplayer
     *  answers com.xiaomi.mitv.tvplayer.EXTSRC_PLAY and nothing else). Otherwise the standard way is
     *  ACTION_VIEW on its channel URI, which the Live TV app handles; where nothing takes that, the
     *  configured `input_action` is sent with the same URI instead (XGIMI's HDMI player only answers
     *  com.xgimi.action.hdmiPlayer). With `input_uri_scheme` set (YaOS: live-tv) the same URI goes out
     *  with that scheme and no MIME type. A player guarded by a permission this build does not declare
     *  throws SecurityException, which used to take Home down with it (YaOS before the manifest asked
     *  for ACCESS_TV_SOURCE). Returns false, after a "not available" toast, when nothing could be started. */
    static boolean open(Context ctx, String inputId, String liveTvPkg, String inputAction, String inputUriScheme) {
        // A CEC device is a child input of its HDMI port; the vendor source number belongs to the port
        // (MiTV-MFFU0). Only that lookup uses the parent: the generic path below still opens the child.
        String sourceId = inputId;
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                TvInputInfo info = tim.getTvInputInfo(inputId);
                if (info != null && info.getParentId() != null) sourceId = info.getParentId();
            } catch (RuntimeException ignored) {
            }
        }
        Integer source = mappedSource(ctx, sourceId);
        if (source != null && source >= 0 && !inputAction.isEmpty()) {
            try {
                ctx.startActivity(new Intent(inputAction).putExtra("input", source.intValue())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall through to the standard way
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = (inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(inputUriScheme).build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Aim it at the TV app first: apps installed later (a streaming app, a file manager) can claim the same
        // channel URI too, and with two or more handlers Android shows a chooser instead of the input (WhaleOS).
        if (!liveTvPkg.isEmpty()) {
            try {
                ctx.startActivity(new Intent(view).setPackage(liveTvPkg));
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        try {
            ctx.startActivity(view);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        try {
            ctx.startActivity(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** One physical port as the glass Home shows it: "HDMI 1" to "HDMI 3", "AV". */
    static final class Port {
        final String name;
        /** The input to open: the port itself, or the CEC device on it, which also wakes that device. */
        final String openId;
        /** Every input id whose state counts for this port (the port and its CEC devices). */
        final Set<String> ids;
        /** The name given to the port (or its device) in Settings > Inputs, or null. */
        final String customLabel;

        Port(String name, String openId, Set<String> ids, String customLabel) {
            this.name = name;
            this.openId = openId;
            this.ids = ids;
            this.customLabel = customLabel;
        }
    }

    /**
     * The TV's passthrough ports by name, HDMI in port order then AV. HDMI ports are numbered by their order in
     * the framework (labels and ids sort the same way on droidlogic: Hdmi1InputService/HW5 ...). Inputs hidden
     * in Settings > Inputs or left out with input_sources ("HW8=-") are still listed, so the row keeps its
     * shape; they just have no signal. A binder call per input: call it off the main thread.
     */
    static Map<String, Port> ports(Context ctx) {
        Map<String, Port> out = new LinkedHashMap<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> inputs;
        try {
            inputs = new ArrayList<>(tim.getTvInputList());
        } catch (RuntimeException e) {
            return out;
        }
        Set<String> ids = new HashSet<>();
        for (TvInputInfo info : inputs) ids.add(info.getId());
        List<TvInputInfo> hdmi = new ArrayList<>(), av = new ArrayList<>();
        for (TvInputInfo info : inputs) {
            if (!info.isPassthroughInput()) continue;
            if (info.getParentId() != null && ids.contains(info.getParentId())) continue;   // a CEC device
            if (info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info);
            else if (info.getType() == TvInputInfo.TYPE_COMPOSITE || info.getType() == TvInputInfo.TYPE_SVIDEO
                    || info.getType() == TvInputInfo.TYPE_COMPONENT || info.getType() == TvInputInfo.TYPE_SCART
                    || info.getType() == TvInputInfo.TYPE_OTHER) av.add(info);
        }
        Collections.sort(hdmi, (a, b) -> String.valueOf(a.loadLabel(ctx)).compareToIgnoreCase(String.valueOf(b.loadLabel(ctx))));
        for (int i = 0; i < hdmi.size(); i++) out.put("HDMI " + (i + 1), port("HDMI " + (i + 1), hdmi.get(i), inputs, ctx));
        if (!av.isEmpty()) out.put("AV", port("AV", av.get(0), inputs, ctx));
        return out;
    }

    private static Port port(String name, TvInputInfo info, List<TvInputInfo> all, Context ctx) {
        Set<String> ids = new HashSet<>();
        ids.add(info.getId());
        String openId = info.getId();
        CharSequence custom = info.loadCustomLabel(ctx);
        for (TvInputInfo child : all) {
            if (!info.getId().equals(child.getParentId()) || !child.isPassthroughInput()) continue;
            ids.add(child.getId());
            if (openId.equals(info.getId())) {
                openId = child.getId();
                CharSequence c = child.loadCustomLabel(ctx);
                if (c == null || c.length() == 0) c = child.loadLabel(ctx);
                if (c != null && c.length() > 0 && (custom == null || custom.length() == 0)) custom = c;
            }
        }
        String label = custom == null ? null : custom.toString().trim();
        if (label != null && (label.isEmpty() || label.toUpperCase(Locale.ROOT).replace(" ", "")
                .equals(name.replace(" ", "")))) label = null;
        return new Port(name, openId, ids, label);
    }
}
