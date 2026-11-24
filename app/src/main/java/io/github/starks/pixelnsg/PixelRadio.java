package io.github.starks.pixelnsg;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.SubscriptionManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class PixelRadio {
    enum Rat {
        LTE("LTE", "B", 2, 8), NR("5G NR", "n", 3, 32);

        final String label;
        final String prefix;
        final int bandField;
        final int wordCount;

        Rat(String label, String prefix, int bandField, int wordCount) {
            this.label = label;
            this.prefix = prefix;
            this.bandField = bandField;
            this.wordCount = wordCount;
        }
    }

    record State(Rat rat, Set<Integer> supported, Mask current, int subId, boolean hasRestore) {}

    record Mask(Rat rat, String[] fields, Set<Integer> primary, Set<Integer> secondary) {
        static Mask parse(Rat rat, String response) throws IOException {
            String[] fields = response.trim().split(",", -1);
            try {
                if (rat == Rat.LTE) {
                    if (fields.length != 10) throw new NumberFormatException();
                    for (String field : fields) {
                        if (!field.matches("[0-9]{1,10}") || Long.parseLong(field) > 0xffffffffL) {
                            throw new NumberFormatException();
                        }
                    }
                    Set<Integer> bands = new TreeSet<>();
                    for (int i = 0; i < rat.wordCount; i++) {
                        addBands(bands, Long.parseLong(fields[rat.bandField + i]), i);
                    }
                    return new Mask(rat, fields, bands, bands);
                }
                if (fields.length != 4 && fields.length != 5) throw new NumberFormatException();
                for (int i = 0; i < fields.length; i++) {
                    int digits = fields[i].length() - 2;
                    if (!fields[i].matches("\"[0-9a-fA-F]+\"") || digits % 8 != 0 ||
                            digits > 256 || (i < 2 && digits != 8) || (i == 2 && digits != 24)) {
                        throw new NumberFormatException();
                    }
                }
                Set<Integer> first = hexBands(fields[3]);
                return new Mask(rat, fields, first, fields.length == 5 ? hexBands(fields[4]) : first);
            } catch (NumberFormatException error) {
                throw new IOException("Unexpected " + rat.label + " mask response.", error);
            }
        }

        private static Set<Integer> hexBands(String field) {
            Set<Integer> result = new TreeSet<>();
            for (int start = 1, i = 0; start < field.length() - 1; start += 8, i++) {
                addBands(result, Long.parseLong(field.substring(start, start + 8), 16), i);
            }
            return result;
        }

        private static void addBands(Set<Integer> bands, long word, int index) {
            for (int bit = 0; bit < 32; bit++) {
                if ((word & (1L << bit)) != 0) bands.add(index * 32 + bit + 1);
            }
        }

        String commandFor(Set<Integer> bands) {
            String[] output = Arrays.copyOf(fields, rat == Rat.LTE ? 10 : 4);
            long[] words = new long[rat.wordCount];
            for (int band : bands) words[(band - 1) / 32] |= 1L << ((band - 1) % 32);
            if (rat == Rat.LTE) {
                for (int i = 0; i < words.length; i++) output[rat.bandField + i] = Long.toString(words[i]);
            } else {
                int used = (bands.stream().mapToInt(Integer::intValue).max().orElse(1) + 31) / 32;
                int currentWords = (fields[rat.bandField].length() - 2) / 8;
                StringBuilder hex = new StringBuilder("\"");
                for (int i = 0; i < Math.max(used, currentWords); i++) {
                    hex.append(String.format(Locale.ROOT, "%08X", words[i]));
                }
                output[rat.bandField] = hex.append('"').toString();
            }
            return String.join(",", output);
        }

        String restoreCommand(Mask baseline) {
            String[] output = Arrays.copyOf(fields, rat == Rat.LTE ? 10 : 4);
            System.arraycopy(baseline.fields, rat.bandField, output, rat.bandField, output.length - rat.bandField);
            return String.join(",", output);
        }

        boolean sameBands(Mask other) {
            return primary.equals(other.primary) && secondary.equals(other.secondary);
        }

        boolean sameOtherFields(Mask other) {
            for (int i = 0; i < rat.bandField; i++) {
                if (!fields[i].equalsIgnoreCase(other.fields[i])) return false;
            }
            return true;
        }

        String snapshot() {
            return String.join(",", fields);
        }
    }

    private final SharedPreferences snapshots;
    private static final String CLIENT_RESOURCE = "/pixel-atci.sh";

    PixelRadio(Context context) {
        snapshots = context.getSharedPreferences("starks_pixel_band_lock", Context.MODE_PRIVATE);
    }

    State read(Rat rat) throws Exception {
        int subId = slotOneSubscription();
        Mask supported = query("capabilities", rat);
        Mask current = query("read", rat);
        checkSubscription(subId);
        return new State(rat, supported.primary, current, subId, snapshots.contains(key(rat, subId)));
    }

    void apply(State displayed, Set<Integer> selection) throws Exception {
        Rat rat = displayed.rat;
        if (selection.isEmpty() || !displayed.supported.containsAll(selection)) {
            throw new IOException("Select at least one supported band.");
        }
        checkSubscription(displayed.subId);
        Mask initial = query("read", rat);
        if (!initial.sameBands(displayed.current)) throw new IOException("Band masks changed. Reopen the panel.");
        if (selection.equals(initial.primary)) return;
        String key = key(rat, displayed.subId);
        boolean firstLock = !snapshots.contains(key);
        if (firstLock && !snapshots.edit().putString(key, initial.snapshot()).commit()) {
            throw new IOException("Could not save the restore point.");
        }
        try {
            checkSubscription(displayed.subId);
            run("apply", rat, initial.commandFor(selection));
            Mask readback = query("read", rat);
            checkSubscription(displayed.subId);
            if (!selection.equals(readback.primary) || !selection.containsAll(readback.secondary) ||
                    !initial.sameOtherFields(readback)) throw new IOException("The modem readback differs from the selection.");
        } catch (Exception applyError) {
            try {
                restoreMask(initial, displayed.subId);
                if (firstLock) snapshots.edit().remove(key).commit();
            } catch (Exception restoreError) {
                throw new IOException("Apply failed and rollback could not be verified. The restore point is saved.", restoreError);
            }
            throw new IOException("Apply failed. The starting masks were restored. " + applyError.getMessage(), applyError);
        }
    }

    void restore(State displayed) throws Exception {
        checkSubscription(displayed.subId);
        String key = key(displayed.rat, displayed.subId);
        String snapshot = snapshots.getString(key, null);
        if (snapshot == null) throw new IOException("No restore point is saved for this SIM.");
        restoreMask(Mask.parse(displayed.rat, snapshot), displayed.subId);
        if (!snapshots.edit().remove(key).commit()) throw new IOException("Masks restored. Could not clear the restore point.");
    }

    private static void restoreMask(Mask baseline, int subId) throws Exception {
        checkSubscription(subId);
        Mask current = query("read", baseline.rat);
        if (current.sameBands(baseline)) return;
        checkSubscription(subId);
        run("apply", baseline.rat, current.restoreCommand(baseline));
        Mask readback = query("read", baseline.rat);
        checkSubscription(subId);
        if (!baseline.sameBands(readback) || !current.sameOtherFields(readback)) {
            throw new IOException("Could not verify the restored masks.");
        }
    }

    private static int slotOneSubscription() throws IOException {
        int subId;
        try {
            subId = SubscriptionManager.getSubscriptionId(0);
        } catch (SecurityException error) {
            throw new IOException("NSG cannot identify logical SIM 1.", error);
        }
        if (!SubscriptionManager.isValidSubscriptionId(subId)) throw new IOException("Logical SIM 1 is inactive.");
        return subId;
    }

    private static void checkSubscription(int expected) throws IOException {
        if (slotOneSubscription() != expected) throw new IOException("Logical SIM 1 changed. Reopen the panel.");
    }

    private static String key(Rat rat, int subId) {
        return Build.FINGERPRINT + ":" + subId + ":" + rat.name();
    }

    private static Mask query(String command, Rat rat) throws Exception {
        return Mask.parse(rat, run(command, rat, null));
    }

    private static String run(String operation, Rat rat, String masks) throws Exception {
        String command = "/system/bin/sh -s -- " + operation + " " + rat.name().toLowerCase(Locale.ROOT);
        if (masks != null) {
            if (!masks.matches("[0-9A-Fa-f,\"]+")) throw new IOException("Invalid mask argument.");
            command += " '" + masks + "'";
        }
        byte[] script;
        try (InputStream asset = PixelRadio.class.getResourceAsStream(CLIENT_RESOURCE)) {
            if (asset == null) throw new IOException("The ATCI client is missing from the module.");
            script = new String(asset.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        }
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        FutureTask<String> response = new FutureTask<>(() -> {
            try (InputStream input = process.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 16384) throw new IOException("ATCI response is too large.");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8).trim();
            }
        });
        Thread reader = new Thread(response, "pixel-atci-reply");
        reader.setDaemon(true);
        reader.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script);
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) throw new IOException("Root command timed out.");
            String reply = response.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IOException(reply.isBlank() ? "Root access or the ATCI socket is unavailable." : reply);
            }
            return reply;
        } finally {
            process.destroy();
            response.cancel(true);
        }
    }
}
